const test = require("node:test");
const assert = require("node:assert/strict");
const WebSocket = require("ws");
const { SessionStore } = require("../dist/session-store.js");
const { WsHub } = require("../dist/ws-server.js");
const { silentLogger } = require("../dist/logger.js");

function waitFor(messages, predicate, timeoutMs = 2000) {
  const existing = messages.find(predicate);
  if (existing) return Promise.resolve(existing);
  return new Promise((resolve, reject) => {
    const deadline = setTimeout(() => reject(new Error("timed out waiting for WS frame")), timeoutMs);
    messages.waiters.push((message) => {
      if (predicate(message)) {
        clearTimeout(deadline);
        resolve(message);
        return true;
      }
      return false;
    });
  });
}

function collect(socket) {
  const messages = [];
  messages.waiters = [];
  socket.on("message", (data) => {
    const message = JSON.parse(data.toString());
    messages.push(message);
    messages.waiters = messages.waiters.filter((waiter) => !waiter(message));
  });
  return messages;
}

function opened(socket) {
  return new Promise((resolve, reject) => {
    socket.once("open", resolve);
    socket.once("error", reject);
  });
}

function waitUntil(predicate, timeoutMs = 2000) {
  const started = Date.now();
  return new Promise((resolve, reject) => {
    const tick = () => {
      if (predicate()) return resolve();
      if (Date.now() - started > timeoutMs) return reject(new Error("timed out waiting for condition"));
      setTimeout(tick, 10);
    };
    tick();
  });
}

const config = {
  token: "correct-test-token",
  wsPort: 0,
  httpPort: 8791,
  machineId: "machine-test",
  machineName: "test-pc",
};

test("authenticated fake client receives ordered snapshot, delta, refresh, and removal", async () => {
  const store = new SessionStore(config, silentLogger);
  const hub = new WsHub(config, store, silentLogger, { host: "127.0.0.1" });
  await hub.start();
  const socket = new WebSocket(`ws://127.0.0.1:${hub.port()}`);
  const messages = collect(socket);
  try {
    await opened(socket);
    socket.send(JSON.stringify({
      type: "hello",
      v: 1,
      token: config.token,
      client: { name: "plugin-agents", version: "test" },
    }));
    const ack = await waitFor(messages, (message) => message.type === "hello_ack");
    const snapshot = await waitFor(messages, (message) => message.type === "snapshot");
    assert.equal(ack.server.name, "nexus-agentd");
    assert.equal(snapshot.seq, 0);
    assert.deepEqual(snapshot.sessions, []);

    store.handleHook({
      session_id: "ws-session",
      cwd: "E:\\work\\ws",
      hook_event_name: "UserPromptSubmit",
      prompt: "Build WS",
    });
    const upsert = await waitFor(messages, (message) => message.type === "session_upsert");
    assert.equal(upsert.seq, 1);
    assert.equal(upsert.session.id, "ws-session");

    socket.send(JSON.stringify({ type: "refresh" }));
    const refreshed = await waitFor(
      messages,
      (message) => message.type === "snapshot" && message !== snapshot,
    );
    assert.equal(refreshed.seq, 1);
    assert.equal(refreshed.sessions.length, 1);

    store.removeSession("ws-session");
    const removed = await waitFor(messages, (message) => message.type === "session_removed");
    assert.equal(removed.seq, 2);
    assert.equal(removed.sessionId, "ws-session");
  } finally {
    socket.terminate();
    await hub.stop();
    store.dispose();
  }
});

test("bad tokens are rejected with close code 4401", async () => {
  const store = new SessionStore(config, silentLogger);
  const hub = new WsHub(config, store, silentLogger, { host: "127.0.0.1" });
  await hub.start();
  const socket = new WebSocket(`ws://127.0.0.1:${hub.port()}`);
  try {
    await opened(socket);
    const closed = new Promise((resolve) => socket.once("close", resolve));
    socket.send(JSON.stringify({ type: "hello", v: 1, token: "wrong" }));
    assert.equal(await closed, 4401);
  } finally {
    socket.terminate();
    await hub.stop();
    store.dispose();
  }
});

test("missing hello is rejected with close code 4408", async () => {
  const store = new SessionStore(config, silentLogger);
  const hub = new WsHub(config, store, silentLogger, {
    host: "127.0.0.1",
    helloTimeoutMs: 30,
  });
  await hub.start();
  const socket = new WebSocket(`ws://127.0.0.1:${hub.port()}`);
  try {
    await opened(socket);
    const closed = new Promise((resolve) => socket.once("close", resolve));
    assert.equal(await closed, 4408);
  } finally {
    socket.terminate();
    await hub.stop();
    store.dispose();
  }
});

test("authenticated clients that do not answer app pings close with 4409", async () => {
  const store = new SessionStore(config, silentLogger);
  const hub = new WsHub(config, store, silentLogger, {
    host: "127.0.0.1",
    keepaliveIntervalMs: 60_000,
    pongTimeoutMs: 30,
  });
  await hub.start();
  const socket = new WebSocket(`ws://127.0.0.1:${hub.port()}`);
  try {
    await opened(socket);
    const closed = new Promise((resolve) => socket.once("close", resolve));
    socket.send(JSON.stringify({ type: "hello", v: 1, token: config.token }));
    assert.equal(await closed, 4409);
  } finally {
    socket.terminate();
    await hub.stop();
    store.dispose();
  }
});

test("a phone that dialled in can be held for approval and answer over the same socket", async () => {
  const store = new SessionStore(config, silentLogger);
  const decisions = [];
  const hub = new WsHub(config, store, silentLogger, {
    host: "127.0.0.1",
    onApprovalDecision: (requestId, decision) => decisions.push({ requestId, decision }),
  });
  await hub.start();
  const socket = new WebSocket(`ws://127.0.0.1:${hub.port()}`);
  const messages = collect(socket);
  try {
    await opened(socket);
    // Before anyone authenticates there is nothing to hold a tool call against.
    assert.equal(hub.hasAuthenticatedClient, false);
    assert.equal(hub.sendApprovalRequest({ type: "approval_request", requestId: "r0" }), false);

    socket.send(JSON.stringify({
      type: "hello",
      v: 1,
      token: config.token,
      client: { name: "plugin-agents", version: "test" },
    }));
    await waitFor(messages, (message) => message.type === "hello_ack");
    assert.equal(hub.hasAuthenticatedClient, true);

    const request = {
      type: "approval_request",
      v: 1,
      requestId: "req-1",
      sessionId: "ws-session",
      tool: "Bash",
      summary: "rm -rf /tmp/x",
    };
    assert.equal(hub.sendApprovalRequest(request), true);
    const delivered = await waitFor(messages, (message) => message.type === "approval_request");
    assert.equal(delivered.requestId, "req-1");
    assert.equal(delivered.tool, "Bash");

    socket.send(JSON.stringify({ type: "approval_decision", requestId: "req-1", decision: "allow" }));
    await waitUntil(() => decisions.length > 0);
    assert.deepEqual(decisions, [{ requestId: "req-1", decision: "allow" }]);

    assert.equal(hub.sendApprovalResolved("req-1", "allow"), true);
    const resolved = await waitFor(messages, (message) => message.type === "approval_resolved");
    assert.equal(resolved.outcome, "allow");
  } finally {
    socket.terminate();
    await hub.stop();
    store.dispose();
  }
});

test("a malformed approval decision is ignored rather than forwarded", async () => {
  const store = new SessionStore(config, silentLogger);
  const decisions = [];
  const hub = new WsHub(config, store, silentLogger, {
    host: "127.0.0.1",
    onApprovalDecision: (requestId, decision) => decisions.push({ requestId, decision }),
  });
  await hub.start();
  const socket = new WebSocket(`ws://127.0.0.1:${hub.port()}`);
  const messages = collect(socket);
  try {
    await opened(socket);
    socket.send(JSON.stringify({
      type: "hello",
      v: 1,
      token: config.token,
      client: { name: "plugin-agents", version: "test" },
    }));
    await waitFor(messages, (message) => message.type === "hello_ack");

    socket.send(JSON.stringify({ type: "approval_decision", decision: "allow" }));
    socket.send(JSON.stringify({ type: "approval_decision", requestId: "req-2" }));
    socket.send(JSON.stringify({ type: "approval_decision", requestId: "req-2", decision: "maybe" }));
    // A well-formed one after them proves the earlier three were dropped, not queued.
    socket.send(JSON.stringify({ type: "approval_decision", requestId: "req-3", decision: "deny" }));
    await waitUntil(() => decisions.length > 0);
    assert.deepEqual(decisions, [{ requestId: "req-3", decision: "deny" }]);
  } finally {
    socket.terminate();
    await hub.stop();
    store.dispose();
  }
});
