import { homedir } from "node:os";
import path from "node:path";
import {
  ApprovalManager,
  approvalTimeoutFromEnv,
  type ApprovalTransport,
  type HookResponse,
} from "./approval-manager";
import { spawnClaudeThread } from "./claude-spawn";
import { CodexMonitor } from "./codex/monitor";
import { configPath, defaultStateDir, ensureConfig } from "./config";
import { discoverRecentSessions } from "./discovery";
import { HookHttpServer } from "./http-server";
import { FileLogger } from "./logger";
import { SessionStore } from "./session-store";
import { TerminalTargets, sendTerminalInput } from "./terminal-input";
import { PhoneLink } from "./phone-link";
import { TranscriptTailManager } from "./transcript";
import { readRecentMessages } from "./transcript-messages";
import type { HookPayload } from "./types";
import { WsHub } from "./ws-server";

export interface RunningDaemon {
  config: ReturnType<typeof ensureConfig>;
  sessions: SessionStore;
  stop(): Promise<void>;
}

export async function startDaemon(): Promise<RunningDaemon> {
  const stateDir = defaultStateDir();
  const config = ensureConfig(stateDir);
  const logger = new FileLogger(stateDir);
  const sessions = new SessionStore(config, logger);
  const claudeDir = process.env.NEXUS_AGENTD_CLAUDE_DIR || path.join(homedir(), ".claude");
  const discovered = await discoverRecentSessions(path.join(claudeDir, "projects"), logger);
  for (const session of discovered) {
    sessions.addDiscovered(session);
  }

  let wsHub: WsHub | undefined;
  let phoneLink: PhoneLink | undefined;
  let approvals: ApprovalManager | undefined;
  let codexMonitor: CodexMonitor | undefined;
  const tailManager = new TranscriptTailManager(
    (sessionId, update) => sessions.applyTranscriptUpdate(sessionId, update),
    logger,
    (sessionId, message) => {
      wsHub?.broadcastDetailMessage(sessionId, message);
      phoneLink?.sendDetailMessage(sessionId, message);
    },
  );

  const detailProvider = async (sessionId: string, limit: number) => {
    if (sessions.get(sessionId)?.provider === "codex") {
      try {
        return await codexMonitor?.readMessages(sessionId, limit) ?? [];
      } catch {
        return [];
      }
    }
    const transcriptPath = sessions.transcriptPath(sessionId);
    return transcriptPath ? readRecentMessages(transcriptPath, limit) : [];
  };
  // Reading a conversation also starts tailing it, so the view stays live
  // even for a session that had been quiet since the daemon started.
  const onDetailOpen = (sessionId: string) => {
    if (sessions.get(sessionId)?.provider === "codex") {
      return;
    }
    const transcriptPath = sessions.transcriptPath(sessionId);
    if (transcriptPath && !tailManager.isTailing(sessionId)) {
      tailManager.start(sessionId, transcriptPath);
    }
  };
  const processHook = (payload: HookPayload): HookResponse | Promise<HookResponse> => {
    sessions.handleHook(payload);
    const sessionId = typeof payload.session_id === "string" ? payload.session_id : undefined;
    const eventName = typeof payload.hook_event_name === "string" ? payload.hook_event_name : undefined;
    const transcriptPath =
      typeof payload.transcript_path === "string"
        ? payload.transcript_path
        : sessionId
          ? sessions.transcriptPath(sessionId)
          : undefined;
    if (sessionId && eventName === "SessionEnd") {
      tailManager.stop(sessionId);
      approvals?.resolveSession(sessionId);
    } else if (sessionId && transcriptPath && sessions.get(sessionId)?.stale === false) {
      tailManager.start(sessionId, transcriptPath);
    }
    // PreToolUse still arrives, and is still what keeps the session's activity
    // current, but it is no longer what the wearer is asked about: it fires for
    // every tool call, so holding on it asked them to approve reads and greps
    // as well as the one command that actually needed them.
    if (eventName === "PermissionRequest") {
      return approvals?.request(payload) ?? {};
    }
    return {};
  };

  // Typing into a live session is the one thing --resume cannot do: it forks a
  // copy while the session's process exists, and two writers on one transcript
  // is how that record stops being true. A multiplexer can, because it is the
  // terminal. Off unless the owner turned it on.
  const terminalTargets = new TerminalTargets();
  const typeIntoSession = (sessionId: string, text: string) => {
    void terminalTargets.refresh();
    return sendTerminalInput(
      {
        enabled: config.allowTerminalInput,
        store: sessions,
        targetFor: (session) => terminalTargets.get(session.id),
      },
      sessionId,
      text,
    );
  };

  const hub = new WsHub(config, sessions, logger, {
    detailProvider,
    onDetailOpen,
    onApprovalDecision: (requestId, decision) => approvals?.handleDecision(requestId, decision),
    onTerminalInput: typeIntoSession,
  });
  wsHub = hub;
  const link = new PhoneLink({
    config,
    configFilePath: configPath(stateDir),
    store: sessions,
    logger,
    detailProvider,
    onDetailOpen,
    onThreadStart: (provider, projectPath, prompt) => {
      if (provider === "claude") {
        return spawnClaudeThread(projectPath, prompt);
      }
      return codexMonitor?.startThread(projectPath, prompt) ?? Promise.resolve({
        ok: false,
        error: "Codex is not available on this computer",
      });
    },
    onApprovalDecision: (requestId, decision) => approvals?.handleDecision(requestId, decision),
    onTerminalInput: typeIntoSession,
    onConnected: () => approvals?.onLinkConnected(),
    onDisconnected: () => approvals?.onLinkDisconnected(),
  });
  phoneLink = link;
  // Either direction can carry an approval. The daemon dials the phone on the
  // zero-setup path, but a phone given a pairing line dials the daemon instead
  // and its link server is then deliberately stopped — so a transport that only
  // knew the dialled link left "paste the pairing line" with a session board and
  // no way to answer anything on it. Prefer the dialled link when both are up,
  // since that is the one the phone's own reconnect logic keeps warm.
  const approvalTransport: ApprovalTransport = {
    get connected(): boolean {
      return link.connected || hub.hasAuthenticatedClient;
    },
    sendApprovalRequest: (request) =>
      link.connected
        ? link.sendApprovalRequest(request)
        : hub.sendApprovalRequest(request),
    sendApprovalResolved: (requestId, outcome) =>
      link.connected
        ? link.sendApprovalResolved(requestId, outcome)
        : hub.sendApprovalResolved(requestId, outcome),
  };
  approvals = new ApprovalManager({
    transport: approvalTransport,
    logger,
    timeoutMs: approvalTimeoutFromEnv(),
  });
  const codex = new CodexMonitor({
    config,
    store: sessions,
    approvals,
    logger,
  });
  codexMonitor = codex;
  const httpServer = new HookHttpServer({
    port: config.httpPort,
    sessionCount: () => sessions.size,
    onHook: processHook,
    logger,
    extraHealth: () => ({ codex: codex.availability() }),
  });
  const heartbeatTimer = setInterval(() => sessions.sweepStalled(), 60_000);
  heartbeatTimer.unref();

  try {
    await httpServer.start();
    await hub.start();
    link.start();
    codex.start();
  } catch (error) {
    clearInterval(heartbeatTimer);
    link.stop();
    await codex.stop();
    await httpServer.stop().catch(() => undefined);
    await hub.stop().catch(() => undefined);
    tailManager.stopAll();
    sessions.dispose();
    throw error;
  }

  logger.info("daemon_started", {
    httpHost: "127.0.0.1",
    httpPort: config.httpPort,
    wsHost: "0.0.0.0",
    wsPort: config.wsPort,
    discoveredSessions: discovered.length,
  });

  let stopped = false;
  return {
    config,
    sessions,
    async stop() {
      if (stopped) {
        return;
      }
      stopped = true;
      clearInterval(heartbeatTimer);
      await codex.stop();
      approvals?.dispose();
      link.stop();
      tailManager.stopAll();
      await Promise.all([httpServer.stop(), hub.stop()]);
      sessions.dispose();
      logger.info("daemon_stopped");
    },
  };
}
