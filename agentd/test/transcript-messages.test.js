const test = require("node:test");
const assert = require("node:assert/strict");
const {
  MAX_TEXT_CHARS,
  condense,
  extractMessage,
  toolSummary,
} = require("../dist/transcript-messages.js");

test("conversation condensation preserves useful line breaks and normalizes horizontal space", () => {
  assert.equal(
    condense("  first\t\tline  \r\n second   value\rthird\n \n\n\n fourth  "),
    "first line\nsecond value\nthird\n\nfourth",
  );

  const message = extractMessage({
    type: "assistant",
    timestamp: "2026-08-08T10:00:00.000Z",
    message: {
      role: "assistant",
      content: [
        { type: "text", text: "Paragraph one.\r\nStill one." },
        { type: "text", text: "Paragraph two." },
      ],
    },
  });
  assert.equal(message.text, "Paragraph one.\nStill one.\nParagraph two.");
});

test("conversation condensation caps text at 3500 characters", () => {
  assert.equal(MAX_TEXT_CHARS, 3500);
  assert.equal(condense("x".repeat(MAX_TEXT_CHARS + 200)).length, MAX_TEXT_CHARS);
});

test("tool summaries stay single-line and within 90 characters", () => {
  assert.equal(
    toolSummary("shell", { command: "git   status\r\n&&\tgit diff" }),
    "shell · git status && git diff",
  );
  const long = toolSummary("shell", { command: `run ${"argument ".repeat(30)}` });
  assert.equal(long.includes("\n"), false);
  assert.equal(long.length, 90);
});

test("Edit and MultiEdit summaries carry a line-change count", () => {
  assert.equal(
    toolSummary("Edit", {
      file_path: "/repo/src/App.tsx",
      old_string: "const a = 1;\nconst b = 2;",
      new_string: "const a = 1;\nconst b = 2;\nconst c = 3;\nconst d = 4;",
    }),
    "Edit · …/src/App.tsx (+2 -0)",
  );

  assert.equal(
    toolSummary("Edit", {
      file_path: "/repo/src/App.tsx",
      old_string: "one\ntwo\nthree",
      new_string: "one\nTWO\nthree",
    }),
    "Edit · …/src/App.tsx (+1 -1)",
  );

  // Identical old/new carries no stat at all: nothing to draw a wearer's eye to.
  assert.equal(
    toolSummary("Edit", {
      file_path: "/repo/src/App.tsx",
      old_string: "same",
      new_string: "same",
    }),
    "Edit · …/src/App.tsx",
  );

  assert.equal(
    toolSummary("MultiEdit", {
      file_path: "/repo/src/App.tsx",
      edits: [
        { old_string: "a", new_string: "a\nb" },
        { old_string: "x\ny", new_string: "x" },
      ],
    }),
    "MultiEdit · …/src/App.tsx (+1 -1)",
  );
});

test("Write summaries carry a line count instead of a diff", () => {
  assert.equal(
    toolSummary("Write", { file_path: "/repo/src/new.ts", content: "a\nb\nc" }),
    "Write · …/src/new.ts (3 lines)",
  );
  assert.equal(
    toolSummary("Write", { file_path: "/repo/src/one.ts", content: "only" }),
    "Write · …/src/one.ts (1 line)",
  );
});
