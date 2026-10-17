# Skills and Workspace written-mode device QA

Dates: 2026-10-08 and 2026-10-09. Branch: `qa/skills-workspace-typed-160`.
Implementation: `5693e897`, based on the previous QA baseline `5f396ca3`.

## Compatible device build

The preceding Reddit session had replaced the QA hubs with main's 1.6.0 hubs.
Those builds do not include Skills discovery, so Assistant and the Skills
providers returned to Pending when their requested capability sets changed.
The previous QA baseline already uses hub version 1.6.0; this was a feature
integration difference, not a version downgrade.

This checkout applies the phone, glasses, and shared native-app changes from a
read-only snapshot of main `72749524`: 17 tracked files and nine new source/test
files. The snapshot patch SHA-256 is
`27d54b55c634cf67bd7bc5fb08650fa078464811a8ad0a351de459c593214f17`.
It applies without conflicts and retains the Skills/Workspace implementation.
The launcher-rework branch was not merged. The original main checkout was not
edited, and the existing Reddit APK/account was not replaced or cleared.

Both installed hubs were backed up before signing and installation. The new
release APKs have the same single certificate as their installed predecessors;
both `adb install -r --user 0` updates returned `Success`, preserving app data.
Normal Plugin access controls restored Assistant and provider approvals.

## Automated verification

The command includes both hubs, so it deliberately omits `skipCxrGlobal`:

```powershell
.\gradlew.bat :shared:testDebugUnitTest :bus-client:testDebugUnitTest :phone-hub:testDebugUnitTest :glasses-hub:testDebugUnitTest :phone-hub:assembleDebug :glasses-hub:assembleDebug :phone-hub:assembleRelease :glasses-hub:assembleRelease :plugin-assistant:testDebugUnitTest :plugin-nav:testDebugUnitTest :plugin-transit:testDebugUnitTest :plugin-media:testDebugUnitTest --console=plain
```

Observed output tail:

```text
> Task :phone-hub:packageRelease
> Task :phone-hub:createReleaseApkListingFileRedirect
> Task :phone-hub:assembleRelease

BUILD SUCCESSFUL in 2m 5s
483 actionable tasks: 257 executed, 171 from cache, 55 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.1/userguide/configuration_cache_enabling.html
```

The XML reports contain 2,604 tests, with zero failures, errors, or skips:

| Module | Tests |
| --- | ---: |
| shared | 386 |
| bus-client | 149 |
| phone-hub | 748 |
| glasses-hub | 683 |
| Assistant | 446 |
| Navigation | 64 |
| Transit | 108 |
| Media Deck | 20 |

## Real written input

The Samsung phone (Android 16/API 36) and Rokid glasses (Android 12L/API 32)
were connected through USB ADB. Assistant retained Type first and its existing
provider, conversation, and voice-output settings.

Each question used Futo's actual AZERTY soft keys on the phone. The observed
transport was `StreamingEditText` -> Nexus editable Assistant field -> the
phone's normal `ENTER` button -> the Assistant pipeline. Before typing, the
helper verified an empty Nexus editor and the visible Assistant question field.
Phone editor text and glasses screenshots independently show the submitted
question. No debug question receiver was used for these checks, and no glasses
UIAutomator dump was used.

| Check | Observed result |
| --- | --- |
| Workspace answer | A question about the Boreal code returned `NIMBUS-3486`, citing `workspace-native-smoke.txt`. |
| Workspace process restart | After force-stopping Assistant and the system document-picker package, the same typed question returned the same code and source. |
| Media read | A typed question returned the silent Android fixture's `NEXUS-QA-TRACK-1` and `NEXUS-QA-ARTIST`. |
| Media pause | A separate ordinary pause question returned confirmation. The fixture logged the exact session's pause callback and a transition from playing (`3`) to paused (`2`). |
| Media reference after restart | The fixture was restarted and Media Deck force-stopped. A question referring to the previous player returned an expired-reference failure and said nothing was done. The replacement session remained playing; no additional pause callback appeared. |
| Transit start | A typed request to guide to Bibliotheque Francois Mitterrand started the real planned journey. Assistant rendered its itinerary as an Ink page, and Transit held its foreground service with types `0x40000008`. |
| View interruption | Back closed the itinerary Ink page while the journey chip remained active. This closed the view, not the journey. |
| Transit process restart | After force-stopping Transit, a typed progress question recovered the persisted destination, current walking leg, and arrival estimate. Its foreground service was active again. |
| Hub stop/restart | The phone's normal STOP HUB/START HUB controls removed the journey chip while stopped and restored it after restart. A subsequent typed progress question displayed the recovered journey in an Ink summary. |
| Transit stop | An ordinary written stop request returned `Guidage Transit arrete`. The journey chip disappeared and the service dump contained no Transit service record. |
| Media refresh after reconnect | A new written read returned the replacement fixture's current title and artist. |
| Workspace after reconnect | A new written question again returned `NIMBUS-3486` with the file source. |
| Missing Workspace fact | A written request for Quartz's signature date returned that no relevant excerpt mentioned it, without inventing a date. The frames did not independently establish whether `search_workspace` ran. |

The capture helper initially missed one successful Workspace answer because its
regex did not allow OCR's spaces around the hyphen. A repeated question captured
the actual code and source. It also did not recognize Transit's initial Ink
itinerary as the expected prose answer; the itinerary screenshot, later progress
answers, and foreground-service observations establish the action independently.
One immediate next-question attempt was blocked by the helper's ownership guard
while the previous answer was still active; it typed nothing and succeeded after
returning to the idle Assistant anchor. These are capture/precondition limitations,
not passing results inferred from a script exit code.

## Remaining acceptance

These checks establish written-input logic and stationary recovery. Actual speech
recognition, real boarding/transfers/missed connections/arrival and underground
progression still need their own acceptance. Navigation's recorded parser and
guidance tests passed, but a new live navigation-app route was not exercised here.
Media compatibility with real third-party players remains broader than the silent
Android MediaSession fixture. Workspace still needs another physical OEM provider
and a full reboot of the owner's phone. The exact `search_workspace` fallback
execution remains unclaimed unless separately observed; a truthful missing-fact
answer alone is insufficient evidence that the tool ran.

This is a local QA integration and does not declare all release acceptance complete.
No new delegated model review was requested or run.

## Cleanup

The temporary Transit guide/progress/stop approvals were restored to off and
verified from fresh UI switch state. Its existing read-operation approvals remain
available. Media's temporary read/pause approvals were restored to off; Offer
skills was disabled again to match the preceding QA handoff, and a subsequent
UI read confirmed it was off. Its ordinary surface approval and Android
notification access were retained. Assistant remains approved with Type first.

The silent fixture service was stopped and its QA-only package uninstalled;
uninstallation returned `Success`, and a package-path check confirmed removal.
Assistant was closed through the glasses Back action. Final service dumps showed
no running Assistant or Transit service. The phone was returned to Home, the
glasses to the Nexus launcher, and the explicit temporary XML/screenshot paths
were removed from both devices. Both USB devices were still connected at the end.

Workspace remains enabled with its existing native folder and smoke document;
this run introduced no new Workspace files. Original main-checkout native sources
were compared with the snapshot hashes and remained unchanged. The compatible
QA hubs remain installed. No main merge, push, or public release was performed.

Local APK backups, signed builds, screenshots, typed-input records, and the build
log are retained in the existing temporary QA directory for diagnosis.
