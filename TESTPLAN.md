# RokidBus Round A Test Plan

Status: 2026-07-04. The original probe project already passed the two hardware gates:
SPP alongside Hi Rokid stayed connected, bind-based wake worked from the accessibility
anchor, and the phone HTTP proxy reached `api.transitous.org` while glasses Wi-Fi was off.
Round A validates the AGP multi-module bus built from those constraints.

Serials used during validation:

- Glasses: `1901092534053723`
- Phone: `R5CW12DK1AY`

## Build

```powershell
cd E:\Tools\Rokid\RokidBus
.\gradlew.bat assembleDebug
```

Expected outputs:

- `phone-hub/build/outputs/apk/debug/phone-hub-debug.apk`
- `phone-client-probe/build/outputs/apk/debug/phone-client-probe-debug.apk`
- `glasses-hub/build/outputs/apk/debug/glasses-hub-debug.apk`
- `glasses-client-probe/build/outputs/apk/debug/glasses-client-probe-debug.apk`
- `bus-client/build/outputs/aar/bus-client-debug.aar`

## Install

```powershell
$glasses = "1901092534053723"
$phone = "R5CW12DK1AY"

adb -s $glasses install -r .\glasses-hub\build\outputs\apk\debug\glasses-hub-debug.apk
adb -s $glasses install -r .\glasses-client-probe\build\outputs\apk\debug\glasses-client-probe-debug.apk
adb -s $phone install -r .\phone-hub\build\outputs\apk\debug\phone-hub-debug.apk
adb -s $phone install -r .\phone-client-probe\build\outputs\apk\debug\phone-client-probe-debug.apk
```

Grant runtime Bluetooth permission to the two hub apps:

```powershell
adb -s $glasses shell pm grant com.anezium.rokidbus.glasses android.permission.BLUETOOTH_CONNECT
adb -s $phone shell pm grant com.anezium.rokidbus.phone android.permission.BLUETOOTH_CONNECT
```

## Arm Accessibility

Append RokidBus to the existing Relay accessibility setting. Do not overwrite Relay.

```powershell
$busA11y = "com.anezium.rokidbus.glasses/.RokidBusAccessibilityService"
$current = (adb -s $glasses shell settings get secure enabled_accessibility_services).Trim()
if ($current -eq "null") { $current = "" }
if ($current -notlike "*$busA11y*") {
    $next = if ([string]::IsNullOrWhiteSpace($current)) { $busA11y } else { "$current`:$busA11y" }
    adb -s $glasses shell settings put secure enabled_accessibility_services "$next"
}
adb -s $glasses shell settings put secure accessibility_enabled 1
adb -s $glasses shell settings get secure enabled_accessibility_services
```

## Start Hubs

Open the phone hub, tap `Authorize with Hi Rokid` once if no saved token is present, then
tap `Start Hub`.

```powershell
adb -s $phone shell monkey -p com.anezium.rokidbus.phone 1
adb -s $glasses shell am broadcast -n com.anezium.rokidbus.glasses/.ProbeBroadcastReceiver -a com.anezium.rokidbus.glasses.PROBE --es probe hub
adb -s $glasses shell am broadcast -n com.anezium.rokidbus.glasses/.ProbeBroadcastReceiver -a com.anezium.rokidbus.glasses.PROBE --es probe state
```

Keep glasses Wi-Fi off for HTTP proxy validation:

```powershell
adb -s $glasses shell svc wifi disable
adb -s $glasses shell settings get global wifi_on
```

## Component Broadcasts

Use component-targeted broadcasts for hub/debug checks:

```powershell
adb -s $glasses shell am broadcast -n com.anezium.rokidbus.glasses/.ProbeBroadcastReceiver -a com.anezium.rokidbus.glasses.PROBE --es probe state
adb -s $glasses shell am broadcast -n com.anezium.rokidbus.glasses/.ProbeBroadcastReceiver -a com.anezium.rokidbus.glasses.PROBE --es probe wake-echo
adb -s $glasses shell am broadcast -n com.anezium.rokidbus.glasses/.ProbeBroadcastReceiver -a com.anezium.rokidbus.glasses.PROBE --es probe wake-http
```

Log collection:

```powershell
adb -s $phone logcat -c
adb -s $glasses logcat -c
adb -s $phone logcat -d -s ROKIDBUS-PHONE:* RokidBusClient:*
adb -s $glasses logcat -d -s ROKIDBUS:* ROKIDBUS-CLIENT:* RelayBridge:*
```

## Acceptance Checks

1. `.\gradlew.bat assembleDebug` is green and produces the four APKs plus `bus-client-debug.aar`.
2. Kill the glasses probe process with `adb -s $glasses shell am kill com.anezium.rokidbus.clientprobe`, open `com.anezium.rokidbus.phoneprobe`, tap `Echo`, and verify phone probe -> phone hub -> CXR-L -> glasses hub -> bind-woken glasses probe -> reply back.
3. In the phone probe, tap `Echo-big 64 KB` and verify the same API succeeds over the SPP data route.
4. In the phone probe, tap `HTTP via bus`; the phone asks the glasses probe to fetch through `/http/request`, glasses Wi-Fi remains off, and the phone hub returns Transitous chunks over the bus.
5. Hi Rokid/CXR-L remains connected throughout; no channel reset, no re-pairing, and phone logs keep `CXR-L connected=true` / `Hi Rokid glass BT connected=true`.
6. Component-targeted broadcasts above report hub state and can bind-wake the glasses probe without using `startService`.

Useful PASS log fragments:

- Phone: `CXR-L connect requested bound=true`
- Phone: `SPP connected`
- Glasses: `SPP server listening name=RokidBus`
- Glasses: `CXR-S subscribe key=rokidbus`
- Glasses: `wake bind connected com.anezium.rokidbus.clientprobe/.ProbeService`
- Glasses probe: `echo request id=...`
- Phone probe: `Echo reply ... side=glasses`
- Phone probe: `Big echo reply ... side=glasses`
- Phone probe: `HTTP via bus status=200 totalBytes=...`

## Cleanup

If you saved the old accessibility setting before appending, restore it. Otherwise remove only
the RokidBus entry and keep Relay armed.

```powershell
adb -s $glasses uninstall com.anezium.rokidbus.glasses
adb -s $glasses uninstall com.anezium.rokidbus.clientprobe
adb -s $phone uninstall com.anezium.rokidbus.phone
adb -s $phone uninstall com.anezium.rokidbus.phoneprobe
```
