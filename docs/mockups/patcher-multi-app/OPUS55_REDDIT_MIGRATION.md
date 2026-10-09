# Reddit tutorial migration into Patcher — delivery note

Delivered 2026-10-09 by Claude Opus 5.5 (subscription provider, no Anthropic API
calls, no delegation). Implementation, focused tests and documentation only. Nothing
was committed, pushed, installed, or run on the shared phone or glasses. This change
has had no API review; parent review and device QA are still outstanding.

## Behaviour now

- **Glasses apps is inventory only.** The "Reddit on glasses" card and its
  "Set up Reddit" button are removed. The passive line under "On your glasses" now
  reads "YouTube and Reddit setup and updates live in Patcher." Loading, empty, error
  and app rows are unchanged.
- **Patcher → Set up Reddit** goes through the same authenticated hub hand-off as
  YouTube: `startActivityForResult` to `PatcherSetupEntryActivity`, which checks the
  action, Android's `callingPackage`, the fixed allowlist and the approved Patcher
  identity (unchanged `PatcherHandoff.setupEntry`), then opens the non-exported
  `RedditSetupActivity`. YouTube still opens `YoutubeSetupActivity` with its vetted
  job hint. An unapproved Patcher is sent to approval for either target.
- **Opening the tutorial is non-destructive.** Patcher only starts the hub; it does
  not select a target, reset the job, or clear a held YouTube source or result. The
  guarded target switch is reached only from the tutorial's later "Patch and install".
  A running job still reopens `PatchActivity` directly and still locks the other card.
- **Older hubs.** The entry now declares the setups it opens in manifest meta-data
  `com.anezium.rokidbus.patcher.SETUP_TARGETS = youtube,reddit`. Patcher reads it,
  clipped to the fixed allowlist. A hub without it (for example the installed 1.6.0
  baseline) is treated as YouTube-only, so Reddit falls back to Patcher's own patch
  screen with "Update Nexus to set up Reddit on the glasses. You can still patch it
  here." instead of a silent refusal.
- **Reddit setup screen.** The "‹ Glasses apps" text link is replaced by the
  `NexusUi.pluginHeader` used by the YouTube setup: 48 dp tile with the existing
  green `ic_app_reddit` (untinted, as on Patcher's card), title "Reddit on glasses",
  subtitle "Patcher · Glasses setup", back chevron. Header back and system Back share
  one `navigateBack()` that finishes back to Patcher, including the API 33+
  `OnBackInvokedCallback` with cleanup that targetSdk 36 needs (same pattern as the
  YouTube fix, which is untouched). Content uses `NexusUi.screen`. The three steps,
  APKM download link, patch/install flow, Keyboard & remote secure session, the
  auto-open keyboard toggle, source pins and explicit install are unchanged. No MicroG.
- **Patcher home card.** Reddit's button is "Set up Reddit", status
  "Glasses setup · 3 steps", summary names its guided steps and keeps "no MicroG
  needed". A ready result says to install it from the Reddit steps.

## Changed files

- `shared/.../PatcherContract.kt` — `SETUP_TARGETS` = YouTube + Reddit;
  `META_SETUP_TARGETS` and `hubSetupTargets()` (legacy = YouTube only).
- `phone-hub/src/main/AndroidManifest.xml` — meta-data on the entry.
- `phone-hub/.../PatcherSetupEntryActivity.kt` — routes the validated target.
- `phone-hub/.../RedditSetupActivity.kt` — header, Back, `intent()`.
- `phone-hub/.../NativeAppsActivity.kt`, `res/values/strings.xml` — card removed,
  hint string renamed to `native_apps_patcher_hint`.
- `plugins/patcher/.../AppTargets.kt` — `hasHubSetup` reads the declaration.
- `plugins/patcher/.../PatcherHomeActivity.kt` — Reddit labels and doc comment.
- Tests: `PatcherContractTest`, `PatcherSetupEntryActivityTest`,
  `PatcherAppFlowTest` updated; new `RedditSetupActivityTest`, `NativeAppsActivityTest`.
- Docs: `plugins/patcher/README.md`, `docs/YOUTUBE_GLASSES.md`.

## Tests added or changed

- Contract: allowlist is exactly YouTube + Reddit; declaration parsing clips unknown
  ids and treats a missing declaration as YouTube-only.
- Entry: Reddit opens `RedditSetupActivity` with no forwarded hint; unknown, empty,
  missing, case/space variants and path-like ids, untrusted and null callers are
  refused; unapproved Patcher → approval for Reddit; manifest keeps
  `RedditSetupActivity` non-exported and the declaration equals the allowlist.
- Reddit screen: Patcher header and all three steps, no "Glasses apps" or MicroG
  text; system Back and header arrow finish; at API 34 the platform callback is
  registered and cleared on destroy.
- Patcher: Reddit asks the hub in result mode with `idle` hint while a YouTube source
  is held, and the job state, target and stock file are byte-for-byte unchanged; an
  older hub keeps Reddit local with the update toast while YouTube still goes to the hub.
- Glasses apps: hub unit tests run without Android resources, so this check reads the
  activity source and strings (no setup activity, no setup labels, inventory states
  kept). The rendered screen needs device QA.

## Observed results (this workspace, main checkout with its existing dirty changes)

`./gradlew :shared:test :phone-hub:testDebugUnitTest :phone-hub:assembleDebug`
(no `-PskipCxrGlobal`):

```
> Task :phone-hub:testDebugUnitTest
> Task :phone-hub:assembleDebug
BUILD SUCCESSFUL in 34s
120 actionable tasks: 6 executed, 114 up-to-date
```

Phone hub: 743 tests, 0 failures, 0 errors, 0 skipped (baseline 736 + 7 new).
Shared: 366 tests, 0 failures, 0 errors, 0 skipped (ran in the preceding focused
invocation after the contract edit; the new declaration test is in the results).

`./gradlew :plugin-patcher:testDebugUnitTest :plugin-patcher:assembleDebug -PskipCxrGlobal=true -PredditPatchBundleInput=E:/Tools/Rokid/reddit-patch-lab/final-preview23/reddit-source-preview23.mpp`:

```
> Task :plugin-patcher:testDebugUnitTest
> Task :plugin-patcher:assembleDebug
BUILD SUCCESSFUL in 48s
87 actionable tasks: 5 executed, 82 up-to-date
```

Patcher: 158 tests, 0 failures, 0 errors, 12 skipped. The skips are the existing
assumption-gated suites (`BundleLifecycleTest`, `BundleUpdateTest`,
`PatchActivityTest`, `RealRedditPatchTest`), not new ones. Without
`-PredditPatchBundleInput` the Patcher build stops at `prepareRedditBundle`
("Reddit preview is unpublished"), as designed.

Debug APKs: `phone-hub/build/outputs/apk/debug/phone-hub-debug.apk`,
`plugins/patcher/build/outputs/apk/debug/plugin-patcher-debug.apk`. These are debug
builds from the root checkout; they are not the compatible signed release and lack
the Skills/PDF work. Do not install them.

## Limitations and parent follow-up

- No device QA, no compatible signed build, no screenshots from this child.
- Pairing matters: a new Patcher with the old installed hub keeps Reddit local (by
  design). Both must be updated for Reddit to open the hub tutorial.
- `build/tmp/patcher-fable-review/resolve_overlay.py` still encodes the old two-card
  Reddit rule; it was left alone (outside child scope). Update it so the PDF
  checkout's Reddit card is not reintroduced during the overlay merge.
- The `rokid-glasses-dev` skill was not available in this session's skill list;
  `frontend-design` was loaded, with the user's rule to stay inside the existing
  NexusUi kit taking precedence (no new visual elements were invented).
