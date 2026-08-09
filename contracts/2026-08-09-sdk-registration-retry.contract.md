---
task: sdk-registration-retry
date: 2026-08-09
status: active
scope_globs:
  - "bus-client/src/**"
forbidden_globs:
  - "phone-hub/**"
  - "glasses-hub/**"
  - "plugins/**"
  - "bus-client/build.gradle.kts"
test_commands:
  - "gradlew.bat :bus-client:testDebugUnitTest"
  - "gradlew.bat :bus-client:lintDebug"
max_failures: 2
---

# Goal

A plugin whose bus client connects BEFORE the hub has started, or before the
wearer has approved the plugin, recovers on its own. Today the registration is
rejected once and never retried: the client sits dead, every `showCard` is a
silent no-op, and the only cure observed repeatedly on hardware is force-stop
+ relaunch of the plugin after approval. After this change the SDK
(bus-client) retries registration with backoff on transient outcomes, and the
plugin's callbacks fire with APPROVED when the hub finally accepts, so a
surface plugin can (re)register its surface and render.

# Non-goals

- No hub-side changes (phone-hub / glasses-hub are out of scope entirely; the
  fix must work against the RELEASED hubs the devices run today).
- No new public API that plugins MUST adopt — existing plugins compiled
  against the current SDK must benefit without a code change wherever
  possible, and must at minimum behave exactly as before.

# Constraints

- MUST be purely additive to the public API surface: no signature changes, no
  removed/renamed members, no changed callback ordering for the already-works
  flow (connect after approval → APPROVED immediately). External developers
  build against this SDK.
- Retry policy: only retry outcomes that are plausibly transient — hub not
  reachable / binding failed, and registration results that mean "not yet"
  (e.g. pending approval, hub not started). A hard rejection that means
  "never" (e.g. explicitly revoked/denied), if distinguishable in
  `PluginRegistrationResult`, MUST NOT be retried forever: cap retries or back
  off to a slow cadence — study the result codes and justify the mapping in
  the report.
- Backoff: start ~1 s, double to a ceiling of ~30-60 s, forever at the
  ceiling (a wearer may approve minutes later). Full jitter optional.
- MUST stop retrying the moment the client is closed/released; no leaked
  handlers, no timers outliving the client (the plugin services already have
  strict lifecycle expectations).
- Threading: match the client's existing dispatch discipline (look at how
  onRegistrationState/onLinkState are delivered — [NexusPluginClient.kt:421]
  onward) — callbacks must keep arriving on the same thread/looper they do
  today.
- MUST log retries at a quiet level with attempt count, never per-tick spam.
- MUST be unit-tested against a fake transport: rejected-then-approved
  delivers APPROVED callback exactly once; closed-during-backoff cancels;
  approved-immediately unchanged; hard-denied does not hot-loop.
- MUST NOT alter capability/grant handling semantics ([NexusPluginClient.kt:427-443]
  — the approval/grants race handling there is deliberate and
  hardware-measured; retries must route through the same code path, not
  around it).
- Commit style: repo convention, English, no AI attribution. If the sandbox
  cannot run gradle (known Android-SDK AccessDeniedException) or commit,
  write the code + tests anyway, leave the tree dirty, and say so in the
  report — the supervisor builds and commits.

# Acceptance tests

| # | Check | Command | Expected |
|---|-------|--------|----------|
| 1 | Unit tests incl. new ones | `gradlew.bat :bus-client:testDebugUnitTest` | green |
| 2 | Lint | `gradlew.bat :bus-client:lintDebug` | no new errors (local.properties PropertyEscape is known noise) |
| 3 | API additive | review `git diff` | no public signature changed/removed |

# Context the executor cannot re-derive

- Observed failure, twice on hardware: plugin's client connected before
  approval / before the phone hub service started → "registration rejected"
  with no retry → `showCard` silently no-ops → wearer sees nothing; restart
  of the plugin after approval fixes it. This is the exact scenario to kill.
- The hub answers `registerPlugin` APPROVED synchronously but sends the
  granted-capability list ~16 ms later on another wire; the client already
  compensates ([NexusPluginClient.kt:427-443]). Preserve that logic verbatim.
- `:bus-client` is part of the ROOT gradle build; build from repo root.
- lintDebug is mandatory in this repo; ignore only the environmental
  local.properties PropertyEscape error.
- Repo comment style: prose explaining constraints/why, not what.

# Escalation triggers (mechanical)

- Any test_command fails after 2 attempts (sandbox gradle failure exempt —
  report it instead).
- Diff outside scope_globs / touching forbidden_globs.
- The transport turns out not to expose enough signal to distinguish
  transient from permanent rejection — stop and report the actual result-code
  inventory rather than guessing.

# Autonomy

- May decide: internal naming, timer mechanism, exact backoff constants
  within the stated envelope, test structure.
- Must stop and report: any public API change beyond additive, any hub-side
  need discovered, any change to capability/grant semantics.
