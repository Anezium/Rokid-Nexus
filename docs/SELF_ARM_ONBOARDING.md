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

Settings navigation is deliberately independent of the wearer's locale and saved
scroll position. Nexus resolves labels such as `adb_wireless_settings`,
`adb_pair_method_code_title`, `development_settings_title`, and `build_number`
from the installed `com.android.settings` package, then keeps a small
vendor-fallback catalog. It first capability-probes the direct Wireless Debugging
route and verifies the resulting page. If traversal is needed, it selects only a
visible `com.android.settings` RecyclerView, returns to the start of the list,
searches forward, and compares a hashed before/after tree signature after every
scroll. An accessibility action that reports success without moving therefore
falls back to a gesture anchored inside the RecyclerView's measured bounds; no
fixed screen percentage or remembered list position is trusted. Accessibility
events may wake the state machine earlier but cannot postpone an already queued
tick.

For photo-based support, the retry card also shows a compact support code:
`PAIR-NOPORT` when the pairing port was unavailable, `PAIR-NOTLS` when the TLS
connect port was unavailable, `PAIR-FAIL` for a local KADB pairing error,
`PAIR-STALL` when pairing did not complete, or `TMO` with the last reported setup
phase after an overall timeout. The six-digit pairing code itself is never shown
or logged; diagnostics redact it before they can reach logs, preferences, or the
HUD.

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

If the secure KADB stream dies during prepare or either arm phase, Nexus closes the dead session,
reconnects over paired TLS, and retries that idempotent phase up to two times. If the stream dies
during the planned `adbd` restart, Nexus assumes the restart was dispatched, reconnects, and resumes
with the post-restart arm verification so the final state is still converged and checked.

The bridge accepts only whitelisted commands: toggling Wi-Fi on/off, forgetting
a files batch's saved hotspot network (`wifi_forget`, API 32 only), and
joining a specific SSID/passphrase (`wifi_connect`, with a `security` argument
of `open`/`wpa2`/`wpa3` — used by the Lens camera link's phone-hosted-hotspot
fallback to join the phone's `LocalOnlyHotspot` by credentials). Each request
carries a nonce and a keyed SHA-256 over an app-private random secret, with
replay rejection — the `wifi_connect` SSID/passphrase/security are part of
that same signed payload, not separately injectable. The app never reads
ordinary shell-created response files (FUSE negative-cache trap); it observes
system state, or the app-precreated forget receipt described below.

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

## The command bridge, and how to add a command to it

Some things Nexus must do on the glasses are refused to an ordinary app and have no API: turning
Wi-Fi on (`setWifiEnabled` is gone since API 29), joining a network, deleting a capture that belongs
to the camera app. The command bridge is the answer — a small shell-uid process, spawned by the
self-arm session and detached with `PPID=1`, that accepts a **fixed, signed list of commands** over a
file channel. It opens no port and takes no free-form input; that closed list is the entire security
story, so keep it closed.

**How a request travels.** The app writes `<nonce>.request` into its own external-files channel
(`…/files/cmd_bridge/`), rings a FIFO doorbell, and the bridge picks it up. The bridge keeps the
FIFO open for its lifetime and blocks in one-second timed reads — the app's ring cannot cross the
FUSE boundary on this ROM, so the read timeout is what actually delivers requests, forklessly.
Every 30 seconds a wake refreshes the diagnostic heartbeat and drives maintenance; only a missing
or unopenable FIFO falls back to the sleep-based poll. The line is
`command:nonce:arg1:…:token`, where the token is `sha256(secret:command:nonce:arg1:…)` and the secret
is a 32-byte value generated once, kept app-private and baked into the deployed script. Arguments
that could contain `:` travel base64-encoded. The bridge re-validates everything itself and refuses
on any surprise: unknown command, malformed nonce, replayed nonce, bad token, wrong shape.
The current allow-list (helper `2026-10-21.5`) is `wifi_enable`, `wifi_disable`,
`wifi_connect`, `wifi_connect_files`, `wifi_forget`, `wifi_cleanup_files`,
`delete_capture`, `adb_wifi_enable`, and `adb_wifi_disable`. The Wireless ADB
plugin uses the last two only to toggle the transport; pairing start/stop uses
the hub's already authenticated local KADB identity with separately validated,
fixed `service call adb` shapes.

`wifi_connect_files` takes the same authenticated base64 SSID/passphrase and
security arguments as `wifi_connect`, with API-32-only target confirmation.
Its app-precreated `<nonce>.connect-result` receipt contains `pending`, `ok`, or
`error`; `ok` requires the requested target, completed supplicant state, and an
IPv4 address. The camera `wifi_connect` path is unchanged.

`wifi_cleanup_files` takes no arguments. On API 32 it retries only network IDs
created by `wifi_connect_files`, recorded with creation timestamps in a shell-owned
file (at most eight entries; a files lease expires after 24 hours). The hub calls
it on start and batch end; helper start and 30-second maintenance also retry it.
Ordinary cleanup retains the currently connected ID; expiry releases only the
recorded files lease. IDs are removed from the record only after verified absence.
No SSID, passphrase, BSSID, or device identity is persisted or logged by cleanup.
Its app-precreated `<nonce>.files-result` receipt contains `pending`, `ok`, or
`error`; retained obligations or unknown output leave cleanup unconfirmed.

`wifi_forget` takes one base64 SSID argument (1–44 encoded characters, at most
32 decoded UTF-8 bytes, no controls). On Android 12L/API 32 only, it matches
`cmd wifi list-networks` SSIDs, refuses to forget a currently connected match,
forgets the matching numeric network IDs, and checks that none remain. Files use
`wifi_cleanup_files` instead, after Wi-Fi ownership reconciliation on a separate
cleanup executor. Failure remains best-effort and never blocks restore.
Unlike the ordinary shell-created response, its outcome uses an app-precreated
`<nonce>.forget-result` inode containing only `pending`, `ok`, or `error`.
This avoids the negative-dentry trap; a stale read or an old helper's unknown-command
rejection leaves cleanup unconfirmed. The app deletes this receipt and its request
on completion or timeout. Neither file adds persistent Wi-Fi identifiers.

**Adding a command — the whole path.** Every step matters; skipping one is how a command ships in the
APK and never reaches the glasses.

1. **`SelfArmCommandBridgeProtocol`** — add the constant, put it in `allowedCommands`, and give it a
   shape rule in `verify` (argument count, encoding, length bounds).
2. **`SelfArmCommandBridgeClient`** — add the call that builds the arguments and submits. Give it a
   way to observe the *real effect* and pass that as `awaitResult`.
3. **The script** (`glasses-hub/src/main/assets/rokid-nexus-cmd-bridge.sh`) — parse the command in the
   request `case` (compute `token_input` exactly as the client does), then execute it in the second
   `case`. **Re-validate the arguments here rather than trusting them**; the delete command rebuilds
   its path from a fixed directory and refuses anything with a separator, a leading dot or an
   unexpected character, so it cannot be steered elsewhere.
4. **Bump both versions**: `VERSION=` in the script *and* `SelfArmConstants.BRIDGE_VERSION`. The app
   keeps a rendered copy and only regenerates it when the constant moves; the running loop only hands
   over when the script version changes. Miss either and nothing deploys.
5. **Never name the secret placeholder in the script.** The app refuses an asset carrying more than
   one occurrence of it, and that failure aborts the whole self-arm. Split the literal if you need to
   refer to it (`"__ROKID_NEXUS_BRIDGE_""SECRET_HEX__"`).

**Never read the bridge's response file from the app.** A file created by the bridge's uid can stay
invisible to the app's uid for seconds behind the FUSE negative-dentry cache, which made every
request look like it had failed. Watch the effect instead: `WifiManager.isWifiEnabled` for the Wi-Fi
commands, the global wireless-debugging state plus its live TLS port for the ADB commands, and a
directory listing for the delete command. A listing also beats a `stat`, which can answer from a
cached entry. The API-32 bridge derives the current BSSID itself, validates it, and never returns or
logs it; no plugin-controlled value reaches that Binder transaction.

**How the script reaches the glasses.** Normally the self-arm session rewrites it on every hub start.
That session needs working ADB, so the bridge also checks the installed APK on a five-minute elapsed
deadline (`unzip -p $(pm path …) assets/rokid-nexus-cmd-bridge.sh`) — the code it runs always comes
from a package we signed, never from a blob handed to it at runtime. A candidate must look like the
script, carry exactly one secret slot and parse under `sh -n`; the successor has to still be alive
three seconds later or the previous script is restored and the current loop keeps serving.

The `2026-10-21.1` bridge adds `wifi_forget`; both its asset version and the
app's `SelfArmConstants.BRIDGE_VERSION` advance together. Already-running bridges
with APK self-update pick it up automatically at their next five-minute check,
without re-pairing or a user action. Normal successful self-arm also refreshes
the internal rendered script and deploys it. A bridge too old to self-update, or
a stopped bridge, still needs the existing working self-arm/repair path; this
command does not change that path or claim to repair stale ADB credentials.

Verifying a command on a device:

```powershell
adb -s $glasses shell "cat /data/local/tmp/rokid-nexus-cmd-bridge.version"
adb -s $glasses shell "tail -20 /data/local/tmp/rokid-nexus-cmd-bridge.log"
```

The log names every request it completed or rejected, with the reason. `command completed
command=<name>` is the line to look for; `request rejected reason=command` means the running loop is
older than the APK and has not handed over yet.

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
