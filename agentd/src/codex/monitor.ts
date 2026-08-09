import { spawn, type ChildProcess } from "node:child_process";
import WebSocket from "ws";
import {
  type ApprovalDecision,
  type ApprovalManager,
  type ApprovalRequest,
} from "../approval-manager";
import {
  normalizeCwd,
  projectName,
  type SessionStore,
  truncateText,
} from "../session-store";
import type {
  AgentConfig,
  Logger,
  PendingRequest,
  Session,
  SessionMessage,
  ThreadStartResult,
} from "../types";
import { codexThreadMessages } from "./messages";
import { codexSpawnSpec } from "./process";
import type {
  AdditionalFileSystemPermissions,
  AdditionalNetworkPermissions,
  ApprovalServerRequest,
  CommandApprovalParams,
  FileChangeApprovalParams,
  PermissionsApprovalParams,
  RequestId,
  RpcMessage,
  Thread,
  ThreadListResponse,
  ThreadReadResponse,
  ThreadResumeResponse,
  ThreadStartParams,
  ThreadStartResponse,
  Turn,
  TurnStartParams,
  TurnStartResponse,
} from "./protocol";

// The HUD board never shows more than 63 rows, and one thread/resume replays a
// full rollout: a deep backlog at 200 threads kept the first synchronize from
// ever finishing on a real machine. Sixty recent threads is more than the
// glasses can show and cheap enough to resume.
export const MAX_CODEX_SESSIONS = 60;
const APP_SERVER_PAGE_SIZE = 60;
const DEFAULT_CONNECT_TIMEOUT_MS = 800;
const DEFAULT_REQUEST_TIMEOUT_MS = 30_000;
const DEFAULT_START_TIMEOUT_MS = 5_000;
const DEFAULT_RECONNECT_DELAY_MS = 3_000;
const START_RETRY_MS = 150;
const REFRESH_CONCURRENCY = 8;
// The app-server only pushes thread/started for turns it hosts itself. Threads
// born in a terminal (codex exec, the CLI) reach the store only when listed, so
// the monitor re-lists on a slow clock and adopts what it has never seen.
const DEFAULT_SWEEP_INTERVAL_MS = 60_000;

export interface CodexAvailability {
  enabled: boolean;
  available: boolean;
  reason?: string;
}

export { codexSpawnSpec, resolveCodexExecutable } from "./process";

interface PendingRpc {
  timer: NodeJS.Timeout;
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
}

interface PendingCodexApproval {
  phoneRequestId: string;
  rpcId: RequestId;
  threadId: string;
  summary: string;
  createdAt: number;
}

export interface CodexMonitorOptions {
  config: Pick<AgentConfig, "machineId" | "machineName" | "codex">;
  store: SessionStore;
  approvals: ApprovalManager;
  logger: Logger;
  connectTimeoutMs?: number;
  requestTimeoutMs?: number;
  startTimeoutMs?: number;
  reconnectDelayMs?: number;
  sweepIntervalMs?: number;
  now?: () => number;
  launch?: (port: number) => ChildProcess;
}

type ApprovalParams =
  | CommandApprovalParams
  | FileChangeApprovalParams
  | PermissionsApprovalParams;

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;
}

function errorMessage(value: unknown): string {
  return value instanceof Error ? value.message : String(value);
}

function rpcIdKey(id: RequestId): string {
  return `${typeof id === "number" ? "number" : "string"}:${String(id)}`;
}

export function codexApprovalRequestId(id: RequestId): string {
  const value = typeof id === "number" ? String(id) : id;
  return `codex:${typeof id === "number" ? "n" : "s"}:${Buffer.from(value).toString("base64url")}`;
}

function approvalThreadId(params: ApprovalParams): string | undefined {
  return typeof params.threadId === "string" && params.threadId
    ? params.threadId
    : undefined;
}

function permissionDetail(params: PermissionsApprovalParams): string {
  const parts: string[] = [];
  if (params.permissions.network) {
    parts.push("network access");
  }
  const fileSystem = params.permissions.fileSystem;
  if (fileSystem?.read?.length) {
    parts.push(`read: ${fileSystem.read.map(normalizeCwd).join(", ")}`);
  }
  if (fileSystem?.write?.length) {
    parts.push(`write: ${fileSystem.write.map(normalizeCwd).join(", ")}`);
  }
  return parts.join("; ");
}

export function describeCodexApproval(request: ApprovalServerRequest): {
  tool: string;
  summary: string;
  detail: string;
  createdAt: number;
} {
  switch (request.method) {
    case "item/commandExecution/requestApproval": {
      const params = request.params;
      const command = params.command?.trim();
      const reason = params.reason?.trim();
      return {
        tool: "Command",
        summary: truncateText(reason || command || "Run a command", 120),
        detail: truncateText(command || reason || normalizeCwd(params.cwd ?? undefined), 400),
        createdAt: params.startedAtMs,
      };
    }
    case "item/fileChange/requestApproval": {
      const params = request.params;
      const reason = params.reason?.trim();
      const root = normalizeCwd(params.grantRoot ?? undefined);
      return {
        tool: "File change",
        summary: truncateText(reason || (root ? `Change files under ${root}` : "Change files"), 120),
        detail: truncateText(root || reason || "Codex requested permission to change files.", 400),
        createdAt: params.startedAtMs,
      };
    }
    case "item/permissions/requestApproval": {
      const params = request.params;
      const reason = params.reason?.trim();
      const detail = permissionDetail(params);
      return {
        tool: "Permissions",
        summary: truncateText(reason || detail || "Grant additional permissions", 120),
        detail: truncateText(detail || reason || normalizeCwd(params.cwd), 400),
        createdAt: params.startedAtMs,
      };
    }
  }
}

export function codexApprovalResponse(
  request: ApprovalServerRequest,
  decision: ApprovalDecision,
): Record<string, unknown> {
  if (request.method !== "item/permissions/requestApproval") {
    return { decision: decision === "allow" ? "accept" : "decline" };
  }

  const permissions: {
    network?: AdditionalNetworkPermissions;
    fileSystem?: AdditionalFileSystemPermissions;
  } = {};
  if (decision === "allow") {
    if (request.params.permissions.network) {
      permissions.network = request.params.permissions.network;
    }
    if (request.params.permissions.fileSystem) {
      permissions.fileSystem = request.params.permissions.fileSystem;
    }
  }
  return { permissions, scope: "turn" };
}

function latestTurn(thread: Thread): Turn | undefined {
  return thread.turns.at(-1);
}

function threadActivityAt(thread: Thread): number {
  const turn = latestTurn(thread);
  const seconds = Math.max(
    thread.createdAt,
    thread.updatedAt,
    thread.recencyAt ?? 0,
    turn?.startedAt ?? 0,
    turn?.completedAt ?? 0,
  );
  return seconds * 1000;
}

export function normalizeCodexThread(
  thread: Thread,
  identity: Pick<AgentConfig, "machineId" | "machineName">,
  pendingApproval?: Pick<PendingCodexApproval, "summary" | "createdAt">,
  terminalError?: string,
): Session {
  const cwd = normalizeCwd(thread.cwd);
  const project = projectName(cwd);
  const turn = latestTurn(thread);
  const waitingOnApproval =
    thread.status.type === "active" &&
    thread.status.activeFlags.includes("waitingOnApproval");
  const failed =
    terminalError ||
    (thread.status.type === "systemError" ? "Codex app-server reported a system error." : undefined) ||
    (turn?.status === "failed" ? turn.error?.message || "Codex turn failed." : undefined);
  const working =
    thread.status.type === "active" ||
    turn?.status === "inProgress";
  const status = pendingApproval || waitingOnApproval
    ? "needs_you"
    : failed
      ? "error"
      : working
        ? "working"
        : "idle";
  const pendingRequest: PendingRequest | undefined =
    pendingApproval || waitingOnApproval
      ? {
          kind: "permission",
          summary: truncateText(
            pendingApproval?.summary || "Codex is waiting for approval.",
            140,
          ),
          createdAt: pendingApproval?.createdAt ?? threadActivityAt(thread),
        }
      : undefined;
  const activeSince =
    turn?.status === "inProgress"
      ? (turn.startedAt ?? thread.updatedAt) * 1000
      : undefined;

  return {
    id: thread.id,
    provider: "codex",
    machineId: identity.machineId,
    machineName: identity.machineName,
    title: truncateText(
      thread.name?.trim() || thread.preview.trim() || project || thread.id.slice(0, 8),
      120,
    ),
    cwd,
    project,
    status,
    statusDetail: status === "error" ? truncateText(failed || "Codex error", 140) : undefined,
    stale: false,
    lastActivityAt: Math.max(threadActivityAt(thread), pendingApproval?.createdAt ?? 0),
    turn: activeSince === undefined ? undefined : { activeSince },
    pendingRequest,
  };
}

async function inBatches<T>(
  values: T[],
  operation: (value: T) => Promise<void>,
): Promise<void> {
  for (let index = 0; index < values.length; index += REFRESH_CONCURRENCY) {
    await Promise.all(
      values.slice(index, index + REFRESH_CONCURRENCY).map(operation),
    );
  }
}

export class CodexMonitor {
  private socket?: WebSocket;
  private ownedProcess?: ChildProcess;
  private reconnectTimer?: NodeJS.Timeout;
  private sweepTimer?: NodeJS.Timeout;
  private sweeping = false;
  private connecting = false;
  private stopped = true;
  private nextRequestId = 0;
  private readonly pendingRpc = new Map<number, PendingRpc>();
  private readonly threads = new Map<string, Thread>();
  private readonly approvalsByRpcId = new Map<string, PendingCodexApproval>();
  private readonly terminalErrors = new Map<string, string>();
  private readonly now: () => number;
  private state: CodexAvailability;
  private lastChildError = "";

  constructor(private readonly options: CodexMonitorOptions) {
    this.now = options.now ?? Date.now;
    this.state = options.config.codex.enabled
      ? { enabled: true, available: false, reason: "connecting" }
      : { enabled: false, available: false, reason: "disabled" };
  }

  availability(): CodexAvailability {
    return { ...this.state };
  }

  async readMessages(threadId: string, limit: number): Promise<SessionMessage[]> {
    if (!this.state.available || this.stopped || !this.socket || limit <= 0) {
      return [];
    }
    const response = await this.request<ThreadReadResponse>("thread/read", {
      threadId,
      includeTurns: true,
    });
    return codexThreadMessages(response.thread, limit);
  }

  async startThread(cwd: string, prompt: string): Promise<ThreadStartResult> {
    if (!this.state.available || this.stopped || !this.socket) {
      return { ok: false, error: "Codex is not available on this computer" };
    }
    try {
      const response = await this.request<ThreadStartResponse>(
        "thread/start",
        { cwd } satisfies ThreadStartParams,
      );
      // A thread born on this connection is already live here: thread/resume
      // would look for a rollout that does not exist yet and fail the spawn.
      // Register and publish directly; the thread/started notification runs
      // the tolerant adoption pass behind us.
      this.threads.set(response.thread.id, response.thread);
      this.trimThreads();
      if (this.threads.has(response.thread.id)) {
        this.publishThread(response.thread.id);
      }
      if (prompt) {
        await this.request<TurnStartResponse>(
          "turn/start",
          {
            threadId: response.thread.id,
            input: [{ type: "text", text: prompt, text_elements: [] }],
          } satisfies TurnStartParams,
        );
      }
      return { ok: true, sessionId: response.thread.id };
    } catch (error) {
      return { ok: false, error: errorMessage(error) };
    }
  }

  start(): void {
    if (!this.options.config.codex.enabled || !this.stopped) {
      return;
    }
    this.stopped = false;
    this.scheduleConnect(0);
  }

  async stop(): Promise<void> {
    this.stopped = true;
    this.clearSweep();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = undefined;
    }
    const socket = this.socket;
    this.socket = undefined;
    if (socket) {
      socket.removeAllListeners();
      socket.on("error", () => undefined);
      socket.terminate();
    }
    this.rejectPendingRpc(new Error("Codex monitor stopped"));
    this.resolveAllApprovals("codex_monitor_stopped");
    const child = this.ownedProcess;
    this.ownedProcess = undefined;
    if (child && child.exitCode === null && child.signalCode === null) {
      child.unref();
      this.options.logger.info("codex_app_server_left_running", { pid: child.pid });
    }
    this.state = {
      enabled: this.options.config.codex.enabled,
      available: false,
      reason: this.options.config.codex.enabled ? "stopped" : "disabled",
    };
  }

  private scheduleConnect(delayMs: number): void {
    if (this.stopped || this.reconnectTimer || this.socket) {
      return;
    }
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      void this.connectCycle();
    }, delayMs);
    this.reconnectTimer.unref();
  }

  private async connectCycle(): Promise<void> {
    if (this.stopped || this.connecting || this.socket) {
      return;
    }
    this.connecting = true;
    try {
      let socket: WebSocket | undefined;
      try {
        socket = await this.openSocket();
        this.options.logger.info("codex_attached", {
          endpoint: this.endpoint(),
          owned: this.ownedProcess !== undefined,
        });
      } catch (attachError) {
        if (this.stopped) {
          return;
        }
        if (!this.ownedProcess) {
          try {
            this.launchAppServer();
          } catch (launchError) {
            this.reportUnavailable(
              `Codex app-server could not start: ${errorMessage(launchError)}`,
            );
            return;
          }
        }
        socket = await this.waitForStartedServer().catch((startError) => {
          const detail = this.lastChildError
            ? `${errorMessage(startError)} (${this.lastChildError})`
            : errorMessage(startError);
          this.reportUnavailable(`Codex app-server is unavailable: ${detail}`);
          this.options.logger.info("codex_attach_failed", {
            reason: errorMessage(attachError),
          });
          return undefined;
        });
      }
      if (socket && !this.stopped) {
        await this.activate(socket);
      } else if (socket) {
        socket.on("error", () => undefined);
        socket.terminate();
      }
    } catch (error) {
      this.reportUnavailable(`Codex app-server connection failed: ${errorMessage(error)}`);
      (this.socket as WebSocket | undefined)?.terminate();
    } finally {
      this.connecting = false;
      if (!this.socket && !this.stopped) {
        this.scheduleConnect(this.options.reconnectDelayMs ?? DEFAULT_RECONNECT_DELAY_MS);
      }
    }
  }

  private launchAppServer(): void {
    const port = this.options.config.codex.port;
    const child = this.options.launch
      ? this.options.launch(port)
      : (() => {
          const spec = codexSpawnSpec(port);
          return spawn(spec.command, spec.args, spec.options);
        })();
    this.ownedProcess = child;
    this.lastChildError = "";
    child.stderr?.setEncoding("utf8");
    child.stderr?.on("data", (chunk: string | Buffer) => {
      this.lastChildError = `${this.lastChildError}${chunk.toString()}`.trim().slice(-2_000);
    });
    child.once("error", (error) => {
      this.lastChildError = error.message;
    });
    child.once("exit", (code, signal) => {
      if (this.ownedProcess !== child) {
        return;
      }
      this.ownedProcess = undefined;
      if (!this.stopped) {
        this.options.logger.info("codex_app_server_exited", { code, signal });
        if (!this.socket) {
          this.scheduleConnect(this.options.reconnectDelayMs ?? DEFAULT_RECONNECT_DELAY_MS);
        }
      }
    });
    child.unref();
    (child.stderr as (NodeJS.ReadableStream & { unref?: () => void }) | null)?.unref?.();
    this.options.logger.info("codex_app_server_started", {
      endpoint: this.endpoint(),
      pid: child.pid,
    });
  }

  private async waitForStartedServer(): Promise<WebSocket> {
    const deadline = Date.now() + (this.options.startTimeoutMs ?? DEFAULT_START_TIMEOUT_MS);
    let lastError: unknown = new Error("startup timed out");
    while (!this.stopped && Date.now() < deadline) {
      try {
        return await this.openSocket();
      } catch (error) {
        lastError = error;
      }
      await new Promise<void>((resolve) => {
        const timer = setTimeout(resolve, START_RETRY_MS);
        timer.unref();
      });
    }
    throw lastError;
  }

  private endpoint(): string {
    return `ws://127.0.0.1:${this.options.config.codex.port}`;
  }

  private openSocket(): Promise<WebSocket> {
    const socket = new WebSocket(this.endpoint());
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        cleanup();
        // Terminating a still-connecting socket emits 'error'; without a
        // listener that brings the whole daemon down (seen live on a slow
        // app-server bind).
        socket.on("error", () => undefined);
        socket.terminate();
        reject(new Error("connection timed out"));
      }, this.options.connectTimeoutMs ?? DEFAULT_CONNECT_TIMEOUT_MS);
      timer.unref();
      const cleanup = () => {
        clearTimeout(timer);
        socket.off("open", onOpen);
        socket.off("error", onError);
      };
      const onOpen = () => {
        cleanup();
        resolve(socket);
      };
      const onError = (error: Error) => {
        cleanup();
        socket.on("error", () => undefined);
        socket.terminate();
        reject(error);
      };
      socket.once("open", onOpen);
      socket.once("error", onError);
    });
  }

  private async activate(socket: WebSocket): Promise<void> {
    this.socket = socket;
    socket.on("message", (data) => this.onMessage(socket, data.toString()));
    socket.on("error", (error) => {
      this.options.logger.info("codex_socket_error", { reason: error.message });
    });
    socket.once("close", () => this.onSocketClosed(socket));

    await this.request("initialize", {
      clientInfo: {
        name: "nexus-agentd",
        title: "Nexus Agent Daemon",
        version: "0.1.0",
      },
      capabilities: {
        experimentalApi: false,
        requestAttestation: false,
      },
    });
    this.write({ method: "initialized" });
    await this.synchronize();
    this.state = { enabled: true, available: true };
    this.options.logger.info("codex_available", {
      endpoint: this.endpoint(),
      sessions: this.threads.size,
    });
    this.armSweep();
  }

  private armSweep(): void {
    if (this.sweepTimer) {
      clearInterval(this.sweepTimer);
    }
    this.sweepTimer = setInterval(
      () => void this.sweepNewThreads(),
      this.options.sweepIntervalMs ?? DEFAULT_SWEEP_INTERVAL_MS,
    );
    this.sweepTimer.unref();
  }

  private clearSweep(): void {
    if (this.sweepTimer) {
      clearInterval(this.sweepTimer);
      this.sweepTimer = undefined;
    }
    this.sweeping = false;
  }

  private async sweepNewThreads(): Promise<void> {
    if (this.stopped || this.sweeping || !this.socket) {
      return;
    }
    this.sweeping = true;
    try {
      const listed = await this.listThreads();
      const unseen = listed.filter((thread) => !this.threads.has(thread.id));
      if (unseen.length > 0) {
        this.options.logger.info("codex_sweep_adopted", { threads: unseen.length });
        await inBatches(unseen, (thread) => this.resumeAndRefresh(thread.id));
      }
    } catch (error) {
      this.options.logger.info("codex_sweep_failed", { reason: errorMessage(error) });
    } finally {
      this.sweeping = false;
    }
  }

  private onSocketClosed(socket: WebSocket): void {
    if (this.socket !== socket) {
      return;
    }
    this.socket = undefined;
    this.clearSweep();
    this.rejectPendingRpc(new Error("Codex app-server connection closed"));
    this.resolveAllApprovals("codex_connection_closed");
    if (!this.stopped) {
      this.reportUnavailable("Codex app-server connection closed; reconnecting.");
      this.scheduleConnect(this.options.reconnectDelayMs ?? DEFAULT_RECONNECT_DELAY_MS);
    }
  }

  private async synchronize(): Promise<void> {
    const resumed = new Map<string, Thread>();
    const previouslyCaredFor = [...this.threads.keys()];

    // Resume first on every connection. thread/read refreshes data but does not
    // subscribe this connection to the live event stream.
    await inBatches(previouslyCaredFor, async (threadId) => {
      const thread = await this.tryResume(threadId);
      if (thread) {
        resumed.set(threadId, thread);
      }
    });

    const listed = await this.listThreads();
    await inBatches(listed, async (thread) => {
      if (resumed.has(thread.id)) {
        return;
      }
      const resumedThread = await this.tryResume(thread.id);
      if (resumedThread) {
        resumed.set(thread.id, resumedThread);
      }
    });

    const refreshed = new Map<string, Thread>();
    await inBatches(listed, async (listedThread) => {
      try {
        const response = await this.request<ThreadReadResponse>("thread/read", {
          threadId: listedThread.id,
          includeTurns: true,
        });
        refreshed.set(listedThread.id, response.thread);
      } catch (error) {
        const fallback = resumed.get(listedThread.id) ?? listedThread;
        refreshed.set(listedThread.id, fallback);
        this.options.logger.info("codex_thread_refresh_failed", {
          threadId: listedThread.id,
          reason: errorMessage(error),
        });
      }
    });

    for (const threadId of this.threads.keys()) {
      if (!refreshed.has(threadId)) {
        this.removeThread(threadId, "authoritative_refresh");
      }
    }
    this.threads.clear();
    for (const thread of refreshed.values()) {
      this.threads.set(thread.id, thread);
      this.publishThread(thread.id);
    }
  }

  private async listThreads(): Promise<Thread[]> {
    const threads: Thread[] = [];
    const seen = new Set<string>();
    let cursor: string | null = null;
    do {
      const response: ThreadListResponse = await this.request<ThreadListResponse>("thread/list", {
        cursor,
        limit: Math.min(APP_SERVER_PAGE_SIZE, MAX_CODEX_SESSIONS - threads.length),
        sortKey: "recency_at",
        sortDirection: "desc",
        archived: false,
        // Omitting this filter means "interactive sources only", which silently
        // hides every `codex exec` run. Sub-agent workers stay out: they are
        // internal machinery, not sessions the wearer supervises.
        sourceKinds: ["cli", "vscode", "exec", "appServer", "unknown"],
      });
      for (const thread of response.data) {
        if (!seen.has(thread.id)) {
          seen.add(thread.id);
          threads.push(thread);
          if (threads.length === MAX_CODEX_SESSIONS) {
            break;
          }
        }
      }
      cursor = threads.length < MAX_CODEX_SESSIONS ? response.nextCursor : null;
    } while (cursor);
    return threads;
  }

  private async tryResume(threadId: string): Promise<Thread | undefined> {
    try {
      return await this.resumeThread(threadId);
    } catch (error) {
      this.options.logger.info("codex_thread_resume_failed", {
        threadId,
        reason: errorMessage(error),
      });
      return undefined;
    }
  }

  private async resumeThread(threadId: string): Promise<Thread> {
    const response = await this.request<ThreadResumeResponse>("thread/resume", { threadId });
    this.threads.set(threadId, response.thread);
    this.publishThread(threadId);
    return response.thread;
  }

  private request<T>(method: string, params: Record<string, unknown>): Promise<T> {
    const id = ++this.nextRequestId;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingRpc.delete(id);
        reject(new Error(`${method} timed out`));
      }, this.options.requestTimeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS);
      timer.unref();
      this.pendingRpc.set(id, {
        timer,
        resolve: (value) => resolve(value as T),
        reject,
      });
      if (!this.write({ id, method, params })) {
        this.pendingRpc.delete(id);
        clearTimeout(timer);
        reject(new Error("Codex app-server is not connected"));
      }
    });
  }

  private onMessage(socket: WebSocket, raw: string): void {
    if (this.socket !== socket) {
      return;
    }
    let message: RpcMessage;
    try {
      const parsed: unknown = JSON.parse(raw);
      const record = asRecord(parsed);
      if (!record) {
        return;
      }
      message = record as RpcMessage;
    } catch {
      this.options.logger.warn("codex_message_invalid");
      return;
    }

    if (message.method && message.id !== undefined) {
      if (this.isApprovalMethod(message.method)) {
        void this.handleApproval(socket, message as ApprovalServerRequest);
      } else {
        this.options.logger.info("codex_server_request_ignored", {
          method: message.method,
        });
      }
      return;
    }
    if (message.method) {
      this.handleNotification(message.method, message.params);
      return;
    }
    if (typeof message.id === "number") {
      const pending = this.pendingRpc.get(message.id);
      if (!pending) {
        return;
      }
      this.pendingRpc.delete(message.id);
      clearTimeout(pending.timer);
      if (message.error) {
        pending.reject(new Error(message.error.message));
      } else {
        pending.resolve(message.result);
      }
    }
  }

  private isApprovalMethod(method: string): method is ApprovalServerRequest["method"] {
    return method === "item/commandExecution/requestApproval" ||
      method === "item/fileChange/requestApproval" ||
      method === "item/permissions/requestApproval";
  }

  private async handleApproval(
    socket: WebSocket,
    request: ApprovalServerRequest,
  ): Promise<void> {
    const threadId = approvalThreadId(request.params);
    if (!threadId) {
      this.options.logger.warn("codex_approval_invalid", { method: request.method });
      return;
    }
    const key = rpcIdKey(request.id);
    if (this.approvalsByRpcId.has(key)) {
      return;
    }
    const described = describeCodexApproval(request);
    const phoneRequestId = codexApprovalRequestId(request.id);
    this.approvalsByRpcId.set(key, {
      phoneRequestId,
      rpcId: request.id,
      threadId,
      summary: described.summary,
      createdAt: described.createdAt,
    });
    this.publishThread(threadId);

    const phoneRequest: ApprovalRequest = {
      type: "approval_request",
      v: 1,
      requestId: phoneRequestId,
      sessionId: threadId,
      tool: described.tool,
      summary: described.summary,
      detail: described.detail,
      createdAt: described.createdAt,
    };
    const decision = await this.options.approvals.requestDecision(phoneRequest);
    if (
      decision === undefined ||
      this.socket !== socket ||
      !this.approvalsByRpcId.has(key)
    ) {
      return;
    }
    this.approvalsByRpcId.delete(key);
    this.write({
      id: request.id,
      result: codexApprovalResponse(request, decision),
    });
    this.publishThread(threadId);
  }

  private handleNotification(method: string, rawParams: unknown): void {
    const params = asRecord(rawParams);
    if (!params) {
      return;
    }
    switch (method) {
      case "thread/started": {
        const thread = asRecord(params.thread) as Thread | undefined;
        if (!thread || typeof thread.id !== "string") {
          return;
        }
        void this.adoptStartedThread(thread);
        break;
      }
      case "thread/status/changed": {
        const threadId = typeof params.threadId === "string" ? params.threadId : undefined;
        const status = asRecord(params.status) as Thread["status"] | undefined;
        const thread = threadId ? this.threads.get(threadId) : undefined;
        if (thread && status) {
          thread.status = status;
          thread.updatedAt = Math.max(thread.updatedAt, this.now() / 1000);
          if (status.type !== "systemError") {
            this.terminalErrors.delete(thread.id);
          }
          this.publishThread(thread.id);
        }
        break;
      }
      case "thread/name/updated": {
        const threadId = typeof params.threadId === "string" ? params.threadId : undefined;
        const thread = threadId ? this.threads.get(threadId) : undefined;
        if (thread) {
          thread.name = typeof params.threadName === "string" ? params.threadName : null;
          this.publishThread(thread.id);
        }
        break;
      }
      case "turn/started":
      case "turn/completed": {
        const threadId = typeof params.threadId === "string" ? params.threadId : undefined;
        const turn = asRecord(params.turn) as Turn | undefined;
        const thread = threadId ? this.threads.get(threadId) : undefined;
        if (thread && turn && typeof turn.id === "string") {
          const index = thread.turns.findIndex((existing) => existing.id === turn.id);
          if (index >= 0) {
            thread.turns[index] = turn;
          } else {
            thread.turns.push(turn);
          }
          thread.updatedAt = Math.max(thread.updatedAt, this.now() / 1000);
          if (method === "turn/started") {
            this.terminalErrors.delete(thread.id);
          }
          this.publishThread(thread.id);
        }
        break;
      }
      case "error": {
        const threadId = typeof params.threadId === "string" ? params.threadId : undefined;
        const error = asRecord(params.error);
        if (threadId && params.willRetry === false) {
          const message =
            typeof error?.message === "string" ? error.message : "Codex turn failed.";
          this.terminalErrors.set(threadId, message);
          this.publishThread(threadId);
        }
        break;
      }
      case "serverRequest/resolved": {
        const requestId =
          typeof params.requestId === "string" || typeof params.requestId === "number"
            ? params.requestId
            : undefined;
        if (requestId !== undefined) {
          this.resolveApproval(requestId, "codex_request_resolved");
        }
        break;
      }
      case "thread/archived":
      case "thread/deleted": {
        if (typeof params.threadId === "string") {
          this.removeThread(params.threadId, method);
        }
        break;
      }
      case "thread/closed": {
        const thread = typeof params.threadId === "string"
          ? this.threads.get(params.threadId)
          : undefined;
        if (thread) {
          thread.status = { type: "idle" };
          this.publishThread(thread.id);
        }
        break;
      }
      case "thread/unarchived": {
        if (typeof params.threadId === "string") {
          void this.resumeAndRefresh(params.threadId);
        }
        break;
      }
      default:
        break;
    }
  }

  private async adoptStartedThread(thread: Thread): Promise<void> {
    this.threads.set(thread.id, thread);
    this.trimThreads();
    if (!this.threads.has(thread.id)) {
      return;
    }
    this.publishThread(thread.id);
    await this.resumeAndRefresh(thread.id);
  }

  private async resumeAndRefresh(threadId: string): Promise<void> {
    const resumed = await this.tryResume(threadId);
    if (!resumed || this.stopped) {
      return;
    }
    try {
      const response = await this.request<ThreadReadResponse>("thread/read", {
        threadId,
        includeTurns: true,
      });
      this.threads.set(threadId, response.thread);
      this.trimThreads();
      this.publishThread(threadId);
    } catch (error) {
      this.options.logger.info("codex_thread_refresh_failed", {
        threadId,
        reason: errorMessage(error),
      });
    }
  }

  private trimThreads(): void {
    const sorted = [...this.threads.values()]
      .sort((left, right) => threadActivityAt(right) - threadActivityAt(left));
    for (const thread of sorted.slice(MAX_CODEX_SESSIONS)) {
      this.removeThread(thread.id, "provider_cap");
    }
  }

  private publishThread(threadId: string): void {
    const thread = this.threads.get(threadId);
    if (!thread) {
      return;
    }
    const pending = [...this.approvalsByRpcId.values()]
      .filter((approval) => approval.threadId === threadId)
      .sort((left, right) => left.createdAt - right.createdAt)[0];
    this.options.store.upsertProviderSession(
      normalizeCodexThread(
        thread,
        this.options.config,
        pending,
        this.terminalErrors.get(threadId),
      ),
    );
  }

  private removeThread(threadId: string, reason: string): void {
    this.threads.delete(threadId);
    this.terminalErrors.delete(threadId);
    for (const approval of [...this.approvalsByRpcId.values()]) {
      if (approval.threadId === threadId) {
        this.resolveApproval(approval.rpcId, reason);
      }
    }
    if (this.options.store.get(threadId)?.provider === "codex") {
      this.options.store.removeSession(threadId);
    }
  }

  private resolveApproval(id: RequestId, reason: string): void {
    const key = rpcIdKey(id);
    const pending = this.approvalsByRpcId.get(key);
    if (!pending) {
      return;
    }
    this.approvalsByRpcId.delete(key);
    this.options.approvals.resolveRequest(pending.phoneRequestId, reason);
    this.publishThread(pending.threadId);
  }

  private resolveAllApprovals(reason: string): void {
    for (const pending of [...this.approvalsByRpcId.values()]) {
      this.options.approvals.resolveRequest(pending.phoneRequestId, reason);
    }
    this.approvalsByRpcId.clear();
    for (const threadId of this.threads.keys()) {
      this.publishThread(threadId);
    }
  }

  private rejectPendingRpc(error: Error): void {
    for (const pending of this.pendingRpc.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pendingRpc.clear();
  }

  private write(message: RpcMessage): boolean {
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      return false;
    }
    try {
      socket.send(JSON.stringify(message));
      return true;
    } catch {
      return false;
    }
  }

  private reportUnavailable(reason: string): void {
    if (this.state.available || this.state.reason !== reason) {
      this.options.logger.info("codex_unavailable", { reason });
    }
    this.state = { enabled: true, available: false, reason };
  }
}
