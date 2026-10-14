# Issue #53 — repair recovery: device test plan

Branch `fix/issue53-repair-recovery`. Covers the three owner-approved items
(observable/retriable pairing request, Settings → Maintenance → Re-pair glasses,
repair verdict from real helper health) and the Settings-search navigator that
will replace the Developer-options traversal on firmware 1.26.011.

Device serials are operator-local. Set them first (Git Bash):

```bash
export MSYS_NO_PATHCONV=1
G="adb -s $ROKID_GLASSES_SERIAL"    # RV101 on USB
P="adb -s $ROKID_PHONE_SERIAL"      # phone on USB
GPKG=com.anezium.rokidbus.glasses
PPKG=com.anezium.rokidbus.phone
```

The glasses screen sleeps after ~5 s: send `$G shell input keyevent KEYCODE_WAKEUP`
before every screenshot or UI dump. Never use `monkey` (it flips auto-rotate); start
activities with `am start -n`.

Log capture for every case (run in a second shell, read back only the slice you need):

```bash
$G logcat -c; $P logcat -c
$G logcat -v time -s ROKIDBUS NexusWirelessSetup > /tmp/i53-glasses.log &
$P logcat -v time -s ROKIDBUS > /tmp/i53-phone.log &
```

Pass criteria are written as the log line or UI text that must (or must not) appear.

---

## 0. Preconditions and safety

| # | Check | Expected |
|---|---|---|
| 0.1 | No other session is using the devices (ListAgents, `dumpsys package` lastUpdateTime) | nobody else installing or testing |
| 0.2 | Record installed versions and signer on both devices | note versionName/versionCode |
| 0.3 | Helper alive before starting: `$G shell 'cd /data/local/tmp; sh rokid-nexus-a11y-watchdog.sh status; sh rokid-nexus-cmd-bridge.sh status'` | `running=yes` twice |
| 0.4 | Glasses firmware: `$G shell getprop ro.build.version.incremental` | `1.26.011-…` (the issue's firmware) |

**Recovery recipe** (when a case leaves the helper dead and the automation cannot
pair on this firmware) — the same manual arm the reporter used, over USB adb:

```bash
$G shell 'cd /data/local/tmp && sh rokid-nexus-a11y-watchdog.sh start && sh rokid-nexus-a11y-watchdog.sh repair && sh rokid-nexus-cmd-bridge.sh start'
```

**Rollback**: the official v1.7.0 release APKs (same versionCode 10700) install over
the test build with `install -r` and keep data.

## 1. Build and install

The installed hubs are release-signed (`CN=Anezium`, SHA-256 `f5e938e2…`). A debug
APK cannot upgrade them without wiping data, so the test build is the debug APK
re-signed with the release key (it stays debuggable, so `run-as` works for
inspecting prefs). Follow the signing recipe (Windows paths, two password files,
never silence apksigner).

| # | Step | Expected |
|---|---|---|
| 1.1 | `./gradlew :phone-hub:assembleDebug :glasses-hub:assembleDebug` in the worktree (hubs without `-PskipCxrGlobal`) | BUILD SUCCESSFUL |
| 1.2 | zipalign + apksigner both APKs with `nexus-release` | `apksigner verify --print-certs` → `f5e938e2…` |
| 1.3 | `$G install -r glasses.apk`, `$P install -r phone.apk` | `Success`, versionName 1.7.0, data kept (onboarding not reset) |
| 1.4 | Link comes back (CXR + SPP), glasses accessibility service still enabled | `ROKIDBUS` link logs, `settings get secure enabled_accessibility_services` lists Nexus |

## 2. Baseline on a healthy unit

| # | Action | Expected |
|---|---|---|
| 2.1 | Phone Settings → **Repair now** | glasses `Owner repair starting mode=repair …`, verdict `already_healthy`; phone shows the healthy text |
| 2.2 | Phone Settings → Maintenance → **Re-pair glasses** visible with Developer mode **off** | entry present |
| 2.3 | Open Re-pair, press nothing, back out | **no** CLOSE with `abandonSessionId` in phone log; no `self_pairing_cancelled` on glasses |

## 3. Reproduce the issue's state (helper dead, glasses unpaired, no arm record)

This is what a reinstall leaves behind, without uninstalling (keeps the accessibility
grant). Do it only after 0.3 passed so the recovery recipe is known to work.

```bash
# stop the helper
$G shell 'cd /data/local/tmp; sh rokid-nexus-cmd-bridge.sh stop; sh rokid-nexus-a11y-watchdog.sh stop'
# drop the glasses' own pairing identity and arm record
$G shell am force-stop $GPKG
$G shell run-as $GPKG rm -rf files/kadb-tls
$G shell run-as $GPKG sh -c 'ls shared_prefs; cat shared_prefs/selfarm_wireless.xml'
```

Then edit `shared_prefs/selfarm_wireless.xml` through `run-as` so that
`wireless_bootstrap_complete=false` and the `armed_*` keys are removed, restart the hub
(`am start -n $GPKG/<launcher activity>` or reconnect), and confirm:

| # | Check | Expected |
|---|---|---|
| 3.1 | helper status | `running=no` twice |
| 3.2 | prefs | `wireless_bootstrap_complete` false, no `armed_boot_instant` |
| 3.3 | accessibility still enabled | listed in `enabled_accessibility_services` |

## 4. Item 1 — pairing request is observable and retriable

| # | Action | Expected |
|---|---|---|
| 4.1 | Repair now | glasses `Owner repair starting … helperRecord=ABSENT`, `self-pairing requested owner=true outcome=started`, `Owner repair verdict=pairing_pending`; phone text "The glasses need to pair again, and have started doing it themselves…" |
| 4.2 | Watch the glasses | automation drives Settings. **Before the navigator lands** it is expected to fail on fw 1.26.011 (4 px Developer-options list) |
| 4.3 | After the run ends | `self-pairing latch released reason=session_ended` (or similar end reason) |
| 4.4 | Repair now again | a new `outcome=started` — never silence, never `already_in_flight` once the previous run ended |
| 4.5 | Repair now while a run is still active | `outcome=already_in_flight` or `busy`, logged |
| 4.6 | Every request | one `self-pairing requested owner=… outcome=… backgroundAttempt=n/6` line; no pairing code, port or IP in any log |

## 5. Background consent (auto-repair preference)

| # | Action | Expected |
|---|---|---|
| 5.1 | Phone: turn automatic repair **off**. Glasses: force a service reconnect (`am force-stop $GPKG`, relaunch) | `self-pairing requested owner=false outcome=auto_repair_off`; **no** Settings screen appears on the lens |
| 5.2 | Repeat 5.1 three times | still no takeover; budget counter not consumed |
| 5.3 | Automatic repair **on**, reconnect | `outcome=started` (takeover allowed), counter increments, stops at 6/6 with `budget_exhausted` |
| 5.4 | With auto-repair off, Repair now | owner request still `outcome=started` |

## 6. Item 2 — Re-pair glasses (phone maintenance entry)

| # | Action | Expected |
|---|---|---|
| 6.1 | Settings → Maintenance → Re-pair glasses (Developer mode off) | preflight shows "Glasses paired to maintain themselves" **unchecked**; screen does not dead-end on "set up" + Cancel |
| 6.2 | Press the main Re-pair button | glasses log `mode=pair`; reply carries a session id (phone log); glasses automation starts — nobody navigates by hand |
| 6.3 | Rotate the phone during the run | run continues, no abandon sent |
| 6.4 | Back out of the screen while pending | glasses `abandon … session matches` → session ended `self_pairing_cancelled`; lens returns to Nexus; no later takeover when Wi-Fi toggles or the service reconnects |
| 6.5 | Back out before the reply arrives | no abandon (no id adopted); run ends on its own within 45 s running / 5 min paused |
| 6.6 | Start a pairing via Repair now, then open Re-pair and back out without pressing | the Repair-now run is **not** cancelled |
| 6.7 | Plain Manual setup screen on an onboarded unit | offers "Helper keeps stopping? Re-pair glasses" |
| 6.8 | Failure texts | point at "Re-pair glasses", never "redo the glasses setup" |

## 7. Forced re-pair survives accessibility-service recreation

| # | Action | Expected |
|---|---|---|
| 7.1 | During a Re-pair run, recreate the service: rewrite `enabled_accessibility_services` (remove then restore Nexus), or let the run toggle Wireless debugging | session resumes pairing, does **not** finish as "core ready" |
| 7.2 | Recreate after > 5 min paused | session abandoned (`self_pairing_abandoned`), no takeover |

## 8. Item 4 — verdict reflects real helper health

| # | State | Repair now verdict |
|---|---|---|
| 8.1 | helper running, armed this boot | `already_healthy` |
| 8.2 | arm succeeds after being dead | `repaired` |
| 8.3 | bridge stopped, watchdog running (`sh rokid-nexus-cmd-bridge.sh stop`) | `partial` |
| 8.4 | unpaired, request started | `pairing_pending` |
| 8.5 | unpaired, request could not start (service off) | `pairing_required` |
| 8.6 | paired, arm fails | `arm_failed` |
| 8.7 | arm succeeded → helper killed → failed repair → glasses Wi-Fi off → Repair | must **not** answer healthy from the old record |
| 8.8 | absent arm record (state 3) with Wi-Fi off | not `already_healthy`; Wi-Fi is turned on through Settings to measure |

## 9. Phone fallback arm (code form) — honest gap

| # | Action | Expected |
|---|---|---|
| 9.1 | Arm through the phone's endpoint+code form | helper `running=yes`; verdict `repaired` |
| 9.2 | prefs after 9.1 | `wireless_bootstrap_complete` still **false**; UI says the pairing lasts until the glasses restart |
| 9.3 | Reboot the glasses, Repair now | back to `pairing_pending` (expected honest result) |

## 10. Compatibility matrix

| # | Phone | Glasses | Expected |
|---|---|---|---|
| 10.1 | new | new | all of the above |
| 10.2 | 1.6.0/1.7.0 release | new | Repair answers only the 5 legacy results (`pairing_*`/`partial` mapped to `arm_failed`) |
| 10.3 | new | 1.6.0/1.7.0 release | Re-pair shows "Update Nexus on the glasses, then use Re-pair glasses again" (one ordinary repair may run first) |
| 10.4 | old | old | baseline behaviour unchanged |

## 11. Settings-search navigator (once implemented)

Route: `android.settings.APP_SEARCH_SETTINGS` → type the label resolved from Settings'
own resources (`adb_wireless_ip_addr_preference_title`, `adb_pair_method_code_title`,
then `enable_adb_wireless` / `adb_wireless_settings`) → click the result whose title
matches exactly → Wireless debugging page → pairing row → pairing dialog
(`id/pairing_code`, `id/ip_addr`). Fallbacks: search "Wireless debugging" (Developer
options with a usable viewport), CoordinatorLayout `content_parent` scroll-forward
collapse, focus traversal.

### 11a. Locale probe (shell, can run before the navigator exists)

For each locale: switch, wait for the search index, run the route by hand with shell
injection, screenshot each step, restore French at the end.

| # | Locale | Checks |
|---|---|---|
| L1 | fr-FR | baseline (already verified by research) |
| L2 | en-US | "Pair device with pairing code" result found by full-label query |
| L3 | zh-CN | full-label query finds the result (no-space tokenization); prefix query behaviour noted |
| L4 | ja-JP, ko-KR | same as L3 |
| L5 | right after a locale switch | does the index still hold the old language? how long until reindexed? |
| L6 | any | "associer"/"pair" also returns Bluetooth results — exact-title selection must pick the right one |

Changing the system locale is a settings change on the owner's glasses: owner go
required, and restore `fr-FR` afterwards.

### 11b. Navigator through the real accessibility service

| # | Case | Expected |
|---|---|---|
| N1 | Fresh pairing, Wireless debugging **off** | search → page → enable → confirmation → pairing dialog → `self-pairing … bootstrap complete`; helper `running=yes`; `wireless_bootstrap_complete=true` |
| N2 | "Allow wireless debugging on this network?" dialog | recognised (package/class + localized resource), positive button clicked; nothing else clicked |
| N3 | Wireless debugging already on | goes straight to the pairing dialog |
| N4 | `ACTION_SET_TEXT` with accented / CJK text | text lands in `android:id/search_src_text` |
| N5 | Search index missing or no exact result | falls back to the next route, logged; no click on an unidentified row |
| N6 | Wearer touches the touchpad mid-run / screen sleeps | run recovers or ends cleanly within the deadline |
| N7 | Cancel from the phone mid-navigation | Settings left, session ended, no resume |
| N8 | End-to-end issue scenario: state 3 → Repair now | `pairing_pending` → automation pairs → `repaired` on the next Repair; survives a glasses reboot (boot repair re-arms by itself) |
| N9 | Pairing values | code/port/IP never in logs or prefs |
| N10 | Older firmware unit (if available) | the old direct route still works |

## 12. Regression suites (before merge)

```bash
./gradlew :shared:test :bus-client:testDebugUnitTest :phone-hub:testDebugUnitTest :phone-hub:assembleDebug :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug
./gradlew :plugin-wireless-adb:testDebugUnitTest :plugin-wireless-adb:assembleDebug -PskipCxrGlobal=true
```

## 13. Restore

1. Helper running (recovery recipe if needed), `wireless_bootstrap_complete=true` if the
   navigator paired, otherwise note that the unit needs the navigator build.
2. Locale back to `fr-FR`, Developer options untouched, Wi-Fi as before.
3. Either keep the test build or install the official v1.7.0 release APKs.
4. Glasses on HOME, log captures stopped, captures with personal data never committed.
