---
task: launcher-fix-freeze
date: 2026-10-24
status: active
executor: GPT-6.1 Sol
worktree: E:\Tools\Rokid\RokidNexus-fx-freeze
branch: fx/freeze
scope_globs:
  - "phone-hub/src/**"
  - "bus-client/src/**"
  - "plugins/sample/**"
  - "BUSSPEC.md"
  - "docs/**"
  - "CHANGELOG.md"
  - "plugins/sample/CHANGELOG.md"
forbidden_globs:
  - "local.properties"
  - "**/build.gradle.kts"
  - "settings.gradle.kts"
  - "gradle/**"
test_commands:
  - "./gradlew :phone-hub:testDebugUnitTest :phone-hub:assembleDebug :bus-client:testDebugUnitTest"
  - "./gradlew -PskipCxrGlobal=true :plugin-sample:testDebugUnitTest :plugin-sample:assembleDebug"
max_failures: 2
---

# Keep a sleeping plugin awake while the glasses wait for its page or command

# Goal
On a Samsung phone (One UI, "Freecess" app freezer), a command or page action sent to a plugin
that the hub woke for a page (Sample's to-do list) is answered within the hub's deadline instead
of timing out as "Unconfirmed: the plugin did not answer" and running 20 s later.

# Evidence (device logcat, 2026-10-24 22:12, Galaxy S23, build f651994c)
```
22:12:29.120 FreecessController: UFZ : com.anezium.rokidbus.plugin.sample reason: bindService
22:12:29.121 BaseRestrictionMgr: ... hostingType: bindService is allowed by freecess, caller is: com.anezium.rokidbus.phone
22:12:35.145 FreecessController: FZ : com.anezium.rokidbus.plugin.sample reason: Bg
22:12:35.145 FreecessHandler: freeze com.anezium.rokidbus.plugin.sample
22:12:57.433 FreecessController: UFZ : com.anezium.rokidbus.plugin.sample reason: ServiceANR
```
The wearer opened the to-do page (~22:12:29), confirmed "Eggs" (~22:12:40); the glasses showed
"Unconfirmed: the plugin did not answer"; the list on the phone had Eggs checked after 22:12:57.
`phone-hub/.../AndroidExternalPluginRuntime.kt:47-53` already binds the plugin opened on the HUD
with `BIND_AUTO_CREATE or BIND_IMPORTANT` precisely because Freecess freezes plainly-bound
background processes; the bind used to wake a plugin for page provision / activity commands /
page actions (find it: page broker `PhonePageBroker.kt`, activity router, skill lease runtime
`AndroidSkillLeaseRuntime.kt`, or the SDK service side) evidently does not keep it unfrozen.
The phone hub runs a foreground service, so `BIND_IMPORTANT` (or the same importance
propagation) on that bind, held while a page from that plugin is visible or a command/action
from the glasses is pending (until its result or deadline), is the expected fix.

# Constraints
- MUST hold the elevated binding only while needed (visible page lease / pending command or
  action until result or deadline), then drop back to today's behaviour; never keep plugins
  permanently bound or important.
- MUST NOT change plugin grants, capabilities, the wire, deadlines or the glasses.
- MUST NOT add a foreground service to plugins unless the binding route is proven impossible
  (then report first).
- MUST NOT touch files outside scope_globs.

# Acceptance tests
| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Bind flags | phone test: waking a plugin for a page or a command binds with `BIND_IMPORTANT` (Robolectric shadow of bindService flags or an injected binder seam) | passes; fails with the old flags |
| 2 | Release | phone test: after the result or the deadline, the elevated bind is released | passes |
| 3 | Suites | test_commands | 0 failures |
| 4 | Device | reported to orchestrator: steps to reproduce on the S23 | in report |

# Escalation triggers
- The waking path is inside the plugin SDK service lifecycle and cannot carry the flag → report.
- Suites failing after 2 attempts; diff outside scope.

# Autonomy
Free: where the hold lives, its naming. Stop and report before any plugin-side foreground
service or any change to deadlines.

# Context the executor cannot re-derive (common to every launcher-fix lane)

- Product: Rokid Nexus, Android/Kotlin. Modules: `:shared`, `:bus-client` (public plugin SDK),
  `:phone-hub`, `:glasses-hub`, plugins under `plugins/`. Read `AGENTS.md` at the repo root and
  obey it. `BUSSPEC.md` is the wire authority, `docs/PLUGIN_SDK.md` the public SDK guide,
  `plans/028a-launcher-contract.md` the launcher contract, `docs/LAUNCHER_COMPLETION_REPORT.md`
  the status report, `CHANGELOG.md` (section `## 1.9.0`, unreleased, with `### SDK 0.24.0`) the
  release notes. Hubs 1.9.0 and SDK 0.24.0 are NOT released yet: both hubs ship together, so a
  launcher-bundle wire change may still be made, as long as peers without the launcher bundle
  (older hubs, `supportsLauncherInteractive()` false) behave exactly as before.
- Your checkout is the git worktree **E:\Tools\Rokid\RokidNexus-fx-freeze** on branch **fx/freeze**, created from
  `dev/launcher-rework` at `f651994c`. Work ONLY there (absolute paths; `cd` into it for Gradle).
  Other worktrees (`E:\Tools\Rokid\RokidNexus-fx-*`, `E:\Tools\Rokid\RokidNexus-launcher-rework`,
  `E:\Tools\Rokid\RokidNexus`) belong to other sessions: never read-modify-write them, never
  switch their branches.
- Building: `ANDROID_HOME` is set in the environment, so a worktree builds without
  `local.properties`; do NOT create, copy or edit `local.properties`, a Gradle home or an SDK.
  The hubs resolve `com.example.cxrglobal:lib` from the sibling `E:\Tools\Rokid\CxrGlobal`
  (tag v0.3.0, read-only for you): build hubs WITHOUT `-PskipCxrGlobal`, plugins WITH
  `-PskipCxrGlobal=true`, in separate Gradle invocations. If a build fails for an environment
  reason, stop and report it; never work around it.
- Redirect Gradle output to a log file and read only its tail / failures (huge stdout stalls the
  host). Example (Git Bash):
  `cd /e/Tools/Rokid/RokidNexus-fx-freeze && ./gradlew :glasses-hub:testDebugUnitTest --console=plain > build/lane.log 2>&1; echo exit=$?; grep -E " FAILED$|^e: |BUILD" build/lane.log | head -40`
- Commits: small, imperative English messages describing what and why, author is the repo's
  configured user (Anezium). NEVER any AI attribution or `Co-Authored-By` trailer. Commit at each
  coherent step (other sessions share the machine). Do NOT push, do NOT merge into other
  branches, do NOT tag, do NOT use adb or touch any device (the orchestrator does device checks).
- Code, comments, docs, commit messages in English. Match the surrounding code: naming, comment
  density (comments explain constraints the code cannot show, never what the next line does),
  idioms. No unrelated refactors or restyling.
- Every behaviour change gets a regression test that fails without it; say in your report which
  tests you observed failing with the change reverted (mutation check), with the command used.
- Update the docs your change makes stale (BUSSPEC, PLUGIN_SDK, plan 028a, completion report,
  CHANGELOG 1.9.0 and plugin CHANGELOGs) in the same lane.
- Final report (your reply): verdict DONE / BLOCKED, commits (sha + subject), files touched,
  test commands run with the real tail of their output (counts, failures), mutation checks,
  open questions, and anything the orchestrator must verify on the device.
