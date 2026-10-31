---
task: launcher-fix-hide
date: 2026-10-24
status: active
executor: Claude Opus 5.5
worktree: E:\Tools\Rokid\RokidNexus-fx-hide
branch: fx/hide
scope_globs:
  - "shared/src/**"
  - "phone-hub/src/**"
  - "glasses-hub/src/**"
  - "BUSSPEC.md"
  - "docs/**"
  - "plans/028a-launcher-contract.md"
  - "CHANGELOG.md"
forbidden_globs:
  - "local.properties"
  - "**/build.gradle.kts"
  - "settings.gradle.kts"
  - "gradle/**"
test_commands:
  - "./gradlew :shared:test"
  - "./gradlew :phone-hub:testDebugUnitTest :phone-hub:assembleDebug :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug"
max_failures: 2
---

# Per-plugin switch: keep a plugin's island and pins off the glasses home

# Goal
In Rokid Nexus on the phone, Plugin access gets, per plugin that can show on the glasses, one
switch (consumer-grade wording, e.g. "Float over the glasses" / "Island and pins on the glasses
home") that, when off, keeps that plugin's activity island and its pinned surfaces off the
glasses' home and off any app, while its card stays in the launcher stack, its pages still open
and its notifications are unaffected. Default on (nothing changes until the user turns it off).
Owner's words: "il faut aussi une option pour désactiver certaines applications d'avoir un
badge/pin".

# Context
- Plugin access screen: `phone-hub/.../PluginPermissionsActivity.kt`, `activityRows()` ~:271
  ("Show its activity on the glasses" = activity enabled at all, "Pin its activity" = first in
  the stack). Another lane removes the quick-action rows just below (~:290-300) in the same
  function: keep your addition self-contained to ease the merge.
- Per-owner glasses presentation preferences already travel phone → glasses as the ambient
  preferences (`shared/.../AmbientPreferencesContract.kt`, `/core/ambient/sizes`,
  `/core/ambient/policy`; phone `PhoneAmbientPreferencesStore`; glasses `AmbientPreferences.kt`,
  `ActivityPresentationPolicy.kt`, `ActivityController.kt`, `ActivityOverlayRenderer.kt`).
- Pins: phone `PhonePinRouter.kt` (canonical owner-scoped state), glasses `PinOverlayRenderer.kt`.
- Another lane (island) changes the island's views inside `ActivityOverlayRenderer` and
  `PinOverlayRenderer.PinPanelView`: do your filtering upstream (policy / controller / router),
  not inside those views.

# Constraints
- The preference is per plugin, persisted on the phone with the other per-grant preferences,
  sent to the glasses with the ambient preferences (or an equally owner-scoped, revisioned,
  hub-to-hub message), and re-sent on link-up. Hub-to-hub only: never a plugin capability.
- Off MUST hide: the activity island (all forms, home and over apps, including alerts/flare of
  that activity) and that plugin's pin surfaces. It MUST NOT end the activity, remove its card
  from the launcher stack, change its pages, its notices/notifications, or the
  "Show its activity" / "Pin its activity" semantics.
- Toggling back on shows the current island/pin again without the plugin re-sending.
- Revoke / uninstall / re-sign of the plugin clears its preference like the others.
- With glasses that do not know the field, nothing breaks (they simply keep showing).
- MUST NOT touch files outside scope_globs.

# Acceptance tests
| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Wire | shared test: the preference round-trips, unknown/absent = shown | passes |
| 2 | Phone | phone test: switch off persists, is sent, re-sent on link-up, cleared on revoke | passes |
| 3 | Glasses island | glasses test: an owner marked hidden gets no island (home and over an app) while its root card stays | passes |
| 4 | Pins | a hidden owner's pin is not drawn; back on → drawn | passes |
| 5 | Suites | test_commands | 0 failures |

# Escalation triggers
- The existing ambient wire cannot carry it without breaking older glasses → report the options.
- Suites failing after 2 attempts; diff outside scope.

# Autonomy
Free: wording, field name, phone vs glasses side for the pin filter (justify). Stop and report
if the change would alter "Show its activity" or "Pin its activity".

# Context the executor cannot re-derive (common to every launcher-fix lane)

- Product: Rokid Nexus, Android/Kotlin. Modules: `:shared`, `:bus-client` (public plugin SDK),
  `:phone-hub`, `:glasses-hub`, plugins under `plugins/`. Read `AGENTS.md` at the repo root and
  obey it. `BUSSPEC.md` is the wire authority, `docs/PLUGIN_SDK.md` the public SDK guide,
  `plans/028a-launcher-contract.md` the launcher contract, `docs/LAUNCHER_COMPLETION_REPORT.md`
  the status report, `CHANGELOG.md` (section `## 1.9.0`, unreleased, with `### SDK 0.24.0`) the
  release notes. Hubs 1.9.0 and SDK 0.24.0 are NOT released yet: both hubs ship together, so a
  launcher-bundle wire change may still be made, as long as peers without the launcher bundle
  (older hubs, `supportsLauncherInteractive()` false) behave exactly as before.
- Your checkout is the git worktree **E:\Tools\Rokid\RokidNexus-fx-hide** on branch **fx/hide**, created from
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
  `cd /e/Tools/Rokid/RokidNexus-fx-hide && ./gradlew :glasses-hub:testDebugUnitTest --console=plain > build/lane.log 2>&1; echo exit=$?; grep -E " FAILED$|^e: |BUILD" build/lane.log | head -40`
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
