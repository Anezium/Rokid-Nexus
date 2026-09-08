import { randomUUID } from "node:crypto";
import { truncateText } from "./session-store";
import type { HookPayload, Logger } from "./types";

export const DEFAULT_APPROVAL_TIMEOUT_MS = 120_000;
export const MAX_PENDING_APPROVALS = 32;
const MAX_TIMER_MS = 2_147_000_000;

export type ApprovalDecision = "allow" | "deny";
export type ApprovalOutcome = ApprovalDecision | "timeout" | "local";

export interface ApprovalRequest {
  type: "approval_request";
  v: 1;
  requestId: string;
  sessionId: string;
  tool: string;
  summary: string;
  detail: string;
  createdAt: number;
}

export interface ApprovalTransport {
  readonly connected: boolean;
  sendApprovalRequest(request: ApprovalRequest): boolean;
  sendApprovalResolved(requestId: string, outcome: ApprovalOutcome): boolean;
}

export type HookResponse = Record<string, unknown>;

interface PendingApproval {
  request: ApprovalRequest;
  timer: NodeJS.Timeout;
  resolve: (decision: ApprovalDecision | undefined) => void;
}

export interface ApprovalManagerOptions {
  transport: ApprovalTransport;
  logger: Logger;
  timeoutMs?: number;
  now?: () => number;
  requestId?: () => string;
  maxPending?: number;
}

const DETAIL_FIELDS = [
  "command",
  "file_path",
  "path",
  "url",
  "query",
  "pattern",
  "prompt",
  "description",
  "old_string",
  "new_string",
  "content",
  "notebook_path",
  "cell_id",
] as const;

function usefulValue(value: unknown, limit: number): string | undefined {
  if (typeof value === "string") {
    const trimmed = value.trim();
    return trimmed ? trimmed.slice(0, limit) : undefined;
  }
  if (typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  if (Array.isArray(value)) {
    const primitives = value
      .filter((entry) => ["string", "number", "boolean"].includes(typeof entry))
      .map(String)
      .join(", ");
    return primitives ? primitives.slice(0, limit) : undefined;
  }
  if (value && typeof value === "object") {
    try {
      return JSON.stringify(value).slice(0, limit);
    } catch {
      return undefined;
    }
  }
  return undefined;
}

function argumentFields(input: Record<string, unknown> | undefined): string[] {
  if (!input) {
    return [];
  }
  return [
    ...DETAIL_FIELDS,
    ...Object.keys(input).filter(
      (field) => !DETAIL_FIELDS.includes(field as typeof DETAIL_FIELDS[number]),
    ),
  ];
}

export function describeTool(payload: HookPayload): {
  tool?: string;
  summary: string;
  detail: string;
} {
  const tool = typeof payload.tool_name === "string" && payload.tool_name.trim()
    ? payload.tool_name.trim().slice(0, 120)
    : undefined;
  const input =
    payload.tool_input && typeof payload.tool_input === "object" && !Array.isArray(payload.tool_input)
      ? payload.tool_input as Record<string, unknown>
      : undefined;

  const command = usefulValue(input?.command, 400);
  const description = usefulValue(input?.description, 120);
  const fields = argumentFields(input);
  const firstArgument = fields
    .filter((field) => field !== "description")
    .map((field) => usefulValue(input?.[field], 120))
    .find((value) => value !== undefined);
  const summary = truncateText(
    description ?? firstArgument ?? (tool ? `${tool} request` : "Tool permission request"),
    120,
  );

  if (command) {
    return { tool, summary, detail: command.slice(0, 400) };
  }

  const parts: string[] = [];
  for (const field of fields) {
    if (field === "description") {
      continue;
    }
    const value = usefulValue(input?.[field], 180);
    if (!value) {
      continue;
    }
    const part = `${field}: ${value}`;
    const separatorLength = parts.length === 0 ? 0 : 2;
    const remaining = 400 - parts.join("; ").length - separatorLength;
    if (remaining <= 0) {
      break;
    }
    parts.push(part.slice(0, remaining));
    if (parts.join("; ").length >= 400) {
      break;
    }
  }

  return {
    tool,
    summary,
    detail: (parts.join("; ") || description || summary).slice(0, 400),
  };
}

/**
 * PermissionRequest's contract, not PreToolUse's: the field is `decision` and
 * an exit code cannot deny, so the verdict has to travel in the JSON. The event
 * matters as much as the shape — PreToolUse fires for every tool call a session
 * makes, including the ones Claude would never have stopped to ask about, so
 * holding on it turned an ordinary working session into hundreds of questions
 * on the wearer's glasses. PermissionRequest fires only when a decision is
 * genuinely needed.
 */
function decisionResponse(decision: ApprovalDecision): HookResponse {
  return {
    hookSpecificOutput: {
      hookEventName: "PermissionRequest",
      decision,
      decisionReason:
        decision === "allow"
          ? "Approved by the wearer in Nexus Agents."
          : "Denied by the wearer in Nexus Agents.",
    },
  };
}

export function approvalTimeoutFromEnv(value = process.env.NEXUS_AGENTD_APPROVAL_TIMEOUT_MS): number {
  if (value === undefined || value.trim() === "") {
    return DEFAULT_APPROVAL_TIMEOUT_MS;
  }
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 && parsed <= MAX_TIMER_MS
    ? parsed
    : DEFAULT_APPROVAL_TIMEOUT_MS;
}

export class ApprovalManager {
  private readonly pending = new Map<string, PendingApproval>();
  private readonly timeoutMs: number;
  private readonly now: () => number;
  private readonly makeRequestId: () => string;
  private readonly maxPending: number;

  constructor(private readonly options: ApprovalManagerOptions) {
    this.timeoutMs = options.timeoutMs ?? DEFAULT_APPROVAL_TIMEOUT_MS;
    this.now = options.now ?? Date.now;
    this.makeRequestId = options.requestId ?? randomUUID;
    this.maxPending = options.maxPending ?? MAX_PENDING_APPROVALS;
  }

  get size(): number {
    return this.pending.size;
  }

  request(payload: HookPayload): Promise<HookResponse> {
    const sessionId =
      typeof payload.session_id === "string" && payload.session_id.length > 0
        ? payload.session_id
        : undefined;
    const description = describeTool(payload);
    if (!sessionId || !description.tool) {
      this.options.logger.warn("approval_invalid_hook", {
        hasSessionId: sessionId !== undefined,
        hasTool: description.tool !== undefined,
      });
      return Promise.resolve({});
    }
    const request: ApprovalRequest = {
      type: "approval_request",
      v: 1,
      requestId: this.makeRequestId(),
      sessionId,
      tool: description.tool,
      summary: description.summary,
      detail: description.detail,
      createdAt: this.now(),
    };

    return this.requestDecision(request).then((decision) =>
      decision ? decisionResponse(decision) : {},
    );
  }

  /**
   * Shares the phone approval lifecycle with providers whose native response
   * contract is not Claude's PreToolUse hook contract.
   */
  requestDecision(request: ApprovalRequest): Promise<ApprovalDecision | undefined> {
    if (!this.options.transport.connected) {
      this.options.logger.info("approval_local", { reason: "phone_not_connected" });
      return Promise.resolve(undefined);
    }
    if (this.pending.size >= this.maxPending) {
      this.options.logger.warn("approval_local", { reason: "pending_limit" });
      return Promise.resolve(undefined);
    }
    if (!request.requestId || this.pending.has(request.requestId)) {
      this.options.logger.warn("approval_local", {
        requestId: request.requestId,
        reason: "duplicate_request",
      });
      return Promise.resolve(undefined);
    }

    return new Promise<ApprovalDecision | undefined>((resolve) => {
      const timer = setTimeout(() => this.onTimeout(request.requestId), this.timeoutMs);
      timer.unref();
      this.pending.set(request.requestId, { request, timer, resolve });
      if (!this.options.transport.sendApprovalRequest(request)) {
        this.resolveLocal(request.requestId, "send_failed", false);
        return;
      }
      this.options.logger.info("approval_requested", {
        requestId: request.requestId,
        sessionId: request.sessionId.slice(0, 16),
        tool: request.tool,
      });
    });
  }

  handleDecision(requestId: string, decision: ApprovalDecision): void {
    const pending = this.take(requestId);
    if (!pending) {
      return;
    }
    pending.resolve(decision);
    this.options.logger.info("approval_decided", {
      requestId,
      decision,
    });
  }

  onLinkConnected(): void {
    for (const [requestId, pending] of this.pending) {
      if (!this.options.transport.sendApprovalRequest(pending.request)) {
        this.resolveLocal(requestId, "resend_failed", false);
      }
    }
  }

  onLinkDisconnected(): void {
    for (const requestId of [...this.pending.keys()]) {
      this.resolveLocal(requestId, "phone_disconnected", false);
    }
  }

  resolveSession(sessionId: string): void {
    for (const [requestId, pending] of [...this.pending]) {
      if (pending.request.sessionId === sessionId) {
        this.resolveLocal(requestId, "session_end", true);
      }
    }
  }

  resolveRequest(requestId: string, reason = "upstream_resolved"): void {
    this.resolveLocal(requestId, reason, true);
  }

  dispose(): void {
    for (const requestId of [...this.pending.keys()]) {
      this.resolveLocal(requestId, "daemon_stop", true);
    }
  }

  private onTimeout(requestId: string): void {
    const pending = this.take(requestId);
    if (!pending) {
      return;
    }
    if (this.options.transport.connected) {
      this.options.transport.sendApprovalResolved(requestId, "timeout");
    }
    pending.resolve(undefined);
    this.options.logger.info("approval_timeout", { requestId });
  }

  private resolveLocal(requestId: string, reason: string, notifyPhone: boolean): void {
    const pending = this.take(requestId);
    if (!pending) {
      return;
    }
    if (notifyPhone && this.options.transport.connected) {
      this.options.transport.sendApprovalResolved(requestId, "local");
    }
    pending.resolve(undefined);
    this.options.logger.info("approval_local", { requestId, reason });
  }

  private take(requestId: string): PendingApproval | undefined {
    const pending = this.pending.get(requestId);
    if (!pending) {
      return undefined;
    }
    this.pending.delete(requestId);
    clearTimeout(pending.timer);
    return pending;
  }
}
