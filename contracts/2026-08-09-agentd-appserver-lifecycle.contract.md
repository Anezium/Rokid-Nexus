---
task: agentd-appserver-lifecycle
date: 2026-08-09
status: active
scope_globs:
  - "agentd/src/**"
  - "agentd/test/**"
  - "agentd/tests/**"
forbidden_globs:
  - "agentd/package.json"
  - "agentd/package-lock.json"
  - "plugins/**"
  - "bus-client/**"
test_commands:
  - "cd agentd && npm run build && npm test"
max_failures: 2
---

# Goal

Spawning the Codex app-server from nexus-agentd no longer leaks orphaned
LISTEN sockets on Windows. Today `codexSpawnSpec` (agentd/src/codex/monitor.ts:78-91)
uses `shell: platform === "win32"`, so the daemon tracks the cmd.exe wrapper's
pid, not codex's, and the listen-socket handle is inherited in a way that has
already permanently condemned ports 8390, 8391, 8395, 8397 and 8402 on the
owner's machine (socket stays LISTEN under a dead pid; the holder is not
listable or killable). After this change: the daemon spawns the real codex
process directly (no cmd.exe wrapper), knows its true pid, and killing the
daemon or the app-server never strands a port.

# Non-goals

- Do NOT change the attach-or-spawn logic, the JSON-RPC protocol code, ports,
  or config semantics. Only the process-spawning/termination mechanics.
- Do NOT make the daemon kill the app-server on normal daemon shutdown —
  leaving it alive for re-attach by the next daemon is a FEATURE (doctrine
  established after taskkill /T killed the owner's live agent sessions).

# Constraints

- MUST remove the `shell: true` path on Windows. `codex` on Windows is an
  npm-style `.cmd` shim, which cannot be spawned with `shell: false` directly
  (EINVAL / not executable). Acceptable resolutions, in preference order:
  1. Resolve the shim to the real executable (read the shim / locate
     `codex.exe` next to it or via the npm prefix) and spawn that directly.
  2. Spawn `cmd.exe` explicitly with `["/d", "/s", "/c", "codex", ...]` BUT
     then obtain and track the real codex pid (e.g. via `wmic`/PowerShell
     child-process lookup) — only if (1) is genuinely infeasible.
  Do not add the `cross-spawn` dependency (package.json is frozen).
- MUST keep `detached`/stdio choices such that the app-server survives the
  daemon's death (it already does today; preserve that).
- MUST audit `codexTerminateSpec` (monitor.ts:93-109, currently
  `taskkill /pid <pid> /t /f`) and every call site: with the wrapper gone the
  pid is the real codex pid; `/t` must not take down codex-spawned agent
  session subprocesses that should survive — decide per call site and justify
  in the report.
- MUST be covered by unit tests in the existing node:test suite style: spec
  construction per-platform, shim resolution (mock the filesystem), pid
  tracking. The suite currently passes; keep every existing test green.
- MUST NOT touch anything outside `agentd/`.
- MUST NOT spawn real codex processes in tests (mock/spec-level only) — real
  codex instances belong to the owner's live environment.
- Commit style: repo convention, English, no AI attribution. If the sandbox
  cannot commit in this worktree, leave the tree dirty and say so.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Build + full suite | `cd agentd && npm run build && npm test` | green |
| 2 | No shell wrapper | grep | no `shell: true`/`shell: platform === "win32"` in spawn spec for the app-server |
| 3 | New tests | review | shim resolution + spec covered per-platform |

# Context the executor cannot re-derive

- The zombie mechanism as diagnosed live: after killing the process tree, the
  codex listen port stays LISTEN owned by a dead pid, permanently; netstat
  shows it, no process holds it, reboot is the only cure. Root cause believed
  to be handle inheritance through the cmd.exe wrapper (`spawn shell:true`).
- Current live config uses `codex.port = 8403` — do not hardcode any port.
- The daemon runs under Windows Task Scheduler (`NexusAgentd` task); it can be
  killed with `taskkill /PID <pid>` (NO /T) by design so the app-server child
  survives and the next daemon re-attaches (attach logic exists and works).
- An unrelated OpenAI desktop Codex app sometimes runs its own app-server on
  nearby ports — never assume a listening port is ours by port number alone
  (the attach path already validates; don't weaken it).
- node:test based suite, ~72 tests, runs fully offline. The phone-link tests
  learned to pass `discoveryPort` ≠ 8793 so the owner's real phone doesn't
  answer mid-test — follow that pattern for anything network-adjacent.

# Escalation triggers (mechanical)

- `npm run build && npm test` fails after 2 attempts.
- Diff outside scope_globs / touching forbidden_globs.
- Resolution (1) and (2) both infeasible — stop and report findings instead
  of inventing a third mechanism.

# Autonomy

- May decide: internal naming, helper module placement, test structure.
- Must stop and report: any protocol/config change, any new dependency, any
  behavior change to attach/shutdown semantics beyond what is specified.
