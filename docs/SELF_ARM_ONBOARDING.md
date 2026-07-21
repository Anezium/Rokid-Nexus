# Glasses self-arm onboarding

Rokid Nexus can arm its glasses AccessibilityService on first launch without a PC. The supported
flow uses Android Wireless Debugging and one app-private KADB TLS identity. It does not enroll or
depend on the separate classic ADB key.

## No-PC first launch

The glasses UI is two HUD cards:

1. **Enable accessibility** — opens Settings on the right screen; the user enables
   **Rokid Nexus Glasses** (the only service they ever enable). Nexus returns to the
   HUD on its own once the service connects, and the freshly armed service
   immediately chains into the wireless bootstrap — no extra tap needed.
2. **Finish setup** — the fallback card for re-running the bootstrap when the
   automatic chain could not complete (for example Wi-Fi was off or the glasses
   were not connected to a network).

The full bootstrap requires the glasses to be joined to a Wi-Fi network through
the Hi Rokid app; having the Wi-Fi toggle on is not enough. After enabling Wi-Fi,
the automator reports `waiting_for_wifi_network` while it waits up to 30 seconds
for a Wi-Fi IPv4 address. If the glasses are still not joined, it stops with
`wifi_network_required`, and the Retry card tells the user to connect in Hi Rokid
before trying again. The separate camera fallback remains a Wi-Fi-toggle-only
operation and still finishes as soon as Wi-Fi is on.

During the bootstrap, the accessibility automator drives Settings itself: it
enables Developer options and Wireless Debugging, opens **Pair device with pairing
code**, and reads the six-digit code, pairing port, and connect port from the
Settings accessibility tree. It pairs only to `127.0.0.1` and returns to the Nexus
HUD with success or a human-readable retry reason; every phase has a status line
on the card. The six-digit code never leaves the glasses and is not written to
logs. The Settings automator is inactive outside an explicitly requested setup
(its only other mode is a single Wi-Fi toggle used as the camera fallback).

## The staged arm sequence

The KADB TLS connection runs a staged **prepare / arm** sequence rather than one
monolithic command:

- **Prepare** installs the payloads: the accessibility watchdog
  (`/data/local/tmp/rokid-nexus-a11y-watchdog.sh`) and the camera command bridge
  (`rokid-nexus-cmd-bridge.sh`, a persistent shell-uid helper detached with
  `nohup`, woken by a doorbell FIFO, no network port).
- **Arm** grants `android.permission.WRITE_SECURE_SETTINGS` to
  `com.anezium.rokidbus.glasses`, preserves the existing
  `enabled_accessibility_services` list while adding the main
  `RokidBusAccessibilityService`, sets `accessibility_enabled=1`, starts both
  helpers, verifies grant/service/global state/watchdog, then disables both
  classic TCP ADB properties and restarts `adbd`. The watchdog is detached
  (PPID 1) and survives that restart; the sequence reconnects afterwards and
  re-arms if anything was lost. The command bridge is best-effort: its status is
  reported but never gates self-arm success.

The bridge accepts only whitelisted commands: toggling Wi-Fi on/off, and
joining a specific SSID/passphrase (`wifi_connect`, with a `security` argument
of `open`/`wpa2`/`wpa3` — used by the Lens camera link's phone-hosted-hotspot
fallback to join the phone's `LocalOnlyHotspot` by credentials). Each request
carries a nonce and a keyed SHA-256 over an app-private random secret, with
replay rejection — the `wifi_connect` SSID/passphrase/security are part of
that same signed payload, not separately injectable. The app never reads
bridge-written files (FUSE negative-cache trap) — it observes the resulting
system state instead.

Once `WRITE_SECURE_SETTINGS` is granted, the app also repairs its accessibility
entry **directly** on every launch — no ADB session needed — so accessibility is
covered from boot even while the watchdog is not yet running. Later process,
boot, or package-replacement entries reconnect with the already paired TLS key to
reinstall/start the watchdog; if the session is unreachable (this ROM boots with
Wi-Fi off), a retry re-arms it as soon as Wireless Debugging or Wi-Fi
reachability returns. Completion is reported to the phone through the additive
`setupComplete` field of the glasses capabilities payload, which drives the
phone onboarding's "Set up your glasses" step. The classic `files/kadb` identity
is consulted only when that key was already provisioned by a maintainer; a fresh
install does not generate or require it.

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
