# Phone composer Back: current-artifact device validation

Date: 2026-10-18. Result: **DEVICE-BACK-01 passed** on the connected Android 16
phone and the existing glasses build. The owner explicitly reconnected the devices
for continued QA. USB was used directly; no USB mode change, ADB server reset,
uninstall or data clearing occurred.

## Source, build and installed artifact

The phone was built from configured `dev/launcher-rework` at
`f020d151b3536a5177d35ab92a8b82faa2d93c2b`. It contains Fable's production
Back fix `5226fccf` and the independently reviewed modern-Back fixture correction.
The eleven incomplete compact UI/parser files in the isolated author worktree
were not copied, compiled, integrated or installed. Glasses remain on `0aa3eb1b`;
Navigation remains on the earlier accepted phone-UI candidate `fad3b8b2`.

Observed command, without skipCxrGlobal:

```powershell
.\gradlew.bat :phone-hub:testDebugUnitTest :phone-hub:assembleDebug :phone-hub:assembleRelease --no-parallel
```

Actual tail:

```text
BUILD SUCCESSFUL in 5s
302 actionable tasks: 1 executed, 301 up-to-date
```

The already successful 952-case phone test output was up-to-date, including the
five Back regression cases; this is not a claim that all tests executed again.
The established release signing reference was supplied only to the subprocess.
The APK was read from output-metadata.json, verified with apksigner against the
existing pinned certificate, hashed, copied locally and installed with install -r.
Actual install output:

```text
Performing Streamed Install
Success
```

APK SHA-256: `3e5ab0f191d2295da7565803dfa7861d649b310b95ec35ffae7b3aa2010e9bfe`.
Signer SHA-256: `f5e938e2e79b0526b31e40d36c8c19098450c1636b7e14a306681b4effddf81c`.
Full local artifact record: `build/phone-ui-ux/phone-back-usb-artifact.json`.
No signing material, APK or private device identity is committed.

The package replacement stopped the phone hub. Its normal Start hub button was
used to restore the running hub before creating a new synthetic Relay notification.
The first fixture posted while the hub was stopped did not create a live glasses
conversation; only the later live fixture was used for the Back test.

## Actual test and result

Relay's own local Post thread harness produced the demonstration conversation;
its reply receiver has no external recipient. Read notifications aloud, Reply by
typing and Longer for long messages were observed false and left unchanged.
No Dictate or Send action was activated.

1. Opened the live Mika chat on the glasses, deliberately selected Type and opened
   the phone keyboard. The initial draft was empty.
2. Typed `Back QA retained draft.` and chose Keep draft on the phone. The glasses
   displayed that exact text as an unsent draft, establishing the prior draft.
3. Selected Type again and appended ` Temporary change.`. The phone editor and live
   capture contained the complete temporary text.
4. Pressed Android system Back once. The IME disappeared (`mInputShown=false`),
   PHONE KEYBOARD remained open, and the live capture retained the edited text.
5. Pressed Back again. The phone returned to Relay settings; the glasses left typing
   and displayed exactly `Back QA retained draft.` with the temporary suffix gone.
   The compose-keyboard notification was absent from the active notification list.

Screenshots were captured after each step. The older `Nexus smoke QA` handoff bubble
was already present before this test; it is not a Send produced by this run.

Current actual evidence under `build/smoke/shots/`:

- `phone-back-usb-before-first-back.png`: editor with the temporary suffix and IME.
- `phone-back-usb-after-first-back.png`: IME hidden; composer remains open.
- `phone-back-usb-glasses-restored-draft.png`: original unsent draft restored.
- `phone-back-usb-synthetic-draft-cleared.png`: no test draft or Send button remains.
- `phone-back-usb-final-native-home.png`: glasses returned to native home.

Machine-readable results: `back-usb-first-back.json`, `back-usb-second-back.json`,
`back-usb-test-state.json` and `back-usb-final-state.json`, under
`build/phone-ui-ux/`. The browser-openable capture gallery now contains 35 actual
images, including these three Back proof stages:
`build/smoke/nexus-captures-20261018.html`.

## Cleanup and limits

Reopened only the exact synthetic draft, verified its text, cleared it and chose
Keep draft with an empty editor. The glasses returned to the two Dictate/Type
buttons with no draft. This did not delete or alter a real user's draft. The
synthetic notification/history may remain as local test data.

Final observed state: phone Main, hub running, glasses native home, font scale 1.0,
Navigation location/notification permissions denied as before, no journey service,
no active compose notification and no synthetic draft. No microphone, new message
Send, journey or GPS request occurred. Accounts, settings and signers were retained.

This accepts the tested phone Back behavior only. Compact glasses rendering,
marquee continuity, external-name regressions and the faithful multi-activity HTML
still require the paused Fable work and fresh acceptance. The complete compact
suite still has one known glasses assertion failure at its last tested committed
pin. No physical navigation/map appearance claim follows from this phone test.
