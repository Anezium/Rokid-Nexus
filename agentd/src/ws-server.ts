import { timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import type { AddressInfo } from "node:net";
import path from "node:path";
import WebSocket, { WebSocketServer, type RawData } from "ws";
import { SessionStore } from "./session-store";
import type {
  ApprovalDecision,
  ApprovalOutcome,
  ApprovalRequest,
} from "./approval-manager";
import type { TerminalInputOutcome } from "./terminal-input";
import type { AgentConfig, Logger, Session, SessionMessage } from "./types";

const PROTOCOL_VERSION = 1;
const SERVER_VERSION = (() => {
  const packageJson = JSON.parse(
    readFileSync(path.resolve(__dirname, "..", "package.json"), "utf8"),
  ) as { version?: unknown };
  if (typeof packageJson.version !== "string") {
    throw new Error("nexus-agentd package version is missing");
  }
  return packageJson.version;
})();

interface ClientState {
  authenticated: boolean;
  lastPongAt: number;
  helloTimer: NodeJS.Timeout;
  pongTimer?: NodeJS.Timeout;
  /** Session whose conversation this client is currently reading, if any. */
  openSessionId?: string;
}

export interface WsHubOptions {
  host?: string;
  helloTimeoutMs?: number;
  keepaliveIntervalMs?: number;
  pongTimeoutMs?: number;
  /** Supplies the recent conversation of a session for the detail view. */
  detailProvider?: (sessionId: string, limit: number) => Promise<SessionMessage[]>;
  /** Called when a client starts reading a session, so tailing can begin. */
  onDetailOpen?: (sessionId: string) => void;
  /** Called when a phone answers a held tool call over this transport. */
  onApprovalDecision?: (requestId: string, decision: ApprovalDecision) => void;
  /** Types the wearer's text into the session's terminal; see terminal-input. */
  onTerminalInput?: (sessionId: string, text: string) => Promise<TerminalInputOutcome>;
}

const DETAIL_MESSAGE_LIMIT = 40;

function tokenMatches(received: unknown, expected: string): boolean {
  if (typeof received !== "string") {
    return false;
  }
  const left = Buffer.from(received);
  const right = Buffer.from(expected);
  return left.length === right.length && timingSafeEqual(left, right);
}

export class WsHub {
  private server?: WebSocketServer;
  private keepaliveTimer?: NodeJS.Timeout;
  private readonly clients = new Map<WebSocket, ClientState>();
  private sequence = 0;
  private readonly unsubscribeUpsert: () => void;
  private readonly unsubscribeRemoved: () => void;

  constructor(
    private readonly config: AgentConfig,
    private readonly store: SessionStore,
    private readonly logger: Logger,
    private readonly options: WsHubOptions = {},
  ) {
    this.unsubscribeUpsert = store.onUpsert((session) => this.broadcastUpsert(session));
    this.unsubscribeRemoved = store.onRemoved((sessionId) => this.broadcastRemoved(sessionId));
  }

  get seq(): number {
    return this.sequence;
  }

  start(): Promise<void> {
    if (this.server) {
      return Promise.resolve();
    }
    const server = new WebSocketServer({
      host: this.options.host ?? "0.0.0.0",
      port: this.config.wsPort,
    });
    this.server = server;
    server.on("connection", (socket) => this.accept(socket));
    server.on("error", (error) => {
      this.logger.error("ws_server_error", { reason: error.name });
    });

    return new Promise((resolve, reject) => {
      const onError = (error: Error) => {
        server.off("listening", onListening);
        this.server = undefined;
        reject(error);
      };
      const onListening = () => {
        server.off("error", onError);
        const interval = this.options.keepaliveIntervalMs ?? 30_000;
        this.keepaliveTimer = setInterval(() => this.keepalive(), interval);
        this.keepaliveTimer.unref();
        resolve();
      };
      server.once("error", onError);
      server.once("listening", onListening);
    });
  }

  port(): number | undefined {
    const address = this.server?.address();
    return address && typeof address !== "string" ? (address as AddressInfo).port : undefined;
  }

  async stop(): Promise<void> {
    if (this.keepaliveTimer) {
      clearInterval(this.keepaliveTimer);
      this.keepaliveTimer = undefined;
    }
    for (const [socket, state] of this.clients) {
      clearTimeout(state.helloTimer);
      if (state.pongTimer) {
        clearTimeout(state.pongTimer);
      }
      socket.terminate();
    }
    this.clients.clear();
    const server = this.server;
    this.server = undefined;
    if (server) {
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
    this.unsubscribeUpsert();
    this.unsubscribeRemoved();
  }

  private accept(socket: WebSocket): void {
    const helloTimer = setTimeout(() => {
      if (!this.clients.get(socket)?.authenticated) {
        socket.close(4408, "hello timeout");
      }
    }, this.options.helloTimeoutMs ?? 5000);
    helloTimer.unref();
    this.clients.set(socket, {
      authenticated: false,
      lastPongAt: Date.now(),
      helloTimer,
    });

    socket.on("message", (data, isBinary) => this.onMessage(socket, data, isBinary));
    socket.on("error", (error) => {
      this.logger.warn("ws_client_error", { reason: error.name });
    });
    socket.on("close", () => {
      const state = this.clients.get(socket);
      if (state) {
        clearTimeout(state.helloTimer);
        if (state.pongTimer) {
          clearTimeout(state.pongTimer);
        }
      }
      this.clients.delete(socket);
    });
  }

  private onMessage(socket: WebSocket, data: RawData, isBinary: boolean): void {
    const state = this.clients.get(socket);
    if (!state) {
      return;
    }
    if (isBinary) {
      if (!state.authenticated) {
        socket.close(4401, "authentication required");
      } else {
        this.logger.info("ws_binary_ignored");
      }
      return;
    }

    let message: Record<string, unknown>;
    try {
      const parsed: unknown = JSON.parse(data.toString());
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Error("not an object");
      }
      message = parsed as Record<string, unknown>;
    } catch {
      if (!state.authenticated) {
        socket.close(4401, "authentication required");
      } else {
        this.logger.info("ws_invalid_json_ignored");
      }
      return;
    }

    if (!state.authenticated) {
      if (
        message.type !== "hello" ||
        message.v !== PROTOCOL_VERSION ||
        !tokenMatches(message.token, this.config.token)
      ) {
        // A phone treats 4401 as final and stops reconnecting, so this is the
        // difference between a link that is retrying and one that has given up
        // for good. Worth saying which of the three it was, without echoing the
        // token itself.
        this.logger.warn("ws_auth_rejected", {
          reason: message.type !== "hello"
            ? "not_hello"
            : message.v !== PROTOCOL_VERSION
              ? "version"
              : "token",
        });
        socket.close(4401, "authentication failed");
        return;
      }
      state.authenticated = true;
      state.lastPongAt = Date.now();
      clearTimeout(state.helloTimer);
      this.armPongDeadline(socket, state);
      this.send(socket, {
        type: "hello_ack",
        v: PROTOCOL_VERSION,
        server: {
          name: "nexus-agentd",
          version: SERVER_VERSION,
          machineId: this.config.machineId,
          machineName: this.config.machineName,
        },
      });
      this.sendSnapshot(socket);
      return;
    }

    switch (message.type) {
      case "refresh":
        this.sendSnapshot(socket);
        break;
      case "pong":
        state.lastPongAt = Date.now();
        this.armPongDeadline(socket, state);
        break;
      case "detail_open": {
        const sessionId = typeof message.sessionId === "string" ? message.sessionId : undefined;
        if (!sessionId) {
          break;
        }
        state.openSessionId = sessionId;
        this.options.onDetailOpen?.(sessionId);
        void this.sendDetail(socket, sessionId);
        break;
      }
      case "detail_close":
        state.openSessionId = undefined;
        break;
      case "session_input": {
        void this.answerTerminalInput(socket, message);
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
        this.logger.info("ws_unknown_message", {
          type: typeof message.type === "string" ? message.type.slice(0, 80) : "missing",
        });
        break;
    }
  }

  private async sendDetail(socket: WebSocket, sessionId: string): Promise<void> {
    const provider = this.options.detailProvider;
    const messages = provider ? await provider(sessionId, DETAIL_MESSAGE_LIMIT) : [];
    const state = this.clients.get(socket);
    // The wearer may have left the conversation while the tail was being read.
    if (!state?.authenticated || state.openSessionId !== sessionId) {
      return;
    }
    this.send(socket, {
      type: "detail",
      sessionId,
      session: this.store.get(sessionId) ?? null,
      messages,
    });
  }

  /**
   * True while some phone has authenticated over this transport, which is what
   * makes it usable for holding a tool call. A phone that dialled in here can
   * answer approvals exactly like one the daemon dialled: the plugin speaks the
   * same frames on both, so only the daemon needed teaching.
   */
  get hasAuthenticatedClient(): boolean {
    for (const state of this.clients.values()) {
      if (state.authenticated) {
        return true;
      }
    }
    return false;
  }

  /** The WebSocket half of typed input; both transports share the judgement. */
  private async answerTerminalInput(
    socket: WebSocket,
    message: Record<string, unknown>,
  ): Promise<void> {
    const id =
      typeof message.id === "string" && message.id.length > 0 && message.id.length <= 64
        ? message.id
        : undefined;
    if (!id) {
      return;
    }
    const sessionId = typeof message.sessionId === "string" ? message.sessionId : "";
    const outcome = this.options.onTerminalInput
      ? await this.options
          .onTerminalInput(sessionId, typeof message.text === "string" ? message.text : "")
          .catch(() => ({ ok: false, error: "Could not send that" }) as TerminalInputOutcome)
      : { ok: false, error: "Typing from the glasses is not available" };
    this.logger.info("ws_session_input", { sessionId: sessionId.slice(0, 16), ok: outcome.ok });
    if (!this.clients.get(socket)?.authenticated) {
      return;
    }
    this.send(socket, {
      type: "session_input_result",
      id,
      ok: outcome.ok,
      error: outcome.ok ? null : outcome.error ?? "Could not send that",
    });
  }

  sendApprovalRequest(request: ApprovalRequest): boolean {
    return this.broadcastToAuthenticated({ ...request });
  }

  sendApprovalResolved(requestId: string, outcome: ApprovalOutcome): boolean {
    return this.broadcastToAuthenticated({
      type: "approval_resolved",
      v: 1,
      requestId,
      outcome,
    });
  }

  /** Delivered is "at least one phone got it", matching the dialled link's contract. */
  private broadcastToAuthenticated(message: Record<string, unknown>): boolean {
    let delivered = false;
    for (const [socket, state] of this.clients) {
      if (state.authenticated) {
        this.send(socket, message);
        delivered = true;
      }
    }
    return delivered;
  }

  /** Streams a newly appended message to whoever is reading that session. */
  broadcastDetailMessage(sessionId: string, message: SessionMessage): void {
    for (const [socket, state] of this.clients) {
      if (state.authenticated && state.openSessionId === sessionId) {
        this.send(socket, { type: "detail_append", sessionId, message });
      }
    }
  }

  private sendSnapshot(socket: WebSocket): void {
    this.send(socket, {
      type: "snapshot",
      seq: this.sequence,
      sessions: this.store.list(),
    });
  }

  private broadcastUpsert(session: Session): void {
    this.sequence += 1;
    this.broadcast({
      type: "session_upsert",
      seq: this.sequence,
      session,
    });
  }

  private broadcastRemoved(sessionId: string): void {
    this.sequence += 1;
    this.broadcast({
      type: "session_removed",
      seq: this.sequence,
      sessionId,
    });
  }

  private keepalive(): void {
    const now = Date.now();
    const pongTimeout = this.options.pongTimeoutMs ?? 90_000;
    for (const [socket, state] of this.clients) {
      if (!state.authenticated || socket.readyState !== WebSocket.OPEN) {
        continue;
      }
      if (now - state.lastPongAt >= pongTimeout) {
        socket.close(4409, "pong timeout");
        continue;
      }
      this.send(socket, { type: "ping", t: now });
      socket.ping();
    }
  }

  private broadcast(message: Record<string, unknown>): void {
    for (const [socket, state] of this.clients) {
      if (state.authenticated && socket.readyState === WebSocket.OPEN) {
        this.send(socket, message);
      }
    }
  }

  private send(socket: WebSocket, message: Record<string, unknown>): void {
    if (socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(message));
    }
  }

  private armPongDeadline(socket: WebSocket, state: ClientState): void {
    if (state.pongTimer) {
      clearTimeout(state.pongTimer);
    }
    state.pongTimer = setTimeout(() => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.close(4409, "pong timeout");
      }
    }, this.options.pongTimeoutMs ?? 90_000);
    state.pongTimer.unref();
  }
}
