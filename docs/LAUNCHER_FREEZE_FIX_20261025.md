# Launcher freezer binding fix — 2026-10-25

Verdict: **DONE for implementation and host acceptance**. The Samsung S23 device
check belongs to the orchestrator and remains unverified.

Contract: `launcher-fix-freeze`, dated 2026-10-24. Worktree:
`E:\Tools\Rokid\RokidNexus-fx-freeze`, branch `fx/freeze`, base `f651994c`.
Implementation commit: `094a8fc8 Keep launcher command providers important until work completes`.

## Findings and changes

The base already used `BIND_AUTO_CREATE | BIND_IMPORTANT` in both
`AndroidExternalPluginRuntime` (including the page runtime) and
`AndroidSkillLeaseRuntime`. The page broker already retained its connection for
requests, display handoffs and visible leases. This lane does **not** establish
why the reported S23 froze Sample six seconds after binding while its page was
apparently still visible.

Two missing lifetimes are now covered:

- A negotiated activity command acquires an independent important binding to
  its approved owner before delivery. It uses a separate instance of the
  existing invocation runtime, so releasing it does not release a page, skill,
  camera or immersion connection. A failed bind leaves the command undelivered.
- A delivered page action remains part of the page broker's binding demand
  until its result or existing deadline, even if the visible lease expires
  first. Covering and closing retain their existing cancellation semantics.

The activity timer now includes the existing pending-command deadlines and
removes expired commands. Results, failed delivery, expiry, access loss,
disconnect, reconnect and hub stop release command bindings when their demand
ends. An idle activity has no command binding. Legacy activity actions keep
their old delivery path.

There are no changes to grants, capabilities, wire fields, deadline values,
glasses code or plugin foreground services. `local.properties` remains absent;
no SDK, Gradle home, dependency cache or build configuration was changed.
No push, merge, tag, ADB or device action was performed.

## Files touched

Production files under `phone-hub/src/main/java/com/anezium/rokidbus/phone/`:

- `AndroidSkillLeaseRuntime.kt`
- `BusHubService.kt`
- `PhoneActivityRouter.kt`
- `PhoneActivityState.kt`
- `PhonePageBroker.kt`

Tests under `phone-hub/src/test/java/com/anezium/rokidbus/phone/`:

- `AndroidExternalPluginRuntimeTest.kt`
- `AndroidSkillLeaseRuntimeTest.kt` (new)
- `PhoneActivityLauncherTest.kt`
- `PhonePageBrokerTest.kt`

Documentation: `BUSSPEC.md`, `CHANGELOG.md`, `docs/PLUGIN_SDK.md`,
`docs/LAUNCHER_COMPLETION_REPORT.md`, and this report. All 14 files are within
the contract scope. Plan 028a, plugin sources and plugin changelogs did not need
changes; the public contract and Sample behaviour are unchanged.

## Required verification

Commands ran in the named worktree through native PowerShell. `gradlew.bat` is
the Windows wrapper for the contract's `./gradlew` commands. Gradle output is
retained in the worktree's ignored `build/` directory.

```powershell
.\gradlew.bat :phone-hub:testDebugUnitTest :phone-hub:assembleDebug :bus-client:testDebugUnitTest --console=plain
```

Log: `build/freeze-hub-sdk.log`. Real tail:

```text
BUILD SUCCESSFUL in 1m 7s
139 actionable tasks: 30 executed, 10 from cache, 99 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.1/userguide/configuration_cache_enabling.html
```

JUnit XML: phone hub **1,037 tests**, bus client **195 tests**, each with **0
failures, 0 errors, 0 skipped**. Phone tests executed; the unchanged bus-client
test task was `FROM-CACHE`. Phone debug assembly executed successfully.

```powershell
.\gradlew.bat -PskipCxrGlobal=true :plugin-sample:testDebugUnitTest :plugin-sample:assembleDebug --console=plain
```

Log: `build/freeze-sample.log`. Real tail:

```text
BUILD SUCCESSFUL in 13s
82 actionable tasks: 19 executed, 21 from cache, 42 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.1/userguide/configuration_cache_enabling.html
```

JUnit XML: Sample **55 tests, 0 failures, 0 errors, 0 skipped**. The unchanged
Sample test task was `FROM-CACHE`; debug assembly executed successfully.

`git diff --check` and a scope/forbidden-path audit also passed.

## Targeted verification and mutation checks

The targeted command was:

```powershell
.\gradlew.bat :phone-hub:testDebugUnitTest --tests '*AndroidExternalPluginRuntimeTest' --tests '*AndroidSkillLeaseRuntimeTest' --tests '*PhonePageBrokerTest' --tests '*PhoneActivityLauncherTest' --console=plain
```

The first run exposed a new test fixture using `ServiceConnection.onNullBinding`
under Robolectric's old default Android API. Log: `build/freeze-targeted.log`.
Real output excerpts:

```text
AndroidSkillLeaseRuntimeTest > a refused bind leaves no hold and a dead binding releases its owner FAILED
    java.lang.NoSuchMethodError at AndroidSkillLeaseRuntimeTest.kt:90
66 tests completed, 1 failed
BUILD FAILED in 1m 38s
101 actionable tasks: 32 executed, 54 from cache, 15 up-to-date
```

The fixture now explicitly uses API 32. The same command passed with **66 tests,
0 failures, 0 errors, 0 skipped** (`build/freeze-targeted-retry.log`):

```text
BUILD SUCCESSFUL in 22s
101 actionable tasks: 2 executed, 99 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.1/userguide/configuration_cache_enabling.html
```

Mutation 1 temporarily removed both runtimes' `BIND_IMPORTANT`, restored the
old page demand without pending invocations, and bypassed command binding.
The same four-class command failed as expected (`build/freeze-mutation-holds.log`):

```text
66 tests completed, 12 failed
BUILD FAILED in 17s
101 actionable tasks: 4 executed, 97 up-to-date
```

Observed failing tests:

- `AndroidExternalPluginRuntimeTest`: `transient service disconnect preserves auto create binding` (flags assertion).
- `AndroidSkillLeaseRuntimeTest`: `commands inherit importance without releasing page or skill bindings` (flags assertion).
- `PhonePageBrokerTest`: `a delivered action keeps the provider important after the visible lease expires until its result`; `a delivered action releases the provider at its deadline without a result or visible lease`.
- `PhoneActivityLauncherTest`: `a command is bound to the visible seq and carries the phone's own type`; `a command is not delivered if its importance binding fails`; `command importance expires at the deadline even when the plugin never answers`; `a command needs exactly one live registration of its owner`.
- `PhoneActivityLauncherTest`: `an approved quick command runs and its result reaches the glasses once`; `command bindings are owner scoped and end with revoke disconnect reconnect or hub stop`; `a command binds before delivery and releases only after its own result`; `withdrawing a quick approval mid-flight drops the pending quick command's result`.

Mutation 2 restored the good bindings and temporarily removed command expiry
cleanup and command deadlines from the activity timer. Command:

```powershell
.\gradlew.bat :phone-hub:testDebugUnitTest --tests '*PhoneActivityLauncherTest.command importance expires at the deadline even when the plugin never answers' --console=plain
```

The named test failed with `expected:<1> but was:<null>` for the remaining
scheduled delay (`build/freeze-mutation-deadline.log`). Real tail excerpts:

```text
1 test completed, 1 failed
101 actionable tasks: 5 executed, 96 up-to-date
BUILD FAILED in 41s
```

Both mutations were fully restored before the required full suites. A further
failed-delivery cleanup test passed in the full phone suite. Expected mutation
failures are distinct from failures of the completed implementation.

## S23 checks for the orchestrator

1. Use the updated phone APK with the matching launcher-enabled glasses hub.
   Keep Nexus's normal hub foreground notification active. In Sample, leave an
   unchecked task named Eggs, publish the to-do activity, put Sample in the
   background and leave the phone idle long enough to reproduce Freecess.
2. Open Sample's to-do page from the glasses activity. Wait at least 12 seconds
   after opening, beyond the six-second refreeze in the report. With checklist
   quick actions disabled, select Eggs and confirm it. Require the result within
   the existing eight-second budget, with no unconfirmed message or delayed
   phone-side check about 20 seconds later. Repeat with quick actions enabled.
3. Capture the Sample service connections and process state while the page is
   visible, while its action is pending and after leaving it. Verify an active
   `BIND_AUTO_CREATE | BIND_IMPORTANT` page connection while its lease is valid;
   no freezer stop should occur during that demand. A visible page may keep its
   page binding after a result; closing the last page should release it.
4. Exercise a live Media Deck activity command while its app is backgrounded:
   confirmed and quick Play/Pause should answer within the existing deadline.
   Verify a separate command binding before delivery and release after result;
   an unanswered command must release at the existing eight-second deadline.
   Page, skill and immersion connections must survive command cleanup.
5. If Sample still freezes while a valid important page connection is present,
   retain the Freecess log timestamps, actual installed phone build, binding
   flags, foreground hub state and page lease state. The base already carried
   those page flags, so this would require further diagnosis of the specific
   OEM behaviour. This lane has neither proven binding impossible nor added a
   plugin foreground service.

Open question: was the page's important connection still present at
22:12:35.145 on the reported build? Host tests prove the lifetimes and flags;
they cannot prove Samsung Freecess honours them on that phone.
