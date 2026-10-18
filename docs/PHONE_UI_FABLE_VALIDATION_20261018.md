# Phone UI corrections by Fable: implementation and validation

## Current status

Fable 5.1 authored five isolated-worktree commits, independently accepted by the
second GPT-6.1 Sol review. The configured integration checkout contains the exact
accepted source at `fad3b8b2326b3ce717e9e8c791bd395dd18a3716`. Tests and debug/release
builds passed; both release APK signatures match the existing signer.

**The corrected phone UI is not installed or device-validated yet.** The supplied
Wi-Fi ADB endpoint refused reconnection before the first installation; one
automatically discovered endpoint also failed. No reset or environment repair was
attempted. Current devices retain the earlier signed `0aa3eb1b` HUD build. New phone
screenshots, enlarged-font checks and keyboard interaction proof remain pending.

The separate GPS-dependent HUD map appearance check is also open. The owner
declined moving the phone to obtain a first fix; no new journey was started.

## Changes and source ownership

Author worktree: `RokidNexus-phone-ui-fable-20261018`, branch
`fix/launcher-phone-ui-fable-20261018`. Base: `0aa3eb1b`; final author pin:
`66bdaf3ffb2fb4f0a1afc15717a928a03d1e8316`. Original four commits `d9cffd23`,
`89db44c5`, `f17fc05d`, `7c308dfb` were preserved; `66bdaf3f` resolves R1 findings.

| Feature | Files and resulting behavior |
| --- | --- |
| Readable, exclusive display choices | `phone-hub/.../AmbientChoiceRow.kt`, `AmbientChoiceRowTest.kt`: two options per row, equal columns including an odd last row, one checked option and one notification per changed choice |
| Honest Display explanations | `phone-hub/.../GlassesDisplaySettingsActivity.kt`: Navigation-only variants retained; descriptions grow in the existing scroll area; collapse copy identifies the primary activity and explicit Navigation-size override |
| Foreground activity modes | `phone-hub/.../NativeAppsActivity.kt`: Activities / Alerts only / Nothing explain persistent text, brief significant-update alerts and hidden activity; no map is promised over a native app |
| Permissions and availability | `phone-hub/.../PluginPermissionsActivity.kt`: readable checklist/media action labels and a missing-grant explanation, with existing grants and approval callbacks unchanged |
| Explicit unsent keyboard draft | `phone-hub/.../ComposeKeyboardActivity.kt`: Phone keyboard title, Draft / not sent yet status, Keep draft action and scrollable content; Cancel/Back and fresh Send authority remain unchanged |
| Short external-guidance note | `plugins/nav/src/main/res/values/strings.xml`: explains that disabling app guidance ends those apps' live routes; walking journeys remain separately controlled |

The full diff is seven files, +235/-54. No public wire/SDK, runtime policy, stored
preference key, capability, signing identity or shared theme was changed. Other
plugins do not gain Navigation's size options. No author build, ADB/device action,
microphone use or nested agent fan-out occurred.

## Independent review and actual checks

R1 requested three bounded corrections: qualify the collapse explanation, explain
the foreground text cap, and remove the remaining line cap from new explanations.
Fable accepted all three and corrected only the affected copy/layout. Fresh R2
reviewed the full original scope and the response, verified the exact clean pin,
and approved with no remaining blocking findings. Optional autofill/collection
metadata observations and unrelated pre-existing settings were not expanded into
new scope. Review records and author reports remain in ignored `build/phone-ui-ux/`.

The parent ran only the configured integration checkout, without skipCxrGlobal:

```powershell
.\gradlew.bat :phone-hub:testDebugUnitTest :plugin-nav:testDebugUnitTest :phone-hub:assembleDebug :plugin-nav:assembleDebug :phone-hub:assembleRelease :plugin-nav:assembleRelease --continue --no-parallel
```

The established signing reference was supplied only to that build process; no
credential value was printed or copied, and no machine configuration was changed.
Observed final tail from `build/phone-ui-ux/canonical-r2-signed.log`:

```text
BUILD SUCCESSFUL in 1m 18s
372 actionable tasks: 24 executed, 4 from cache, 344 up-to-date
```

Current XML totals are **947 phone + 201 Navigation cases**, zero failures/errors/
skips. Phone tests executed; the unchanged Navigation suite was reused. All four
new AmbientChoiceRow regressions pass. These tests do not establish OEM glyph
fitting, accessibility collection semantics, IME behavior or physical scrolling.

Both current release candidates were checked with apksigner, matched the existing
pinned release certificate, and were copied to ignored local storage with SHA-256
records in `build/phone-ui-ux/apk-verification-r2.json`. The earlier unsigned build
was compilation evidence only; it was not installed or substituted for a signed
upgrade. No APK, key, credential or private device data is committed here.

## Remaining phone validation

1. Install the exact two accepted signed candidates in place after connection is
   available, preserving accounts, pairing, preferences and drafts.
2. Capture Display at normal and enlarged fonts; check complete explanations,
   readable choices, exclusive selection and restoration of the original choice.
3. Capture native-app modes and relevant plugin availability/approval sections
   without changing grants merely to obtain a screenshot.
4. Exercise the phone composer with a synthetic conversation, long text and IME:
   actions remain reachable; Keep draft stays unsent; Cancel/Back preserve the
   prior draft. No real message Send is required.
5. Restore any temporary font/preference changes exactly and record actual device
   results before labeling new images as after evidence.

Existing `hud-fixed-phone-*` images are BEFORE captures from `0aa3eb1b`, not images
of Fable's accepted source. The older OSM gallery also predates these phone changes
and the current HUD map correction. Private screenshots and raw evidence stay local.
