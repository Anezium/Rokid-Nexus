const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const {
  HOOK_EVENTS,
  installHooks,
  uninstallHooks,
} = require("../dist/settings.js");

function forwarderCommands(settings) {
  const commands = [];
  for (const groups of Object.values(settings.hooks || {})) {
    if (!Array.isArray(groups)) continue;
    for (const group of groups) {
      if (group && typeof group.command === "string") commands.push(group.command);
      if (group && Array.isArray(group.hooks)) {
        for (const hook of group.hooks) {
          if (hook && typeof hook.command === "string") commands.push(hook.command);
        }
      }
    }
  }
  return commands.filter((command) => command.includes("hook-forward.js"));
}

test("install creates a fresh settings file with one forwarder per event", async () => {
  const tempDir = await fsp.mkdtemp(path.join(os.tmpdir(), "nexus-agentd-settings-"));
  const settingsPath = path.join(tempDir, ".claude", "settings.json");
  try {
    const result = installHooks("E:\\Tools\\Rokid\\wt-agentd\\agentd", { settingsPath });
    assert.equal(result.changed, true);
    assert.equal(result.backupPath, undefined);
    const settings = JSON.parse(await fsp.readFile(settingsPath, "utf8"));
    assert.deepEqual(Object.keys(settings.hooks).sort(), [...HOOK_EVENTS].sort());
    assert.ok(HOOK_EVENTS.includes("PreToolUse"));
    // The wearer is asked on PermissionRequest, which fires only when a
    // decision is needed. Holding on PreToolUse instead asks them about every
    // read and grep a session makes, which is what this install must not do.
    assert.ok(HOOK_EVENTS.includes("PermissionRequest"));
    assert.equal(forwarderCommands(settings).length, HOOK_EVENTS.length);
  } finally {
    await fsp.rm(tempDir, { recursive: true, force: true });
  }
});

test("install preserves unrelated hooks, backs up, and is idempotent; uninstall is surgical", async () => {
  const tempDir = await fsp.mkdtemp(path.join(os.tmpdir(), "nexus-agentd-settings-"));
  const settingsPath = path.join(tempDir, "settings.json");
  const original = {
    theme: "dark",
    hooks: {
      Stop: [
        {
          matcher: "keep-me",
          hooks: [{ type: "command", command: "node other-hook.js" }],
        },
      ],
    },
  };
  await fsp.writeFile(settingsPath, `${JSON.stringify(original, null, 2)}\n`);
  const fixedNow = () => new Date("2026-07-24T12:34:56");

  try {
    const first = installHooks("E:\\agentd", { settingsPath, now: fixedNow });
    assert.equal(first.changed, true);
    assert.ok(first.backupPath);
    assert.deepEqual(JSON.parse(await fsp.readFile(first.backupPath, "utf8")), original);
    let settings = JSON.parse(await fsp.readFile(settingsPath, "utf8"));
    assert.equal(settings.theme, "dark");
    assert.equal(settings.hooks.Stop[0].hooks[0].command, "node other-hook.js");
    assert.equal(forwarderCommands(settings).length, HOOK_EVENTS.length);

    const backupsBefore = fs.readdirSync(tempDir).filter((name) => name.includes("agentd-backup"));
    const second = installHooks("E:\\agentd", { settingsPath, now: fixedNow });
    assert.equal(second.changed, false);
    const backupsAfter = fs.readdirSync(tempDir).filter((name) => name.includes("agentd-backup"));
    assert.deepEqual(backupsAfter, backupsBefore);

    const removed = uninstallHooks({ settingsPath, now: fixedNow });
    assert.equal(removed.changed, true);
    assert.ok(removed.backupPath);
    settings = JSON.parse(await fsp.readFile(settingsPath, "utf8"));
    assert.equal(settings.theme, "dark");
    assert.equal(settings.hooks.Stop[0].hooks[0].command, "node other-hook.js");
    assert.equal(forwarderCommands(settings).length, 0);
  } finally {
    await fsp.rm(tempDir, { recursive: true, force: true });
  }
});

test("malformed settings abort without writes or backups", async () => {
  const tempDir = await fsp.mkdtemp(path.join(os.tmpdir(), "nexus-agentd-settings-"));
  const settingsPath = path.join(tempDir, "settings.json");
  const malformed = '{"hooks":';
  await fsp.writeFile(settingsPath, malformed);
  try {
    assert.throws(
      () => installHooks("E:\\agentd", { settingsPath }),
      /Cannot parse Claude settings/,
    );
    assert.equal(await fsp.readFile(settingsPath, "utf8"), malformed);
    assert.deepEqual(await fsp.readdir(tempDir), ["settings.json"]);
  } finally {
    await fsp.rm(tempDir, { recursive: true, force: true });
  }
});
