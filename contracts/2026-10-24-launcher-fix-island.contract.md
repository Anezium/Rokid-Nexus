---
task: launcher-fix-island
date: 2026-10-24
status: active
executor: Claude Opus 5.5
worktree: E:\Tools\Rokid\RokidNexus-fx-island
branch: fx/island
scope_globs:
  - "shared/src/**"
  - "bus-client/src/**"
  - "glasses-hub/src/**"
  - "phone-hub/src/**"
  - "plugins/media/**"
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
  - "./gradlew :shared:test :bus-client:testDebugUnitTest"
  - "./gradlew :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug :phone-hub:testDebugUnitTest :phone-hub:assembleDebug"
  - "./gradlew -PskipCxrGlobal=true :plugin-media:testDebugUnitTest :plugin-media:assembleDebug"
max_failures: 2
---

# Full media titles: a long headline on launcher cards and a compact, scrolling island

# Goal
A playing track reads in full on the glasses. (1) The launcher's activity card shows the whole
title on one line and, when it does not fit, scrolls it. (2) The activity island over the
glasses' home is compact: the glyph beside two lines, title then artist, nothing under them; a
too-long title scrolls. (3) The island's command strip never shows truncated labels like
"Pre…"/"Pau…". (4) The island never contradicts Rokid's own media pill drawn under it (today
our island draws ▶ while playing next to Rokid's ⏸, and ⏸ while paused). Owner's words: "on a
toute la place sauf qu'on l'utilise pas", "pourquoi mettre l'artiste tout en bas ? mettre en
2e ligne sous le titre et rétrécir le badge", "si le titre est trop long on le fait défiler".

# Evidence (device, 2026-10-24, build f651994c)
- Card: "internet gi…" uses half the card width. Cause: `ActivitySurfaceContract.MAX_PRIMARY_CHARS
  = 12` (shared `ActivitySurfaceContract.kt:297`), enforced by the SDK (`SurfaceModels.kt:1135`),
  and Media Deck ellipsizes the title to 12 before sending
  (`plugins/media/.../MediaDeckActivityModel.kt:108`). Card rendering itself already gives the
  primary the full width (`glasses-hub/.../page/SessionScreenView.kt` ~:317-330).
- Island: activities reuse the pins' `PinOverlayRenderer.PinPanelView` (MEDIUM) through
  `ActivityOverlayRenderer.kt` ~:361-385: title on the first row, `measure` stacked under it
  beside a 32 dp glyph, and `secondary` (the artist) as a separate line under the whole block,
  leaving a large empty area. Pins also use `PinPanelView`: their look must not change.
- Command strip truncation seen on the media island's expanded form ("⏮ Pre…", "⏸ Pau…", "⏭")
  when a new track starts.

# Constraints
- Add ONE optional launcher-bundle field on the activity (suggested name `headline`, max 40
  printable chars, validated like `primary`): a longer form of `primary` for surfaces with room.
  It MUST only travel when the launcher bundle is negotiated (same rule as `destination` /
  `activation`), MUST be ignored/absent for older peers, and `primary` (12) stays mandatory and
  unchanged. SDK: a defaulted parameter on the public activity model, documented in
  `docs/PLUGIN_SDK.md`, `BUSSPEC.md`, CHANGELOG `### SDK 0.24.0`.
- Media Deck sends the full title as `headline` (ellipsized at 40) and keeps `primary` as today.
- Glasses: the card uses `headline ?: primary`; the island uses `headline ?: primary`. Scrolling
  (marquee) only when the text does not fit its width; it starts after a short pause, loops, and
  never changes the view's size. It MUST NOT run while the island/card is not shown.
- Island compact form for activities WITHOUT a `measure`: glyph (smaller, sized to the two text
  lines) beside title (line 1) and `secondary` (line 2); no line under. Activities WITH a
  `measure` (navigation text form) keep their current layout. Guidance islands (map forms) are
  out of scope. Pins MUST look exactly as before.
- Island command strip: icons only (or labels that always fit) — never an ellipsized label.
- Play/pause: the island MUST NOT draw a ▶ or ⏸ transport glyph for media; use the activity's
  source glyph (Media Deck's own glyph) and say "Paused" (second line suffix or similar) when
  paused. Card command buttons keep their action labels (Play/Pause).
- MUST NOT change the classic launcher's behaviour with an older phone (no bundle): there, no
  headline exists and the 12-char primary is drawn as today (layout changes of the island are
  allowed for both, since the island belongs to these hubs).
- MUST NOT touch files outside scope_globs; MUST NOT change plugin capabilities or grants.

# Acceptance tests
| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Contract | shared test: headline accepted ≤40, rejected >40 / non-printable, absent without bundle | passes |
| 2 | SDK | bus-client test: headline sent only with `supportsLauncherInteractive()` | passes |
| 3 | Media Deck | media test: a 30-char title travels whole as headline, primary ≤12 | passes |
| 4 | Card | glasses render test: a 30-char headline is drawn whole (no "…" from the hub) on the card | passes |
| 5 | Island | render test: no-measure activity island has exactly two text rows beside the glyph, secondary on row 2, nothing below; with a measure the old layout | passes |
| 6 | Pins | existing pin render tests unchanged and green | passes |
| 7 | Suites | the three test_commands | 0 failures |

# Plan sketch
1. Contract + SDK field, tests. 2. Media Deck. 3. Card. 4. Island layout + marquee + strip +
glyph rule. 5. Docs. Commit after each.

# Escalation triggers
- The field cannot be made bundle-only without touching phone validation in ways you cannot
  test → report.
- Any suite failing after 2 focused attempts; diff outside scope.

# Autonomy
Free: field name, exact marquee speed/pause, glyph size, "Paused" wording. Stop and report:
any change to `primary` limits, to pins' look, or to the guidance island.

# Context the executor cannot re-derive (common to every launcher-fix lane)

- Product: Rokid Nexus, Android/Kotlin. Modules: `:shared`, `:bus-client` (public plugin SDK),
  `:phone-hub`, `:glasses-hub`, plugins under `plugins/`. Read `AGENTS.md` at the repo root and
  obey it. `BUSSPEC.md` is the wire authority, `docs/PLUGIN_SDK.md` the public SDK guide,
  `plans/028a-launcher-contract.md` the launcher contract, `docs/LAUNCHER_COMPLETION_REPORT.md`
  the status report, `CHANGELOG.md` (section `## 1.9.0`, unreleased, with `### SDK 0.24.0`) the
  release notes. Hubs 1.9.0 and SDK 0.24.0 are NOT released yet: both hubs ship together, so a
  launcher-bundle wire change may still be made, as long as peers without the launcher bundle
  (older hubs, `supportsLauncherInteractive()` false) behave exactly as before.
- Your checkout is the git worktree **E:\Tools\Rokid\RokidNexus-fx-island** on branch **fx/island**, created from
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
  `cd /e/Tools/Rokid/RokidNexus-fx-island && ./gradlew :glasses-hub:testDebugUnitTest --console=plain > build/lane.log 2>&1; echo exit=$?; grep -E " FAILED$|^e: |BUILD" build/lane.log | head -40`
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
