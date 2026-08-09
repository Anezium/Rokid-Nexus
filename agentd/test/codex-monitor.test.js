const test = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const { PassThrough } = require("node:stream");
const { WebSocketServer } = require("ws");
const { ApprovalManager } = require("../dist/approval-manager.js");
const {
  CodexMonitor,
  MAX_CODEX_SESSIONS,
  codexApprovalRequestId,
  codexApprovalResponse,
  codexSpawnSpec,
  describeCodexApproval,
  normalizeCodexThread,
  resolveCodexExecutable,
} = require("../dist/codex/monitor.js");
const {
  codexThreadMessages,
  extractCodexMessage,
} = require("../dist/codex/messages.js");
const { silentLogger } = require("../dist/logger.js");
const { SessionStore } = require("../dist/session-store.js");
const {
  CODEX_APP_SERVER_BINDINGS_VERSION,
} = require("../dist/codex/protocol.js");

const identity = {
  machineId: "codex-machine",
  machineName: "codex-pc",
};

function makeThread(overrides = {}) {
  return {
    id: "codex-thread-1",
    sessionId: "codex-session-tree",
    forkedFromId: null,
    parentThreadId: null,
    preview: "Implement the Codex monitor",
    ephemeral: false,
    modelProvider: "openai",
    createdAt: 1_753_000_000,
    updatedAt: 1_753_000_010,
    recencyAt: 1_753_000_020,
    status: { type: "idle" },
    path: "C:\\Users\\test\\.codex\\sessions\\thread.jsonl",
    cwd: "E:\\work\\sample",
    cliVersion: "0.145.0",
    source: "cli",
    threadSource: null,
    agentNickname: null,
    agentRole: null,
    gitInfo: null,
    name: null,
    turns: [],
    ...overrides,
  };
}

function turn(status, overrides = {}) {
  return {
    id: "turn-1",
    items: [],
    itemsView: "all",
    status,
    error: status === "failed"
      ? { message: "model failed", codexErrorInfo: null, additionalDetails: null }
      : null,
    startedAt: 1_753_000_030,
    completedAt: status === "inProgress" ? null : 1_753_000_040,
    durationMs: status === "inProgress" ? null : 10_000,
    ...overrides,
  };
}

// Redacted copies of item shapes observed through thread/read on the live
// app-server. Field names and nesting are preserved; user content is not.
const REAL_APP_SERVER_ITEMS = [
  {
    id: "item-user-redacted",
    type: "userMessage",
    content: [{ type: "text", text: "[redacted user request]\r\nsecond line", text_elements: [] }],
    clientId: null,
  },
  {
    id: "item-agent-redacted",
    type: "agentMessage",
    text: "[redacted answer]\n\nwith detail",
    phase: "final_answer",
    memoryCitation: null,
  },
  {
    id: "item-file-redacted",
    type: "fileChange",
    changes: [{ path: "E:\\redacted\\src\\monitor.ts", kind: "update", diff: "[redacted]" }],
    status: "completed",
  },
  {
    id: "item-mcp-redacted",
    type: "mcpToolCall",
    server: "cua-driver",
    tool: "get_window_state",
    arguments: { session: "[redacted]" },
    status: "completed",
    durationMs: 10,
    result: null,
    error: null,
    pluginId: null,
    appContext: null,
  },
  { id: "item-reasoning-redacted", type: "reasoning", content: [], summary: [] },
  { id: "item-search-redacted", type: "webSearch", query: "[redacted]", action: null, results: [] },
];

// Redacted copies of representative JSONL records observed in real rollouts.
const REAL_ROLLOUT_LINES = [
  {
    timestamp: "2026-08-08T10:00:00.000Z",
    type: "response_item",
    payload: {
      type: "message",
      role: "user",
      content: [{ type: "input_text", text: "[redacted rollout request]\r\nnext line" }],
    },
  },
  {
    timestamp: "2026-08-08T10:00:01.000Z",
    type: "response_item",
    payload: {
      type: "message",
      role: "assistant",
      content: [{ type: "output_text", text: "[redacted rollout answer]" }],
    },
  },
  {
    timestamp: "2026-08-08T10:00:02.000Z",
    type: "response_item",
    payload: {
      type: "function_call",
      name: "exec_command",
      arguments: JSON.stringify({ cmd: "git status", workdir: "E:\\redacted" }),
      call_id: "call-redacted",
    },
  },
  {
    timestamp: "2026-08-08T10:00:03.000Z",
    type: "response_item",
    payload: { type: "reasoning", content: null, summary: [], encrypted_content: null },
  },
];

class FakeApprovalTransport {
  connected = true;
  frames = [];

  sendApprovalRequest(request) {
    if (!this.connected) return false;
    this.frames.push({ ...request });
    return true;
  }

  sendApprovalResolved(requestId, outcome) {
    if (!this.connected) return false;
    this.frames.push({ type: "approval_resolved", v: 1, requestId, outcome });
    return true;
  }
}

async function waitUntil(predicate, message, timeoutMs = 2500) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const result = predicate();
    if (result) return result;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  throw new Error(message);
}

function listen(server, port = 0) {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", () => resolve(server.address().port));
  });
}

function mockCodexFileSystem(files, directories = []) {
  const normalize = (filePath) => filePath.replaceAll("/", "\\").toLowerCase();
  const fileEntries = new Map(
    Object.entries(files).map(([filePath, contents]) => [normalize(filePath), contents]),
  );
  const directoryEntries = new Set(directories.map(normalize));
  return {
    readText(filePath) {
      const contents = fileEntries.get(normalize(filePath));
      if (contents === undefined) throw new Error(`missing mock file: ${filePath}`);
      return contents;
    },
    isFile(filePath) {
      return fileEntries.has(normalize(filePath));
    },
    isDirectory(filePath) {
      return directoryEntries.has(normalize(filePath));
    },
  };
}

async function closeServer(server) {
  for (const client of server.clients) client.terminate();
  await new Promise((resolve) => server.close(resolve));
}

function makeHarness(port, overrides = {}) {
  const store = new SessionStore(identity, silentLogger);
  const transport = new FakeApprovalTransport();
  const approvals = new ApprovalManager({
    transport,
    logger: silentLogger,
    timeoutMs: overrides.approvalTimeoutMs ?? 1000,
  });
  const monitor = new CodexMonitor({
    config: {
      ...identity,
      codex: { enabled: true, port },
    },
    store,
    approvals,
    connectTimeoutMs: 80,
    requestTimeoutMs: 1000,
    startTimeoutMs: 500,
    reconnectDelayMs: 20,
    sweepIntervalMs: overrides.sweepIntervalMs,
    logger: overrides.logger ?? silentLogger,
    launch: overrides.launch ?? (() => {
      throw new Error("codex executable not found");
    }),
  });
  return { store, transport, approvals, monitor };
}

function attachProtocol(server, threadFactory = () => makeThread()) {
  const connections = [];
  server.on("connection", (socket) => {
    const record = { socket, messages: [] };
    connections.push(record);
    socket.on("message", (raw) => {
      const message = JSON.parse(raw.toString());
      record.messages.push(message);
      if (message.id === undefined) return;
      let result;
      switch (message.method) {
        case "initialize":
          result = { userAgent: "fake-codex" };
          break;
        case "thread/list":
          result = { data: [threadFactory()], nextCursor: null, backwardsCursor: null };
          break;
        case "thread/resume":
          result = {
            thread: threadFactory(),
            model: "gpt-5",
            modelProvider: "openai",
            serviceTier: null,
            cwd: threadFactory().cwd,
          };
          break;
        case "thread/read":
          result = { thread: threadFactory() };
          break;
        default:
          return;
      }
      socket.send(JSON.stringify({ id: message.id, result }));
    });
  });
  return connections;
}

test("Codex display mapping uses observed app-server items and skips internal chatter", () => {
  const startedAt = 1_753_000_030;
  const threadValue = makeThread({
    turns: [turn("completed", { startedAt, items: structuredClone(REAL_APP_SERVER_ITEMS) })],
  });
  const messages = codexThreadMessages(threadValue, 40);

  assert.deepEqual(messages.map((message) => message.role), [
    "user",
    "assistant",
    "tool",
    "tool",
  ]);
  assert.equal(messages[0].text, "[redacted user request]\nsecond line");
  assert.equal(messages[1].text, "[redacted answer]\n\nwith detail");
  assert.match(messages[2].text, /^edit · .*monitor\.ts$/);
  assert.equal(messages[2].tool, "edit");
  assert.equal(messages[3].text, "get_window_state");
  assert.equal(messages[3].tool, "get_window_state");
  assert.equal(messages.every((message) => message.at === startedAt * 1000), true);
  assert.deepEqual(codexThreadMessages(threadValue, 2), messages.slice(-2));
});

test("Codex display mapping accepts real rollout record shapes defensively", () => {
  const messages = REAL_ROLLOUT_LINES
    .map((line) => extractCodexMessage(line, 1))
    .filter(Boolean);

  assert.deepEqual(messages.map((message) => message.role), ["user", "assistant", "tool"]);
  assert.equal(messages[0].text, "[redacted rollout request]\nnext line");
  assert.equal(messages[1].text, "[redacted rollout answer]");
  assert.equal(messages[2].text, "shell · git status");
  assert.equal(messages[2].tool, "shell");
  assert.equal(messages[2].at, Date.parse("2026-08-08T10:00:02.000Z"));
  assert.equal(extractCodexMessage(null), undefined);
  assert.equal(extractCodexMessage({ type: "response_item", payload: null }), undefined);
});

test("readMessages uses only thread/read and returns the requested display tail", async () => {
  const server = new WebSocketServer({ port: 0, host: "127.0.0.1" });
  await new Promise((resolve) => server.once("listening", resolve));
  const port = server.address().port;
  const currentThread = makeThread({
    turns: [turn("completed", { items: structuredClone(REAL_APP_SERVER_ITEMS) })],
  });
  const connections = attachProtocol(server, () => structuredClone(currentThread));
  const { store, approvals, monitor } = makeHarness(port);
  try {
    monitor.start();
    await waitUntil(() => monitor.availability().available, "monitor did not connect");
    const requestStart = connections[0].messages.length;

    const messages = await monitor.readMessages(currentThread.id, 3);

    assert.deepEqual(
      connections[0].messages.slice(requestStart).map((message) => message.method),
      ["thread/read"],
    );
    assert.deepEqual(messages.map((message) => message.role), ["assistant", "tool", "tool"]);
  } finally {
    await monitor.stop();
    approvals.dispose();
    store.dispose();
    await closeServer(server);
  }
});

test("Codex normalization has conservative explicit status, title, and path mappings", () => {
  const cases = [
    [{ status: { type: "active", activeFlags: [] }, turns: [turn("inProgress")] }, "working"],
    [{ status: { type: "active", activeFlags: ["waitingOnApproval"] } }, "needs_you"],
    [{ status: { type: "idle" }, turns: [turn("failed")] }, "error"],
    [{ status: { type: "systemError" } }, "error"],
    [{ status: { type: "notLoaded" }, turns: [turn("completed")] }, "idle"],
  ];
  for (const [overrides, expected] of cases) {
    assert.equal(normalizeCodexThread(makeThread(overrides), identity).status, expected);
  }

  const named = normalizeCodexThread(
    makeThread({ name: "  Friendly name  " }),
    identity,
  );
  assert.equal(named.provider, "codex");
  assert.equal(named.title, "Friendly name");
  assert.equal(named.cwd, "E:/work/sample");
  assert.equal(named.project, "sample");

  const pending = normalizeCodexThread(
    makeThread({ status: { type: "idle" }, turns: [turn("failed")] }),
    identity,
    { summary: "Approve npm test", createdAt: 1_753_000_050_000 },
  );
  assert.equal(pending.status, "needs_you", "an active approval takes precedence");
  assert.equal(pending.pendingRequest.kind, "permission");
});

test("approval projections and responses use the JSON-RPC id and exact stable contracts", () => {
  assert.notEqual(codexApprovalRequestId("rpc-7"), codexApprovalRequestId(7));
  assert.equal(codexApprovalRequestId("rpc-7"), "codex:s:cnBjLTc");

  const command = {
    id: "rpc-7",
    method: "item/commandExecution/requestApproval",
    params: {
      threadId: "thread",
      turnId: "turn",
      itemId: "reused-item-id",
      startedAtMs: 123,
      environmentId: null,
      reason: "Run tests",
      command: "npm test",
      cwd: "E:\\repo",
    },
  };
  assert.deepEqual(describeCodexApproval(command), {
    tool: "Command",
    summary: "Run tests",
    detail: "npm test",
    createdAt: 123,
  });
  assert.deepEqual(codexApprovalResponse(command, "allow"), { decision: "accept" });
  assert.deepEqual(codexApprovalResponse(command, "deny"), { decision: "decline" });

  const permissions = {
    id: 9,
    method: "item/permissions/requestApproval",
    params: {
      threadId: "thread",
      turnId: "turn",
      itemId: "item",
      environmentId: null,
      startedAtMs: 456,
      cwd: "E:\\repo",
      reason: null,
      permissions: {
        network: { enabled: true },
        fileSystem: { read: ["E:\\input"], write: ["E:\\output"] },
      },
    },
  };
  assert.deepEqual(codexApprovalResponse(permissions, "allow"), {
    permissions: permissions.params.permissions,
    scope: "turn",
  });
  assert.deepEqual(codexApprovalResponse(permissions, "deny"), {
    permissions: {},
    scope: "turn",
  });
});

test("spawn specs launch the real Windows executable without a shell", () => {
  assert.equal(CODEX_APP_SERVER_BINDINGS_VERSION, "0.145.0");
  const executable = "C:\\npm\\node_modules\\@openai\\codex-win32-x64" +
    "\\vendor\\x86_64-pc-windows-msvc\\bin\\codex.exe";
  const bundledPath = "C:\\npm\\node_modules\\@openai\\codex-win32-x64" +
    "\\vendor\\x86_64-pc-windows-msvc\\codex-path";
  const windows = codexSpawnSpec(
    8390,
    "win32",
    () => ({ command: executable, pathDirs: [bundledPath] }),
    { Path: "C:\\Windows\\System32" },
  );
  assert.equal(windows.command, executable);
  assert.deepEqual(windows.args, [
    "app-server",
    "--listen",
    "ws://127.0.0.1:8390",
  ]);
  assert.equal(windows.options.shell, false);
  assert.equal(windows.options.detached, true);
  assert.equal(windows.options.windowsHide, true);
  assert.deepEqual(windows.options.stdio, ["ignore", "ignore", "pipe"]);
  assert.equal(
    windows.options.env.Path,
    `${bundledPath};C:\\Windows\\System32`,
  );
  assert.equal(windows.args.some((arg) => arg.includes("0.0.0.0")), false);

  for (const platform of ["linux", "darwin"]) {
    const spec = codexSpawnSpec(8390, platform, () => {
      throw new Error("non-Windows specs must not resolve a .cmd shim");
    });
    assert.equal(spec.command, "codex");
    assert.equal(spec.options.shell, false);
    assert.equal(spec.options.detached, true);
    assert.deepEqual(spec.options.stdio, ["ignore", "ignore", "pipe"]);
  }
});

test("Windows shim resolution finds nested current Codex npm binaries", () => {
  const shim = "C:\\npm\\codex.cmd";
  const executable = "C:\\npm\\node_modules\\@openai\\codex\\node_modules\\@openai" +
    "\\codex-win32-x64\\vendor\\x86_64-pc-windows-msvc\\bin\\codex.exe";
  const bundledPath = "C:\\npm\\node_modules\\@openai\\codex\\node_modules\\@openai" +
    "\\codex-win32-x64\\vendor\\x86_64-pc-windows-msvc\\codex-path";
  const fileSystem = mockCodexFileSystem({
    [shim]: '@"%dp0%\\node.exe" "%dp0%\\node_modules\\@openai\\codex\\bin\\codex.js" %*',
    [executable]: "",
  }, [bundledPath]);

  assert.deepEqual(
    resolveCodexExecutable("x64", { Path: '"C:\\missing";C:\\npm' }, fileSystem),
    { command: executable, pathDirs: [bundledPath] },
  );
});

test("Windows shim resolution accepts absolute shims and hoisted legacy binaries", () => {
  const shim = "D:\\links\\codex.cmd";
  const executable = "C:\\npm\\node_modules\\@openai\\codex-win32-arm64" +
    "\\vendor\\aarch64-pc-windows-msvc\\codex\\codex.exe";
  const bundledPath = "C:\\npm\\node_modules\\@openai\\codex-win32-arm64" +
    "\\vendor\\aarch64-pc-windows-msvc\\path";
  const fileSystem = mockCodexFileSystem({
    [shim]: '@node "C:\\npm\\node_modules\\@openai\\codex\\bin\\codex.js" %*',
    [executable]: "",
  }, [bundledPath]);

  assert.deepEqual(
    resolveCodexExecutable("arm64", { PATH: "D:\\links" }, fileSystem),
    { command: executable, pathDirs: [bundledPath] },
  );
});

test("startThread creates, adopts, and optionally starts a turn in binding order", async () => {
  const server = new WebSocketServer({ port: 0, host: "127.0.0.1" });
  await new Promise((resolve) => server.once("listening", resolve));
  const port = server.address().port;
  const messages = [];
  const threads = new Map();
  let threadNumber = 0;
  server.on("connection", (socket) => {
    socket.on("message", (raw) => {
      const message = JSON.parse(raw.toString());
      messages.push(message);
      if (message.id === undefined) return;
      let result;
      switch (message.method) {
        case "initialize":
          result = { userAgent: "fake-codex" };
          break;
        case "thread/list":
          result = { data: [], nextCursor: null, backwardsCursor: null };
          break;
        case "thread/start": {
          if (message.params.cwd.endsWith("rpc-error")) {
            socket.send(JSON.stringify({
              id: message.id,
              error: { code: -32000, message: "Start denied by test" },
            }));
            return;
          }
          const thread = makeThread({
            id: `started-thread-${++threadNumber}`,
            cwd: message.params.cwd,
            preview: "",
          });
          threads.set(thread.id, thread);
          result = { thread };
          break;
        }
        case "thread/resume": {
          const thread = threads.get(message.params.threadId);
          result = {
            thread,
            model: "gpt-5",
            modelProvider: "openai",
            serviceTier: null,
            cwd: thread.cwd,
          };
          break;
        }
        case "thread/read":
          result = { thread: threads.get(message.params.threadId) };
          break;
        case "turn/start":
          result = { turn: turn("inProgress") };
          break;
        default:
          return;
      }
      socket.send(JSON.stringify({ id: message.id, result }));
    });
  });
  const { store, approvals, monitor } = makeHarness(port);
  try {
    assert.deepEqual(await monitor.startThread("E:\\work\\new", "Do work"), {
      ok: false,
      error: "Codex is not available on this computer",
    });
    monitor.start();
    await waitUntil(() => monitor.availability().available, "monitor did not connect");

    let requestStart = messages.length;
    assert.deepEqual(await monitor.startThread("E:\\work\\new", "Do work"), {
      ok: true,
      sessionId: "started-thread-1",
    });
    const firstRequests = messages.slice(requestStart);
    // A thread born on this connection is live already: no resume, no read.
    assert.deepEqual(firstRequests.map((message) => message.method), [
      "thread/start",
      "turn/start",
    ]);
    assert.deepEqual(firstRequests[0].params, { cwd: "E:\\work\\new" });
    assert.deepEqual(firstRequests[1].params, {
      threadId: "started-thread-1",
      input: [{ type: "text", text: "Do work", text_elements: [] }],
    });
    assert.equal(store.get("started-thread-1").provider, "codex");

    requestStart = messages.length;
    assert.deepEqual(await monitor.startThread("E:\\work\\empty", ""), {
      ok: true,
      sessionId: "started-thread-2",
    });
    assert.deepEqual(messages.slice(requestStart).map((message) => message.method), [
      "thread/start",
    ]);

    assert.deepEqual(await monitor.startThread("E:\\work\\rpc-error", "ignored"), {
      ok: false,
      error: "Start denied by test",
    });
  } finally {
    await monitor.stop();
    approvals.dispose();
    store.dispose();
    await closeServer(server);
  }
});

test("monitor attaches, resumes, refreshes, streams statuses, and routes approval responses", async () => {
  const server = new WebSocketServer({ noServer: true });
  const port = await new Promise((resolve, reject) => {
    const http = require("node:http").createServer();
    http.once("error", reject);
    http.listen(0, "127.0.0.1", () => {
      http.on("upgrade", (request, socket, head) => {
        server.handleUpgrade(request, socket, head, (ws) => server.emit("connection", ws, request));
      });
      server._httpServer = http;
      resolve(http.address().port);
    });
  });
  let currentThread = makeThread();
  const connections = attachProtocol(server, () => structuredClone(currentThread));
  const { store, transport, approvals, monitor } = makeHarness(port);

  try {
    monitor.start();
    await waitUntil(() => monitor.availability().available, "monitor did not attach");
    assert.equal(store.get(currentThread.id).provider, "codex");
    assert.deepEqual(
      connections[0].messages.map((message) => message.method),
      ["initialize", "initialized", "thread/list", "thread/resume", "thread/read"],
    );

    currentThread = makeThread({
      status: { type: "active", activeFlags: [] },
      turns: [turn("inProgress")],
    });
    connections[0].socket.send(JSON.stringify({
      method: "turn/started",
      params: { threadId: currentThread.id, turn: currentThread.turns[0] },
    }));
    await waitUntil(() => store.get(currentThread.id)?.status === "working", "turn did not go working");

    const approvalRpcId = "approval-request-44";
    connections[0].socket.send(JSON.stringify({
      id: approvalRpcId,
      method: "item/commandExecution/requestApproval",
      params: {
        threadId: currentThread.id,
        turnId: "turn-1",
        itemId: "not-unique",
        startedAtMs: Date.now(),
        environmentId: null,
        reason: "Run the test suite",
        command: "npm test",
        cwd: "E:\\work\\sample",
      },
    }));
    const frame = await waitUntil(
      () => transport.frames.find((entry) => entry.type === "approval_request"),
      "approval was not sent to the phone transport",
    );
    assert.equal(frame.requestId, codexApprovalRequestId(approvalRpcId));
    assert.equal(store.get(currentThread.id).status, "needs_you");

    approvals.handleDecision(frame.requestId, "allow");
    const response = await waitUntil(
      () => connections[0].messages.find(
        (message) => message.id === approvalRpcId && message.result,
      ),
      "approval response was not returned to Codex",
    );
    assert.deepEqual(response.result, { decision: "accept" });

    const droppedRpcId = 45;
    connections[0].socket.send(JSON.stringify({
      id: droppedRpcId,
      method: "item/fileChange/requestApproval",
      params: {
        threadId: currentThread.id,
        turnId: "turn-1",
        itemId: "also-not-a-routing-key",
        startedAtMs: Date.now(),
        reason: "Write the generated report",
        grantRoot: "E:\\work\\sample",
      },
    }));
    await waitUntil(
      () => transport.frames.find(
        (entry) => entry.requestId === codexApprovalRequestId(droppedRpcId),
      ),
      "second approval was not sent to the phone transport",
    );
    transport.connected = false;
    approvals.onLinkDisconnected();
    await new Promise((resolve) => setTimeout(resolve, 20));
    assert.equal(
      connections[0].messages.some((message) => message.id === droppedRpcId && message.result),
      false,
      "phone link loss must not answer the Codex request",
    );
    assert.equal(store.get(currentThread.id).status, "needs_you");
    transport.connected = true;

    connections[0].socket.terminate();
    await waitUntil(() => connections.length === 2, "monitor did not reconnect");
    await waitUntil(
      () => connections[1].messages.some((message) => message.method === "thread/read"),
      "reconnect did not refresh",
    );
    const methods = connections[1].messages.map((message) => message.method);
    assert.ok(
      methods.indexOf("thread/resume") < methods.indexOf("thread/list"),
      "known threads must be re-resumed before the authoritative list refresh",
    );
    assert.ok(methods.indexOf("thread/list") < methods.indexOf("thread/read"));
  } finally {
    await monitor.stop();
    approvals.dispose();
    store.dispose();
    for (const client of server.clients) client.terminate();
    await new Promise((resolve) => server._httpServer.close(resolve));
    server.close();
  }
});

test("monitor caps discovery at the session cap and never requests an extra page", async () => {
  const server = new WebSocketServer({ port: 0, host: "127.0.0.1" });
  await new Promise((resolve) => server.once("listening", resolve));
  const port = server.address().port;
  const fakePageSize = 40;
  const listRequests = [];
  server.on("connection", (socket) => {
    socket.on("message", (raw) => {
      const message = JSON.parse(raw.toString());
      if (message.id === undefined) return;
      if (message.method === "initialize") {
        socket.send(JSON.stringify({ id: message.id, result: {} }));
        return;
      }
      if (message.method === "thread/list") {
        listRequests.push(message.params);
        const offset = (listRequests.length - 1) * fakePageSize;
        const data = Array.from({ length: fakePageSize }, (_, index) =>
          makeThread({
            id: `thread-${offset + index}`,
            updatedAt: 1_753_001_000 - offset - index,
            recencyAt: 1_753_001_000 - offset - index,
          }),
        );
        socket.send(JSON.stringify({
          id: message.id,
          result: {
            data,
            nextCursor: `page-${listRequests.length + 1}-must-not-be-read-past-the-cap`,
            backwardsCursor: null,
          },
        }));
        return;
      }
      const threadId = message.params.threadId;
      const thread = makeThread({ id: threadId });
      const result = message.method === "thread/resume"
        ? {
            thread,
            model: "gpt-5",
            modelProvider: "openai",
            serviceTier: null,
            cwd: thread.cwd,
          }
        : { thread };
      socket.send(JSON.stringify({ id: message.id, result }));
    });
  });
  const { store, approvals, monitor } = makeHarness(port);
  try {
    monitor.start();
    await waitUntil(() => monitor.availability().available, "monitor did not finish discovery", 5000);
    assert.equal(
      store.list().filter((session) => session.provider === "codex").length,
      MAX_CODEX_SESSIONS,
    );
    assert.equal(listRequests.length, Math.ceil(MAX_CODEX_SESSIONS / fakePageSize));
    assert.equal(listRequests[0].limit, MAX_CODEX_SESSIONS);
    assert.equal(listRequests.at(-1).limit, MAX_CODEX_SESSIONS - fakePageSize);
    assert.ok(listRequests.every((params) => params.sortKey === "recency_at"));
    assert.ok(listRequests.every((params) => params.sourceKinds.includes("exec")));
  } finally {
    await monitor.stop();
    approvals.dispose();
    store.dispose();
    await closeServer(server);
  }
});

test("the monitor tracks the spawned app-server pid and leaves it alive on stop", async () => {
  const probe = new WebSocketServer({ port: 0, host: "127.0.0.1" });
  await new Promise((resolve) => probe.once("listening", resolve));
  const port = probe.address().port;
  await closeServer(probe);

  let ownedServer;
  let killed = false;
  const logEvents = [];
  class FakeChild extends EventEmitter {
    pid = 4242;
    stderr = new PassThrough();
    exitCode = null;
    signalCode = null;
    unreferenced = false;
    unref() {
      this.unreferenced = true;
    }
    kill() {
      killed = true;
      this.exitCode = 0;
      for (const client of ownedServer.clients) client.terminate();
      this.emit("exit", 0, null);
      return true;
    }
  }
  const child = new FakeChild();
  const launch = (requestedPort) => {
    assert.equal(requestedPort, port);
    ownedServer = new WebSocketServer({ port: requestedPort, host: "127.0.0.1" });
    attachProtocol(ownedServer);
    return child;
  };
  const { store, approvals, monitor } = makeHarness(port, {
    launch,
    logger: {
      ...silentLogger,
      info(event, meta) {
        logEvents.push({ event, meta });
      },
    },
  });
  try {
    monitor.start();
    await waitUntil(() => monitor.availability().available, "owned server was not reached");
    assert.equal(store.get("codex-thread-1").provider, "codex");
    assert.equal(child.unreferenced, true);
    assert.deepEqual(
      logEvents.find((entry) => entry.event === "codex_app_server_started")?.meta,
      { endpoint: `ws://127.0.0.1:${port}`, pid: 4242 },
    );
  } finally {
    await monitor.stop();
    assert.equal(killed, false, "normal monitor shutdown must preserve the app-server");
    assert.deepEqual(
      logEvents.find((entry) => entry.event === "codex_app_server_left_running")?.meta,
      { pid: 4242 },
    );
    if (ownedServer) {
      child.kill();
      await closeServer(ownedServer);
    }
    approvals.dispose();
    store.dispose();
  }
});

test("missing Codex is an availability reason, not a monitor startup error", async () => {
  const probe = new WebSocketServer({ port: 0, host: "127.0.0.1" });
  await new Promise((resolve) => probe.once("listening", resolve));
  const port = probe.address().port;
  await closeServer(probe);
  const { store, approvals, monitor } = makeHarness(port);
  try {
    assert.doesNotThrow(() => monitor.start());
    const state = await waitUntil(
      () => {
        const availability = monitor.availability();
        return availability.reason?.includes("could not start") ? availability : undefined;
      },
      "missing Codex reason was not reported",
    );
    assert.equal(state.enabled, true);
    assert.equal(state.available, false);
    assert.match(state.reason, /codex executable not found/);
    assert.equal(store.size, 0);
  } finally {
    await monitor.stop();
    approvals.dispose();
    store.dispose();
  }
});

test("disabled Codex monitoring neither connects nor launches a process", async () => {
  const store = new SessionStore(identity, silentLogger);
  const transport = new FakeApprovalTransport();
  const approvals = new ApprovalManager({ transport, logger: silentLogger });
  let launches = 0;
  const monitor = new CodexMonitor({
    config: { ...identity, codex: { enabled: false, port: 8390 } },
    store,
    approvals,
    logger: silentLogger,
    launch() {
      launches += 1;
      throw new Error("must not launch");
    },
  });
  try {
    monitor.start();
    await new Promise((resolve) => setTimeout(resolve, 20));
    assert.deepEqual(monitor.availability(), {
      enabled: false,
      available: false,
      reason: "disabled",
    });
    assert.equal(launches, 0);
  } finally {
    await monitor.stop();
    approvals.dispose();
    store.dispose();
  }
});

test("the periodic sweep adopts threads born outside the app-server connection", async () => {
  const server = new WebSocketServer({ port: 0, host: "127.0.0.1" });
  await new Promise((resolve) => server.once("listening", resolve));
  const port = server.address().port;
  const threads = new Map([["thread-a", makeThread({ id: "thread-a" })]]);
  server.on("connection", (socket) => {
    socket.on("message", (raw) => {
      const message = JSON.parse(raw.toString());
      if (message.id === undefined) return;
      let result;
      switch (message.method) {
        case "initialize":
          result = { userAgent: "fake-codex" };
          break;
        case "thread/list":
          result = { data: [...threads.values()], nextCursor: null, backwardsCursor: null };
          break;
        case "thread/resume":
          result = {
            thread: threads.get(message.params.threadId) ?? makeThread(),
            model: "gpt-5",
            modelProvider: "openai",
            serviceTier: null,
            cwd: "E:\work\sample",
          };
          break;
        case "thread/read":
          result = { thread: threads.get(message.params.threadId) ?? makeThread() };
          break;
        default:
          return;
      }
      socket.send(JSON.stringify({ id: message.id, result }));
    });
  });
  const { store, approvals, monitor } = makeHarness(port, { sweepIntervalMs: 40 });
  try {
    monitor.start();
    await waitUntil(() => monitor.availability().available, "monitor did not connect");
    assert.equal(store.get("thread-b"), undefined);

    // A terminal-only run appears in storage without any server notification.
    threads.set("thread-b", makeThread({ id: "thread-b", preview: "CLI run" }));
    const adopted = await waitUntil(
      () => store.get("thread-b"),
      "sweep did not adopt the CLI thread",
    );
    assert.equal(adopted.provider, "codex");
    assert.equal(adopted.title, "CLI run");
  } finally {
    await monitor.stop();
    approvals.dispose();
    store.dispose();
    await closeServer(server);
  }
});
