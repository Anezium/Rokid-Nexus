---
task: launcher-fix-noconfirm
date: 2026-10-24
status: active
executor: GPT-6.1 Sol
worktree: E:\Tools\Rokid\RokidNexus-fx-noconfirm
branch: fx/noconfirm
scope_globs:
  - "glasses-hub/src/**"
  - "phone-hub/src/main/java/com/anezium/rokidbus/phone/PluginPermissionsActivity.kt"
  - "phone-hub/src/test/**"
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
  - "./gradlew :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug"
  - "./gradlew :phone-hub:testDebugUnitTest :phone-hub:assembleDebug"
max_failures: 2
---

# Remove every confirmation step from the glasses launcher

# Goal
On the glasses' new launcher no action ever goes through a confirmation screen: a tap on an
activity card command (Play, Pause, Next, Previous, any plugin command), on a plugin page row
(for example a to-do item), on the hub's **Open app** (card slot, page row, Applications entry)
runs it at once. Owner's decision, verbatim: "pourquoi avoir une confirmation de play ? les
boutons sont faits pour ça, l'user sait ce qu'il fait — et pour les autres boutons aussi, nsm
les confirmations."

# Non-goals
- The composer: a message is still sent only through the conversation page's own **Send**,
  which needs a fresh tap after the draft is visible. `compose.send` never becomes a row.
- Phone-side validation, the quick-action wire (`/core/policy/quick-actions`, `hubQuick`,
  `MODE_QUICK`/`MODE_CONFIRMED`) and the plugin SDK stay as they are.
- Anything about islands, titles, plugin binding or navigation (other lanes).

# Constraints
- MUST keep the fresh-press rule: only a press that began after the target was drawn acts
  (`fresh(model, event)` in `SessionReducer`).
- MUST keep every existing guard of the old confirm path that is not the dialog itself: the
  revision / provider epoch the wearer saw, no second command while one is pending for that
  owner, offline (last-known) entries inert, link-lost shows NotAvailable.
- A command or page action sends `MODE_QUICK` when `QuickGate.allows(...)` would have allowed it,
  `MODE_CONFIRMED` otherwise (the phone accepts confirmed invocations; the tap is the choice).
- MUST remove what becomes unreachable (the `FramePanel.Confirm`, `FramePanel.ConfirmCommand`,
  `FramePanel.Launch` states, their screens and their focus/step handling) unless still used by
  another path; `FramePanel.Display` (route display page) stays.
- MUST remove the phone's now-meaningless per-plugin quick-action rows from Plugin access
  (`PluginPermissionsActivity.kt` ~:290-300, "Check tasks with one tap", "Play, pause and skip
  with one tap") but MUST NOT change the stored policy, its wire or the phone's validation.
- MUST NOT change behaviour without the launcher bundle beyond removing the dialog (old phones
  previously confirmed every page action; now a tap invokes it confirmed).
- MUST NOT touch files outside scope_globs.

# Acceptance tests
| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Glasses suite | `./gradlew :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug` | 0 failures |
| 2 | Phone suite | `./gradlew :phone-hub:testDebugUnitTest :phone-hub:assembleDebug` | 0 failures |
| 3 | Card command | new/updated test: focus Play, one fresh tap sends exactly one `SendActivityCommand` (mode CONFIRMED without policy, QUICK with) and no panel appears | passes; fails if the confirm panel is restored |
| 4 | Page row | a to-do row tap sends exactly one `SendAction` for the shown revision; a stale press does nothing | passes |
| 5 | Open app | card slot, page row and Applications launch go straight to `SessionState.Launching` on a fresh tap | passes |
| 6 | No dialog strings | `grep -rn "· CONFIRM\"\|Opens the app over the glasses screen" glasses-hub/src/main` | no match (unless still reachable, then justify) |

# Plan sketch
1. Read `SessionReducer.kt` around `focusEnter` (~:1900), `enterPanel` (~:685), the page item
   switch (~:648-680), `commandPanelEnter`, `panelStep`; `TypedPageItems.pluginAction` (~:188),
   `PageItems.kt:109`, `SessionScreens.kt` (~:1110 confirm screen, ~:748/866 hints).
2. A draft of steps 1-2 already exists as a diff:
   `E:\Tools\Rokid\_scratch\merge-20261023\wip-noconfirm.diff` (reducer only; untested; 14
   glasses tests failed with it because they asserted the dialogs). Use or improve it.
3. Rewrite the failing tests to assert the new behaviour (they are listed in the draft's run:
   LauncherInteractiveTest x7, SessionPagesTest x4, SessionReducerTest rule 11,
   RouteNavigationTest real Navigation page, NexusSessionRuntimeTest Applications launch step).
4. Remove the dead panels and screens; remove the phone rows; docs.

# Escalation triggers
- Any test command still failing after 2 focused attempts.
- A guard you cannot keep without the dialog.
- Diff outside scope_globs.

# Autonomy
Free: test names, helper extraction inside the touched files, hint strings on the glasses.
Stop and report: any change to the wire, to phone validation, or to the composer's Send.

# Context the executor cannot re-derive (common to every launcher-fix lane)

- Product: Rokid Nexus, Android/Kotlin. Modules: `:shared`, `:bus-client` (public plugin SDK),
  `:phone-hub`, `:glasses-hub`, plugins under `plugins/`. Read `AGENTS.md` at the repo root and
  obey it. `BUSSPEC.md` is the wire authority, `docs/PLUGIN_SDK.md` the public SDK guide,
  `plans/028a-launcher-contract.md` the launcher contract, `docs/LAUNCHER_COMPLETION_REPORT.md`
  the status report, `CHANGELOG.md` (section `## 1.9.0`, unreleased, with `### SDK 0.24.0`) the
  release notes. Hubs 1.9.0 and SDK 0.24.0 are NOT released yet: both hubs ship together, so a
  launcher-bundle wire change may still be made, as long as peers without the launcher bundle
  (older hubs, `supportsLauncherInteractive()` false) behave exactly as before.
- Your checkout is the git worktree **E:\Tools\Rokid\RokidNexus-fx-noconfirm** on branch **fx/noconfirm**, created from
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
  `cd /e/Tools/Rokid/RokidNexus-fx-noconfirm && ./gradlew :glasses-hub:testDebugUnitTest --console=plain > build/lane.log 2>&1; echo exit=$?; grep -E " FAILED$|^e: |BUILD" build/lane.log | head -40`
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
