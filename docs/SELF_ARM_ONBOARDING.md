# Glasses self-arm onboarding

Rokid Nexus can arm its glasses AccessibilityService on first launch without a PC. The supported
flow uses Android Wireless Debugging and one app-private KADB TLS identity. It does not enroll or
depend on the separate classic ADB key.

## No-PC first launch

1. Launch **Rokid Nexus Glasses**.
2. Select **Open Accessibility** and enable **Rokid Nexus Hub**. This is the only accessibility
   service the user enables. Return to Nexus with Back.
3. Select **Start Wireless Setup**.
4. Enable Developer options and Wireless Debugging when Settings asks. Open **Pair device with
   pairing code** and keep the six-digit code visible.
5. Nexus reads the code, pairing port, and connect port from the Settings accessibility tree. It
   pairs only to `127.0.0.1` and returns to the Nexus HUD with success or a retry reason.

The six-digit code never leaves the glasses and is not written to logs. The Settings automator is
inactive outside an explicitly requested setup.

## One authenticated shell

The KADB TLS connection performs one bootstrap command which:

1. grants `android.permission.WRITE_SECURE_SETTINGS` to
   `com.anezium.rokidbus.glasses`;
2. preserves the existing `enabled_accessibility_services` list while adding the main
   `RokidBusAccessibilityService`, then sets `accessibility_enabled=1`;
3. installs, repairs, and starts `/data/local/tmp/rokid-nexus-a11y-watchdog.sh`;
4. verifies the grant, main service entry, global accessibility state, and running watchdog;
5. disables both classic TCP ADB properties and restarts `adbd` after the watchdog detaches.

Later process, boot, or package-replacement entries first reconnect with the already paired TLS key
to reinstall/start the watchdog. The classic `files/kadb` identity is consulted only when that key
was already provisioned by a maintainer; a fresh install does not generate or require it.

## Network posture

Wireless Debugging must be on while pairing. Before bootstrap, a device may have both the encrypted
Wireless Debugging endpoint and an older legacy listener:

```text
adb_wifi_enabled=1
service.adb.tls.port=<dynamic TLS port>
persist.adb.tcp.port=<empty, -1, or legacy value>
service.adb.tcp.port=<empty, -1, or legacy value>
127.0.0.1:5555=<possibly listening from an older setup>
```

Successful bootstrap sets and verifies this steady state:

```text
adb_wifi_enabled=<unchanged; normally 1 for paired encrypted maintenance>
service.adb.tls.port=<system-managed dynamic TLS port when Wireless Debugging is on>
persist.adb.tcp.port=-1
service.adb.tcp.port=-1
127.0.0.1:5555=closed
```

The TLS endpoint remains authenticated and encrypted. There is no LAN-reachable unauthenticated
legacy ADB listener: Nexus does not persist port 5555, restarts `adbd`, and refuses to record a safe
or complete state until a localhost connection to port 5555 is rejected. A wildcard `*:5555`
listener would also accept localhost, so this live refusal detects the observed LAN-exposed state.

Every launcher resume refreshes this posture off the UI thread and fails closed if hidden-property
or socket checks cannot be completed. An old completion marker cannot override an unsafe current
posture.

## ADB-user fallback

ADB users can still grant the development permission directly. Grant before a cold launch:

```powershell
$pkg = "com.anezium.rokidbus.glasses"
adb -s $glasses shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS
adb -s $glasses shell am force-stop $pkg
adb -s $glasses shell am start -W -n "$pkg/.MainActivity"
```

On launch, Nexus preserves other enabled accessibility services and repairs its own entry. The HUD
accepts this fallback only when the current legacy ADB properties are disabled and port 5555 is
closed. If an older install left legacy TCP ADB exposed, Nexus keeps the secure cleanup step visible.

Verification:

```powershell
adb -s $glasses shell settings get secure accessibility_enabled
adb -s $glasses shell settings get secure enabled_accessibility_services
adb -s $glasses shell getprop persist.adb.tcp.port
adb -s $glasses shell getprop service.adb.tcp.port
adb -s $glasses shell getprop service.adb.tls.port
adb -s $glasses shell "ss -ltnp | grep ':5555' || true"
```

Expected values are `accessibility_enabled=1`, a service list containing
`RokidBusAccessibilityService`, both classic TCP properties equal to `-1` (or otherwise disabled on
a fallback-only device), and no `:5555` listener.
