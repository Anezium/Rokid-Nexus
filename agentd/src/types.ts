export type SessionStatus = "working" | "needs_you" | "idle" | "done" | "error";

export type AgentProvider = "claude" | "codex";

export interface ThreadStartResult {
  ok: boolean;
  sessionId?: string;
  error?: string;
}

export type PendingRequestKind = "permission" | "question" | "idle_prompt";

export interface PendingRequest {
  kind: PendingRequestKind;
  summary: string;
  createdAt: number;
}

export interface SessionTurn {
  lastTool?: string;
  activeSince: number;
}

export interface Session {
  id: string;
  provider: AgentProvider;
  machineId: string;
  machineName: string;
  title: string;
  cwd: string;
  project: string;
  status: SessionStatus;
  statusDetail?: string;
  stale: boolean;
  lastActivityAt: number;
  lastAssistantText?: string;
  /** Set when the session runs inside screen or tmux, so it can be typed into. */
  answerable?: boolean;
  turn?: SessionTurn;
  pendingRequest?: PendingRequest;
}

export interface CodexConfig {
  enabled: boolean;
  port: number;
}

export interface AgentConfig {
  token: string;
  wsPort: number;
  httpPort: number;
  machineId: string;
  machineName: string;
  phoneHosts: string[];
  tailnetDiscovery: boolean;
  /**
   * Whether a phone may type into a session's terminal. Off unless the owner
   * says otherwise: unlike answering a held tool call, this originates input
   * rather than responding to something Claude proposed.
   */
  allowTerminalInput: boolean;
  codex: CodexConfig;
}

export interface HookPayload {
  session_id?: unknown;
  transcript_path?: unknown;
  cwd?: unknown;
  hook_event_name?: unknown;
  tool_name?: unknown;
  tool_input?: unknown;
  tool_use_id?: unknown;
  source?: unknown;
  prompt?: unknown;
  message?: unknown;
  reason?: unknown;
  [key: string]: unknown;
}

export interface LogMeta {
  [key: string]: unknown;
}

export interface Logger {
  info(event: string, meta?: LogMeta): void;
  warn(event: string, meta?: LogMeta): void;
  error(event: string, meta?: LogMeta): void;
}

export interface TranscriptUpdate {
  activityAt: number;
  lastAssistantText?: string;
  lastTool?: string;
  error?: string;
}

export type MessageRole = "user" | "assistant" | "tool";

/** One conversation entry, trimmed to what a HUD can usefully show. */
export interface SessionMessage {
  role: MessageRole;
  text: string;
  at: number;
  tool?: string;
}
