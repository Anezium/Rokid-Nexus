# Patcher phone UI — adjustments after the Fable 5.1 review

Implemented on 2026-10-09 by Claude Opus 5.5 from the five priorities of the
Fable 5.1 API review. Presentation only: no route, protocol, capability,
authentication, patch pin, job engine or signing change. Everything uses existing
`NexusUi` helpers; `NexusUi.kt` is untouched by this round.

## Files

- `plugins/patcher/src/main/java/com/anezium/rokidbus/plugin/patcher/PatcherHomeActivity.kt`
- `plugins/patcher/src/test/java/com/anezium/rokidbus/plugin/patcher/PatcherAppFlowTest.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/YoutubeSetupActivity.kt`
- `phone-hub/src/test/java/com/anezium/rokidbus/phone/YoutubeSetupActivityTest.kt`
- this note

## The five adjustments

1. **App card type roles (Patcher home).** Each card is now title, one short mono
   `rowSub` status, then a sans `cardBody` sentence. Status values: `Glasses setup ·
   4 steps`, `Official <version>`, `Checking file`, `Patching`, `Patched APK ready`,
   `Stopped · open to retry`, `File checked · ready`. The "leaving this screen does
   not stop it" explanation moved into the body. Status and the amber lock line wrap
   to two lines instead of ellipsizing. The 44 dp mark is top-aligned with the
   title now that the text column has three parts.
2. **Step headers.** Titles are `MicroG`, `Official YouTube`, `Patch and install`,
   `Sign in and open`; the subtitle is `Step N of 4 · YouTube`. Back chevron and
   `pluginHeader` unchanged. Measured: one line at 1.0 and 1.3 font scale.
3. **Controller report.** The free-floating status sentence ("Glasses apps
   refreshed.", download progress, failures) is now a report line with a small dot
   (green while busy). It sits under the `SETUP` section row on the overview and
   inside the step card under its status on step screens. It wraps at 13 sp sans, so
   long errors stay whole. It is not packed into the section-row value.
4. **Overview and Advanced.** Advanced is now its own route, opened from a `navCard`
   with a chevron (`Morphe or patched APK`). It has a header, Back to the overview
   and the same actions and lock rules as before. Overview step rows show only the
   short state (`To do`, `Needs attention`, `Patching in Patcher`…); the full sentence
   stays on the step screen and in the row's `contentDescription`.
5. **Secondary actions.** `More` / `Less` and the actions it reveals are end-aligned
   text buttons, like the signing-key card, with a 48 dp touch target. The same
   applies to the step-3 extras and the sign-in mark, renamed from `Done` to
   `Mark sign-in done`. Primary actions remain full-width pills.

## Review assumptions corrected

- **"Step cards are ~150 dp".** Wrong: the review read 720 px renders (density 2) as
  dp. Measured overview rows are 67 dp at 1.0 and 78 dp at 1.3. The rows already used
  `pressableCard` + 34 dp tile + chevron. No density change was made, so kit padding
  and touch targets are preserved.
- **Footer repeat path "unverified".** It was already implemented and tested:
  `Get or approve Patcher`, `Patch now`, `Patch an update`, `Install patched APK` and
  `Open running job`. "Get or approve Patcher" is the real state when Patcher is
  absent or not approved. The logic is unchanged; the render below shows
  `Open running job`.
- **Keyboard and Advanced above the fold.** Not achievable within the kit. In a
  360×740 dp window the overview scroll viewport is 584 dp and the content is 726 dp
  at 1.0 (900 dp at 1.3, with a 572 dp viewport). The four steps fit above the fold; `PHONE KEYBOARD` starts
  at y=444 dp (1.0) and Advanced is below the fold. Fitting both would mean shrinking
  rows below kit sizes.
- **Uppercase mono status values** (`NOT SET UP · 4 STEPS`). Not used: the kit's
  `rowSub` is mixed case. The plugin also cannot know hub setup progress, so the
  YouTube card does not claim "not set up".
- **Reddit "ad filtering" copy.** Not used; the body uses the target's own
  description (glasses HUD, reviewed replies, no MicroG).

## Verification (observed)

```
.\gradlew.bat :plugin-patcher:testDebugUnitTest :plugin-patcher:assembleDebug -PskipCxrGlobal=true -PredditPatchBundleInput=E:/Tools/Rokid/reddit-patch-lab/final-preview23/reddit-source-preview23.mpp
BUILD SUCCESSFUL in 1m 15s
JUnit XML: 157 tests, 145 passed, 12 skipped, 0 failures, 0 errors

.\gradlew.bat :phone-hub:testDebugUnitTest :phone-hub:assembleDebug
BUILD SUCCESSFUL in 53s
JUnit XML: 736 tests, 736 passed, 0 skipped, 0 failures, 0 errors
```

Debug builds only. No release build, signing, install, commit or push.

## Native renders (Robolectric, not device captures)

Location: `build/outputs/patcher-ui-adjustments-20261009/` (git-ignored). These were
made with a temporary Robolectric `GraphicsMode.NATIVE` harness: `w360dp-h740dp-xhdpi`
(density 2.0, 720×1480 px), font scale 1.0 and 1.3. The harness has been removed from
the source tree; a copy is in `build/tmp/opus55-adjust-scripts/`. Hub renders needed
`includeAndroidResources` through a command-line init script
(`render-resources.init.gradle`); no build file was changed. The action bar that
Robolectric adds was hidden before capture.

For each scenario there is `-viewport.png` (what fits in the window, with fixed
header and footer), `-content.png` (the full scroll content) and `-geometry.txt`
(dp bounds, line counts, ellipsis and overflow flags for every visible text and
button):

- `plugin-home`, `plugin-home-running`, `plugin-home-stopped`. The last one actually
  shows the Reddit `File checked · ready` state: the harness failed to force
  `FAILURE`. The stopped state is covered by code review only.
- `hub-overview`, `hub-overview-running` (footer `Open running job`),
  `hub-overview-error` (long disconnection message under SETUP)
- `hub-microg`, `hub-microg-more`, `hub-source-locked`, `hub-patch`, `hub-signin`,
  `hub-advanced`

The parent's artifact count confirms 24 geometry scenarios (12 routes/states at
two font scales). The geometry scan found no overflow. The only ellipsized text
is the canonical `uninstallCard` subtitle at 1.3, which is unchanged kit code.

## Limitations

- Not yet seen on a device. Real fonts and OEM font scaling can differ from
  Robolectric's.
- The kit's `pillButton` (primary and footer) is 46 dp and the kit's `textButton` is
  42 dp. Only the standalone end-aligned text buttons on these screens were raised to
  48 dp. The kit itself was not changed.
- The signing-key card's `More` / `Import key` / `Export key` row still uses 42 dp
  text buttons. It was left as is because that card was declared out of scope.
