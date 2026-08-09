---
task: agentd-hardening
date: 2026-08-09
status: active
scope_globs:
  - "agentd/src/**"
  - "agentd/test/**"
forbidden_globs:
  - "agentd/package.json"
  - "agentd/package-lock.json"
  - "agentd/src/codex/monitor.ts"
  - "agentd/src/codex/process.ts"
  - "plugins/**"
  - "bus-client/**"
test_commands:
  - "cd agentd && npm run build && npm test"
max_failures: 2
---

# Goal

Four small, independent robustness defects in nexus-agentd are fixed, each
with a regression test. They come from an adversarial audit; each has a
precise failure story. Nothing else about the daemon changes.

1. **Discovery reply can crash or divert the daemon** (phone-link.ts ~94,
   ~213, ~391): a UDP discovery response with a fractional/absurd numeric
   port passes parsing, then `net.connect` throws a synchronous
   `ERR_SOCKET_BAD_PORT` outside any guard and the process dies. A
   syntactically valid black-hole address occupies the single dial slot until
   the OS TCP timeout because the 15 s deadline only starts after the
   connection is established. Fix: validate the port as an integer in
   [1, 65535] at parse time, wrap connection construction in the existing
   failure path, and start the connect deadline before TCP connect so a
   black-hole candidate is abandoned and the loop moves on.
2. **Stopped transcript tailer resurrects a timer** (transcript.ts ~205,
   ~238, ~338): `start()` marks running then awaits a `stat`; a `stop()`
   during that await finds no interval to clear, then `start()` resumes and
   installs a timer nobody can reach. Fix: generation/cancellation token
   checked after every await before installing watchers or timers.
3. **Unbounded quadratic remainder in the JSONL tailer** (transcript.ts
   ~135, ~273): a multi-megabyte line, or a writer that never emits a
   newline, grows the carry-over string and every 64 KiB chunk recopies it.
   Fix: cap the carried record at a sane maximum (e.g. 8 MiB) with a
   discard-until-newline state that logs once; a discarded record must not
   corrupt subsequent parsing.
4. **TCP chunk decoding tears multibyte UTF-8** (phone-link.ts ~45, ~476):
   each chunk is decoded independently with `chunk.toString("utf8")`, so a
   codepoint split across TCP chunks becomes U+FFFD — a non-ASCII path or
   prompt is silently altered. Fix: decode with `string_decoder`'s
   `StringDecoder` (or accumulate bytes to newline before decoding), and
   enforce the existing line-size cap on encoded BYTES, not UTF-16 chars.

# Non-goals

- Do NOT touch `agentd/src/codex/monitor.ts` or `process.ts` (a parallel
  branch owns them) — if a fix seems to need them, stop and report.
- No protocol changes, no new frame types, no auth changes, no new
  dependencies (`string_decoder` is a Node builtin and is allowed).
- Do not fix any other audit finding, however tempting.

# Constraints

- MUST keep every existing test green; the suite is node:test based
  (~69 tests) and runs offline.
- MUST add at least one regression test per fix: fractional-port reply
  (daemon survives, candidate skipped), black-hole candidate (deadline fires
  pre-connect, next candidate tried — fake timers or injected connector are
  fine), stop-during-start tailer race (no timer survives), oversized/never-
  terminated line (bounded memory, later lines parse), split-UTF-8 frame
  (path with e.g. "é"/"漢" split mid-codepoint arrives intact).
- Phone-link tests MUST pass a `discoveryPort` different from 8793 so the
  owner's real phone does not answer the test broadcast (existing harness
  convention — copy it).
- MUST NOT alter public log event names or the wire protocol; new log
  events are fine at debug/info level.
- Commit style: repo convention, English, no AI attribution. The sandbox
  cannot commit here (gitdir read-only) — leave the tree dirty and say so.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Build + suite | `cd agentd && npm run build && npm test` | green, new tests included |
| 2 | Scope | `git status --short` | only agentd/src + agentd/test files dirty, monitor.ts/process.ts untouched |

# Context the executor cannot re-derive

- The audit stories above were verified against this exact tree; line
  numbers are approximate anchors, read the surrounding code.
- The daemon runs under Windows Task Scheduler on the owner's PC; a process
  crash kills the phone link, hooks endpoint, and Codex monitoring at once —
  that is why the fractional-port crash is the top item.
- The phone (SM-S916B) answers discovery broadcasts on the real network at
  any time of day; hence the discoveryPort rule.
- Node is 20+; `string_decoder` builtin is available.

# Escalation triggers (mechanical)

- Tests fail after 2 attempts.
- Diff touches forbidden_globs.
- A fix genuinely requires monitor.ts/process.ts or a protocol change.

# Autonomy

- May decide: internal naming, exact cap values within the stated order of
  magnitude, test structure.
- Must stop and report: any behavior change beyond the four fixes.
