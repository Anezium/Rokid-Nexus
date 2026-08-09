const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const { SessionStore } = require("../dist/session-store.js");
const {
  MAX_TRANSCRIPT_LINE_BYTES,
  TranscriptLineParser,
  TranscriptTailer,
} = require("../dist/transcript.js");
const { silentLogger } = require("../dist/logger.js");

test("tailer reads appends, buffers partial lines, and applies tool and error entries", async () => {
  const tempDir = await fsp.mkdtemp(path.join(os.tmpdir(), "nexus-agentd-transcript-"));
  const transcriptPath = path.join(tempDir, "session.jsonl");
  const fixturePath = path.join(__dirname, "fixtures", "transcript-appends.jsonl");
  const fixtureLines = fs.readFileSync(fixturePath, "utf8").trimEnd().split(/\r?\n/);
  await fsp.writeFile(transcriptPath, '{"type":"system","timestamp":1753380000000}\n');

  const store = new SessionStore(
    { machineId: "machine-test", machineName: "test-pc" },
    silentLogger,
  );
  store.handleHook({
    session_id: "tail-session",
    transcript_path: transcriptPath,
    cwd: "E:\\work\\tail",
    hook_event_name: "UserPromptSubmit",
    prompt: "Tail this",
  });
  const tailer = new TranscriptTailer(
    transcriptPath,
    (update) => store.applyTranscriptUpdate("tail-session", update),
    silentLogger,
    { pollIntervalMs: 60_000 },
  );

  try {
    await tailer.start();
    const assistantLine = fixtureLines[0];
    const splitAt = Math.floor(assistantLine.length / 2);
    await fsp.appendFile(transcriptPath, assistantLine.slice(0, splitAt));
    await tailer.pollNow();
    assert.equal(store.get("tail-session").lastAssistantText, undefined);

    await fsp.appendFile(transcriptPath, `${assistantLine.slice(splitAt)}\n`);
    await tailer.pollNow();
    let session = store.get("tail-session");
    assert.equal(session.lastAssistantText, "I am checking the build output now.");
    assert.equal(session.turn.lastTool, "Bash");
    assert.equal(session.status, "working");

    await fsp.appendFile(transcriptPath, `${fixtureLines[1]}\n`);
    await tailer.pollNow();
    session = store.get("tail-session");
    assert.equal(session.status, "error");
    assert.equal(session.statusDetail, "API request failed while reading the response");
  } finally {
    tailer.stop();
    store.dispose();
    await fsp.rm(tempDir, { recursive: true, force: true });
  }
});

test("oversized unterminated records stay bounded and parsing resumes after their newline", () => {
  const entries = [];
  const updates = [];
  const logger = {
    info(event, meta) { entries.push({ level: "info", event, meta }); },
    warn(event, meta) { entries.push({ level: "warn", event, meta }); },
    error(event, meta) { entries.push({ level: "error", event, meta }); },
  };
  const parser = new TranscriptLineParser((update) => updates.push(update), logger, () => 123);
  const chunk = Buffer.alloc(64 * 1024, 0x78);

  for (let offset = 0; offset < MAX_TRANSCRIPT_LINE_BYTES; offset += chunk.length) {
    parser.push(chunk);
  }
  parser.push(chunk);
  parser.push(chunk);

  assert.equal(parser.remainderBytes, 0);
  assert.equal(parser.remainder.length, 0);
  assert.equal(parser.discardingOversizedRecord, true);
  assert.equal(
    entries.filter((entry) => entry.event === "transcript_line_oversized").length,
    1,
  );

  const validLine = JSON.stringify({
    type: "assistant",
    timestamp: 123,
    message: { role: "assistant", content: "recovered" },
  });
  parser.push(Buffer.from(`discarded tail\n${validLine}\n`));

  assert.equal(parser.discardingOversizedRecord, false);
  assert.equal(updates.length, 1);
  assert.equal(updates[0].lastAssistantText, "recovered");
  assert.equal(
    entries.filter((entry) => entry.event === "transcript_line_oversized").length,
    1,
  );
});

test("stop during start prevents the pending stat from resurrecting watchers or timers", async () => {
  const tempDir = await fsp.mkdtemp(path.join(os.tmpdir(), "nexus-agentd-start-race-"));
  const transcriptPath = path.join(tempDir, "session.jsonl");
  await fsp.writeFile(transcriptPath, "");
  const tailer = new TranscriptTailer(transcriptPath, () => undefined, silentLogger, {
    pollIntervalMs: 5,
  });

  try {
    const starting = tailer.start();
    tailer.stop();
    await starting;
    await new Promise((resolve) => setTimeout(resolve, 20));

    assert.equal(tailer.running, false);
    assert.equal(tailer.watcher, undefined);
    assert.equal(tailer.pollTimer, undefined);
  } finally {
    tailer.stop();
    await fsp.rm(tempDir, { recursive: true, force: true });
  }
});
