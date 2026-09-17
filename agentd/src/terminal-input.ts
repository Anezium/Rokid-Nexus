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

/**
 * The exact place to type, not merely which multiplexer: a screen session has
 * more than one window, and a tmux server can be reached over more than one
 * socket, running more than one session, each with more than one pane. Naming
 * only the session (or worse, guessing window 0) can land the reply on
 * whatever else happens to share it.
 */
export type TerminalTarget =
  | { kind: "screen"; session: string; window: string }
  | { kind: "tmux"; socketPath: string; pane: string };

export interface TerminalInputOutcome {
  ok: boolean;
  error?: string;
}

export type EnvReader = (pid: number) => Promise<string | undefined>;
export type Runner = (command: string, args: string[]) => Promise<void>;
export type QueryRunner = (command: string, args: string[]) => Promise<string>;

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

const runQuery: QueryRunner = (command, args) =>
  new Promise((resolve, reject) => {
    execFile(command, args, { timeout: 5000 }, (error, stdout) => {
      if (error) reject(error);
      else resolve(stdout);
    });
  });

/**
 * Finds the multiplexer a session is running inside, if any.
 *
 * `ps eww` prints the environment as one space-separated run, so the values
 * are matched rather than split.
 *
 * A screen `STY` looks like `4242.work`; the window actually holding the
 * session is a separate `WINDOW` variable (a plain number), and a session
 * outside window 0 is not "close enough" — `-p` addresses one window
 * exactly, so an absent `WINDOW` falls back to `0` rather than being guessed.
 *
 * tmux identifies a pane globally as `TMUX_PANE` (`%12`), unique across every
 * window and session on the server — worth reading directly instead of
 * reconstructing from `TMUX`'s session field, which only says which session,
 * not which window or pane inside it. `TMUX` itself still carries the one
 * thing `TMUX_PANE` does not: `/tmp/tmux-501/default,4242,0`'s first field is
 * the server's own socket path, required on `-S` so the command reaches the
 * same server the pane lives on rather than whatever `tmux` would attach to
 * by default for the account running agentd. Absent either half, there is no
 * reliable target — guessing a socket is how a reply reaches a stranger's pane.
 */
export function parseTerminalTarget(processEnv: string | undefined): TerminalTarget | undefined {
  if (!processEnv) {
    return undefined;
  }
  const sty = /(?:^|\s)STY=(\S+)/.exec(processEnv);
  if (sty?.[1]) {
    const window = /(?:^|\s)WINDOW=(\S+)/.exec(processEnv)?.[1] ?? "0";
    return { kind: "screen", session: sty[1], window };
  }
  const pane = /(?:^|\s)TMUX_PANE=(\S+)/.exec(processEnv)?.[1];
  const tmux = /(?:^|\s)TMUX=(\S+)/.exec(processEnv)?.[1];
  const socketPath = tmux?.split(",")[0];
  if (pane && socketPath) {
    return { kind: "tmux", socketPath, pane };
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

/**
 * Binds a phone's `session_input` to one session store and target cache.
 *
 * Awaits the refresh before ever reading the cache: a fire-and-forget refresh
 * left the very first send after a cold start reading an empty cache, so a
 * session that was genuinely reachable was refused as if it were not — the
 * cache having a `TARGET_CACHE_MS` window does not help the request that
 * arrives before it has ever been filled once.
 */
export function createTerminalInputHandler(options: {
  enabled: () => boolean;
  store: SessionStore;
  targets: TerminalTargets;
  run?: Runner;
  query?: QueryRunner;
}): (sessionId: string, text: string) => Promise<TerminalInputOutcome> {
  return async (sessionId, text) => {
    await options.targets.refresh();
    return sendTerminalInput(
      {
        enabled: options.enabled(),
        store: options.store,
        targetFor: (session) => options.targets.get(session.id),
        run: options.run,
        query: options.query,
      },
      sessionId,
      text,
    );
  };
}

export interface TerminalInputOptions {
  enabled: boolean;
  store: SessionStore;
  targetFor: (session: Session) => TerminalTarget | undefined;
  run?: Runner;
  query?: QueryRunner;
}

/**
 * True while the pane is in copy mode — scrolled back with the mouse or a
 * keybinding, and no longer forwarding keys to the program running inside it.
 * `send-keys` in this state does not fail; it succeeds at moving the copy-mode
 * cursor around instead of ever reaching Claude Code, so a reply "sent" this
 * way is silently lost. `#{pane_in_mode}` is tmux's own answer to whether a
 * pane is in this or any other mode (view mode after a search behaves the same
 * way); screen has no comparable query, so this check is tmux-only.
 */
async function isTmuxPaneInCopyMode(
  target: Extract<TerminalTarget, { kind: "tmux" }>,
  query: QueryRunner,
): Promise<boolean> {
  try {
    const out = await query("tmux", [
      "-S", target.socketPath,
      "display-message", "-p", "-t", target.pane, "#{pane_in_mode}",
    ]);
    return out.trim() === "1";
  } catch {
    // Can't tell — proceed as before this check existed rather than refuse
    // a session solely because the probe itself failed.
    return false;
  }
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
          ["screen", ["-S", target.session, "-p", target.window, "-X", "stuff", text]],
          ["screen", ["-S", target.session, "-p", target.window, "-X", "stuff", "\r"]],
        ]
      : [
          ["tmux", ["-S", target.socketPath, "send-keys", "-t", target.pane, "-l", text]],
          ["tmux", ["-S", target.socketPath, "send-keys", "-t", target.pane, "Enter"]],
        ];
  try {
    // A pane left scrolled back swallows send-keys into copy-mode navigation
    // instead of the program running inside it — leave that mode first, or
    // the reply below succeeds at nothing.
    if (target.kind === "tmux" && (await isTmuxPaneInCopyMode(target, options.query ?? runQuery))) {
      await run("tmux", ["-S", target.socketPath, "send-keys", "-X", "-t", target.pane, "cancel"]);
    }
    for (const [command, args] of sends) {
      await run(command, args);
    }
    return { ok: true };
  } catch {
    return { ok: false, error: `Could not reach that ${target.kind} session` };
  }
}
