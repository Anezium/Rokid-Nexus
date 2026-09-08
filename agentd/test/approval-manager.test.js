const test = require("node:test");
const assert = require("node:assert/strict");
const {
  ApprovalManager,
  DEFAULT_APPROVAL_TIMEOUT_MS,
  MAX_PENDING_APPROVALS,
  approvalTimeoutFromEnv,
  describeTool,
} = require("../dist/approval-manager.js");
const { silentLogger } = require("../dist/logger.js");

class FakeTransport {
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

function harness(options = {}) {
  const transport = new FakeTransport();
  let nextId = 0;
  const manager = new ApprovalManager({
    transport,
    logger: silentLogger,
    now: () => 1_737_000_000_000,
    requestId: () => `request-${++nextId}`,
    ...options,
  });
  return { manager, transport };
}

function bashHook(overrides = {}) {
  return {
    hook_event_name: "PreToolUse",
    session_id: "session-approval",
    tool_name: "Bash",
    tool_use_id: "tool-use-1",
    tool_input: {
      command: "cd /repo && npm test",
      description: "npm test",
    },
    ...overrides,
  };
}

test("phone allow and deny decisions return Claude's exact PermissionRequest contract", async () => {
  const { manager, transport } = harness();

  const allowed = manager.request(bashHook());
  assert.deepEqual(transport.frames[0], {
    type: "approval_request",
    v: 1,
    requestId: "request-1",
    sessionId: "session-approval",
    tool: "Bash",
    summary: "npm test",
    detail: "cd /repo && npm test",
    createdAt: 1_737_000_000_000,
  });
  manager.handleDecision("not-pending", "deny");
  assert.equal(manager.size, 1);
  manager.handleDecision("request-1", "allow");
  assert.deepEqual(await allowed, {
    hookSpecificOutput: {
      hookEventName: "PermissionRequest",
      decision: "allow",
      decisionReason: "Approved by the wearer in Nexus Agents.",
    },
  });
  assert.equal(manager.size, 0);
  assert.equal(transport.frames.length, 1, "a decision must not emit approval_resolved");

  const denied = manager.request(bashHook());
  manager.handleDecision("request-2", "deny");
  assert.deepEqual(await denied, {
    hookSpecificOutput: {
      hookEventName: "PermissionRequest",
      decision: "deny",
      decisionReason: "Denied by the wearer in Nexus Agents.",
    },
  });
  assert.equal(transport.frames.length, 2);
});

test("no phone, timeout, and link loss fall through locally without a decision", async () => {
  const disconnected = harness();
  disconnected.transport.connected = false;
  assert.deepEqual(await disconnected.manager.request(bashHook()), {});
  assert.equal(disconnected.manager.size, 0);
  assert.deepEqual(disconnected.transport.frames, []);

  const timed = harness({ timeoutMs: 20 });
  const timeoutResult = timed.manager.request(bashHook());
  const timeoutGuard = new Promise((_, reject) => {
    setTimeout(() => reject(new Error("approval timeout did not resolve")), 250);
  });
  assert.deepEqual(await Promise.race([timeoutResult, timeoutGuard]), {});
  assert.deepEqual(timed.transport.frames[1], {
    type: "approval_resolved",
    v: 1,
    requestId: "request-1",
    outcome: "timeout",
  });
  assert.equal(timed.manager.size, 0);

  const dropped = harness();
  const dropResult = dropped.manager.request(bashHook());
  dropped.transport.connected = false;
  dropped.manager.onLinkDisconnected();
  assert.deepEqual(await dropResult, {});
  assert.equal(dropped.manager.size, 0);
  assert.equal(dropped.transport.frames.length, 1);
});

test("reconnect resends pending requests and session end resolves them locally", async () => {
  const { manager, transport } = harness();
  const result = manager.request(bashHook());
  manager.onLinkConnected();
  assert.equal(transport.frames.length, 2);
  assert.deepEqual(transport.frames[1], transport.frames[0]);

  manager.resolveSession("another-session");
  assert.equal(manager.size, 1);
  manager.resolveSession("session-approval");
  assert.deepEqual(await result, {});
  assert.deepEqual(transport.frames[2], {
    type: "approval_resolved",
    v: 1,
    requestId: "request-1",
    outcome: "local",
  });
  assert.equal(manager.size, 0);
});

test("pending approvals are bounded at 32 and dispose clears every timer and entry", async () => {
  const { manager, transport } = harness();
  assert.equal(MAX_PENDING_APPROVALS, 32);
  const held = [];
  for (let index = 0; index < MAX_PENDING_APPROVALS; index += 1) {
    held.push(manager.request(bashHook({ session_id: `session-${index}` })));
  }
  assert.equal(manager.size, 32);
  assert.equal(transport.frames.length, 32);
  assert.deepEqual(await manager.request(bashHook({ session_id: "overflow" })), {});
  assert.equal(manager.size, 32);

  manager.dispose();
  assert.equal(manager.size, 0);
  assert.equal(
    transport.frames.filter((frame) => frame.type === "approval_resolved").length,
    32,
  );
  assert.deepEqual(await Promise.all(held), Array.from({ length: 32 }, () => ({})));
});

test("tool descriptions are concise projections rather than whole hook payloads", () => {
  const described = describeTool({
    tool_name: "Edit",
    tool_input: {
      file_path: "E:/repo/src/large.ts",
      old_string: "x".repeat(300),
      new_string: "y".repeat(300),
      unrelated_secret_blob: "must-not-be-copied",
    },
  });
  assert.equal(described.tool, "Edit");
  assert.ok(described.summary.length <= 120);
  assert.ok(described.detail.length <= 400);
  assert.match(described.detail, /file_path/);
  assert.doesNotMatch(described.detail, /must-not-be-copied/);

  const custom = describeTool({
    tool_name: "mcp__example__deploy",
    tool_input: {
      environment: "staging",
      service: "agents-api",
      rollout: { percent: 25 },
    },
  });
  assert.match(custom.summary, /staging/);
  assert.match(custom.detail, /environment: staging/);
  assert.match(custom.detail, /rollout: \{"percent":25\}/);
});

test("approval timeout defaults to 120 seconds and accepts a positive environment override", () => {
  assert.equal(DEFAULT_APPROVAL_TIMEOUT_MS, 120_000);
  assert.equal(approvalTimeoutFromEnv(undefined), 120_000);
  assert.equal(approvalTimeoutFromEnv("45000"), 45_000);
  assert.equal(approvalTimeoutFromEnv("invalid"), 120_000);
  assert.equal(approvalTimeoutFromEnv("-1"), 120_000);
  assert.equal(approvalTimeoutFromEnv("999999999999"), 120_000);
});

test("provider-native approvals share timeout and link-loss handling without Claude responses", async () => {
  const { manager, transport } = harness({ timeoutMs: 20 });
  const request = {
    type: "approval_request",
    v: 1,
    requestId: "codex:n:NDI",
    sessionId: "codex-thread",
    tool: "Command",
    summary: "npm test",
    detail: "npm test",
    createdAt: 1_737_000_000_000,
  };
  const timed = manager.requestDecision(request);
  assert.deepEqual(transport.frames[0], request);
  assert.equal(await timed, undefined);
  assert.deepEqual(transport.frames[1], {
    type: "approval_resolved",
    v: 1,
    requestId: request.requestId,
    outcome: "timeout",
  });

  const dropped = manager.requestDecision({ ...request, requestId: "codex:n:NDM" });
  transport.connected = false;
  manager.onLinkDisconnected();
  assert.equal(await dropped, undefined);
  assert.equal(transport.frames.length, 3, "link loss must not send a provider decision");
});
