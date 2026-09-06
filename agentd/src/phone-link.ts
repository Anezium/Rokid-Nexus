import { createSocket, type Socket as UdpSocket } from "node:dgram";
import { existsSync } from "node:fs";
import { readdir, stat } from "node:fs/promises";
import { homedir, networkInterfaces, platform } from "node:os";
import { connect, type Socket as TcpSocket } from "node:net";
import type {
  ApprovalDecision,
  ApprovalOutcome,
  ApprovalRequest,
} from "./approval-manager";
import { parsePhoneTarget, readPhoneLinkConfig } from "./config";
import {
  listDirectory,
  listRoots,
  validateExistingDirectory,
  type FsListing,
} from "./fs-browse";
import type { SessionStore } from "./session-store";
import { TailscalePeerDiscovery } from "./tailnet-discovery";
import type {
  AgentConfig,
  AgentProvider,
  Logger,
  Session,
  SessionMessage,
  ThreadStartResult,
} from "./types";

/**
 * Outbound link to the phone.
 *
 * The daemon is the CLIENT here even though it owns the data: Windows blocks
 * unsolicited inbound connections unless an administrator adds a firewall rule,
 * while outbound connections and the unicast replies to a broadcast we just sent
 * are allowed by default. Android has no such filter, so the phone listens and
 * the PC dials — no rule, no cable, nothing to configure.
 */
export const DISCOVERY_PORT = 8793;
const DISCOVERY_INTERVAL_MS = 2000;
export const TAILNET_REFRESH_INTERVAL_MS = 60_000;
const RECONNECT_DELAY_MS = 3000;
export const PHONE_HELLO_TIMEOUT_MS = 15_000;
export const REFUSAL_RECONNECT_DELAY_MS = 60_000;
const DETAIL_MESSAGE_LIMIT = 40;
const MAX_LINE_BYTES = 512 * 1024;
export const MAX_PHONE_SESSIONS_PER_PROVIDER = 200;
export const THREAD_START_TIMEOUT_MS = 30_000;
export const CODEX_DETAIL_REFRESH_DELAY_MS = 1_000;
/** The phone's read loop times out at 90s; two of these fit inside it. */
const LINK_PING_INTERVAL_MS = 30_000;
const MAX_THREAD_PROMPT_BYTES = 16 * 1024;

export interface PhoneLinkOptions {
  config: AgentConfig;
  store: SessionStore;
  logger: Logger;
  detailProvider: (sessionId: string, limit: number) => Promise<SessionMessage[]>;
  onDetailOpen?: (sessionId: string) => void;
  onApprovalDecision?: (requestId: string, decision: ApprovalDecision) => void;
  onThreadStart?: (
    provider: AgentProvider,
    path: string,
    prompt: string,
  ) => Promise<ThreadStartResult>;
  onConnected?: () => void;
  onDisconnected?: () => void;
  operatorMessage?: (message: string) => void;
  discoveryPort?: number;
  helloTimeoutMs?: number;
  reconnectDelayMs?: number;
  targetRefreshIntervalMs?: number;
  threadStartTimeoutMs?: number;
  codexDetailRefreshDelayMs?: number;
  configFilePath?: string;
  tailnetDiscovery?: TailnetPeerSource;
  now?: () => number;
}

export interface TailnetPeerSource {
  discover(): Promise<string[]>;
}

interface PhoneAnnouncement {
  host: string;
  port: number;
  name: string;
}

interface RejectedPhone {
  retryAt: number;
  reportedReason: string;
}

function parseAnnouncement(raw: Buffer, host: string): PhoneAnnouncement | undefined {
  try {
    const value: unknown = JSON.parse(raw.toString("utf8"));
    if (!value || typeof value !== "object") {
      return undefined;
    }
    const record = value as Record<string, unknown>;
    if (record.nexus !== "agents-phone") {
      return undefined;
    }
    const port = typeof record.port === "number" ? record.port : undefined;
    if (!port || port < 1 || port > 65535) {
      return undefined;
    }
    return {
      host,
      port,
      name: typeof record.name === "string" ? record.name.slice(0, 64) : "phone",
    };
  } catch {
    return undefined;
  }
}

/** Broadcast addresses of every IPv4 interface, plus the global fallback. */
function broadcastTargets(): string[] {
  const targets = new Set<string>(["255.255.255.255"]);
  for (const addresses of Object.values(networkInterfaces())) {
    for (const address of addresses ?? []) {
      if (address.family !== "IPv4" || address.internal || !address.netmask) {
        continue;
      }
      const ip = address.address.split(".").map(Number);
      const mask = address.netmask.split(".").map(Number);
      if (ip.length !== 4 || mask.length !== 4 || ip.some(isNaN) || mask.some(isNaN)) {
        continue;
      }
      targets.add(ip.map((part, index) => part | (~mask[index] & 0xff)).join("."));
    }
  }
  return [...targets];
}

function normalizedHost(host: string): string {
  return host.toLowerCase();
}

export function buildPhoneDialTargets(
  phoneHosts: readonly string[],
  discoveredTargets: readonly string[],
  unavailableHosts: ReadonlySet<string> = new Set(),
): PhoneAnnouncement[] {
  const configured: PhoneAnnouncement[] = [];
  const reservedHosts = new Set([...unavailableHosts].map(normalizedHost));
  for (const configuredTarget of phoneHosts) {
    const target = parsePhoneTarget(configuredTarget);
    if (target) {
      configured.push({ ...target, name: target.host });
      reservedHosts.add(normalizedHost(target.host));
    }
  }

  const discovered: PhoneAnnouncement[] = [];
  for (const discoveredTarget of discoveredTargets) {
    const target = parsePhoneTarget(discoveredTarget);
    if (!target || reservedHosts.has(normalizedHost(target.host))) {
      continue;
    }
    reservedHosts.add(normalizedHost(target.host));
    discovered.push({ ...target, name: target.host });
  }
  return [...configured, ...discovered];
}

export class PhoneLink {
  private discovery?: UdpSocket;
  private socket?: TcpSocket;
  private discoveryTimer?: NodeJS.Timeout;
  private targetRefreshTimer?: NodeJS.Timeout;
  private reconnectTimer?: NodeJS.Timeout;
  private helloTimer?: NodeJS.Timeout;
  private detailRefreshTimer?: NodeJS.Timeout;
  private detailSendInFlight?: Promise<void>;
  private pendingDetailSessionId?: string;
  private pingTimer?: NodeJS.Timeout;
  private buffer = "";
  private sequence = 0;
  private openSessionId?: string;
  private connectedTo?: string;
  private phoneName?: string;
  private authenticated = false;
  private stopped = false;
  private unsubscribe?: () => void;
  private readonly rejectedPhones = new Map<string, RejectedPhone>();
  private readonly retryAt = new Map<string, number>();
  private readonly now: () => number;
  private readonly tailnetPeerSource: TailnetPeerSource;
  private phoneHosts: string[];
  private tailnetDiscoveryEnabled: boolean;
  private discoveredPhoneTargets: string[] = [];
  private targetRefreshPromise?: Promise<void>;
  private targetRefreshGeneration = 0;
  private publishedSessions = new Map<string, Session>();

  constructor(private readonly options: PhoneLinkOptions) {
    this.now = options.now ?? Date.now;
    this.phoneHosts = [...options.config.phoneHosts];
    this.tailnetDiscoveryEnabled = options.config.tailnetDiscovery !== false;
    this.tailnetPeerSource = options.tailnetDiscovery ?? new TailscalePeerDiscovery(options.logger);
  }

  start(): void {
    this.stopped = false;
    this.targetRefreshGeneration += 1;
    const socket = createSocket({ type: "udp4", reuseAddr: true });
    this.discovery = socket;
    socket.on("error", (error) => {
      this.options.logger.warn("discovery_socket_error", { reason: error.name });
    });
    socket.on("message", (message, remote) => {
      if (this.socket) {
        return;
      }
      const announcement = parseAnnouncement(message, remote.address);
      if (announcement) {
        this.connectToPhone(announcement);
      }
    });
    socket.bind(() => {
      socket.setBroadcast(true);
      this.announce();
      this.discoveryTimer = setInterval(() => this.announce(), DISCOVERY_INTERVAL_MS);
      this.discoveryTimer.unref();
    });
    void this.refreshTargets();
    this.targetRefreshTimer = setInterval(
      () => void this.refreshTargets(),
      this.options.targetRefreshIntervalMs ?? TAILNET_REFRESH_INTERVAL_MS,
    );
    this.targetRefreshTimer.unref();
  }

  stop(): void {
    this.stopped = true;
    if (this.discoveryTimer) {
      clearInterval(this.discoveryTimer);
      this.discoveryTimer = undefined;
    }
    if (this.targetRefreshTimer) {
      clearInterval(this.targetRefreshTimer);
      this.targetRefreshTimer = undefined;
    }
    this.targetRefreshGeneration += 1;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = undefined;
    }
    this.clearHelloTimer();
    this.clearDetailRefreshTimer();
    this.clearPingTimer();
    this.pendingDetailSessionId = undefined;
    this.unsubscribe?.();
    this.unsubscribe = undefined;
    this.socket?.destroy();
    this.socket = undefined;
    this.authenticated = false;
    this.publishedSessions.clear();
    this.discovery?.close();
    this.discovery = undefined;
  }

  get connected(): boolean {
    return this.authenticated && this.socket !== undefined && !this.socket.destroyed;
  }

  sendApprovalRequest(request: ApprovalRequest): boolean {
    return this.send({ ...request });
  }

  sendApprovalResolved(requestId: string, outcome: ApprovalOutcome): boolean {
    return this.send({ type: "approval_resolved", v: 1, requestId, outcome });
  }

  /** Streams an appended conversation message if the phone is reading that session. */
  sendDetailMessage(sessionId: string, message: SessionMessage): void {
    if (this.openSessionId === sessionId) {
      this.send({
        type: "detail_append",
        sessionId,
        provider: this.options.store.get(sessionId)?.provider ?? "claude",
        message,
      });
    }
  }

  async refreshTargets(): Promise<void> {
    if (this.targetRefreshPromise) {
      return this.targetRefreshPromise;
    }
    const refresh = this.doRefreshTargets();
    this.targetRefreshPromise = refresh;
    try {
      await refresh;
    } finally {
      if (this.targetRefreshPromise === refresh) {
        this.targetRefreshPromise = undefined;
      }
    }
  }

  private async doRefreshTargets(): Promise<void> {
    if (this.options.configFilePath) {
      try {
        const config = readPhoneLinkConfig(this.options.configFilePath);
        this.phoneHosts = config.phoneHosts;
        this.tailnetDiscoveryEnabled = config.tailnetDiscovery;
      } catch {
        // Atomic saves can briefly hide the file; retain the last good dial settings.
      }
    }

    const generation = this.targetRefreshGeneration;
    let discoveredTargets: string[] = [];
    if (this.tailnetDiscoveryEnabled) {
      try {
        discoveredTargets = await this.tailnetPeerSource.discover();
      } catch {
        // Discovery is optional and must not affect the daemon lifecycle.
      }
    }
    if (this.stopped || generation !== this.targetRefreshGeneration) {
      return;
    }
    this.discoveredPhoneTargets = discoveredTargets;
    this.announce();
  }

  private announce(): void {
    if (this.socket || !this.discovery) {
      return;
    }
    const payload = Buffer.from(
      JSON.stringify({
        nexus: "agentd",
        v: 1,
        machineId: this.options.config.machineId,
        machineName: this.options.config.machineName,
      }),
    );
    const port = this.options.discoveryPort ?? DISCOVERY_PORT;
    for (const target of broadcastTargets()) {
      this.discovery.send(payload, port, target, (error) => {
        if (error) {
          this.options.logger.warn("discovery_send_failed", { target, reason: error.name });
        }
      });
    }
    for (const target of buildPhoneDialTargets(
      this.phoneHosts,
      this.discoveredPhoneTargets,
      this.backingOffHosts(),
    )) {
      this.connectToPhone(target);
      if (this.socket) {
        break;
      }
    }
  }

  private backingOffHosts(): Set<string> {
    const hosts = new Set<string>();
    const now = this.now();
    for (const [target, retryAt] of this.rejectedPhones) {
      if (retryAt.retryAt > now) {
        const parsed = parsePhoneTarget(target);
        if (parsed) {
          hosts.add(parsed.host);
        }
      }
    }
    for (const [target, retryAt] of this.retryAt) {
      if (retryAt > now) {
        const parsed = parsePhoneTarget(target);
        if (parsed) {
          hosts.add(parsed.host);
        }
      }
    }
    if (this.connectedTo) {
      const parsed = parsePhoneTarget(this.connectedTo);
      if (parsed) {
        hosts.add(parsed.host);
      }
    }
    return hosts;
  }

  private connectToPhone(announcement: PhoneAnnouncement): void {
    if (this.socket || this.stopped) {
      return;
    }
    const target = `${announcement.host}:${announcement.port}`;
    const now = this.now();
    if ((this.rejectedPhones.get(target)?.retryAt ?? 0) > now) {
      return;
    }
    if ((this.retryAt.get(target) ?? 0) > now) {
      return;
    }
    const socket = connect({ host: announcement.host, port: announcement.port });
    this.socket = socket;
    this.connectedTo = target;
    this.phoneName = announcement.name;
    this.authenticated = false;
    socket.setNoDelay(true);
    socket.setKeepAlive(true, 30_000);
    socket.on("connect", () => {
      this.helloTimer = setTimeout(
        () => this.onHelloTimeout(socket),
        this.options.helloTimeoutMs ?? PHONE_HELLO_TIMEOUT_MS,
      );
      this.helloTimer.unref();
      this.write({
        type: "hello",
        v: 1,
        machineId: this.options.config.machineId,
        machineName: this.options.config.machineName,
        token: this.options.config.token,
      });
    });
    socket.on("data", (chunk) => this.onData(chunk));
    socket.on("error", (error) => {
      // name alone is "Error" for every socket failure, which says nothing
      // about whether the phone reset the link, went unreachable, or refused
      // the dial. The errno code is the part worth having in a log.
      this.options.logger.warn("phone_link_error", {
        reason: error.name,
        code: (error as NodeJS.ErrnoException).code ?? "none",
      });
    });
    socket.on("close", () => this.onClose());
  }

  private subscribe(): () => void {
    const upsert = this.options.store.onUpsert((session) => this.reconcileSessions(session.id));
    const removed = this.options.store.onRemoved((sessionId) => this.reconcileSessions(sessionId));
    return () => {
      upsert();
      removed();
    };
  }

  private onClose(): void {
    const wasAuthenticated = this.authenticated;
    const target = this.connectedTo;
    this.clearHelloTimer();
    this.clearPingTimer();
    this.unsubscribe?.();
    this.unsubscribe = undefined;
    this.socket = undefined;
    this.authenticated = false;
    this.publishedSessions.clear();
    this.buffer = "";
    this.clearDetailRefreshTimer();
    this.pendingDetailSessionId = undefined;
    this.openSessionId = undefined;
    this.connectedTo = undefined;
    this.phoneName = undefined;
    if (target && !this.rejectedPhones.has(target)) {
      this.retryAt.set(target, this.now() + (this.options.reconnectDelayMs ?? RECONNECT_DELAY_MS));
    }
    if (wasAuthenticated && target) {
      this.options.logger.info("phone_link_closed", { target });
      this.options.onDisconnected?.();
    }
    if (!this.stopped) {
      // Discovery keeps running; give the phone a moment before dialling again.
      if (this.reconnectTimer) {
        clearTimeout(this.reconnectTimer);
      }
      this.reconnectTimer = setTimeout(
        () => this.announce(),
        this.options.reconnectDelayMs ?? RECONNECT_DELAY_MS,
      );
      this.reconnectTimer.unref();
    }
  }

  private onData(chunk: Buffer): void {
    this.buffer += chunk.toString("utf8");
    if (this.buffer.length > MAX_LINE_BYTES) {
      this.options.logger.warn("phone_link_flood");
      this.socket?.destroy();
      return;
    }
    let newline = this.buffer.indexOf("\n");
    while (newline >= 0) {
      const line = this.buffer.slice(0, newline).trim();
      this.buffer = this.buffer.slice(newline + 1);
      if (line) {
        this.handleLine(line);
      }
      newline = this.buffer.indexOf("\n");
    }
  }

  private handleLine(line: string): void {
    let message: Record<string, unknown>;
    try {
      const parsed: unknown = JSON.parse(line);
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        return;
      }
      message = parsed as Record<string, unknown>;
    } catch {
      return;
    }
    switch (message.type) {
      case "hello_ack":
        this.onHelloAck();
        break;
      case "hello_reject":
        this.onHelloReject(message.reason);
        break;
      default:
        if (!this.authenticated) {
          return;
        }
        this.handleAuthenticatedMessage(message);
        break;
    }
  }

  private handleAuthenticatedMessage(message: Record<string, unknown>): void {
    switch (message.type) {
      case "refresh":
        this.sendSnapshot();
        break;
      case "detail_open": {
        const sessionId = typeof message.sessionId === "string" ? message.sessionId : undefined;
        if (!sessionId) {
          break;
        }
        this.clearDetailRefreshTimer();
        this.pendingDetailSessionId = undefined;
        this.openSessionId = sessionId;
        this.options.logger.info("phone_link_detail_open", { sessionId: sessionId.slice(0, 8) });
        this.options.onDetailOpen?.(sessionId);
        this.queueDetailSend(sessionId);
        break;
      }
      case "detail_close":
        this.clearDetailRefreshTimer();
        this.pendingDetailSessionId = undefined;
        this.openSessionId = undefined;
        break;
      case "ping":
        this.send({ type: "pong", t: message.t });
        break;
      case "fs_list": {
        const id =
          typeof message.id === "string" && message.id.length > 0 && message.id.length <= 64
            ? message.id
            : undefined;
        const socket = this.socket;
        if (!id || !socket) {
          break;
        }
        void this.sendFsListing(socket, id, message.path);
        break;
      }
      case "thread_start": {
        const id =
          typeof message.id === "string" && message.id.length > 0 && message.id.length <= 64
            ? message.id
            : undefined;
        const socket = this.socket;
        if (!id || !socket) {
          break;
        }
        void this.sendThreadStarted(
          socket,
          id,
          message.provider,
          message.path,
          message.prompt,
        );
        break;
      }
      case "approval_decision": {
        const requestId =
          typeof message.requestId === "string" && message.requestId.length > 0
            ? message.requestId
            : undefined;
        const decision =
          message.decision === "allow" || message.decision === "deny"
            ? message.decision
            : undefined;
        if (requestId && decision) {
          this.options.onApprovalDecision?.(requestId, decision);
        }
        break;
      }
      default:
        break;
    }
  }

  private onHelloAck(): void {
    if (this.authenticated || !this.socket || this.socket.destroyed) {
      return;
    }
    this.authenticated = true;
    this.clearHelloTimer();
    if (this.connectedTo) {
      this.rejectedPhones.delete(this.connectedTo);
      this.retryAt.delete(this.connectedTo);
    }
    this.options.logger.info("phone_link_connected", {
      phone: this.phoneName ?? "phone",
      target: this.connectedTo ?? "unknown",
    });
    this.sendSnapshot();
    this.unsubscribe = this.subscribe();
    // The phone hangs up after 90 quiet seconds; TCP keepalive never reaches
    // its read loop, so an idle board needs application-level pings.
    this.clearPingTimer();
    this.pingTimer = setInterval(() => {
      this.send({ type: "ping", t: this.now() });
    }, LINK_PING_INTERVAL_MS);
    this.pingTimer.unref();
    this.options.onConnected?.();
  }

  private clearPingTimer(): void {
    if (this.pingTimer) {
      clearInterval(this.pingTimer);
      this.pingTimer = undefined;
    }
  }

  private onHelloReject(rawReason: unknown): void {
    if (this.authenticated || !this.connectedTo) {
      return;
    }
    const reason = rawReason === "bad_token" ? "bad_token" : "unknown_machine";
    const reportedReason = this.reasonKey(rawReason);
    const previous = this.rejectedPhones.get(this.connectedTo);
    this.rejectedPhones.set(this.connectedTo, {
      retryAt: this.now() + REFUSAL_RECONNECT_DELAY_MS,
      reportedReason,
    });

    if (!previous || previous.reportedReason !== reportedReason) {
      if (rawReason !== "unknown_machine" && rawReason !== "bad_token") {
        this.options.logger.warn("phone_link_reject_unknown_reason", {
          reason: rawReason,
          target: this.connectedTo,
        });
      }
      const message =
        reason === "bad_token"
          ? "The phone refused this computer because its saved token no longer matches, usually because the daemon identity was regenerated. On the phone, use Forget computers to clear it, then open Agents \u2192 Link a computer and start the daemon again."
          : "The phone refused this computer because it is not linked and the linking window is closed. On the phone, open Agents \u2192 Link a computer, then start the daemon again.";
      this.options.logger.warn("phone_link_rejected", {
        reason,
        rawReason,
        target: this.connectedTo,
        message,
      });
      this.tellOperator(message);
    }
    this.socket?.destroy();
  }

  private onHelloTimeout(socket: TcpSocket): void {
    if (this.socket !== socket || this.authenticated) {
      return;
    }
    this.options.logger.warn("phone_link_hello_timeout", {
      target: this.connectedTo ?? "unknown",
      timeoutMs: this.options.helloTimeoutMs ?? PHONE_HELLO_TIMEOUT_MS,
    });
    socket.destroy();
  }

  private clearHelloTimer(): void {
    if (this.helloTimer) {
      clearTimeout(this.helloTimer);
      this.helloTimer = undefined;
    }
  }

  private tellOperator(message: string): void {
    if (this.options.operatorMessage) {
      this.options.operatorMessage(message);
      return;
    }
    try {
      process.stderr.write(`nexus-agentd: ${message}\n`);
    } catch {
      // The durable log still contains the full operator guidance.
    }
  }

  private reasonKey(reason: unknown): string {
    try {
      return JSON.stringify(reason);
    } catch {
      return String(reason);
    }
  }

  private async sendDetail(sessionId: string): Promise<void> {
    const messages = await this.options.detailProvider(sessionId, DETAIL_MESSAGE_LIMIT);
    this.options.logger.info("phone_link_detail_read", {
      sessionId: sessionId.slice(0, 8),
      messages: messages.length,
    });
    if (this.openSessionId !== sessionId) {
      return;
    }
    const session = this.options.store.get(sessionId) ?? null;
    this.send({
      type: "detail",
      sessionId,
      // The phone routes a detail frame by provider; omitting it means Claude.
      provider: session?.provider ?? "claude",
      session,
      messages,
    });
  }

  private scheduleCodexDetailRefresh(session: Session): void {
    if (session.provider !== "codex" || this.openSessionId !== session.id) {
      return;
    }
    this.clearDetailRefreshTimer();
    this.detailRefreshTimer = setTimeout(() => {
      this.detailRefreshTimer = undefined;
      if (this.openSessionId === session.id) {
        this.queueDetailSend(session.id);
      }
    }, this.options.codexDetailRefreshDelayMs ?? CODEX_DETAIL_REFRESH_DELAY_MS);
    this.detailRefreshTimer.unref();
  }

  private queueDetailSend(sessionId: string): void {
    if (this.detailSendInFlight) {
      this.pendingDetailSessionId = sessionId;
      return;
    }
    const operation = this.sendDetail(sessionId);
    this.detailSendInFlight = operation;
    void operation
      .catch((error) => {
        this.options.logger.info("phone_link_detail_read_failed", {
          sessionId: sessionId.slice(0, 8),
          reason: error instanceof Error ? error.message : String(error),
        });
      })
      .finally(() => {
        if (this.detailSendInFlight !== operation) {
          return;
        }
        this.detailSendInFlight = undefined;
        const pendingSessionId = this.pendingDetailSessionId;
        this.pendingDetailSessionId = undefined;
        if (pendingSessionId && this.openSessionId === pendingSessionId) {
          this.queueDetailSend(pendingSessionId);
        }
      });
  }

  private clearDetailRefreshTimer(): void {
    if (this.detailRefreshTimer) {
      clearTimeout(this.detailRefreshTimer);
      this.detailRefreshTimer = undefined;
    }
  }

  private async sendFsListing(
    socket: TcpSocket,
    id: string,
    requestedPath: unknown,
  ): Promise<void> {
    let listing: FsListing;
    try {
      if (requestedPath === undefined || requestedPath === null) {
        listing = listRoots(platform(), homedir(), existsSync);
      } else if (typeof requestedPath === "string") {
        listing = await listDirectory(requestedPath, readdir);
      } else {
        listing = {
          path: null,
          parent: null,
          entries: [],
          truncated: false,
          error: "Path must be a local absolute path",
        };
      }
    } catch {
      listing = {
        path: typeof requestedPath === "string" ? requestedPath : null,
        parent: null,
        entries: [],
        truncated: false,
        error: "Unable to list directory",
      };
    }
    if (this.socket !== socket || socket.destroyed || !this.authenticated) {
      return;
    }
    this.send({ type: "fs_listing", id, ...listing });
  }

  private async sendThreadStarted(
    socket: TcpSocket,
    id: string,
    rawProvider: unknown,
    requestedPath: unknown,
    rawPrompt: unknown,
  ): Promise<void> {
    const provider: AgentProvider | undefined =
      rawProvider === "codex" || rawProvider === "claude" ? rawProvider : undefined;
    const prompt = typeof rawPrompt === "string" ? rawPrompt.trim() : "";
    const promptLength = Buffer.byteLength(prompt, "utf8");
    let result: ThreadStartResult;

    if (!provider) {
      result = { ok: false, error: "Unknown provider" };
    } else if (typeof requestedPath !== "string") {
      result = { ok: false, error: "Path must be a local absolute path" };
    } else {
      const pathError = await validateExistingDirectory(requestedPath, stat);
      if (pathError) {
        result = { ok: false, error: pathError };
      } else if (typeof rawPrompt !== "string") {
        result = {
          ok: false,
          error: provider === "claude" ? "Prompt is required for Claude" : "Prompt must be a string",
        };
      } else if (promptLength > MAX_THREAD_PROMPT_BYTES) {
        result = { ok: false, error: "Prompt is too long" };
      } else if (provider === "claude" && !prompt) {
        result = { ok: false, error: "Prompt is required for Claude" };
      } else {
        result = await this.invokeThreadStart(provider, requestedPath, prompt);
      }
    }

    this.options.logger.info("phone_link_thread_start", {
      provider: provider ?? (typeof rawProvider === "string" ? rawProvider.slice(0, 16) : "unknown"),
      ok: result.ok,
      path: typeof requestedPath === "string" ? requestedPath.slice(0, 40) : "",
      promptLength,
    });
    if (this.socket !== socket || socket.destroyed || !this.authenticated) {
      return;
    }
    this.send({
      type: "thread_started",
      id,
      ok: result.ok,
      provider: provider ?? (typeof rawProvider === "string" ? rawProvider : null),
      ...(result.ok && result.sessionId ? { sessionId: result.sessionId } : {}),
      error: result.ok ? null : result.error ?? "Unable to start thread",
    });
  }

  private invokeThreadStart(
    provider: AgentProvider,
    requestedPath: string,
    prompt: string,
  ): Promise<ThreadStartResult> {
    const callback = this.options.onThreadStart;
    if (!callback) {
      return Promise.resolve({ ok: false, error: "Thread start is not available" });
    }
    return new Promise<ThreadStartResult>((resolve) => {
      let settled = false;
      const finish = (result: ThreadStartResult) => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timer);
        resolve(result);
      };
      const timer = setTimeout(
        () => finish({ ok: false, error: "Timed out" }),
        Math.min(
          this.options.threadStartTimeoutMs ?? THREAD_START_TIMEOUT_MS,
          THREAD_START_TIMEOUT_MS,
        ),
      );
      timer.unref();
      void Promise.resolve()
        .then(() => callback(provider, requestedPath, prompt))
        .then(finish, (error) => finish({ ok: false, error: shortErrorMessage(error) }));
    });
  }

  private sendSnapshot(): void {
    const sessions = sessionsForPhone(this.options.store.list());
    this.publishedSessions = new Map(sessions.map((session) => [session.id, session]));
    this.send({
      type: "snapshot",
      seq: this.sequence,
      sessions,
    });
  }

  private reconcileSessions(changedSessionId: string): void {
    if (!this.authenticated) {
      return;
    }
    const sessions = sessionsForPhone(this.options.store.list());
    const next = new Map(sessions.map((session) => [session.id, session]));
    for (const sessionId of this.publishedSessions.keys()) {
      if (!next.has(sessionId)) {
        this.sendRemoved(sessionId);
      }
    }
    for (const session of sessions) {
      if (!this.publishedSessions.has(session.id) || session.id === changedSessionId) {
        this.sendUpsert(session);
      }
    }
    this.publishedSessions = next;
  }

  private sendUpsert(session: Session): void {
    this.sequence += 1;
    this.send({ type: "session_upsert", seq: this.sequence, session });
    this.scheduleCodexDetailRefresh(session);
  }

  private sendRemoved(sessionId: string): void {
    this.sequence += 1;
    this.send({ type: "session_removed", seq: this.sequence, sessionId });
  }

  private send(message: Record<string, unknown>): boolean {
    if (!this.authenticated) {
      return false;
    }
    return this.write(message);
  }

  private write(message: Record<string, unknown>): boolean {
    const socket = this.socket;
    if (!socket || socket.destroyed) {
      return false;
    }
    try {
      socket.write(`${JSON.stringify(message)}\n`);
      return true;
    } catch {
      return false;
    }
  }
}

function shortErrorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) {
    return error.message.trim().slice(0, 200);
  }
  return "Unable to start thread";
}

export function sessionsForPhone(sessions: Session[]): Session[] {
  const counts = new Map<Session["provider"], number>();
  return sessions.filter((session) => {
    const count = counts.get(session.provider) ?? 0;
    if (count >= MAX_PHONE_SESSIONS_PER_PROVIDER) {
      return false;
    }
    counts.set(session.provider, count + 1);
    return true;
  });
}
