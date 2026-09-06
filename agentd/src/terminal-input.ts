import { execFile } from "node:child_process";
import type { SessionStore } from "./session-store";
import type { Session } from "./types";

/**
 * Typing into a session that is genuinely running, from somewhere else.
 *
 * `claude --resume` cannot do this: it forks a copy whenever the session's
 * process still exists — measured on both an interactive session and a
 * background one whose status read `idle / done`. Two processes then append to
 * one transcript, which is how the record of what an agent did stops being
 * true.
 *
 * A terminal multiplexer can, because it is the terminal: `screen -X stuff`
 * puts the text in the session's input buffer exactly as a person typing would.
 * No second process, no second writer. The cost is that only sessions actually
 * running inside `screen` or tmux can be reached, which is why
 * [detectTerminalTarget] exists and why sessions without one are simply not
 * offered as answerable.
 */

/** Longer than any reply worth typing on glasses, short enough to paste safely. */
export const MAX_TERMINAL_INPUT_CHARS = 2000;

export interface TerminalTarget {
  kind: "screen" | "tmux";
  /** The handle to address: a screen session name, or a tmux pane. */
  handle: string;
}

export interface TerminalInputOutcome {
  ok: boolean;
  error?: string;
}

export type EnvReader = (pid: number) => Promise<string | undefined>;
export type Runner = (command: string, args: string[]) => Promise<void>;

/** `ps eww` is readable for this user's own processes; other users' are not. */
export const readProcessEnv: EnvReader = (pid) =>
  new Promise((resolve) => {
    execFile("ps", ["eww", "-p", String(pid)], { timeout: 2000 }, (error, stdout) => {
      resolve(error ? undefined : stdout);
    });
  });

const runProcess: Runner = (command, args) =>
  new Promise((resolve, reject) => {
    execFile(command, args, { timeout: 5000 }, (error) => {
      if (error) reject(error);
      else resolve();
    });
  });

/**
 * Finds the multiplexer a session is running inside, if any.
 *
 * `ps eww` prints the environment as one space-separated run, so the values are
 * matched rather than split: a screen `STY` looks like `4242.work`, and a tmux
 * `TMUX` like `/tmp/tmux-501/default,4242,0` whose last field is the session.
 */
export function parseTerminalTarget(processEnv: string | undefined): TerminalTarget | undefined {
  if (!processEnv) {
    return undefined;
  }
  const sty = /(?:^|\s)STY=(\S+)/.exec(processEnv);
  if (sty?.[1]) {
    return { kind: "screen", handle: sty[1] };
  }
  const tmux = /(?:^|\s)TMUX=(\S+)/.exec(processEnv);
  if (tmux?.[1]) {
    const parts = tmux[1].split(",");
    const session = parts[parts.length - 1];
    return session ? { kind: "tmux", handle: session } : undefined;
  }
  return undefined;
}

export async function detectTerminalTarget(
  pid: number | undefined,
  readEnv: EnvReader = readProcessEnv,
): Promise<TerminalTarget | undefined> {
  if (!pid || !Number.isSafeInteger(pid) || pid <= 0) {
    return undefined;
  }
  return parseTerminalTarget(await readEnv(pid));
}

/**
 * Maps live sessions to the process running them.
 *
 * Hook payloads carry no pid — they describe what a session is doing, not where
 * it lives — so the process has to come from `claude agents --json`, which
 * lists interactive and background sessions alike with their pid and session
 * id. Resolved on a short cache because a wearer opening the board should not
 * fork a CLI per session per redraw.
 */
export function parseAgentPids(stdout: string): Map<string, number> {
  const pids = new Map<string, number>();
  let rows: unknown;
  try {
    rows = JSON.parse(stdout);
  } catch {
    return pids;
  }
  if (!Array.isArray(rows)) {
    return pids;
  }
  for (const row of rows) {
    if (!row || typeof row !== "object") continue;
    const { sessionId, pid } = row as { sessionId?: unknown; pid?: unknown };
    if (typeof sessionId === "string" && sessionId && Number.isSafeInteger(pid) && (pid as number) > 0) {
      pids.set(sessionId, pid as number);
    }
  }
  return pids;
}

export const listAgentPids = (): Promise<Map<string, number>> =>
  new Promise((resolve) => {
    execFile("claude", ["agents", "--json"], { timeout: 5000 }, (error, stdout) => {
      resolve(error ? new Map() : parseAgentPids(stdout));
    });
  });

/** Long enough that a board redraw costs nothing, short enough to stay true. */
export const TARGET_CACHE_MS = 10_000;

export class TerminalTargets {
  private cache = new Map<string, TerminalTarget | undefined>();
  // Never zero: "no refresh yet" must not be a timestamp the window can be
  // measured from, or the first refresh is skipped whenever the clock is small.
  private refreshedAt = Number.NEGATIVE_INFINITY;
  private inFlight?: Promise<void>;

  constructor(
    private readonly listPids: () => Promise<Map<string, number>> = listAgentPids,
    private readonly readEnv: EnvReader = readProcessEnv,
    private readonly now: () => number = Date.now,
  ) {}

  /** The last known answer; refresh() is what makes it current. */
  get(sessionId: string): TerminalTarget | undefined {
    return this.cache.get(sessionId);
  }

  async refresh(): Promise<void> {
    if (this.now() - this.refreshedAt < TARGET_CACHE_MS) {
      return this.inFlight ?? Promise.resolve();
    }
    if (this.inFlight) {
      return this.inFlight;
    }
    const work = (async () => {
      const pids = await this.listPids();
      const next = new Map<string, TerminalTarget | undefined>();
      for (const [sessionId, pid] of pids) {
        next.set(sessionId, await detectTerminalTarget(pid, this.readEnv));
      }
      this.cache = next;
      this.refreshedAt = this.now();
    })().finally(() => {
      this.inFlight = undefined;
    });
    this.inFlight = work;
    return work;
  }
}

export interface TerminalInputOptions {
  enabled: boolean;
  store: SessionStore;
  targetFor: (session: Session) => TerminalTarget | undefined;
  run?: Runner;
}

/**
 * The one place typed input is judged, for every transport.
 *
 * Both the dialled link and the WebSocket reach this: an earlier attempt at
 * this feature was implemented in only one of them, so a reply typed on the
 * glasses was dropped as an unknown frame and the wearer was told nothing.
 */
export async function sendTerminalInput(
  options: TerminalInputOptions,
  rawSessionId: unknown,
  rawText: unknown,
): Promise<TerminalInputOutcome> {
  if (!options.enabled) {
    return { ok: false, error: "Typing from the glasses is turned off" };
  }
  const sessionId = typeof rawSessionId === "string" ? rawSessionId : "";
  const text = typeof rawText === "string" ? rawText : "";
  const session = sessionId ? options.store.get(sessionId) : undefined;
  if (!session) {
    return { ok: false, error: "That session is no longer here" };
  }
  if (!text.trim()) {
    return { ok: false, error: "Nothing to send" };
  }
  if (text.length > MAX_TERMINAL_INPUT_CHARS) {
    return { ok: false, error: "That is too long to send" };
  }
  // A turn in progress is not waiting for anything, and typing into it would
  // land mid-thought rather than at a prompt.
  if (session.status === "working") {
    return { ok: false, error: "That session is still working" };
  }
  const target = options.targetFor(session);
  if (!target) {
    return { ok: false, error: "That session is not running in screen or tmux" };
  }

  // Arguments, never a shell string: the text comes off the network and must
  // not be able to become a command.
  // Two sends, not one. A trailing newline inside the stuffed string lands in
  // the REPL as part of the pasted text and leaves it sitting on the input
  // line: measured against a live Claude session, which only answered once a
  // carriage return arrived on its own.
  const run = options.run ?? runProcess;
  const sends: Array<[string, string[]]> =
    target.kind === "screen"
      ? [
          ["screen", ["-S", target.handle, "-p", "0", "-X", "stuff", text]],
          ["screen", ["-S", target.handle, "-p", "0", "-X", "stuff", "\r"]],
        ]
      : [
          ["tmux", ["send-keys", "-t", target.handle, "-l", text]],
          ["tmux", ["send-keys", "-t", target.handle, "Enter"]],
        ];
  try {
    for (const [command, args] of sends) {
      await run(command, args);
    }
    return { ok: true };
  } catch {
    return { ok: false, error: `Could not reach that ${target.kind} session` };
  }
}
