# Compact glasses UI validation ? 2026-10-18

Status: incomplete. Source corrections and the multi-activity HTML have not been accepted.
No current compact APK has been installed or validated on the devices.

## Integrated source and observed verification

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
