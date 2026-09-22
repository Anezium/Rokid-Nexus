# HUD interaction review follow-up

Review baseline: `cf424fa4`, compared with `117c0ce3`.

## Resolved findings

- **P1 — SDK callbacks after an SPP interruption.** Removed the link-state
  transition that incorrectly marked the notice as hidden. CXR-only operation
  and SPP recovery now preserve the current question. Explicit hide, replacement,
  registration changes and matching close callbacks still retire the appropriate
  context. A reset on reconnect would also reopen an explicitly hidden notice,
  so it is not used.
- **P2 — owner hide after a missing replacement show.** The trusted hub's global
  sequence orders hides. A newer hide clears the actually displayed instance,
  including when a replacement was lost or failed image decoding; stale hides
  remain rejected. An older hide cannot cancel a newer pending image decode.
- **P3 — close callback contract.** BUSSPEC and PLUGIN_SDK now explain that
  callbacks belong to the current local question. In particular, hide followed
  immediately by show can suppress the old owner-close callback. No exactly-once
  callback is promised for each show call.
- **P3 — protocol maintenance.** Invalid close reasons are logged and ignored;
  same-owner re-shows no longer report a redundant replacement close. The SDK
  client token stays in canonical phone state and owner callbacks, and is absent
  from glasses-bound show/update/hide payloads.
- **P3 — accessibility and compatibility.** Ink's Select action uses an app-owned
  resource id. Relay's changelog describes the frozen countdown label on older
  hubs that reject `rearm = false`; the send countdown still proceeds.

## Validation

Before the SDK fix, the new CXR-only and SPP-recovery regressions both failed:
32 notice tests ran, with 2 failures. After removing the incorrect transition,
the same 32 tests passed. Other regressions cover an explicit hide across
reconnection, hide/show callback filtering, missing replacement shows, late
hides, pending image decoding, and phone-only client-token retention.

The final combined verification passed **2,063 tests**, with zero failures,
errors or skips: Ink 57, shared 316, SDK 127, phone 568, glasses 616, Relay 77,
Assistant 288 and Sample 14. This is 11 more tests than the review baseline.

```powershell
.\gradlew.bat :ink-engine:test :shared:test :bus-client:testDebugUnitTest :bus-client:assembleDebug :phone-hub:testDebugUnitTest :phone-hub:assembleDebug :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug :plugin-relay:testDebugUnitTest :plugin-relay:assembleDebug :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug :plugin-sample:testDebugUnitTest :plugin-sample:assembleDebug --console=plain
```

Observed final output:

```text
BUILD SUCCESSFUL in 12s
293 actionable tasks: 6 executed, 287 up-to-date
```

The targeted hardware regression below was retested at `66176aaf`. The earlier
fuller hardware evidence still belongs to the review baseline. Shared lint was
not rerun; its known `PropertyEscape` failure in unchanged `local.properties` remains.
Release versions, tags and publication are still pending; notice-v5 hubs must
be deployed together, and plugins need the rebuilt SDK for callback filtering.

### Device retest: Relay reply after an SPP-only interruption

On 2026-09-22, both hubs and Relay were rebuilt and upgraded in place on the
connected Samsung phone (API 36) and Rokid glasses (API 32), preserving app data.
Relay includes the corrected SDK from `66176aaf`.

```powershell
.\gradlew.bat :phone-hub:assembleDebug :glasses-hub:assembleDebug :plugin-relay:assembleDebug --console=plain
```

Observed build tail:

```text
BUILD SUCCESSFUL in 2s
168 actionable tasks: 168 up-to-date
```

The existing Relay test harness posted a synthetic MessagingStyle notification.
Reply-by-typing and a 45-second notice timeout were temporarily enabled so the
callback could be observed without recording audio or sending a message.

1. The glasses displayed the notice at 14:19:11.931, with Reply selected.
2. An attached debugger invoked the existing `SppServerManager.closeCurrent()`
   method, without changing the APK. While the socket was closed, debugger
   queries returned `CxrBusBridge.isUp() = true` and `isConnected() = false`
   for SPP. The phone observed the socket failure at 14:19:37.702 and reconnected
   at 14:19:39.404. Screenshots show the same notice before and after the blip.
3. One injected DPAD_CENTER activation reached `/notice/action`; Relay opened
   `/surface/show`, visibly displaying the typing field and `Typing… · Back to
   cancel`. The glasses received the surface at 14:19:45.418. No additional
   notice show, update or Relay registration occurred between the original show
   and the answer. The cosmetic typing update arrived only after the answer.

**Result: pass for the reported P1 on the installed revision.** This was an
injected activation on real devices, not a new wearer gesture test. It does not
cover total-link recovery, long disconnects, lost rearming updates, or the R08
ring. The debugger briefly paused the glasses process during fault injection.

The reply was cancelled without entering or sending text. The synthetic
notification was removed, the five changed Relay preferences were restored and
verified, and the debugger and its ADB forward were removed. The final probe
reported `spp=true cxr=true`; the glasses returned to the Nexus launcher. No
Nexus crash or microphone start was observed in the session logs.

## Separate follow-ups

### Relay: an old hide fallback can close the next notice's client

Confirmed in the source before `cf424fa4`; unchanged in this follow-up.
`RelayNoticeRuntime.dismissNotice()` schedules a 500 ms fallback guarded only by
`activeNotice`. Dismiss A, receive its close, then show B within 500 ms: A's timer
can close B's client and erase its reply context. B may remain visible but inert;
the fallback does not itself send a hide for B.

Suggested fix: capture `showGeneration` before hiding A and require that same
generation in the fallback, as already done for `SENT_LINGER`. Regression targets:
B shown at 100 ms survives A's 500 ms fallback; A still closes on the fallback
when no close callback or replacement arrives. No external issue was posted.

### Ring tap followed by a swipe during the 350 ms delay

The current implementation answers the action captured at key-down even if the
selection changes before resolution. The state test explicitly requires this;
the baseline read selection at resolution time. This is a gesture-policy choice,
not a demonstrated cross-question reply. A full delayed-gesture test and wearer
check should decide whether movement should cancel the tap or freeze selection.

### A rearming update lost between phone and glasses

Mutation before transport delivery and the absence of recovery predate the
review. Strict question identity now rejects an old answer instead of allowing
an old, reused action id to answer a new question. Follow up with a dropped-Q2
update test and a defined bounded invalidation/resynchronization policy. Do not
blindly roll back or retry an answer after ambiguous delivery.

### Phone notice expiry after total link loss

`PhoneNoticeState.expireIfDue()` and `expiryDeadlineMs()` currently have no
production callers. If both transports disappear, the glasses cannot deliver
their disconnect close and canonical phone notice state can outlive its TTL.
This pre-existing recovery gap belongs with the transport follow-up above;
the SPP-only retest does not cover it. Add a total-link-loss regression and a
bounded phone-side expiry or reconciliation policy before claiming that recovery
path is covered.
