---
task: launcher-fix-nav-misc
date: 2026-10-24
status: active
executor: Claude Opus 5.5
worktree: E:\Tools\Rokid\RokidNexus-fx-nav-misc
branch: fx/nav-misc
scope_globs:
  - "glasses-hub/src/**"
  - "phone-hub/src/**"
  - "plugins/media/**"
  - "plugins/relay/**"
  - "plugins/nav/**"
  - "shared/src/**"
  - "BUSSPEC.md"
  - "docs/**"
  - "plans/028a-launcher-contract.md"
  - "CHANGELOG.md"
  - "plugins/*/CHANGELOG.md"
forbidden_globs:
  - "local.properties"
  - "**/build.gradle.kts"
  - "settings.gradle.kts"
  - "gradle/**"
test_commands:
  - "./gradlew :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug :phone-hub:testDebugUnitTest :phone-hub:assembleDebug"
  - "./gradlew -PskipCxrGlobal=true :plugin-media:testDebugUnitTest :plugin-relay:testDebugUnitTest :plugin-nav:testDebugUnitTest"
max_failures: 2
---

# Honest map choices, a stable stack, and two conversation glitches

# Goal
Four defects seen in the 2026-10-24 device footage are gone:
1. **Map choices that do nothing.** With Google Maps (or Citymapper) as the route source the
   Navigation plugin has no route geometry (it only reads the guidance notification; geometry
   exists only for walks Nexus plans itself, `plugins/nav/.../NavJourneyService.kt`,
   `NavMapPublisher.kt`). The route page's Commands › Display offers Text / Minimap / Panel and
   saving Minimap changes nothing visible. Make the choice honest: on the glasses' Display page
   (`glasses-hub/.../session/RouteNavigation.kt`, `SessionReducer` Display panel) Minimap and
   Panel are shown unavailable with a short reason while the route has no geometry (no live
   `/activity/geometry` and no page geometry), and cannot be saved; in phone Settings › Display
   (`phone-hub/.../GlassesDisplaySettingsActivity.kt` ~:240-270) the note says maps need a route
   Nexus plans (a walk started from Navigation), sources like Google Maps show text.
2. **The stack reshuffles.** Each new Spotify track moved Media Deck above a running route,
   because Media Deck marks a track change as a significant update
   (`plugins/media/.../MediaDeckActivityPublisher.kt:288`) and the root orders by
   `significantOrder` (`glasses-hub/.../session/RootStops.kt:126-131`). A new track must not
   move media above other activities; significant updates for real events (a route's next
   manoeuvre, a start) keep their meaning. Prefer fixing the source (what Media Deck calls
   significant) over changing the hub ordering rule; if you change the rule, update plan 028a.
3. **Conversation card repeats the sender.** The root card shows "Mika" then
   "Mika: Can you check the build…" for a one-to-one thread. Drop the leading "<sender>: "
   prefixes from the card's preview line when every line's sender is the card title (group
   threads keep their sender prefixes). Find where the preview text is made (Relay's notice
   body, or `NotificationHistory`/`RootStops` preview on the glasses) and fix it at the right
   layer.
4. **The sent message blinks out.** On a conversation page, right after Send, the bubble of the
   message just sent disappears for one revision and comes back as "handed to the app" in the
   next one. The draft bubble must stay (as sending) until the page revision that carries the
   handoff arrives (glasses `ComposerState`/`DraftStore`/`SessionScreens` bubbles, Relay
   `RelayPages`/`ReplyRepository.recordHandoff`).

# Constraints
- MUST NOT change: guidance islands, map rendering, the 24-hour bound, the frozen order while a
  session is open, Relay reply authority rules, the composer's Send rule.
- MUST NOT touch files outside scope_globs.

# Acceptance tests
| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Display | glasses test: route without geometry → Minimap/Panel rows inert with reason; with live geometry → selectable | passes |
| 2 | Order | media test (and/or glasses root test): a track change is not significant; route stays above media after a new track | passes |
| 3 | Preview | test: 1:1 thread card preview has no "Mika:" prefix; group thread keeps prefixes | passes |
| 4 | Sent bubble | test: after Send, every rendered revision until the handoff shows the sent text | passes |
| 5 | Suites | test_commands | 0 failures |

# Escalation triggers
- Item 2 needs a change to the significance semantics other plugins rely on → report.
- Suites failing after 2 attempts; diff outside scope.

# Autonomy
Free: wording of the unavailable reason, the layer for item 3. One commit per item.

# Context the executor cannot re-derive (common to every launcher-fix lane)

- Product: Rokid Nexus, Android/Kotlin. Modules: `:shared`, `:bus-client` (public plugin SDK),
  `:phone-hub`, `:glasses-hub`, plugins under `plugins/`. Read `AGENTS.md` at the repo root and
  obey it. `BUSSPEC.md` is the wire authority, `docs/PLUGIN_SDK.md` the public SDK guide,
  `plans/028a-launcher-contract.md` the launcher contract, `docs/LAUNCHER_COMPLETION_REPORT.md`
  the status report, `CHANGELOG.md` (section `## 1.9.0`, unreleased, with `### SDK 0.24.0`) the
  release notes. Hubs 1.9.0 and SDK 0.24.0 are NOT released yet: both hubs ship together, so a
  launcher-bundle wire change may still be made, as long as peers without the launcher bundle
  (older hubs, `supportsLauncherInteractive()` false) behave exactly as before.
- Your checkout is the git worktree **E:\Tools\Rokid\RokidNexus-fx-nav-misc** on branch **fx/nav-misc**, created from
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
  `cd /e/Tools/Rokid/RokidNexus-fx-nav-misc && ./gradlew :glasses-hub:testDebugUnitTest --console=plain > build/lane.log 2>&1; echo exit=$?; grep -E " FAILED$|^e: |BUILD" build/lane.log | head -40`
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
