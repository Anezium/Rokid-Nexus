# Launcher device smoke, 2026-10-18

The later [OSM street-basemap smoke](NAV_OSM_DEVICE_SMOKE_20261018.md) supersedes
the route-only Navigation screenshots below for street-map appearance. This
earlier launcher smoke did not establish a surrounding street basemap.

The authorized device smoke is complete. Three defects found on hardware were fixed:
ambient foreground selection under an accessibility bridge, provider lifetime during
page navigation, and duplicated walking guidance. The affected suites passed, and
the final signed APKs were exercised on the connected glasses and Android 16 phone.
This is bounded device evidence, not acceptance of every remaining release matrix.

Source baseline: `5e520f23`. Final runtime source: `b8edbddd` on
`dev/launcher-rework`. Builds ran only in the configured integration checkout.
The implementation worktree is synchronized after the documentation commit. Root
main, unrelated files, accounts, pairing, signing identity and build configuration
were preserved. No push, release, tag or external message was performed.

## Fixes and independent review

| Observed failure | Change | Verification |
|---|---|---|
| An accessibility bridge overlay replaced the native home package, hiding an ambient activity under SESSION policy | `70c7ead9`, `a62ca5e9`: sample application windows instead of trusting the event package | Home/native-app/home smoke; policy and adapter regressions |
| A front application with an unreadable root could expose a readable home window behind it | `7b17b26a`: retain the previous foreground when the winning application is unreadable | Pure policy and real adapter Robolectric regressions; independent R2 clearance |
| Applications -> Relay -> first conversation returned Page unavailable | `198947e3`: request destination demand before covering the source; on Back, lease the return page before closing the detail and refreshing | Regression-only run failed exactly the two new ordering cases; green suite; clean-APK first open, repeated Back/reopen, and Back during loading |
| Native walking guidance repeated the same distance and instruction | `b8edbddd`: suppress equal complete formatted fields before clipping, retaining distinct and degraded fields | Eight new regressions; Nav suite; second real route on the final Nav APK |

Fresh independent GPT-6.1 Sol reviews found no remaining actionable blocker in the
combined foreground/page patch (`5e520f23..7b17b26a`) or the subsequent two-file Nav
patch. These were static reviews, not independent device runs or a new whole-product
acceptance. Their local records are `build/smoke/SMOKE-FIXES-SOL-REVIEW-R2.md` and
`build/smoke/NAV-GUIDANCE-SOL-REVIEW.md`. The first foreground review's P2 was fixed
before the fresh combined review. Temporary package tracing and Relay diagnostic
instrumentation were removed before the final builds and installs.

## Observed builds and tests

```powershell
.\gradlew.bat :shared:test :bus-client:testDebugUnitTest :plugin-relay:testDebugUnitTest :phone-hub:testDebugUnitTest :glasses-hub:testDebugUnitTest :phone-hub:assembleDebug :glasses-hub:assembleDebug :plugin-relay:assembleRelease :glasses-hub:assembleRelease
```

Actual result in `build/smoke/logs/takeover-final-green.log`:

```text
BUILD SUCCESSFUL in 2m 28s
372 actionable tasks: 21 executed, 5 from cache, 346 up-to-date
```

```powershell
.\gradlew.bat :plugin-nav:testDebugUnitTest :plugin-nav:assembleDebug :plugin-nav:assembleRelease -PskipCxrGlobal=true
```

Actual result in `build/smoke/logs/nav-guidance-green.log`:

```text
BUILD SUCCESSFUL in 27s
204 actionable tasks: 18 executed, 186 up-to-date
```

JUnit XML totals: **3033 tests, zero failures, errors or skips**: shared 494,
bus-client 192, Relay 128, phone hub 936, glasses hub 1146, Navigation 137.
These are the affected suites; historical whole-repository totals in the completion
report are separate observations. Some Gradle tasks were up-to-date or cached as the
actual tails state. No build environment workaround was used.

Release signatures were verified against the existing pinned signer before in-place
replacement. Final glasses hub and Relay APKs came from `7b17b26a`; final Nav came
from `b8edbddd`. Their runtime sources are unchanged by later documentation. The
already installed phone hub, Sample and Media Deck were not unnecessarily replaced
after these glasses/Nav-only production changes. APKs and backups remain ignored
local artifacts; version codes were not raised for publication.

## Device results

| Scenario | Observed result | Local evidence under `build/smoke/` |
|---|---|---|
| Link, launcher and lists | Authenticated phone/glasses connection; root/app icons; selection follows a long application list; Back returns to native home | `shots/final-native-home-clean.png`, earlier launcher screenshots |
| Foreground policy and rollback | SESSION ambient activity appears at home, hides over native Settings after the allowed signal flare, and returns at home. Explicit LEGACY preserves its island over Settings; SESSION restored | `shots/s3-legacy-home-confirmed.png`, `shots/s3-legacy-native-confirmed.png`, `shots/s7-native-app-after-signal.png` |
| Relay page handoff | Final clean build opens the fresh synthetic conversation from Applications on the first attempt, with no repost/Retry workaround; repeated returns and Back while loading work | `shots/final-relay-first-open-fixed.png`, `shots/final-relay-repeat-1.png`, `shots/final-relay-after-fast-back.png`, `logs/final-relay-phone.log` |
| Type and Dictate | Explicit phone keyboard produces a retained unsent draft. The wearer's retry produces a dictation draft from glasses PCM; the stream stops before completion | `shots/s4-human-retry-result.png`, `logs/dictate-human-retry-phone.log` |
| Fresh Send | First tap focuses Send without sending; a new contact sends the synthetic text to Relay's own FakeReplyReceiver. Phone harness reports reply received (14 chars), exact text `Nexus smoke QA`. HUD says handed to the app, with our message on the right | `shots/send-test-send-focused.png`, `shots/send-test-after-fresh-send.png`, `logs/send-test-phone-after-send.xml` |
| Exact notification withdrawal | Snoozing the exact synthetic Android notification disables Reply, retains the user's draft inert, and removes the active preview. Timed expiry restores the notification; manual unsnooze was denied by Android and is not claimed | `shots/s4-exact-notification-withdrawn.png`, `shots/s4-root-withdraw-placeholder.png` |
| Owner revocation | Revoking Relay removes captured sender/preview from the frozen root, leaving a neutral placeholder. Repeating while disconnected, killing/restarting the phone hub, then reconnecting delivers the durable purge | `shots/s5-owner-revoked-root.png`, `shots/s5-durable-revocation-after-hub-restart.png`, `logs/s5-durable-phone.log` |
| Public todo activity | Opt-in activity opens its page inside Nexus, changes a checked state with confirmation, and reflects the result. Task values and opt-in are restored | `shots/s6-todo-page.png`, `shots/s6-todo-checked.png`, `logs/s6-todo-final-off.xml` |
| Media Deck lifecycle | Activity tap focuses transport controls; explicit Open app enters the full surface; closing it retains monitoring/activity. No playback command was issued | `shots/s6-media-focused.png`, `shots/s6-media-full-app.png`, `shots/s6-media-after-close.png` |
| Navigation | A real planned route to the owner's chosen public station renders route geometry, Follow/Overview full map, Text/Minimap/Panel ambient modes and saved size. Stale GPS is honestly degraded. Final APK removes duplicated native guidance | `shots/s7-fullmap-follow.png`, `shots/s7-fullmap-overview.png`, `shots/s7-minimap-native-home.png`, `shots/final-nav-native-guidance-dedup.png` |
| Outage and recovery | Separate 60-second Bluetooth outages preserve an open chat/draft and a route as readable inert snapshots. Explicit Retry restores fresh content; nothing automatically sends | `logs/s8-outage-state.json`, `logs/s8-route-outage-state.json`, corresponding `s8-*` screenshots |

Only the built-in fake notification and local fake reply receiver were used for Send;
no messaging account or real recipient was contacted. This establishes Android
RemoteInput handoff, not delivery or reading by another person. The initial speech
attempt returned no speech; the wearer then retried successfully. Wear detection as
the cause of the first failure was not independently established. The successful
audio log records stream start 10:43:41.786, stop 10:43:46.475, and completion
10:43:46.740, local device time.

## Cleanup and evidence boundaries

Bluetooth is on, the phone hub is running, and the glasses are back at native home.
Both test journeys were stopped; no NavJourneyService remains. Navigation's ambient
size is restored to Automatic. Temporary location and notification grants are revoked.
Android retains ONE_TIME/history metadata on the denied location permissions; no
permission database or global permission reset was used to erase that history.

Relay's original three capabilities and activity visibility are restored, with
pinning still off. Its typing/read-aloud/long-message preferences were preserved.
Sample todo values and its Show switch, and Media Deck's Show switch, are restored.
Media playback was not changed. The synthetic send completed the test draft; synthetic
conversation/harness history can remain locally. Evidence:
`logs/final-device-state.json`, `logs/s5-relay-grant-restored.xml`,
`logs/s6-todo-final.xml`, `logs/s6-media-restored-off.xml`,
`logs/s7-nav-size-restored.xml`, `logs/s7-nav-permissions-restored.txt`.

Screenshots, raw logs, APKs, audio diagnostics and private route geometry remain in
ignored local storage. They must not be committed or published with this report.
The historical untracked contract was preserved unchanged.

The following acceptance remains unobserved in this smoke: physical walking and
transit progress/ribbon; physical ring behavior and optical comfort; replacement
old-phone/offline-boot combinations and both independent transport ordering cases;
the full API 30/32 keyboard launch matrix; process death during typing/dictation;
all classic-inbox send modes and clear-after-reply combinations; uninstall and
multiple-owner purge variants; Media Skills live/stale player choice and actual
transport commands. ADB-injected contact/key events establish the exercised input
path, not the entire physical gesture matrix. Existing review nits and the SDK/plugin
version publication gates in the completion report remain open unless separately
resolved. Targeted patch reviews do not close the older whole-product review gate.
