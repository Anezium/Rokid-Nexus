# Issue #53 — device test results (2026-10-14)

Build: `fix/issue53-repair-recovery` at `6aa117ad` (code at `ac871772`), debug APKs
re-signed with the release key, installed over release 1.6.0 on an RV101
(fw 1.26.011-20260929-150201, fr-FR) and a Galaxy S23+ (SM-S916B). Plan:
`qa/issue53-repair-recovery-test-plan.md`.

## Passed

| Case | Evidence |
|---|---|
| 1.1–1.4 build, sign, install, link | signer `f5e938e2…`, `install -r` kept data, SPP authenticated, glasses shown as V1.7.0 |
| 2.1 Repair on a healthy unit | `verdict=already_healthy`; "Nothing to repair — the glasses' helper is running." |
| 2.2 Re-pair entry with Developer mode off | Settings → Maintenance → Re-pair glasses |
| 2.3 open Re-pair, back out | plain CLOSE only, no abandon |
| 3 issue state reproduced | helper `running=no`, `wireless_bootstrap_complete=false`, no arm record, accessibility bound |
| 4.1 Repair in the issue state | `helperRecord=ABSENT` → `self-pairing requested owner=true outcome=started` → `verdict=pairing_pending`; phone text "The glasses need to pair again, and have started doing it themselves." |
| 4.3 latch release | `self-pairing latch released reason=session_ended` after the run ends |
| 4.4 retry | second Repair → `outcome=started` again |
| 4.6 secrets | only `codeLen=6` logged; no code, port of the pairing server only as loopback |
| 5.1/5.2 consent | auto-repair off (phone switch) → every background request `outcome=auto_repair_off backgroundAttempt=0/6`, no automator start, across two service rebinds |
| 5.4 owner with auto-repair off | Re-pair/Repair still `outcome=started` |
| 6.1 preflight | "Glasses paired to maintain themselves" unchecked on the unpaired unit; healthy unit shows all checks and "Re-pair anyway" |
| 6.2 Re-pair | `Owner repair starting mode=pair` → `pairing_pending`; automation opens Settings |
| 6.4 cancel | back out mid-run → `close accepted=true` → `self-pairing cancelled by the owner`, latch released, lens back on Nexus; no resume after a later service rebind |
| 6.6 scoped cancel | Repair-now run kept going after opening/leaving Re-pair (reached `searching_pairing_code`) |
| **End-to-end on fw 1.26.011** | with the display kept awake the existing automator paired the unit by itself: `wireless_debugging_open` → pairing dialog → `self-pair KADB success` → `local self-pair bootstrap complete`, helper `running=yes` ×2 (twice, ~15 s) |

## Findings

1. **Display sleep stalls the automation (new, high).** With nobody wearing the
   glasses the panel sleeps after 5 s (vendor timeout); the run then loops
   `waiting_for_settings` until `wireless_setup_timeout` (~95 s). Neither the
   accessibility service nor the automator holds the display (no
   `DisplayWakePolicy` hold). Kept awake, the same run succeeds. Wearers who stop
   touching the touchpad will hit the same stall.
2. **The "4 px Developer-options list" is not a blocker for the service.** The
   automator's accessibility scroll reaches Wireless debugging on this firmware;
   the earlier conclusion came from shell swipes/uiautomator only. The
   Settings-search navigator is not needed for this firmware.
3. **system_server watchdog kill after cancelling a re-pair (new, high, not yet
   reproduced on purpose).** ~30 s after the 6.4 cancel, `system_server` blocked in
   `AdbDebuggingManager$AdbDebuggingHandler.handleMessage` → `Thread.join`
   (23:09:48), and the Watchdog killed it at 23:10:59 (`Blocked in handler on
   foreground thread (android.fg)`): Android userspace restarted, Nexus restarted.
   Suspected trigger: dismissing the pairing dialog (return to Nexus) while the
   KADB pairing handshake is open, so the platform's pairing-thread join never
   returns. ANR traces exist in `/data/anr/anr_2026-10-14-23-10-18-482` (not
   readable from shell).
4. **Verdict after reviving a dead helper says "already healthy" (8.2).** Helper
   stopped on a paired unit, Repair restarted it (`result=READY bridge=true`) but
   answered `already_healthy` because the record said alive this boot. The verdict
   does not use the pre-arm state.
5. Minor: a plain CLOSE with no manual session gets an `SPP TX /error` reply (2.3).
   The phone's auto-repair push overwrites the glasses-side preference (expected,
   but the plan's 5.1 step must use the phone switch).

## Round 6 on device (glasses build with cd41a405 + 94f5e719)

| Case | Evidence |
|---|---|
| Finding 1 fixed | panel `Asleep`, issue state, Repair from the phone, nobody touching the glasses: `setup display hold run=3 decision=acquire` → `self-pair KADB success` → `local self-pair bootstrap complete` in 10.5 s → `release reason=succeeded`; panel back to `Asleep`, `Wake Locks: size=0` |
| Cancel releases the hold | Re-pair anyway, back out at `wireless_debugging_open` → `self-pairing cancelled by the owner` → `release reason=stopped` 2 ms later; panel sleeps |
| Finding 4 fixed (8.2) | scripts stopped on a paired unit → `helper running before arm=false` → `verdict=repaired`; "Repaired. The glasses' helper is running again." |
| 2.1 still right | healthy unit → `helper running before arm=true` → `already_healthy` |
| Finding 3 | not reproduced in four deliberate attempts (cancel after KADB success, at `wireless_debugging_open`, at `searching_pairing_code`, exact 6.4 replay on an unpaired unit) nor during the round-6 cancel; kept as a watch item |

## Round 7 on device (glasses build at dddde636)

| Case | Result |
|---|---|
| H1 sleeping unit → Re-pair | pass: one `acquire … wake=true`, panel `Awake` for the whole 35 s run on the plain lock (14 renewals), paired, `release reason=succeeded`, panel back to `Asleep`, `Wake Locks: size=0` |
| H2 power button mid-run | pass: `release reason=wearer_slept` at 4.4 s, panel stays `Asleep` for 12 s, no lock held |
| H3 wearer relights | pass: `acquire … wake=false`, run continues to `self-pair KADB success` |
| H4/H5 end of run | pass: `release reason=succeeded`, `size=0` 15 s later |
| 8.9 Wi-Fi off, helpers running | **fail**: `bridge Wi-Fi enable answered=true`, then the arm runs at once → `SESSION_UNREACHABLE` → `verdict=arm_failed` ("could not restore the helper" while it was running); `Repair Wi-Fi restore skipped trigger=owner armed=false` leaves Wi-Fi on |
| 8.10 Wi-Fi off, helpers stopped | **fail** on the pre-existing Settings path: Wi-Fi enable automator → arm → `SESSION_UNREACHABLE` → `arm_failed`, Wi-Fi left on, helpers still dead |
| Same Repair seconds later, Wi-Fi on | `helper running before arm=false` → `repaired`: the Wi-Fi-off failures are a race between Wi-Fi coming up and the TLS session |

Observation: once, Developer options needed three scroll passes (`searching_wireless_debugging` ×16/×14/×14, 22 s) before the Wireless debugging row was found; the run still succeeded within its deadline.

## Round 8 on device (both hubs at b61a023e)

| Case | Result |
|---|---|
| 8.9 Wi-Fi off, helpers running | pass: `bridge Wi-Fi enable answered=true` → `Wi-Fi readiness=ready waitedMs=1707` → `helper running before arm=true` → `READY` → `Repair Wi-Fi restore … viaBridge=true observedOff=true owedKept=false` → `already_healthy`; `wifi_on=0` afterwards |
| 8.10 Wi-Fi off, helpers stopped | pass: `answered=false` → Settings Wi-Fi run (hold 1.9 s) → `readiness=ready waitedMs=2594` → `helper running before arm=false` → `READY` → restore `observedOff=true` → `repaired`; helpers running, `wifi_on=0` |
| H6 wearer sleep then service recreation | pass: `release reason=wearer_slept`, accessibility service rebound, panel `Asleep` for 12 s with no acquire; relit → new service `acquire … wake=false`, pairing completes |

Edge seen while setting up H6: a power press in the ~1 s gap between the Wi-Fi-enable run and the bootstrap run is not observed by any renewal, so the next run acquires with `wake=true` once.

## Round 9 on device (both hubs at 639d3470)

| Case | Result |
|---|---|
| 8.9 Wi-Fi off, helpers running | pass: `answered=true` → `readiness=ready waitedMs=1737` → `READY` → `Repair Wi-Fi restore trigger=owner applied=true viaBridge=true observedOff=true` → `already_healthy`, `wifi_on=0` |
| 8.10 Wi-Fi off, helpers stopped | pass: `answered=false` → `readiness=ready waitedMs=2511` → `helper running before arm=false` → `READY` → restore `observedOff=true` → `repaired`, helpers running, `wifi_on=0` |
| 8.14 radio turned off by someone else during the repair | pass: `Repair Wi-Fi claim dropped reason=radio_turned_off_elsewhere` → `readiness=no_network waitedMs=20119` → one `arm retry result=SESSION_UNREACHABLE` → restore `result=dropped … wifiLeftAsIs=true` → `verdict=wifi_unavailable` |
| 8.11 text | "Could not reach the glasses over Wi-Fi, so their helper may not have been checked or restored…" |

Not exercised on device: 8.13 (native app during the arm) and 8.15 (setup started during the readiness wait).

## Round 10 on device (glasses at 196b4092)

| Case | Result |
|---|---|
| 8.9 regression | pass: `readiness=ready waitedMs=1696` → `helper running before arm=true` → restore `viaBridge=true observedOff=true` → `already_healthy`, `wifi_on=0` |
| 8.10 regression | pass: `readiness=ready waitedMs=2535` → `helper running before arm=false` → restore `observedOff=true` → `repaired`, helpers running |

The setup-during-bridge-wait fallback veto is covered by `SelfArmBootRepairPolicyTest` only; it could not be timed by hand on the device.

## Not run

6.3 rotation, 6.5, 7 (service recreation), 8.3–8.8, 9 (phone code form), 10
(compatibility matrix), 11 (navigator — superseded by finding 2).

## State left

Unit re-paired by itself (new identity), helper running, auto-repair back on,
accessibility bound, test build installed on both devices.
