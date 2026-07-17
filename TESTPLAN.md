# RokidBus Round A Test Plan

> **RESULT 2026-07-04 — ROUND A VALIDATED ON HARDWARE, ALL 6 ACCEPTANCE CHECKS PASS**
>
> - Check 2 (end-to-end small path): phone probe → phone hub → **CXR-L** (`CXR TX /probe/echo`) → glasses hub → **bind-wake of the dead probe process** (~1.6 s including cold start) → reply back via CXR-S. `linkState=7` on the client.
> - Check 3 (data plane): 64 KB echo automatically routed **SPP** (`SPP TX/RX`), same client API.
> - Check 4 (HTTP proxy): `HTTP via bus status=200 totalBytes=7592` from api.transitous.org, glasses `wifi_on=0` at test time.
> - Check 5: zero `connected=false` phone-side for the whole session — Hi Rokid/CXR-L never dropped.
> - Check 6: `wake-echo` broadcast queued + delivered via the 5 s register-wait flush path.
> - Hi Rokid authorization was granted silently (already remembered for this signature) — no manual tap was needed.
>
> Known limitations found (Round B material):
> - **No ordering guarantee across planes**: an HTTP `done` (small → CXR-L) arrived *before* its data chunk (10 KB → SPP). Fix idea: per-request-id plane affinity or sequence numbers.
> - `am kill` on a probe bound by the hub does not actually kill it (bound processes are protected); use `am force-stop` in tests when a true cold start is required (force-stop also unbinds the supervisor).
> - Phone hub has no wake-on-message supervisor (glasses-only for now, per spec "phone rarely needs it").

Status: 2026-07-04. The original probe project already passed the two hardware gates:
SPP alongside Hi Rokid stayed connected, bind-based wake worked from the accessibility
anchor, and the phone HTTP proxy reached `api.transitous.org` while glasses Wi-Fi was off.
Round A validates the AGP multi-module bus built from those constraints.

Device serials are operator-local. Populate the PowerShell variables from the
operator's environment before running any device command:

```powershell
$glasses = $env:ROKID_GLASSES_SERIAL
$phone = $env:ROKID_PHONE_SERIAL
if ([string]::IsNullOrWhiteSpace($glasses) -or [string]::IsNullOrWhiteSpace($phone)) {
    throw "Set ROKID_GLASSES_SERIAL and ROKID_PHONE_SERIAL in the operator environment."
}
```

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

For the supported no-PC first launch, open the glasses app and follow the two HUD steps:

1. Open Accessibility and enable only **Rokid Nexus Glasses**.
2. Start Wireless Setup, enable Wireless Debugging, and keep **Pair device with pairing code** open.

Nexus preserves other enabled services, performs the grant plus accessibility plus watchdog setup
in one authenticated KADB TLS shell, and disables legacy TCP ADB. See
[`docs/SELF_ARM_ONBOARDING.md`](docs/SELF_ARM_ONBOARDING.md) for the complete flow and network
posture.

For an ADB-driven test, preserve the documented development-permission fallback and cold-launch the
app. Do not replace the enabled-service list manually:

```powershell
$pkg = "com.anezium.rokidbus.glasses"
adb -s $glasses shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS
adb -s $glasses shell am force-stop $pkg
adb -s $glasses shell am start -W -n "$pkg/.MainActivity"
adb -s $glasses shell settings get secure accessibility_enabled
adb -s $glasses shell settings get secure enabled_accessibility_services
adb -s $glasses shell getprop persist.adb.tcp.port
adb -s $glasses shell getprop service.adb.tcp.port
adb -s $glasses shell "ss -ltnp | grep ':5555' || true"
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

> **RESULT 2026-07-05 — ROUND B SLICE 1 VALIDATED ON HARDWARE**
>
> - `surface-activity` probe: SurfaceActivity started from background (a11y BAL
>   exemption confirmed by `ActivityStarter: Background activity start allowed`),
>   demo card rendered.
> - `surface-overlay` probe: TYPE_ACCESSIBILITY_OVERLAY rendered — including while
>   Rokid Relay's glasses activity was relaunching itself in a tight loop and
>   starving activity surfaces. Overlay is therefore the default display path.
> - Screen wake: surfaces wake the sleeping display (3 s wakelock; display
>   re-sleeps after its normal timeout — lyrics keep rendering underneath).
> - End-to-end Lyrics: notification access granted via
>   `cmd notification allow_listener com.anezium.rokidbus.phone/com.anezium.rokidbus.lyrics.media.MediaNotificationListenerService`,
>   hub started, `/launcher/list` synced (count=1). Spotify playback auto-opened the
>   surface; track without synced lyrics showed the fallback card (NETEASE+LRCLIB
>   "no synced lyrics"); Blinding Lights showed line-synced lyrics (NETEASE / synced)
>   with prev/current/next lines.
> - Anchor mechanism: lyrics advanced several lines with ZERO bus messages in a 9 s
>   window (`/surface` TX count 27 → 27) — glasses tick on their own clock.
> - Seq protection observed live: a `/surface/show` (seq 1) arriving after seq 3 was
>   dropped as stale (`Surface stale drop id=lyrics seq=1 latest=3`).
> - Known quirk: after granting notification access into an already-running hub
>   process, the media monitor did not attach until the hub app was force-stopped
>   and relaunched. One-time setup path; revisit if it bites again.

Round B surface renderer checks:

```powershell
adb -s $glasses shell am broadcast -n com.anezium.rokidbus.glasses/.ProbeBroadcastReceiver -a com.anezium.rokidbus.glasses.PROBE --es probe surface-activity
adb -s $glasses shell input keyevent 4
adb -s $glasses shell am broadcast -n com.anezium.rokidbus.glasses/.ProbeBroadcastReceiver -a com.anezium.rokidbus.glasses.PROBE --es probe surface-overlay
adb -s $glasses shell input keyevent 4
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

## Image surface v1 hardware gates

Install the debug phone and glasses hubs, arm accessibility, start both hubs,
and wait until the phone log contains `SPP connected` followed by
`renderer capabilities image=true maxImageBytes=65536`. Push the bundled sample
JPEG through the full phone-hub -> SPP -> glasses decode -> HUD path without a
plugin:

```powershell
adb -s $phone shell am broadcast -n com.anezium.rokidbus.phone/.PhoneProbeBroadcastReceiver -a com.anezium.rokidbus.phone.PROBE --es probe image-surface
adb -s $phone logcat -d -s ROKIDBUS-PHONE:*
adb -s $glasses logcat -d -s ROKIDBUS:*
```

Expected: phone logs `debug image probe sent bytes=26335 surfaceId=debug:image`;
glasses logs the binary surface receive with no
validation/decode error; the tree/lake JPEG is FIT_CENTER on black. The physical
panel is GREEN MONOCHROME, so the unchanged bitmap appears as green luminance.
Do not add or expect tone mapping, dithering, or color transforms in v1. The
probe receiver is debug-build-only and adds no permission.

Run the remaining hardware matrix with a small approved test plugin or a debug
fixture that uses the public `showImage`/`updateImage` calls:

1. Encode the same 480 px source at approximately 16, 32, 48, and exactly 64 KiB.
   Record phone send-to-glasses-publish latency and confirm every frame renders;
   65,537 bytes must return/reject as `IMAGE_TOO_LARGE` or SDK `INVALID_PAYLOAD`.
2. Send missing binary, empty binary, WebP/incorrect MIME, dimensions 0 and 513,
   declared/body dimension mismatch, malformed JPEG/PNG, and bad SHA-256. Confirm
   rejection at SDK where applicable, phone hub, and glasses renderer; no HUD
   replacement and no process crash.
3. Send two updates for one surface less than 150 ms apart. Confirm the second
   receives `/error` code `IMAGE_RATE_LIMITED`, then succeeds at 150 ms. Send
   rapid A/B replacements with intentionally delayed decode and confirm an older
   decode never replaces the latest `(surfaceId, seq, contentKey)`.
4. Drop SPP while an image is visible and while an update is in flight. Confirm
   the phone capability bit disappears, the SDK returns
   `CAPABILITY_NOT_AVAILABLE`, text surfaces continue over CXR when eligible,
   and image capability returns only after SPP reconnect plus renderer
   announcement.
5. Repeatedly replace/hide images under Android memory pressure. Confirm replaced,
   stale, and detached bitmaps are recycled; no growing bitmap heap, OOM, frozen
   overlay, or use-after-recycle draw occurs.
6. Verify both overlay and activity display paths, back/input forwarding, aspect
   ratios portrait/landscape/square, JPEG and PNG, black card background, and
   green-luminance panel output.

## Round B slice 2

Install/build as in the earlier sections, then verify both hubs report API v2:

```powershell
.\gradlew.bat assembleDebug
adb -s $phone install -r phone-hub\build\outputs\apk\debug\phone-hub-debug.apk
adb -s $phone install -r phone-client-probe\build\outputs\apk\debug\phone-client-probe-debug.apk
adb -s $glasses install -r glasses-hub\build\outputs\apk\debug\glasses-hub-debug.apk
adb -s $glasses install -r glasses-client-probe\build\outputs\apk\debug\glasses-client-probe-debug.apk
```

Phone probe mic lease:

```powershell
adb -s $phone logcat -c
adb -s $phone shell monkey -p com.anezium.rokidbus.phoneprobe 1
adb -s $phone logcat -d -s ROKIDBUS-PHONE:* RokidBusClient:*
```

In the phone probe, tap `Mic 5 s`. Expect roughly 50 frames and roughly 160 KB
over 5 s, with zero gaps. While the first capture is still running, tap `Mic 5 s`
again and expect the second acquire to be denied with `BUSY`. Hi Rokid/CXR-L
must stay connected through audio start/stop.

Glasses wake-http binary regression, repeated 5x with glasses Wi-Fi OFF:

```powershell
1..5 | % {
  adb -s $glasses shell am broadcast -n com.anezium.rokidbus.glasses/.ProbeBroadcastReceiver -a com.anezium.rokidbus.glasses.PROBE --es probe wake-http
  Start-Sleep -Seconds 8
}
adb -s $phone logcat -d -s ROKIDBUS-PHONE:* RokidBusClient:*
adb -s $glasses logcat -d -s ROKIDBUS:* ROKIDBUS-CLIENT:* RelayBridge:*
```

Verify every run logs binary chunks before the final `done`; no `done` may appear
before all chunks for the same id.

Phone wake:

```powershell
adb -s $phone logcat -c
adb -s $glasses logcat -c
adb -s $phone shell am force-stop com.anezium.rokidbus.phoneprobe
adb -s $glasses shell am broadcast -n com.anezium.rokidbus.glasses/.ProbeBroadcastReceiver -a com.anezium.rokidbus.glasses.PROBE --es probe phone-wake-echo
Start-Sleep -Seconds 8
adb -s $phone logcat -d -s ROKIDBUS-PHONE:* ROKIDBUS-CLIENT:* RokidBusClient:* RokidBusClientSvc:*
adb -s $glasses logcat -d -s ROKIDBUS:* ROKIDBUS-CLIENT:* RelayBridge:*
```

Verify the phone hub bind-wakes `com.anezium.rokidbus.phoneprobe/.ProbeService`,
the queued `/probe/echo` flushes after the probe registers, and the echo reply
returns to glasses.

64 KB echo regression:

```powershell
adb -s $phone shell monkey -p com.anezium.rokidbus.phoneprobe 1
```

In the phone probe, tap `Echo 64K` and verify it still succeeds over the SPP data
route.

Useful PASS log fragments:

- Hubs: `apiVersion=3` on phone and glasses
- Phone probe: `Mic lease granted id=... rate=16000 channels=1`
- Phone probe: `Mic lease denied reason=BUSY`
- Phone probe: `Mic frames=... bytes=... gaps=0`
- Phone: `CXR-L connected=true`
- Phone: `Hi Rokid glass BT connected=true`
- Glasses probe: `HTTP chunk id=... bytes=... dataBytes=...`
- Glasses probe: `HTTP done id=... status=200 totalBytes=...`
- Glasses: `Broadcast probe result: phoneWakeEchoSent=true path=/probe/echo id=...`
- Phone: `queued wake path=/probe/echo target=com.anezium.rokidbus.phoneprobe/.ProbeService`
- Phone: `wake bind connected com.anezium.rokidbus.phoneprobe/.ProbeService`
- Phone probe: `phone echo request id=...`
- Glasses: `remote RX /probe/echo/reply id=...`
- Glasses probe: `echo reply observed id=...`
- Phone probe: `Big echo reply ... side=glasses`

## Cleanup

If you saved the old accessibility setting before appending, restore it. Otherwise remove only
the RokidBus entry and keep Relay armed.

```powershell
adb -s $glasses uninstall com.anezium.rokidbus.glasses
adb -s $glasses uninstall com.anezium.rokidbus.clientprobe
adb -s $phone uninstall com.anezium.rokidbus.phone
adb -s $phone uninstall com.anezium.rokidbus.phoneprobe
```

## Transit plugin on-device check

> **RESULT 2026-07-07 — TRANSIT PLUGIN VALIDATED ON HARDWARE (end-to-end)**
>
> - Launcher list synced count=2 (Lyrics + Transit); Transit opened from the
>   glasses launcher activity via DPAD/ENTER.
> - Full data path live: phone location → Transitous `reverse-geocode?type=STOP`
>   (nearest stop "Chapellerie", 171 m) → `stoptimes` → card rendered on the HUD:
>   4 departures, aligned columns, realtime minutes, footer `upd HH:mm . 171m`.
> - BACK: input forwarded to the phone, plugin sent `/surface/hide`, and zero
>   `/surface/show|update` in the following 75 s — refresh loop stops on close.
>
> Findings:
> - **Platform bug — surface seq resets on hub restart**: a restarted phone hub
>   process restarts plugin seq counters at 1 while the glasses keep
>   `latestSeqBySurface` from the previous process, so every show/update/hide is
>   dropped as stale (`Surface stale hide drop id=transit seq=1 latest=4`).
>   TransitPlugin now seeds its counter with wall clock; LyricsPlugin and any
>   future plugin have the same exposure — fix generically in Round C (seed or
>   reset handshake on `/launcher/list` sync).
> - ADB-driven glasses testing quirks: keys injected while the display sleeps are
>   eaten as wake events (send `input keyevent 224`, pause, then the key), and
>   while any surface is active the accessibility filter forwards DPAD to the
>   plugin instead of the launcher activity — dismiss surfaces (BACK) and pause
>   Spotify (lyrics auto-reopen) before driving the menu.

> **RESULT 2026-07-08 — TRANSIT v2 VALIDATED ON HARDWARE (chooser, modes, favorites, plugin-handled BACK)**
>
> Full navigation cycle driven on the glasses via the accessibility input path
> (swipe = DPAD, tap = ENTER/NOTIFICATION, back = BACK):
> - **Chooser** renders on open: `Transit` / `> Near Me` / `Favorites` / footer
>   `swipe . tap opens`; swipe toggles the `>` marker, tap enters the mode.
> - **Near Me board**: live IDFM data at "Chapellerie" (171 m) — two directions
>   split (`11 >Victor Basch`, `11 >Saint-Denis`), 3 next passages each; swipe
>   pages within the stop (line 32 groups on page 2); **tap jumps to the next
>   nearby stop** ("Ampère - Chartrel", 195 m) — the multi-stop answer to
>   "bus stop but a station is also nearby".
> - **Plugin-handled BACK** (`handlesBack = true`): BACK inside a board sends
>   `/surface/input` and the plugin replies with `/surface/update` (the chooser),
>   NOT `/surface/hide` — it navigates back to the chooser and preserves the
>   active mode's `>` marker. BACK from the chooser closes fully (`/surface/hide`).
>   Confirmed zero surface traffic in the 10 s after close (refresh loop stops).
> - **Favorites**: empty state shows `No favorites yet.` / `Add in phone app.`;
>   after adding a stop from the phone the board renders its live departures with a
>   location-sorted distance footer.
> - **Phone favorites manager**: text search returns 8 disambiguated results
>   (`Chapellerie - Goussainville`, `- Sucy-en-Brie`, `La Chapelle - Paris`, …
>   name + default-area city from the geocode `areas`); tapping a result adds it
>   (`Added Chapellerie.`) and it appears under Saved stops with a Remove button.
>
> Findings:
> - **Reinstalling the glasses APK disables its accessibility service** (Android
>   security behaviour). Re-enable it before testing: append
>   `com.anezium.rokidbus.glasses/…RokidBusAccessibilityService` to
>   `settings put secure enabled_accessibility_services`.
> - **Single-display focus trap**: when the glasses are not worn the display sleeps
>   and input focus lands on an invisible `MockWindow`, so `adb input` never
>   reaches the debug launcher activity. Fix for testing: `svc power stayon true`
>   + `input keyevent 224` — focus returns to the activity and injected keys land.
>   The accessibility-driven surface input path (used once a surface is active) is
>   focus-independent and works regardless.

## Plan 002 plugin identity and capability acceptance

> **PENDING OWNER ON-DEVICE VERIFICATION (2026-07-10)**
>
> Software gates passed locally, but this execution was explicitly prohibited
> from using `adb`, installing APKs, or reading device logs. The owner should
> verify the following with identifiers, certificate digests, and user payloads
> redacted:
>
> - Debug phone and glasses probes register through the debug-only legacy path.
> - An unapproved plugin cannot send a surface or request HTTP/audio.
> - Approving only `surfaces` enables its surface while HTTP/audio stay denied.
> - Revocation unregisters/closes the plugin and prevents wake-on-message.
> - Normal and developer consent views transition pending -> partially approved
>   -> active -> revoked; microphone remains disabled with the HUD-indicator note.
> - CXR-L and SPP remain connected and the built-in Lyrics/Transit flows still work.

## Plan 003 external plugin SDK acceptance

> **PENDING OWNER ON-DEVICE VERIFICATION (2026-07-10)**
>
> The SDK, catalog/controller, Hello sample, local publication, published-coordinate
> consumer, lint, and JVM/build gates passed locally. This execution was explicitly
> prohibited from using device tools, installing APKs, or reading device logs.
> The owner should verify the following with device identity, signer details, and
> payload/user text redacted:
>
> - Install the Hello sample and confirm it appears pending, never auto-approved.
> - Approve only `surfaces`, force-stop the sample, and open it from the glasses
>   launcher; confirm bind-wake and the Hello card.
> - Confirm one physical paired swipe moves exactly once, tap updates the selected
>   row, and BACK hides without reaching the app underneath.
> - Revoke the sample and confirm it closes, disappears, and cannot wake or send.
> - Uninstall the sample and confirm the phone and glasses catalogs update safely.
> - Confirm CXR-L/SPP continuity and the remaining temporary built-ins.

## Plan 004 external Transit acceptance

> **PENDING OWNER ON-DEVICE VERIFICATION (2026-07-10)**
>
> The independent Transit APK, typed surface runtime, plugin-owned settings and
> permissions, foreground lifecycle, one-time verified favorite migration, hub
> decoupling, tests, lint, and build gates passed locally. Device interaction was
> prohibited for this execution, so both hardware gates remain owner work.
>
> Background-location architecture gate:
>
> - Open Transit on the phone, grant location/notification, and add a favorite.
> - Put the phone UI in the background, stop the Transit process, then open Transit
>   from the glasses and enter Near Me.
> - Confirm bind-wake can legally start the plugin-owned location foreground
>   service, a live board arrives, and no foreground-start or location security
>   exception occurs.
> - Return to chooser and close; confirm refresh stops and the notification is removed.
>
> If Android blocks the background foreground-service start, stop and choose one:
>
> - require the user to start Transit from its phone notification/Activity before Near Me;
> - add a narrowly scoped hub-owned location broker with lazy runtime permission;
> - request background location in Transit with explicit user education.
>
> The recommended initial-beta fallback is the first option. Do not select a
> fallback silently.
>
> Full acceptance cycle:
>
> - Transit absent → no Transit catalog/launcher row; install → pending; approve
>   `surfaces` → dynamic phone/glasses row.
> - Force-stop → glasses open bind-wakes; validate chooser, Near Me, Favorites,
>   pagination, single-count paired swipe, tap, both BACK levels, and refresh stop.
> - Restart the hub, revoke, and uninstall Transit; surfaces must not become stale,
>   Transit must disappear/cannot wake, and the remaining hub stays stable.
> - Confirm the one-release favorite migration imports once without duplication,
>   and CXR-L/SPP remain connected. Record only redacted PASS/FAIL evidence.
## Plan 007 Phase E camera/Lens on-device validation

> **PENDING OWNER ON-DEVICE VERIFICATION (2026-07-12)**
>
> Phase E covers code, documentation, CI, and local Gradle gates only. The owner
> will run the following hardware matrix in a later session with device identity,
> camera content, credentials, and user text redacted from captured evidence.

- Start cold with the Lens plugin's nearby-device permission absent, then grant it
  and repeat with the permission present.
- Exercise Lens absent, pending, approved, and revoked; the glasses camera empty/
  ready state must match `CAMERA_CONSUMER_READY` in all four states.
- Force-stop Lens, then open the glasses camera session; verify a cold bind with
  important process priority and live processing within that same session.
- Validate live translation with a latency spot-check against the Phase A numbers,
  frozen translation, and multi-script frozen OCR for Chinese, Korean, and Hindi.
- Drop P2P mid-session and reconnect; keep a session idle longer than 60 seconds;
  pause/resume the glasses camera activity; restart the phone hub mid-session.
- Run the Store lifecycle: install, approve, open, update, and uninstall Lens while
  confirming CXR/SPP transport continuity throughout.
