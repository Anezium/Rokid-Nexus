# Compact glasses UI validation ? 2026-10-18

Status: source/test integration approved by independent R8 review; 2,340 checks pass. Signed compact glasses and Navigation releases installed in place. Bounded on-device render/scroll checks pass; current GPS map captures are available; final owner visual approval remains pending.

## Later transit test: new detail-page defect remains open

A subsequent owner-requested real Google Maps transit test passed root/ambient
walking-to-stop rendering but exposed a false GPS-loss warning on the text-only
external detail page. Fable High is correcting it; prior R8 acceptance and the
2,340 passing cases do not cover that new correction. See
`docs/NAV_TRANSIT_DEVICE_CHECK_20261018.md` for full evidence and cleanup.
The street map on the internal route page was independently recaptured after
loading and is present; its former early screenshot was replaced in the gallery.

## Latest check: R10 fixture correction

Current integration `d5e44d1f6cb31dabf2d4de32113236557b9212a0` includes Fable's
production `661d2fd4`, attached capture `d28f8bde`, and test-only `9e26f358`.
R7 independently accepted both R6 production fixes and requested one correction:
the ScrollView capture host measured the weighted HUD at 112 px. Fable replaced
only that test host with FrameLayout and retained exact 480 x 640 dimensions,
real framework pre-draw, paint and viewport checks, and first-frame sampling.

The parent's configured-checkout command completed with exit 0:

```text
BUILD SUCCESSFUL in 52s
469 actionable tasks: 5 executed, 464 up-to-date
```

Phone: 952; glasses: 1,177; Navigation: 211. Total: **2,340 cases, zero failures,
errors or skips**. Glasses tests newly executed; phone and Navigation reused their
up-to-date outputs. OverflowLine is 9/9 and RootCompactRendering is 7/7. All three
modules' debug and signed-release assembly tasks completed or were up to date.
Full command/log: `build/glasses-ux-compact/canonical-r10-tests-build.log`;
XML summary: `r10-test-results.json` in the same ignored directory.

Fresh source-rendered PNGs and SHA sidecars are in `rendered-r10/`. Each is
480 x 640 and its interior paint guard passes. These are simulated fixture scenes,
not hardware captures. The unchanged Fable HTML previews without console errors
at widths 728 and 360 (content heights 2,586 and 2,265).

## Final review and bounded device check

Independent R8 approved immutable `9e26f358` for source/test integration, with no
outstanding blocker in scope. It independently checked the completed R10 log/XML,
fresh exports and full prior finding dispositions. Report:
`build/glasses-ux-compact/REVIEW-R8-SOL.md`. Source/test approval is not owner
visual acceptance.

Installed the accepted glasses and Navigation signed-release APKs using only
`adb install -r`; both returned `Success`. Existing signer SHA-256 was verified:
`f5e938e2e79b0526b31e40d36c8c19098450c1636b7e14a306681b4effddf81c`.
Frozen local artifacts and exact hashes are in
`build/glasses-ux-compact/apks/installed-artifacts.json`:

- Glasses SHA-256: `c2afe83367ea7942faabd489bbdc89670f202d756f0059d8edf395b7c7a9b71c`.
- Navigation SHA-256: `555122c8dc4619b94cc8bd75f6ea7769129c42cc98cd1e48b2a052dd28464e22`.

On the actual glasses, captured the compact root with the Sample Todo activity
(last-known/offline state) and the local synthetic Relay notification preview.
Both Notifications and Applications show their canonical marks. The Applications
page shows compact rows with app icons; the old exclusive-surface subtitle is
absent. Selecting the Relay preview makes the long message move; screenshots
three seconds apart differ only inside its text line, bounds `(32,235)-(448,251)`.
After selecting Notifications, screenshots two seconds apart are pixel-identical.
These are renderer/input observations; physical wearer readability was not rated.

The root test enabled Sample's Show in activities temporarily. Its switch was
restored OFF, with Milk/Eggs/Bread/Coffee still to do and Apples still done.
Media's switch was temporarily enabled, reported nothing playing, and was restored
OFF without any playback command. Consequently no actual two-promoted-activity
root was captured in this pass. Navigation/Relay/multiple-activity composition is
shown separately as a clearly labelled Android source-fixture simulation, not
hardware evidence or a real route. No new GPS test, journey, microphone, draft or
Send action occurred. A local test notification/history entry may remain.

Final state: phone Main with hub running, glasses native home, font scale 1.0,
Bluetooth on, no Navigation journey service. No device/USB reset, uninstall,
data clear, account, grant, signer or build-environment change. Previously passed
phone Back and font-scale evidence remains valid; the phone APK was not replaced.

Local evidence: `build/glasses-ux-compact/device-check-r10.json`,
`build/smoke/shots/compact-*.png`, and the self-contained browser gallery
`build/smoke/nexus-captures-20261018.html` (43 entries: historical/current real
captures and three separately labelled source simulations). Gallery preview passed
at widths 728 and 360 with no console errors. Owner visual approval and current
Navigation/map/native-row appearance with a real GPS fix remain open; the owner's
refusal of another GPS test is respected.

## Owner-requested Navigation recapture

The owner explicitly requested recapturing the installed Navigation UI. Started a
new real walking journey to the previously supplied public destination, Gare de
Goussainville, from the visible phone UI. Precise location was allowed once for
this bounded test. The phone obtained a sufficient fix and planned a 1.8 km route.

Fresh screenshots from the current installed releases now show:

- Navigation activity plus the retained local Relay notification preview in the
  actual Nexus root. This is an activity and a notification, not two promoted
  plugin activities.
- The smaller ambient text guidance above the native launcher/status row.
- The compact minimap and wide panel with actual downloaded OpenStreetMap streets,
  route/position geometry, scale and attribution. The native status row is clear.
- The route page opened with one tap from the root. Its first screenshot preceded
  the basemap response; the later ambient captures include the loaded street map.

Evidence: `build/smoke/shots/nav-recapture-current-{root,ambient-text,minimap,panel,route-page}.png`
and `build/glasses-ux-compact/nav-recapture-current-state.json`. These are real
screenshots, replacing the former lack of current GPS appearance evidence; they
are not simulated images. Initial captures reported on-route; later phone status
reported low GPS accuracy before stopping. No claim of sustained GPS quality,
physical wearer readability or a freshly captured GPS-lost variant is made.

Stopped the journey through its visible Stop control after capture. Restored
Navigation size Automatic (checked true, other three false). Removed only the
new temporary location grants; exact observed permission/flag state matches the
saved pre-test baseline, including the pre-existing ONE_TIME metadata. No journey
service remains. Phone returned to Main, glasses to native home. Navigation's
activity disappears after stop by design. No microphone, draft or Send action.

The browser gallery now defaults to Navigation current and has 48 entries,
including five fresh Navigation images. Successful previews at 728/360: no
console errors, content heights 2,002/1,586. No source or build change was made
for this recapture. Owner visual approval is still pending.

## Historical integrated checks

## Latest integrated check after subscription resumed

Fable completed source commits `d9b7ca40`, `382062a7` and the test-only correction
`12085f45`, preserving all interrupted work. Canonical integration is
`d010156d01214410c0bd783c24d716defe3538a3`. The fresh R6 independent review is
complete and requests changes; no current compact APK is accepted or installed.

The full affected suites and debug/release assembly invocation compiled and ran
both new suites after two initial test-compilation errors were corrected.
Observed results at this pin:

| Module | Cases | Failures | Errors / skipped |
|---|---:|---:|---:|
| phone-hub | 952 | 0 | 0 / 0 |
| glasses-hub | 1,176 | 3 | 0 / 0 |
| plugin-nav | 211 | 0 | 0 / 0 |

All six new Navigation long-name cases pass. Three glasses continuity cases fail:
the rebuilt line remains at offset zero, and the island/root live-update cases do
not observe the end of the line. The old cue-description assertion now passes.
The current command used an ignored, per-invocation Gradle init script only to
forward the fixture-export property to the test JVM; no persistent build or machine
configuration was changed.

Actual tail from `build/glasses-ux-compact/canonical-r8-tests-build.log`:

```text
1176 tests completed, 3 failed
> Task :glasses-hub:testDebugUnitTest FAILED
...
BUILD FAILED in 1m 12s
469 actionable tasks: 8 executed, 461 up-to-date
```

Machine-readable totals and exact failures: `r8-test-results.json`. Optional PNGs
were actually produced in `rendered-r7/`. The root with Navigation, Relay, a
conversation preview and the Activities row fits its viewport. The Activities
page PNG instead shows the first two cards as empty outlines while the selected
Todo card is drawn; the HTML shows all three populated. This is an observed
source-rendered artifact mismatch to investigate, not an established device defect.
The 728/360 HTML previews have no console errors or clipped frames. Neither those
previews nor the still images establish working live scrolling.

The completed R6 review isolated two P2 corrections: inherited TextView pre-draw
alignment overwrites the restored offset before the next timer tick, and an island
rebuilt while hidden loses the local flag that should force a fresh hold on show.
The latter has a reachable flare/rebind/fold lifecycle and needs its own bounded
regression. The original constructor/detach memory erasers are corrected; full
runtime starvation is not established by post-render sampling failures. External
name preservation is accepted at source level with the passing new cases; Google
transit and Citymapper lack new long-name cases, a stated coverage limitation.

The same Fable author has received the full R6 report while correcting these
failures. The three exported PNGs are frozen with hashes in `rendered-r8-frozen/`.
Image-tool observations disagree about which card interiors are blank, despite
stable file hashes and opaque bright pixels in those regions. No production
blank-card defect is established; capture/traversal fidelity remains unaccepted
and must be distinguished from the two demonstrated continuity defects.
No additional writer, API implementation, device action, GPS, microphone or Send
was started. The encrypted API credential remains outside the repository as a fallback;
current implementation uses the restored subscription.

## Earlier integrated source and observed verification

The configured integration checkout is `dev/launcher-rework` at
`02a7d722d560666c725c1c4c921f1a0d446da00b`. It includes Fable's compact cards,
Navigation island sizes, selected-line scrolling, plain Open app copy, bounded-name
separator cleanup, modern phone Back handling and corrected Back test setup.

The latest observed command was:

```powershell
.\gradlew.bat :phone-hub:testDebugUnitTest :glasses-hub:testDebugUnitTest :plugin-nav:testDebugUnitTest --continue --no-parallel
```

| Module | Cases | Failures | Errors / skipped |
|---|---:|---:|---:|
| phone-hub | 952 | 0 | 0 / 0 |
| glasses-hub | 1,170 | 1 | 0 / 0 |
| plugin-nav | 205 | 0 | 0 / 0 |

The remaining failure is `LauncherRenderingTest`, the compact-bottom live-turn case:
`expected:<turn-left> but was:<null>` at line 145. The regular glyph has no content
description, whereas the compact form has one. This assertion does not establish
that the arrow is visually missing. Whole-cue and native-clearance checks must still
run successfully after the correction.

Actual command tail:

```text
Execution failed for task ':glasses-hub:testDebugUnitTest'.
> There were failing tests.
...
BUILD FAILED in 1m 21s
141 actionable tasks: 9 executed, 132 up-to-date
```

Evidence: `build/glasses-ux-compact/canonical-r5-fixtures-tests.log` and
`r5-fixtures-test-results.json`. The five phone Back regressions pass at this pin;
first Back hiding the IME and second Back restoring the retained draft subsequently
passed signed-phone device validation; see
[the current device report](PHONE_COMPOSER_BACK_DEVICE_20261018.md). Earlier debug/release assembly results do not
make this failed verification successful.

## Independent review and interrupted corrections

The completed R4 review of `50433992194f9ff32fa8835fa5c09e41ba84b01e`
requested corrections for live updates restarting marquee progress, early external
Navigation name truncation, test fixtures and inaccurate mockup content.

Fable's subsequent implementation was interrupted by its subscription session limit.
The actual provider message was:

```text
You've hit your session limit ? resets 8:10pm (Europe/Paris)
```

The reset is 2026-10-18 at 20:10 Europe/Paris. No retry or timer was created.
The task wrapper reported `Claude API rate limit reached. Try again later.`
No paid API or replacement author was used.

The author checkout remains on
`1a3c6f98ae804a43359c02a64df6f1efc0f3c553`, with eleven preserved uncommitted
source changes. They introduce scroll memory and retain an external street name for
the existing wider route-page field. They have no new regression tests or updated
findings response and are not integrated, built or accepted. The HTML still needs
the R4 fidelity corrections: ordinary cards must not invent command-count lines,
examples must respect validated activity text bounds, and the Activities row must
match its actual icon policy.

A byte-for-byte partial snapshot, source hashes and patch are saved in
`build/glasses-ux-compact/r6-partial-snapshot/`. The patch SHA-256 is
`4f3f41e0271b15f48e617236183a16e88f3b1daa2125061b978eca03da844e98`.
The fresh R5 independent read-only review is complete and requests changes. It
found two remaining scroll-memory reset paths: constructing an unattached replacement
forgets its predecessor's progress, and the framework's detach visibility callback
forgets it before detach retention can run. The name-preservation path addresses the
Navigation finding at source level, with regression/build evidence still missing.
The inaccurate fixtures and HTML are unchanged. The eleven source hashes were
rechecked after review and remain identical to the saved manifest.

The complete report is `build/glasses-ux-compact/REVIEW-R5-PARTIAL-SOL.md`.
The original scope, R4/R5 findings and frozen manifest are consolidated in
`FABLE-R6-QUOTA-RESUME-BRIEF.md` for the same author's later recovery. This is a
prepared continuation brief; no restart, timer or implementation substitution has
been dispatched. The partial review is not final acceptance.

## Remaining acceptance

1. Complete the interrupted implementation and behavioral regressions, preserving
   unchanged-text scrolling across legitimate live updates and resetting it on
   changed text, geometry, selection or visibility.
2. Correct the multi-activity fixtures and browser-openable HTML, then obtain a
   fresh independent review of a complete immutable pin.
3. Run affected suites and signed APK builds from the configured checkout; verify
   certificates before any in-place installation.
4. Validate permitted non-GPS compact UI behavior on devices, record actual captures
   and obtain the owner's final visual approval. Phone Back has separately passed
   its signed-artifact device check.

The owner declined further GPS testing. No new journey, location request,
microphone capture or message Send was performed. Navigation's current map/island
appearance and native HUD clearance remain unverified on hardware; simulations
must be labelled as simulations. Accounts, settings, signing material, machine
build configuration and unrelated source remain preserved.
