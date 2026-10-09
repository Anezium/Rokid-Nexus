# Native Patcher implementation review

Reviewed on 2026-10-09 after the approved Fable design and two implementation
rounds delegated to Claude Opus 5.5 (`claudeAgent`, `claude-opus-5-5`).

## Delivered behavior

Patcher's settings home presents YouTube and Reddit as equal app targets, with
their official mark geometry adapted to Nexus green. Reddit remains Preview.
The screens reuse the existing `NexusUi` phone widget kit, palette, typography,
cards, header and system-inset handling. Signing-key maintenance and the canonical
plugin uninstall entry remain on the home screen.

The complete YouTube setup is reached through Patcher -> YouTube: MicroG,
official source, patch/install, sign-in, the Nexus keyboard switch and collapsed
Advanced Morphe/import actions. Glasses apps retains ordinary inventory and Open
actions but no YouTube setup entry; it has a passive location hint. Reddit's
existing hub setup route is retained.

The privileged tutorial is still a non-exported hub activity. A filterless
explicit entry authenticates the approved Patcher caller in activity-result
mode and permits only the fixed YouTube target. Patcher keeps empty capabilities;
no trusted `/core/*` route is exposed to plugins. The authenticated APK-result
handoff, hub validation, inventory confirmation and manual sign-in checklist stay
under hub authority. A job-state hint is informational, never install evidence.

The parent found and requested correction of a key-maintenance/patch race.
`PatchJobStore` now atomically excludes key maintenance and preparation/patch
starts across activities in the shared `:patcher` process. The in-memory lease
is released in `finally`, rejects stale lease release, and cannot survive process
death. Tests include contention, late picker results, a failed then successful
test-key import and subsequent job retry. The production key format and material
are unchanged.

## Observed verification

The parent inspected actual source, entry authentication tests, job-store guards,
JUnit XML, native renders, APK metadata, file hashes and real command-output tails.
`git diff --check` returned exit code zero.

| Module | Total | Passed | Skipped | Failures / errors |
|---|---:|---:|---:|---:|
| Patcher | 157 | 145 | 12 | 0 / 0 |
| Phone hub | 736 | 736 | 0 | 0 / 0 |
| Shared | 365 | 365 | 0 | 0 / 0 |
| Bus client | 131 | 131 | 0 | 0 / 0 |
| Glasses hub | 683 | 683 | 0 | 0 / 0 |
| Total | 2072 | 2060 | 12 | 0 / 0 |

The Patcher suite was rerun after round 2. Other suites passed in round 1 and their
sources were unchanged in round 2. The Patcher tests use the frozen preview23
Reddit bundle; environment-dependent opt-in checks remain skipped.

Commands observed:

```text
.\gradlew.bat :plugin-patcher:testDebugUnitTest :plugin-patcher:assembleDebug
  -PskipCxrGlobal=true
  -PredditPatchBundleInput=E:/Tools/Rokid/reddit-patch-lab/final-preview23/reddit-source-preview23.mpp

.\gradlew.bat :phone-hub:testDebugUnitTest :phone-hub:assembleDebug :shared:test
  :bus-client:testDebugUnitTest :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug

.\gradlew.bat --no-daemon :plugin-patcher:assembleRelease
  -PskipCxrGlobal=true
  -PredditPatchBundleInput=E:/Tools/Rokid/reddit-patch-lab/final-preview23/reddit-source-preview23.mpp

.\gradlew.bat --no-daemon :phone-hub:assembleRelease
```

Actual final release output tails:

```text
Patcher:
BUILD SUCCESSFUL in 1m 19s
123 actionable tasks: 40 executed, 4 from cache, 79 up-to-date

Phone hub:
BUILD SUCCESSFUL in 1m 41s
180 actionable tasks: 22 executed, 158 up-to-date
```

The existing authorized release-signing reference and keystore were read only.
The password was supplied only in the Gradle subprocess environment, never in
arguments or logs. Both new APK certificates match the reference and previous
release APKs:

`f5e938e2e79b0526b31e40d36c8c19098450c1636b7e14a306681b4effddf81c`.

The Claude permission guard initially refused the release command as a
Secret-Store Writes operation. After inspecting the script's read-only credential
use, the parent's approval-reviewed command was accepted and both builds finished.
No secret store, machine environment, SDK path, Gradle home or signing configuration
was modified.

## Release artifacts

Verified copies, public manifest and build logs are in
`build/outputs/patcher-nexus-ui-release-20261009/`.

| APK | Version | SHA-256 |
|---|---|---|
| `plugin-patcher-release.apk` | 1.0.0 / 1 | `84791de935f90bbbd4bac890c07ac682b31c6aff1655ac69a76de9936a5f02be` |
| `phone-hub-release.apk` | 1.6.0 / 10600 | `4362d12fd5a64612b447906a2a67881b0d3cd22e1b863d7b03ff4380142ef208` |

Update the phone hub and Patcher together for the new activity entry. The glasses
hub has no functional change from this UI task and needs no new installation.

## UI evidence and limits

Native Robolectric renders at 360 dp were visually inspected, including home and
overview with 130% font sizing. The gallery includes the unchanged final YouTube
overview, MicroG attention state and sign-in tutorial, copied under
`native-renders/`. These are local test states, not screenshots of connected
devices. First-round home/patch captures retain the earlier locality wording and
are not presented as final screenshots.

Known implementation limits: Patcher retains one app's source/result at a time,
with explicit confirmation before switching; patch choices remain app-specific.
The official source picker stays in Patcher's patch screen. Install progress uses
the existing hub controller messages/checklist, and reopening a standalone result
for hub installation can require an extra action.

No APK was installed during this task. Physical-device stack navigation, MicroG,
installation and keyboard behavior for this new UI remain unverified. No real
login, Reddit write, vote or save occurred. No commit, publication, release upload,
bundle-pin change or unrelated worktree reset occurred. The approved HTML and
DESIGN.md hashes are unchanged.
