# Phone UI corrections by Fable: implementation and validation

## Current status

The earlier source/build and enlarged-font sections below describe the R2 artifact;
the current phone Back upgrade and device proof are linked separately.

Fable 5.1 authored five isolated-worktree commits, independently accepted by the
second GPT-6.1 Sol review. The configured integration checkout contains the exact
accepted source at `fad3b8b2326b3ce717e9e8c791bd395dd18a3716`. Tests and debug/release
builds passed; both release APK signatures match the existing signer.

Both exact signed phone/Navigation candidates installed in place after the owner
supplied the current Wi-Fi ADB port. The glasses retain the `0aa3eb1b` HUD build.
Actual phone screenshots confirm the display layout at normal, 1.3x and 1.5x fonts,
exclusive choices, full explanations and readable native-app/quick-action notes.
The original font and Navigation choice were restored. No reset or environment
repair was used.

**The composer Back defect was corrected and passed current-artifact device QA.**
After the owner reconnected USB, the signed phone update containing `5226fccf`
was installed in place. On the Android 16 phone, first Back hid the IME without
ending capture; second Back closed capture and restored the prior unsent draft on
the glasses, without the temporary suffix. The compose notification disappeared.
Only the exact synthetic test draft was then cleared; no Send or microphone was
used. See [the current device report](PHONE_COMPOSER_BACK_DEVICE_20261018.md).

This resolves DEVICE-BACK-01 for the tested phone/installed glasses pairing.
The separate compact-glasses changes and final visual approval remain incomplete.

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

## Actual phone checks and remaining validation

| Check | Observed result |
| --- | --- |
| Current signed phone and Nav APK upgrade | Both install-r operations returned Success; hashes/results in ignored `install-r2.json` |
| Display at normal, 1.3x and 1.5x fonts | Explanations wrap completely; Automatic/Text/Minimap/Panel labels remain readable; real `phone-ui-after-display-*` screenshots |
| Choice persistence and exclusivity | Panel, Minimap and Text each selected alone; Automatic restored; ignored `choice-device-check.json` |
| Foreground modes and action grants | Native-app note captured at normal/1.5x, checklist/media notes and confirmation warnings captured without changing any grant |
| Keyboard with IME and long text | 629 synthetic characters; Cancel and Keep draft both inside the resized viewport after scrolling; ignored `composer-layout-device.json` |
| Cancel and Keep draft | Cancel restored the initially empty draft; Keep draft produced a right-side unsent synthetic draft; no Send action used |
| Android system Back | Initial pre-fix run failed; current signed phone retest passed the IME-hide, cancel, restored-draft and notification-dismissal checks. See the current device report. |

The failing Back image is unfortunately named
`phone-ui-fixture-back-keeps-draft.png`; its content shows the failure, not a pass.
The later `phone-ui-composer-back-current.png` shows the phone back on Relay
settings. The composer currently overrides legacy onBackPressed at target SDK 36;
the root cause and supported modern Back handling are assigned to Fable rather
than inferred as already fixed.

During explicit cleanup, a glasses capture-finish action committed the temporary
test suffix to an unsent synthetic draft. The parent reopened Type, verified the
known synthetic prefix, and cleared only that test draft using an empty Keep draft
commit. It did not press Send. The initial empty draft was restored and glasses
returned to native home. The older "Nexus smoke QA / handed to the app" bubble in
some images belongs to the previous smoke, not a send in this run.

Font scale was read back at its original 1.0, Navigation Automatic was restored,
other settings/grants were not changed, and no journey or audio capture started.
Synthetic harness history may remain locally. Raw proofs and screenshots stay in
ignored `build/phone-ui-ux/` and `build/smoke/`.

Remaining work: implement and independently review the Back correction, run the
affected suite/signed build, upgrade the phone in place, then repeat real Back
cancellation and draft-retention checks with the IME. No real message Send is needed.

Existing `hud-fixed-phone-*` images are BEFORE captures from `0aa3eb1b`.
`phone-ui-after-*` images show the installed Fable phone layout. The older OSM
gallery images predate these phone changes and the current HUD map correction;
their category is labeled historical. Private screenshots and raw evidence stay local.
