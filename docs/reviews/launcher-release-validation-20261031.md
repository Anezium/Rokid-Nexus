# Launcher release validation — 2026-10-31

This is a bounded validation of the current `dev/launcher-rework` working tree,
not a release or approval of every historical hardware-matrix entry.

## Main integration

- Starting HEAD: `86f13fbbfc638ac1e07639ce758f088808dfad52`.
- Integrated `origin/main` through `046bcca0` (file-transfer source-read timeout),
  including `ec0331d4` (Hi Rokid scene/reconnection) and the R08 helper recovery fixes.
- Merge commits: `a6fa3384`, `db5ec3fe`. The only conflicts were hub version fields;
  the launcher's prepared `1.9.0` / `10900` was retained rather than downgraded to `1.8.3`.
- Existing tracked and untracked local work was saved in stash
  `e4b4ed5be3ddd41eb541a2101c9c148c61f0f4e3`, then reapplied without conflicts.
  The stash and `backup/launcher-before-main-20261031` remain available.
- No push, tag, release or external message was performed. The UI/SDK work remains
  in the working tree; these QA APKs are not final tagged release artifacts.

## Observed automated validation

All 19 production module suites were requested in one serialized Gradle invocation:
shared, Ink, KADB, SDK, both hubs and every plugin. XML results contain 5,011 cases:
**4,999 passed, 12 skipped, zero failures or errors**. The twelve skipped Patcher
cases require POSIX file permissions and explicitly skip on Windows. Some unchanged
tasks were up-to-date or restored from Gradle's cache; this was not a forced rerun.

Additional checks passed:

- R08 watchdog shell recovery/idempotency/opt-out tests, using Git Bash and mocked
  Android services; this is not a physical reboot test.
- Patcher preparation scripts: 11 Python unit tests.
- `git diff --check` after main integration.

Real output tails:

```text
BUILD SUCCESSFUL in 2m 4s
451 actionable tasks: 62 executed, 5 from cache, 384 up-to-date

BUILD SUCCESSFUL in 59s
366 actionable tasks: 28 executed, 6 from cache, 332 up-to-date
```

The second invocation built signed release APKs for both hubs, Media Deck,
Navigation and Sample. Every selected APK's signing certificate was checked before
successful in-place installation on the S23 and glasses. Application data was retained.
No build environment, SDK path, signing identity or dependency cache was changed.

Ignored local logs and totals:

- `build/whole-lines/release-tests-20261031.log`
- `build/whole-lines/release-build-20261031.log`
- `build/whole-lines/release-test-totals-20261031.json`

## Observed device checks

| Scenario | Observation |
| --- | --- |
| Fable menus | Media Deck's SDK settings link opens Floating Activities. The custom five-mode dialog opens and cancels. Existing On changes preference is retained. |
| Card quick commands | Fresh taps enter controls and run Play/Pause. Android's YouTube Music controller confirms PLAYING then PAUSED, within 0.9 seconds on the first run and 0.1 seconds on the post-reconnect run. |
| Integrated player | Cover, full title/artist, progress and controls are present in the captured player. One Back from focused player controls returns to the containing Nexus root. |
| Phone process outage | With the player open, force-stopped only the phone hub for more than 60 seconds. Its page retained readable last-known content with Retry/Back. Reopening the phone hub restored the player; a freshly opened root showed live commands. Playback remained paused; subsequent explicit Play/Pause both worked. Bluetooth and ADB settings were untouched. |
| Temporary floating band | A real media next-track event showed the compact cover/title band. It disappeared after its window while the activity card remained in Nexus. |
| Groceries opt-in | Turned Keep in activities off: a freshly opened root omitted Groceries. Restored it to on: the retained card returned with the same two outstanding items. No grocery item was added, removed or checked. |
| Relay local reply | Posted only Relay's synthetic Mika thread. Phone typing appeared live on the glasses. Keep draft left a dashed, explicitly unsent bubble. A separate focus tap and fresh Send delivered `QA_release_20261031` to Relay's local FakeReplyReceiver, which confirmed the exact 19-character text. No real recipient was contacted. |

Captures are under `build/smoke/shots/qa31-*`. Clips are under
`build/release-qa-20261031/videos/`. Glasses clips are timestamped screencap
sequences, not optical recordings: the firmware's screenrecord virtual display
does not capture these overlays. Android captures can omit unchanged areas; they
do not supersede the owner's earlier confirmation that content stays visible
through the physical glasses.

At cleanup, playback was paused, both devices were returned to Nexus, the original
Groceries opt-in was restored, and no phone screen recorder remained running.

## Remaining release boundaries

Follow-up after the owner's Relay report: withdrawn notifications no longer stay
promoted on the root, including while the root is open or covered by a page.
They remain in Notifications history. Surviving selection follows its identity;
a replacement for a withdrawn selected preview requires a newly drawn contact.
The full glasses suite then passed **1,390 tests**, zero failures/errors/skips,
and the signed glasses APK was installed. Real output:

```text
BUILD SUCCESSFUL in 1m 15s
223 actionable tasks: 3 executed, 220 up-to-date
```

Device verification posted only Relay's synthetic thread and snoozed that exact
Android notification: its preview disappeared from both the open and reopened
root. The synthetic notification was subsequently dismissed from the phone's
notification shade. Evidence: `build/smoke/shots/relay-withdraw-*.png` and
`build/release-qa-20261031/videos/relay-withdraw-glasses.mp4`. No production app
notification, playback state, grocery data or permission was changed by this test.

The earlier all-module counts above remain evidence for that earlier invocation;
the follow-up reran the affected glasses suite, not all nineteen modules again.

- The twelve POSIX-only tests still need their normal Linux CI run.
- Older-peer/Classic fallback and ownership/replay cases have automated coverage;
  this pass did not attach an older phone or perform a physical ring gesture test.
- Navigation's full live-walk/GPS-degradation matrix, microphone failure cases,
  phone typing recreation and an updated R08-helper reboot were not repeated here.
  Historical device evidence is linked from `docs/LAUNCHER_COMPLETION_REPORT.md`.
- Known memory-only individual notification withdrawals across phone death remain
  documented in the completion report. Durable owner purges are a separate path.
- Final shipping still needs source commits, version/changelog reconciliation,
  CI and builds from the exact release tag before publishing. No new full-branch
  independent review was run during this validation pass.
