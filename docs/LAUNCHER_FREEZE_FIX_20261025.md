# Launcher freeze fix, rounds 2–3 — 2026-10-25

Verdict: **DONE for implementation and host verification**. The S23 checks below
are pending with the orchestrator; no device result is claimed.

The [round-3 follow-up](#round-3-follow-up) records the three LOW review fixes,
their mutation checks, and the final host suites. The round-2 evidence below
is retained as history.

Contract: `launcher-fix-freeze`, 2026-10-24, with the orchestrator's round-2
authorization to reuse the SDK session foreground service for pages and commands.
Worktree: `E:\Tools\Rokid\RokidNexus-fx-freeze`, branch `fx/freeze`.
Round-1 commits `094a8fc8` and `b4d7af14` remain in the branch.

## Why round 1 was insufficient

The independent review was correct: pages already had `BIND_IMPORTANT`, and the
pending-action hold does not explain or fix the 22:12 freeze. Commit `0404260e`
already documented Freecess freezing a plugin despite that bind, and verified
the plugin's own session foreground service as the exemption. Commands/actions
use a oneway registration callback, which a frozen process silently queues.

## Changes

- `NexusPluginService` reuses its existing quiet **Active on your glasses**
  notification and `specialUse` type for page request/display handoffs, visible
  page leases, and delivered actions/commands through result sending. A null
  command result remains protected until its asynchronous result or existing
  deadline. One local timer observes the existing durations; no wire deadline
  changed. Page cover, close, expiry, lost link/support, registration reset/loss
  and service destruction release page demand. Unavailable or unsent page
  answers release the request hold.
- Surface, audio and explicit ongoing-work holds compose with that demand.
  Page/command events do not open or close an immersion or replace an active
  feature's foreground types. Releasing ongoing work restores session-only
  types when a page still needs the service. Android foreground-start failures,
  including absent manifest declarations, remain non-fatal, as on the surface
  path. No plugin manifests changed.
- A command and each accepted page action make a fresh bind before callback
  delivery. A replacement bind is acquired before dropping the old connection,
  preserving the service's registration; a refused refresh preserves the old
  hold. Existing round-1 result/deadline holds remain independent of other
  session bindings. An important binding is not described as a Freecess exemption.
- Refused command binds log the failure and still deliver to the one live
  registered owner. A dead command connection releases only its pending command
  and hold; real activity disconnection remains owned by the registration path.
  A replacement command updates a retained hold's invocation even if its refresh
  is refused before the previous deadline tick ran.
- SDK/BUS docs and SDK 0.24.0 release notes now describe these lifetimes and the
  non-fatal fallback. The stale round-1 implementation report is replaced here.

First-party plugins already declare the session foreground service, but their
APKs must be rebuilt and installed against the updated SDK. Updating only the
hub cannot add foreground protection to an old installed plugin binary.

No grants, capabilities, wire fields, deadline values, glasses code, build
configuration or machine environment changed. No push, merge, tag, ADB or device
operation was performed. Plan 028a and plugin changelogs are unchanged: no
public page/wire contract or plugin source changed, and the plan is outside
the lane's allowed paths.

## Implementation commits

- `72057558` Protect launcher pages and commands with the session foreground service
- `8fbb0da3` Thaw launcher deliveries without dropping live commands or activities
- `064b30a0` Track replacement commands when a retained binding cannot refresh

## Files touched in round 2

- `BUSSPEC.md`
- `CHANGELOG.md`
- `bus-client/src/main/java/com/anezium/rokidbus/client/plugin/NexusPluginClient.kt`
- `bus-client/src/main/java/com/anezium/rokidbus/client/plugin/NexusPluginService.kt`
- `bus-client/src/main/java/com/anezium/rokidbus/client/plugin/PageModels.kt`
- `bus-client/src/test/java/com/anezium/rokidbus/client/plugin/NexusPluginServiceForegroundTest.kt`
- `docs/LAUNCHER_COMPLETION_REPORT.md`
- `docs/LAUNCHER_FREEZE_FIX_20261025.md`
- `docs/PLUGINS.md`
- `docs/PLUGIN_SDK.md`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/AndroidExternalPluginRuntime.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/AndroidSkillLeaseRuntime.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/BusHubService.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/PhoneActivityRouter.kt`
- `phone-hub/src/main/java/com/anezium/rokidbus/phone/PhonePageBroker.kt`
- `phone-hub/src/test/java/com/anezium/rokidbus/phone/AndroidExternalPluginRuntimeTest.kt`
- `phone-hub/src/test/java/com/anezium/rokidbus/phone/AndroidSkillLeaseRuntimeTest.kt`
- `phone-hub/src/test/java/com/anezium/rokidbus/phone/HubLauncherBindingTest.kt`
- `phone-hub/src/test/java/com/anezium/rokidbus/phone/PhoneActivityLauncherTest.kt`
- `phone-hub/src/test/java/com/anezium/rokidbus/phone/PhonePageBrokerLauncherTest.kt`
- `phone-hub/src/test/java/com/anezium/rokidbus/phone/PhonePageBrokerTest.kt`

## Required verification

All commands ran from this worktree using the Windows Gradle wrapper.
Each test summary below has zero failures, errors and skipped tests.

```powershell
.\gradlew.bat :phone-hub:testDebugUnitTest :phone-hub:assembleDebug :bus-client:testDebugUnitTest --console=plain
```

Log: `build/freeze-r2-final-hub-sdk.log`. Real tail:

```text

BUILD SUCCESSFUL in 46s
139 actionable tasks: 10 executed, 129 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.1/userguide/configuration_cache_enabling.html
```

**phone-hub: 1045 tests**; **bus-client: 206 tests**.

```powershell
.\gradlew.bat :bus-client:testDebugUnitTest --console=plain
```

Log: `build/freeze-r2-final-sdk.log`. Real tail:

```text

BUILD SUCCESSFUL in 3s
29 actionable tasks: 29 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.1/userguide/configuration_cache_enabling.html
```

**bus-client: 206 tests**.

```powershell
.\gradlew.bat -PskipCxrGlobal=true :plugin-sample:testDebugUnitTest :plugin-sample:assembleDebug --console=plain
```

Log: `build/freeze-r2-final-sample.log`. Real tail:

```text

BUILD SUCCESSFUL in 8s
82 actionable tasks: 9 executed, 73 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.1/userguide/configuration_cache_enabling.html
```

**plugins/sample: 55 tests**.

```powershell
.\gradlew.bat -PskipCxrGlobal=true :plugin-media:testDebugUnitTest :plugin-sample:testDebugUnitTest :plugin-relay:testDebugUnitTest :plugin-nav:testDebugUnitTest :plugin-transit:testDebugUnitTest --console=plain
```

Log: `build/freeze-r2-final-plugins.log`. Real tail:

```text

BUILD SUCCESSFUL in 47s
160 actionable tasks: 39 executed, 43 from cache, 78 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.1/userguide/configuration_cache_enabling.html
```

**plugins/media: 78 tests**; **plugins/nav: 218 tests**; **plugins/relay: 169 tests**; **plugins/transit: 130 tests**; **plugins/sample: 55 tests**.

The final full hub/SDK suites executed after restoring every mutation.
The standalone SDK rerun and Sample in the combined plugin run were up-to-date;
the Media, Relay, Nav and Transit unit suites executed. Gradle emitted existing
deprecation warnings.

## Regression and mutation checks

New tests cover page request promotion, visible lease renewal/expiry, cover and
close, multiple frames, action promotion through the result, synchronous and
asynchronous commands, null/throwing handlers, lost registration/link, teardown,
surface/ongoing-work composition and type preservation, non-fatal Android
foreground rejection, new bind events, refused binds, and stale connection
callbacks. `HubLauncherBindingTest` exercises the actual hub host's owner
resolution, runtime factory, principal map, fresh bind and death callback;
router-only fakes are not the only evidence.

The final targeted run before mutation had 11 SDK tests and 74 phone tests,
zero failures (`build/freeze-r2-targeted-verified.log`). The retained replacement
invocation regression was then added and passed in the final full phone suite.
The development runs found malformed new test payloads (cover lease fields,
activity identity/action fields, and a missing activity kind); these were fixed.
No existing production test was weakened.

Mutation commands (the ignored script restores original bytes in `finally`):

```powershell
$env:PYTHONUTF8='1'; $env:PYTHONIOENCODING='utf-8'
python build/freeze-r2-mutations.py
python build/freeze-r2-mutations.py hub-delivery hub-binding-death
```

The first script invocation completed the SDK mutation, then stopped before
running the hub tests because its replacement expected LF while one source had
CRLF. The script restored all files; newline handling was corrected before the
hub mutation retry. This was a harness replacement error, not a Gradle result.

The script's exact Gradle commands and observed failures follow.

### sdk-promotion

Disable page/command foreground reconciliation, reproducing the old service policy.

```powershell
.\gradlew.bat :bus-client:testDebugUnitTest --tests '*NexusPluginServiceForegroundTest' --console=plain
```

Observed **7 failures / 11 tests**, exit 1.
Log: `build/freeze-r2-mutation-sdk-promotion.log`. Tests observed failing:

- `com.anezium.rokidbus.client.plugin.NexusPluginServiceForegroundTest: a page request promotes before callback and uses the existing special use notification`
- `com.anezium.rokidbus.client.plugin.NexusPluginServiceForegroundTest: renewed visibility expires on the local lease and closing one frame preserves another`
- `com.anezium.rokidbus.client.plugin.NexusPluginServiceForegroundTest: an asynchronous command keeps promotion until its explicit result or registration loss`
- `com.anezium.rokidbus.client.plugin.NexusPluginServiceForegroundTest: page demand ends on cover close or the request handoff timeout`
- `com.anezium.rokidbus.client.plugin.NexusPluginServiceForegroundTest: an unavailable page releases its request promotion after the answer`
- `com.anezium.rokidbus.client.plugin.NexusPluginServiceForegroundTest: commands promote before handling and demote only after sending their result`
- `com.anezium.rokidbus.client.plugin.NexusPluginServiceForegroundTest: a page action stays foreground through result send after its visible lease expires`

Real tail:

```text
For more on this, please refer to https://docs.gradle.org/9.5.1/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD FAILED in 27s
29 actionable tasks: 4 executed, 25 up-to-date
```

### hub-delivery

Restore connection reuse, omit page-action rebind, and restore command suppression after a refused bind.

```powershell
.\gradlew.bat :phone-hub:testDebugUnitTest --tests '*AndroidExternalPluginRuntimeTest' --tests '*AndroidSkillLeaseRuntimeTest' --tests '*PhonePageBrokerTest' --tests '*PhoneActivityLauncherTest' --tests '*HubLauncherBindingTest' --console=plain
```

Observed **8 failures / 75 tests**, exit 1.
Log: `build/freeze-r2-mutation-hub-delivery.log`. Tests observed failing:

- `com.anezium.rokidbus.phone.AndroidExternalPluginRuntimeTest: page delivery rebind replaces a held connection with a fresh bind event`
- `com.anezium.rokidbus.phone.AndroidExternalPluginRuntimeTest: a refused page rebind preserves the existing service hold`
- `com.anezium.rokidbus.phone.AndroidSkillLeaseRuntimeTest: a refused command rebind keeps the previous hold until its normal release`
- `com.anezium.rokidbus.phone.AndroidSkillLeaseRuntimeTest: delivery rebind creates a new connection and ignores an obsolete binding death`
- `com.anezium.rokidbus.phone.HubLauncherBindingTest: hub command glue resolves the approved owner rebinds and isolates connection death from activity death`
- `com.anezium.rokidbus.phone.PhoneActivityLauncherTest: a refused command binding still delivers to its live registration and accepts the result`
- `com.anezium.rokidbus.phone.PhoneActivityLauncherTest: a refused refresh of a retained command hold tracks the replacement invocation`
- `com.anezium.rokidbus.phone.PhonePageBrokerTest: each page action rebinds before oneway delivery even with a visible page hold`

Real tail:

```text
For more on this, please refer to https://docs.gradle.org/9.5.1/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD FAILED in 21s
101 actionable tasks: 8 executed, 2 from cache, 91 up-to-date
```

### hub-binding-death

Restore the production hub callback that clears the activity on command-binding death.

```powershell
.\gradlew.bat :phone-hub:testDebugUnitTest --tests '*HubLauncherBindingTest' --console=plain
```

Observed **1 failures / 1 tests**, exit 1.
Log: `build/freeze-r2-mutation-hub-binding-death.log`. Tests observed failing:

- `com.anezium.rokidbus.phone.HubLauncherBindingTest: hub command glue resolves the approved owner rebinds and isolates connection death from activity death`

Real tail:

```text
For more on this, please refer to https://docs.gradle.org/9.5.1/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD FAILED in 16s
101 actionable tasks: 5 executed, 96 up-to-date
```

## S23 verification for the orchestrator

These steps are not executed by this lane. Replace `S23_SERIAL` with the phone's
ADB serial. Keep its existing battery settings and normal hub foreground
notification; do not force-stop the plugin, which tests registration loss rather
than the Freecess scenario.

1. Install the rebuilt phone hub and Sample APK, and use the matching launcher
   glasses hub. Also build/install Media Deck against this SDK for command
   checks (`.\gradlew.bat -PskipCxrGlobal=true :plugin-media:assembleDebug`). In
   Sample create an unchecked **Eggs**, publish its to-do activity, then background
   the phone UI. Capture logs while waiting for the dormant plugin's natural
   `FreecessController: FZ ... reason: Bg`:

   ```powershell
   adb -s S23_SERIAL logcat -v threadtime FreecessController:V FreecessHandler:V ActivityManager:I NexusPluginService:V '*:S' > s23-freeze-r2.log
   ```

2. Open Sample's to-do page on the glasses. Before touching the phone UI, inspect
   `adb -s S23_SERIAL shell dumpsys activity services com.anezium.rokidbus.plugin.sample`
   and `adb -s S23_SERIAL shell dumpsys activity processes`. Expect the plugin's
   own session service to be foreground (`specialUse`, normally `0x40000000`),
   using its one existing session notification, as well as the important page
   connection. Wait **12 seconds**, past the original six-second refreeze, then
   select Eggs and confirm it with quick actions disabled. Require an outcome
   within the existing **8 seconds**, with no unconfirmed result or delayed
   execution about 20 seconds later. Reset the item and repeat with checklist
   quick actions enabled.

3. Leave the page visible for **150 seconds**, crossing a lease renewal. Check
   that the own foreground service persists, and correlate action-time new bind
   events with any `UFZ ... reason: bindService`. Specifically verify that a
   fresh connection to an already-bound service can thaw it on this firmware;
   host tests cannot establish this OEM behaviour. Cover the page, uncover it,
   and Back out of the last page. With no open surface/audio/ongoing work,
   require demotion and page-connection release. Repeat open/Back twice to catch
   orphan holds. While an immersion or audio/ongoing-work hold is also active,
   leaving the page must preserve that independent foreground reason.

4. With rebuilt Media Deck backgrounded, publish its activity and exercise
   confirmed and quick Play/Pause from the glasses after **12-second** idle
   gaps. Observe a fresh command bind before delivery, a transient own session
   foreground service during handling/result send, and command-bind release
   after result. Use a test/debug callback returning null to observe the full
   **8-second** asynchronous hold, its explicit-result demotion, and expiry
   without a result. Verify the activity and any page/skill/immersion remain.

5. Use a test plugin without the special-use declaration: page and command
   callbacks must still run without a foreground-transition crash, while no
   Freecess exemption is promised. Exercise a refused/null keep-alive bind with
   a live registration: commands must still be attempted and connection loss
   must not end its activity; a real registration/process death must retain its
   existing activity-end/offline behaviour. Save installed build versions, the
   service/process dumps and the Freecess/result timestamps on any failure.

Open questions are limited to this hardware validation: whether the S23 firmware
thaws on the new connection while another hold exists, and whether the updated
SDK protection eliminates the original 22:12 timeline. The existing surface
device result in `0404260e` supports the mechanism; it does not substitute for
testing these new page/command paths. No round-2 device success is claimed.

## Round 3 follow-up

Verdict: **DONE for implementation and host verification**. R2-1, R2-2 and
R2-3 are fixed. Device verification remains with the orchestrator.

### Changes

1. **R2-1:** after an accepted action rebind, the page broker records the
   connection even if the earlier page-request and visibility binds were
   refused. Reconciliation releases it after the result and visible lease end,
   or at the existing action deadline. Tests cover both release paths.
2. **R2-2:** foreground refresh runs inline on the main looper and posts the
   same reconciliation runnable otherwise. Promotion still precedes main-thread
   callbacks. A worker-thread async-result test observes no foreground transition
   until the main looper runs, checks the transition thread, and covers a new
   page arriving before that queued refresh.
3. **R2-3:** an explicit plugin stop is remembered while page/command demand
   wins, and re-promotes with session-only types immediately. Internal demand
   promotion preserves the stop request, so even an open ADOPTED session stops
   once its last page/command hold ends. A new explicit feature promotion
   supersedes the request. SDK tests mirror Relay's ADOPTED stop and Transit's
   location-stop decision, including overlapping page/async-command holds,
   lease expiry, later pages, type preservation and new ongoing work.
4. CHANGELOG and PLUGIN_SDK now describe the fresh connection without promising
   a thaw; verification on the S23 is explicitly pending. The SDK guide also
   documents deferred explicit stops. No wire, deadline, manifest, grant,
   plugin source or machine configuration changed.

### Files touched in round 3

- `phone-hub/src/main/java/com/anezium/rokidbus/phone/PhonePageBroker.kt`
- `phone-hub/src/test/java/com/anezium/rokidbus/phone/PhonePageBrokerTest.kt`
- `bus-client/src/main/java/com/anezium/rokidbus/client/plugin/NexusPluginService.kt`
- `bus-client/src/test/java/com/anezium/rokidbus/client/plugin/NexusPluginServiceForegroundTest.kt`
- Documentation: `CHANGELOG.md`, `docs/PLUGIN_SDK.md`,
  `docs/LAUNCHER_COMPLETION_REPORT.md`, `docs/LAUNCHER_FREEZE_FIX_20261025.md`.

### Final suites

Commands ran from this worktree with the Windows Gradle wrapper. The focused
pre-mutation run passed 42 page-broker and 16 foreground-lifecycle tests:

```powershell
.\gradlew.bat :phone-hub:testDebugUnitTest --tests '*PhonePageBrokerTest' :bus-client:testDebugUnitTest --tests '*NexusPluginServiceForegroundTest' --console=plain
```

Observed tail (`build/freeze-r3-targeted.log`):

```text
BUILD SUCCESSFUL in 43s
106 actionable tasks: 12 executed, 94 up-to-date
```

All mutations were restored before the final suites below. All seven module
unit-test tasks executed; the later Sample-only test task was up-to-date.
Counts come from the resulting JUnit XML: **1,908 tests, zero failures/errors
or skips**. Gradle emitted existing deprecation warnings.

```powershell
.\gradlew.bat :phone-hub:testDebugUnitTest :phone-hub:assembleDebug :bus-client:testDebugUnitTest --console=plain
```

Phone hub **1,047**, bus client **211**. Observed tail
(`build/freeze-r3-hub-sdk.log`):

```text
BUILD SUCCESSFUL in 59s
139 actionable tasks: 11 executed, 5 from cache, 123 up-to-date
```

```powershell
.\gradlew.bat -PskipCxrGlobal=true :plugin-media:testDebugUnitTest :plugin-sample:testDebugUnitTest :plugin-relay:testDebugUnitTest :plugin-nav:testDebugUnitTest :plugin-transit:testDebugUnitTest --console=plain
```

Media **78**, Sample **55**, Relay **169**, Navigation **218**, Transit **130**.
Observed tail (`build/freeze-r3-plugins.log`):

```text
BUILD SUCCESSFUL in 28s
160 actionable tasks: 14 executed, 146 up-to-date
```

The contract's additional Sample test/build command also passed:

```powershell
.\gradlew.bat -PskipCxrGlobal=true :plugin-sample:testDebugUnitTest :plugin-sample:assembleDebug --console=plain
```

Observed tail (`build/freeze-r3-sample.log`):

```text
BUILD SUCCESSFUL in 3s
82 actionable tasks: 2 executed, 80 up-to-date
```

### Round-3 mutation checks

The ignored `build/freeze-r3-mutations.py` removes one fix per run and restores
the original source bytes in `finally`. Invocation:

```powershell
$env:PYTHONUTF8='1'; $env:PYTHONIOENCODING='utf-8'
python build/freeze-r3-mutations.py
```

R2-1 removed the successful-action-bind tracking. Command:

```powershell
.\gradlew.bat :phone-hub:testDebugUnitTest --tests '*PhonePageBrokerTest' --console=plain
```

Observed **2 failures / 42 tests**, exit 1. Both regressions failed:
`an accepted action bind after a refused page bind releases on result and lease expiry`
and `an accepted action bind after a refused page bind releases at the action deadline`.
Observed tail (`build/freeze-r3-mutation-R2-1.log`):

```text
BUILD FAILED in 10s
101 actionable tasks: 5 executed, 96 up-to-date
```

R2-2 removed main-looper dispatch. R2-3 restored the early-return stop policy,
removing stop-request recording and type downgrade. Each ran:

```powershell
.\gradlew.bat :bus-client:testDebugUnitTest --tests '*NexusPluginServiceForegroundTest' --console=plain
```

R2-2: **1 failure / 16 tests**, exit 1:
`a worker command result queues foreground reconciliation on the main thread`.
R2-3: **3 failures / 16 tests**, exit 1:
`a feature stop removes the location type while retaining page protection`,
`an adopted session stop waits for both the page and asynchronous command holds`,
and `an adopted session stop takes effect when a visible page lease expires`.
Both logs (`build/freeze-r3-mutation-R2-2.log` and
`build/freeze-r3-mutation-R2-3.log`) ended with:

```text
BUILD FAILED in 9s
29 actionable tasks: 4 executed, 25 up-to-date
```

### Remaining device gate

No open implementation questions remain. Run the five S23 steps above with
rebuilt plugin APKs; the fresh-connection thaw premise is still unverified.
Also check Relay adopting a band while one of its pages is visible: protection
must remain until page/command demand ends, then demote without waiting for
`onClose`. For Transit, stopping location while a page is visible must remove
the `location` type while retaining `specialUse`; an active journey must keep
its location type. No ADB, device action, push, merge or tag was performed.
