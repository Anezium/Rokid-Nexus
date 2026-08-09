---
task: agents-detail-reopen
date: 2026-08-09
status: active
scope_globs:
  - "plugins/agents/src/**"
  - "plugins/agents/CHANGELOG.md"
forbidden_globs:
  - "agentd/**"
  - "bus-client/**"
  - "glasses-hub/**"
  - "phone-hub/**"
  - "plugins/agents/build.gradle.kts"
  - "**/AndroidManifest.xml"
test_commands:
  - "gradlew.bat -p plugins/agents testDebugUnitTest"
max_failures: 2
---

# Goal

A conversation open on the glasses survives a computer-link flap. Today the
Agents plugin sends `detail_open` once when the wearer opens a chat
([AgentsMonitorService.kt:78-79] — sent to both `agentdClient` and
`linkServer`); if the daemon link drops and reconnects while the chat is
open, nothing re-emits `detail_open`, so the daemon no longer streams that
conversation and the wearer stares at a stale view (observed as a chat stuck
on "Reading…" after a flap). After this change, the plugin remembers which
detail is open and re-emits `detail_open` on every (re)connection of either
transport while it is open, and the view refreshes.

# Non-goals

- No daemon changes (the daemon already answers any `detail_open` with a
  fresh `detail` frame; that is the refresh mechanism — reuse it).
- No protocol changes: `detail_open` / `detail_close` frames as they are
  ([AgentdProtocol.kt:262] area).
- No UI redesign; no changes to how conversations render.

# Constraints

- MUST track the currently-open detail (sessionId + provider if the close
  path needs it — mirror whatever `openDetail`/`closeDetail` already key on)
  in ONE place in the service/store, cleared on `closeDetail` and on plugin
  surface close. Check how `AgentsPluginService` signals leaving the
  conversation ([AgentsPluginService.kt:313] is the open path; find the
  symmetric close) — the re-emit must never fire for a chat the wearer has
  left.
- MUST re-emit on reconnection of EACH transport independently: the agentd
  WS client (`AgentdClient`) and the inverted-TCP link (`AgentdLinkServer`)
  both carry `openDetail` today; hook their respective
  connected/authenticated moments (after `hello` handshake completes, not on
  raw socket connect — an unauthenticated peer must never receive frames it
  should not).
- MUST be idempotent and cheap: at most one re-emit per (re)connection, no
  periodic resend loop.
- MUST NOT introduce cross-thread mutation bugs: match the service's existing
  threading (main-handler or IO dispatch — read how openDetail is dispatched
  today and stay consistent; the codebase has a hard-learned rule about
  socket writes never happening on the main thread).
- Unit tests: pure-logic part (the "what should be re-sent on reconnect"
  decision) extracted testably, plus tests for open→flap→re-emit,
  open→close→flap→no re-emit, never-opened→flap→no re-emit.
- MUST add a CHANGELOG entry (Unreleased section, file's own convention).
- Commit style: repo convention, English, no AI attribution. The sandbox
  likely cannot run gradle (known Android-SDK AccessDeniedException) — write
  code + tests anyway, leave the tree dirty, report it; the supervisor
  builds. plugins/agents is a STANDALONE gradle build: `gradlew.bat -p
  plugins/agents ...` from repo root, never `:plugins:agents:` — and it needs
  its own `local.properties` inside `plugins/agents/` (copy the repo root's
  one if missing).

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Unit tests incl. new ones | `gradlew.bat -p plugins/agents testDebugUnitTest` | green |
| 2 | Re-emit logic covered | review tests | 3 scenarios above present |
| 3 | No frames before auth | review | re-emit hooked after hello/auth, not socket-connect |

# Context the executor cannot re-derive

- The gap was logged as known during the 2026-08-08 reader work: "le plugin
  ne re-émet pas detail_open après reconnexion du lien (chat ouvert pendant
  un flap reste « Reading… »)".
- Link flap history: the phone cuts at soTimeout=90 s without APPLICATION
  traffic; the daemon now sends an application-level ping every 30 s, so
  flaps are rarer but still happen (Wi-Fi↔5G transitions, daemon restarts —
  daemon restart is routine and MUST result in the open chat repopulating).
- `NetworkOnMainThreadException` scar: every socket write in this plugin goes
  through `scope.launch(Dispatchers.IO)` or equivalent — a write from a
  callback on the main thread fails SILENTLY under runCatching. Keep the
  re-emit on the same write path the original openDetail uses.
- The daemon stamps `provider` on `detail`/`detail_append` frames; the phone
  keys conversations by (provider, sessionId). A Codex chat and a Claude chat
  are different keys — do not collapse them.

# Escalation triggers (mechanical)

- Tests fail after 2 attempts (sandbox gradle failure exempt — report).
- Diff outside scope_globs / forbidden_globs.
- The transports turn out to lack a usable authenticated-connected callback —
  stop and report what exists instead of adding protocol frames.

# Autonomy

- May decide: naming, where the open-detail memory lives (service vs store),
  test structure.
- Must stop and report: anything needing daemon or protocol changes, any
  behavior change beyond the re-emit.
