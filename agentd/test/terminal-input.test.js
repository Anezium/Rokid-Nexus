const test = require("node:test");
const assert = require("node:assert/strict");
const { SessionStore } = require("../dist/session-store.js");
const { silentLogger } = require("../dist/logger.js");
const {
  MAX_TERMINAL_INPUT_CHARS,
  TARGET_CACHE_MS,
  TerminalTargets,
  createTerminalInputHandler,
  parseAgentPids,
  parseTerminalTarget,
  sendTerminalInput,
} = require("../dist/terminal-input.js");

const config = {
  token: "terminal-input-token",
  wsPort: 8792,
  httpPort: 8791,
  machineId: "machine-terminal",
  machineName: "test-pc",
};

function storeWith(sessions) {
  const store = new SessionStore(config, silentLogger);
  for (const hook of sessions) store.handleHook(hook);
  return store;
}

test("a multiplexer is recognised from the process environment, and its absence is not guessed at", () => {
  // ps eww prints the environment as one space-separated run.
  const screen = parseTerminalTarget(
    "PID TTY TIME CMD TERM=xterm STY=4242.work WINDOW=2 SHELL=/bin/zsh",
  );
  assert.deepEqual(screen, { kind: "screen", session: "4242.work", window: "2" });

  // No WINDOW at all still resolves — to window 0, not to a guess.
  const screenNoWindow = parseTerminalTarget("TERM=xterm STY=4242.work SHELL=/bin/zsh");
  assert.deepEqual(screenNoWindow, { kind: "screen", session: "4242.work", window: "0" });

  // tmux identifies the exact pane via TMUX_PANE, and the server via TMUX's socket field.
  const tmux = parseTerminalTarget(
    "TERM=xterm TMUX=/tmp/tmux-501/default,4242,0 TMUX_PANE=%12",
  );
  assert.deepEqual(tmux, { kind: "tmux", socketPath: "/tmp/tmux-501/default", pane: "%12" });

  // Either half missing leaves no reliable target — never guess a socket or a pane.
  assert.equal(parseTerminalTarget("TERM=xterm TMUX=/tmp/tmux-501/default,4242,0"), undefined);
  assert.equal(parseTerminalTarget("TERM=xterm TMUX_PANE=%12"), undefined);

  // An ordinary terminal has neither, and must not be treated as answerable.
  assert.equal(parseTerminalTarget("TERM=xterm-256color SHELL=/bin/zsh"), undefined);
  assert.equal(parseTerminalTarget(undefined), undefined);
  // A variable that merely ends in STY is not STY.
  assert.equal(parseTerminalTarget("TERM=xterm MYSTY=nope"), undefined);
});

test("agent listings map sessions to pids and survive junk", () => {
  const pids = parseAgentPids(JSON.stringify([
    { sessionId: "a", pid: 10 },
    { sessionId: "b", pid: 20, kind: "background" },
    { sessionId: "c" },
    { pid: 30 },
    { sessionId: "d", pid: -1 },
    { sessionId: "e", pid: 1.5 },
    null,
  ]));
  assert.deepEqual([...pids.entries()], [["a", 10], ["b", 20]]);

  assert.equal(parseAgentPids("not json").size, 0);
  assert.equal(parseAgentPids("{}").size, 0);
});

test("targets are cached so a redraw does not fork a CLI per session", async () => {
  let listings = 0;
  let clock = 1_000;
  const targets = new TerminalTargets(
    async () => {
      listings += 1;
      return new Map([["s1", 111]]);
    },
    async () => "STY=4242.work",
    () => clock,
  );

  await targets.refresh();
  assert.deepEqual(targets.get("s1"), { kind: "screen", session: "4242.work", window: "0" });
  assert.equal(listings, 1);

  await targets.refresh();
  assert.equal(listings, 1, "a refresh inside the window reuses what it has");

  clock += TARGET_CACHE_MS + 1;
  await targets.refresh();
  assert.equal(listings, 2, "past the window it looks again");
});

test("typed input reaches screen only when every guard is satisfied", async () => {
  const store = storeWith([
    { session_id: "idle", cwd: "/work/idle", hook_event_name: "Stop" },
    {
      session_id: "busy",
      cwd: "/work/busy",
      hook_event_name: "UserPromptSubmit",
      prompt: "keep working",
    },
  ]);
  const runs = [];
  const base = {
    enabled: true,
    store,
    targetFor: (session) =>
      session.id === "idle" || session.id === "busy"
        ? { kind: "screen", session: "4242.work", window: "1" }
        : undefined,
    run: async (command, args) => {
      runs.push({ command, args });
    },
  };

  try {
    // The switch is the outermost guard: off means nothing else is even asked.
    assert.deepEqual(
      await sendTerminalInput({ ...base, enabled: false }, "idle", "hello"),
      { ok: false, error: "Typing from the glasses is turned off" },
    );

    const refusals = [
      ["missing", "hello", "That session is no longer here"],
      ["idle", "   ", "Nothing to send"],
      ["idle", "x".repeat(MAX_TERMINAL_INPUT_CHARS + 1), "That is too long to send"],
      ["busy", "hello", "That session is still working"],
    ];
    for (const [sessionId, text, error] of refusals) {
      assert.deepEqual(await sendTerminalInput(base, sessionId, text), { ok: false, error }, error);
    }

    // A session in an ordinary terminal has nowhere to put the text.
    assert.deepEqual(
      await sendTerminalInput({ ...base, targetFor: () => undefined }, "idle", "hello"),
      { ok: false, error: "That session is not running in screen or tmux" },
    );
    assert.deepEqual(runs, [], "a refused send never reaches a process");

    assert.deepEqual(await sendTerminalInput(base, "idle", "on my way"), { ok: true });
    // The return is its own send: folded into the text it is pasted rather than
    // pressed, and the REPL keeps the line unsent.
    assert.deepEqual(runs, [
      {
        command: "screen",
        // Arguments, never a shell string: the text arrives from the network.
        // The window comes from the target, never a hardcoded "0".
        args: ["-S", "4242.work", "-p", "1", "-X", "stuff", "on my way"],
      },
      { command: "screen", args: ["-S", "4242.work", "-p", "1", "-X", "stuff", "\r"] },
    ]);
  } finally {
    store.dispose();
  }
});

test("a send the multiplexer refuses is reported, not swallowed", async () => {
  const store = storeWith([{ session_id: "idle", cwd: "/work/idle", hook_event_name: "Stop" }]);
  try {
    const outcome = await sendTerminalInput({
      enabled: true,
      store,
      targetFor: () => ({ kind: "tmux", socketPath: "/tmp/tmux-501/default", pane: "%12" }),
      run: async () => {
        throw new Error("no such session");
      },
    }, "idle", "hello");
    assert.deepEqual(outcome, { ok: false, error: "Could not reach that tmux session" });
  } finally {
    store.dispose();
  }
});

test("the handler awaits discovery before it ever reads the cache", async () => {
  const store = storeWith([{ session_id: "idle", cwd: "/work/idle", hook_event_name: "Stop" }]);
  try {
    const targets = new TerminalTargets(
      // A slow listing: if the handler read the cache before this resolved,
      // it would see nothing and refuse a session that is actually reachable.
      async () => {
        await new Promise((resolve) => setTimeout(resolve, 20));
        return new Map([["idle", 111]]);
      },
      async () => "STY=4242.work",
    );
    const runs = [];
    const handler = createTerminalInputHandler({
      enabled: () => true,
      store,
      targets,
      run: async (command, args) => {
        runs.push({ command, args });
      },
    });

    const outcome = await handler("idle", "on my way");
    assert.deepEqual(outcome, { ok: true });
    assert.equal(runs.length, 2, "the first call already found a target, cold cache and all");
  } finally {
    store.dispose();
  }
});
