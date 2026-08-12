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

Install/build as in the earlier sections, then verify both hubs report API v3:

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

## STT capability v1

Use an approved phone plugin whose descriptor requests `stt` and receives
`/stt`, plus a second unapproved or differently granted fixture. Configure at
least one realtime engine and one buffered engine in the hub. Evidence must
redact transcript text, provider credentials, signer details, and device
identity.

Software gates:

1. Descriptor parsing accepts `stt` with `/stt` and rejects `/stt` without the
   capability. Route policy rejects unregistered, pending, revoked, and
   approved-but-ungranted callers for both `/stt/session/start` and
   `/stt/session/stop`.
2. Start with unknown `version` and with `mode:"continuous"`; each reply keeps
   the request ID and returns `accepted:false, reason:"INVALID_REQUEST"`.
   Verify `BUSY`, `NO_LINK`, `NOT_READY`, and `START_FAILED` with the matching
   arbitration/readiness/fault fixture.
3. Exercise the typed SDK's pending/active/idle transitions, all denial and end
   mappings, pluginId mismatch filtering, the 128-ID dedup window, idempotent
   stop, registration loss, direct client close, normal service close, and
   sticky `/stt/*` routing.

On-device/session matrix:

1. Start the Speech settings dictation test, then start the plugin; the plugin
   receives `DENIED_BUSY`. Reverse the order and confirm the settings test is
   busy. After either session ends, the other can start.
2. Hold a raw `microphone` audio lease and start STT; expect `DENIED_BUSY`.
   Start STT first and, while it is still capturing, request raw audio; expect
   audio `BUSY`. Confirm there is still exactly one CXR audio stream and it is
   released after each terminal path.
2b. Speak, then stop, and watch `CXR audio stream state` while the transcript is
   still in flight. The stream must report `started=false` at the voice
   endpoint, before the final arrives — not at the end of the session. Raw audio
   acquisition must succeed during that window, and dropping CXR-L there must
   still deliver the final and `completed`. Every `started=true` must have a
   matching `started=false`, including across dictations started back to back.
3. With a realtime engine, verify accepted reply → `listening` →
   `recognizing` → zero or more monotonic partials → `processing` → final →
   ended `completed`. State IDs must be `<sessionId>:s<n>`, partial IDs
   `<sessionId>:p<seq>`, and final/ended IDs must be unique. With a buffered
   engine, `realtime:false` and no partials are expected.
4. Stay silent through endpointing. Expect one ended event with
   `reason:"no_speech"`, no final transcript, and no orphan audio lease.
5. Drop CXR-L mid-utterance, while audio is still being captured. Expect
   `reason:"link_lost"` with a structured transcript-free error, then verify a
   later reconnect permits a fresh session.
6. Kill the plugin binder and separately call unregister mid-session. Each
   cancels and releases the session without attempting transcript replay.
   Re-register and confirm another plugin can acquire immediately.
7. Revoke the `stt` grant while the binder is alive. Expect one targeted ended
   event with `reason:"revoked"`, then no partial/final traffic. Confirm the
   plugin returns to the correct approval state and cannot restart without the
   grant.
8. Start with a valid language override (for example `fr`) and verify that
   session uses it without changing the hub default. Repeat with an absent and
   invalid ID; both use the hub-configured language.
9. Send stop with the correct session ID, repeat it, then send stale/wrong IDs
   from both owner and another approved principal. Every caller receives
   `stopped:true`; only the verified owner+binder's current session is
   cancelled.
10. Search phone logcat, the Settings log broadcast, queued traffic, remote
    glasses traffic, and Bus inspector output using a known spoken marker.
    The marker must appear only in the holder's in-process callbacks. The
    journal may show direction/path/size/verdict but never payload text.

### Android engine

The phone's own recognizer needs no credentials, so it is the engine a fresh
install lands on. It also needs the phone microphone permission, which the
cloud engines never did.

1. On a profile that has never picked an engine, the Speech screen selects the
   Android engine and readiness is never `MISSING_KEY`. Pick a cloud engine and
   reopen the screen: the explicit choice wins and survives a hub restart.
2. Deny `RECORD_AUDIO`. Readiness reads `MISSING_MIC_PERMISSION` and the
   dictation card offers to grant it; granting from that card returns the
   screen to `READY` without leaving the app. Deny permanently (twice) and
   confirm the copy points at Android settings instead of a dead prompt.
3. Force a language, run the Android engine, then switch back to a cloud
   engine. The forced language must still be selected: the Android engine runs
   on auto-detection but never rewrites the stored choice. While it is
   selected the language grid is read-only.
4. Run one dictation and confirm the hub audio lease opens and closes exactly
   once, and that the recognizer receives the glasses PCM rather than the phone
   microphone — speak toward the glasses only, with the phone face down and
   away.
5. Start a plugin STT session while the Android engine is selected, and check
   the plugin sees no protocol difference from a cloud engine: same reply
   shape, same events, same stop semantics.
6. On a phone that refuses the microphone foreground-service type, the hub must
   stay alive and fall back to its connected-device foreground service, and the
   session must end with a structured `source-unavailable` error rather than a
   crash.

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
>   -> active -> revoked; microphone is grantable to any plugin that requests it.
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

## LOHS reverse camera link on-device validation

> Validated 2026-07-21 on reference hardware (S23+ phone, RG glasses); numbers
> below are that session's measurements, re-check if the client-l SDK or the
> P2P/LOHS policies change.

- Cold-open with the phone's Wi-Fi off and the glasses' Wi-Fi off: opening
  Lens must enable the glasses' Wi-Fi automatically (no manual toggle), have
  the phone host a `LocalOnlyHotspot`, and connect within the P2P-fallback
  timeout. Confirm via `cameraLinkStage stage=connected transport=lohs_reverse`
  in the glasses log; measured 5.5s (warm Wi-Fi radio) to 9-14s (glasses
  Wi-Fi chip cold boot).
- Confirm the first join attempt succeeds without a WPA2-then-retry-SAE
  association rejection (`Association rejection ... statusCode`) — the offer's
  `security` field must match the hotspot's actual security type.
- Confirm the glasses skip Wi-Fi Direct group creation (no `group_created`/
  `offer_sent#1` in the log) when they already know `CAMERA_LOHS_REVERSE_REQUIRED`
  from a recent phone capabilities announcement, going straight to
  `offer_sent#0` (the reverse-mode bootstrap offer) instead.
- With the phone's Wi-Fi ON, confirm the P2P path is completely unaffected:
  same group-owner/offer/join sequence and timings as before this feature
  existed.
- Toggle the phone's Wi-Fi off mid-session (already open, live-streaming):
  confirm the existing P2P link keeps working until the session ends — LOHS
  mode is only selected at session start, not renegotiated mid-session.

## Video playback MVP on-device validation

> **DEFERRED — NOT RUN FOR THE SOFTWARE MVP (2026-08-12).** Do not mark Plan 006
> done or release the feature as hardware-ready until this matrix passes.

- Install matching phone/glasses hubs and Feeds, re-approve its new
  `video_playback` grant, and grant Feeds Nearby devices access on the phone.
- From X, play AVC MP4 variants at 360p and 720p for at least 10 minutes. Confirm
  real motion, correct cadence, pause/resume without skipping to the end, BACK
  teardown, and no display sleep, thermal throttling, or stale P2P group.
- From Bluesky, play VOD HLS using both MPEG-TS and fMP4/CMAF examples if the
  service exposes both. Confirm bounded prefetch, first-frame latency, cleanup of
  the private cache file, and honest poster fallback for live/encrypted/
  incompatible playlists.
- Play a GIF/video variant in a loop and confirm it stays muted, returns to the
  first frame cleanly, and releases the link when gallery selection changes.
- Exercise AAC mono/stereo at 44.1 and 48 kHz. Record the actual routed output;
  verify the phone speaker is never used and measure lip sync against a
  flash/click clip before accepting sound.
- Start Camera and MediaSync before video: video must report `busy`. While video
  is active, explicitly open Camera: video must stop and Camera/Lens must regain
  its normal P2P/LOHS behavior. Re-run Lens live/freeze on both transports.
- Force-stop, revoke and uninstall Feeds at each playback stage; drop CXR and
  SPP; turn either Wi-Fi radio off; and kill `:video`. Every path must close the
  decoder/audio/socket, release only the group it created, and restore the
  poster/normal HUD.
- Record sustained throughput, dropped frames, decoder identity/profile/level,
  battery, temperature and A/V error. Treat decoder instability, unintended
  audio routing, repeated sync error above 120 ms, or Lens regression as a stop
  condition requiring architecture work.

## Background update checks on-device validation

- With the phone app's UI never opened, wait for the hourly background tick
  (or force it by restarting `BusHubService`) and confirm both
  `NexusUpdateManager.checkForUpdates` and `PluginUpdateChecker.refreshIfStale`
  run on their own schedule.
- With a genuinely newer app version published, confirm a system notification
  ("Rokid Nexus update available") appears without the app UI ever being
  opened, and that re-ticking with the same available version does not
  re-notify.
- Same check for a newer plugin version: notification text pluralizes
  correctly for 1 vs N pending plugin updates, and a repeat tick with the same
  pending set does not re-notify.
- Deny the `POST_NOTIFICATIONS` runtime permission and confirm the periodic
  checks still run without crashing (notification post is silently skipped).

## Photos Sync v1 on-device validation

Nothing below is covered by unit tests; all of it needs both devices. Photo sync
runs over the Bluetooth bus — **no Wi-Fi is involved at any point**, on either
device. If a test seems to need Wi-Fi, something is wrong.

- Grant `READ_EXTERNAL_STORAGE` on the glasses (the hub asks on first open of its
  own screen; `adb shell pm grant com.anezium.rokidbus.glasses
  android.permission.READ_EXTERNAL_STORAGE` is the shortcut) and confirm the
  status stops reporting "Allow storage access on the glasses".
- Install Photos Sync, approve `mediasync` in Plugin access, and confirm the hub
  only starts syncing after that approval (before it, the glasses log
  `skip reason=not_consented`).
- **Photos first**: with a few captures pending and the glasses charging, confirm
  each lands in `Download/Hi Rokid/` with its original filename, beside Hi
  Rokid's own imports, at roughly 4-5 s per photo.
- **Then a video**: confirm it transfers to completion (minutes are expected) and
  that progress in the plugin advances without flooding — status pushes should be
  ~1 per file plus one every couple of seconds, not per chunk.
- **Camera-open-during-sync**: start a video sync, then open the camera. The sync
  must abort with `camera_active` within a chunk or two, the camera must behave
  exactly as it does without photo sync, and the next trigger must **resume the
  video from its offset** (glasses log `resuming name=… offset=…`), not restart
  it. Verify the same for a mid-transfer link drop (walk out of range).
- **Charge trigger**: with mode `While charging`, unplug, take a capture (nothing
  should happen), then plug in and confirm the sync starts on the charging edge.
- **Modes**: `Always` must sync a capture within a few seconds of taking it while
  connected and off charge (this is the `FileObserver` trigger, ~2 s debounce
  plus the stability settling window). `Manual only` must do nothing on any
  automatic trigger while Sync now still works.
- Re-trigger with nothing new: the run must end `up_to_date` with no transfer.
- Delete a synced photo from the phone gallery, sync again: it must NOT come back
  (the ledger is authoritative, not MediaStore presence).
- Shoot a video and start a sync while it is still recording: the video must be
  absent from that session's catalog and appear only in a later one.
- **Politeness**: during a video sync, use the glasses normally — open a plugin
  surface, scroll a feed. The HUD must stay responsive; the glasses log should
  show the transfer yielding rather than the UI stuttering.
- Enable delete-after-sync and sync one capture: check whether the file is
  actually gone from `/sdcard/DCIM/Camera`. If the ROM refuses, the settings
  screen must show the amber "The glasses refused the last delete" line — that is
  the expected honest outcome, not a bug to hide.
- Kill the Photos Sync process mid-sync (`am force-stop`): the transfer must keep
  running (it lives in the hub) and the screen must recover on reopen.
- **Consent after a glasses hub restart**: force-stop only the glasses hub. When
  it comes back it must request config and resume working without touching the
  phone hub (this is what previously needed a phone-side force-stop).

## Assistant phone calendar validation

Software gate:

```powershell
.\gradlew.bat :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug -PskipCxrGlobal=true
```

On the phone and glasses:

1. Open Assistant settings, grant **Phone calendar**, and confirm Android shows
   Calendar access as granted.
2. With the glasses worn, ask Assistant to create a uniquely titled event at a
   precise future local time. Confirm the success response appears through the
   normal Assistant notice/card/Ink path, then verify the title and time in the
   phone's calendar app.
3. Ask what is on the calendar for the matching window. Confirm the event is
   returned once at the same local time; a timezone offset must not be applied
   twice.
4. Ask to delete that exact title at that exact start time. Confirm the event is
   absent both from a second list request and from the phone calendar app.

Fail-closed cases:

- A missing title/start match reports that nothing was deleted.
- Two events with the same title and start time are reported as ambiguous and
  neither event is removed.
- If the matched event's title, start, or all-day identity changes before the
  provider delete, the guarded delete reports that the event changed and leaves
  it untouched.
- A recurring event is refused unless the request explicitly says to delete
  the whole series. If testing that scope, use a disposable series and verify
  every occurrence is removed.
- Revoking either Android calendar permission makes the tool report the missing
  grant and leaves calendar data unchanged.

Typed debug injection after speech recognition is sufficient to isolate the
provider/tool path, but it does not replace one physical assist-button and
microphone pass on worn glasses. Remove all disposable events after validation.

## Relay live reader validation

1. Open Relay's Messages inbox on the glasses, open a conversation, and leave
   the reader at its bottom.
2. Add a message to that same Android messaging-style notification. Confirm the
   open reader gains the message without Back/reopen and remains pinned to the
   new bottom.
3. Scroll away from the bottom, add another message, and confirm the reader
   refreshes without losing the current reading offset.
4. Start dictation or reach reply review, update the source notification, and
   confirm Relay does not replace the reply UI. Return to reading and confirm
   the updated thread is present.
5. While reading one conversation, update a different conversation. Confirm the
   open reader does not redraw; Back shows the updated conversation in the
   inbox list.

## Wireless ADB v1 validation

Software gate:

```powershell
.\gradlew.bat :shared:test :plugin-wireless-adb:testDebugUnitTest :plugin-wireless-adb:assembleDebug -PskipCxrGlobal=true
.\gradlew.bat :phone-hub:testDebugUnitTest :phone-hub:assembleDebug :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug
```

Use normally signed, upgrade-compatible phone and glasses hubs at version 1.4.1 or
newer for the complete plan below. Hubs 1.3.x cover the original pairing contract
but do not restore a disabled Wi-Fi radio. Do not uninstall, downgrade, or replace a
release-signed hub merely to make a debug build install. On validated Rokid Android
12L/API 32 glasses connected to the same LAN as the test computer:

1. Install Wireless ADB, approve only `wireless_debugging`, and open its settings.
   Confirm an unapproved or revoked plugin receives a capability rejection and no
   pairing service starts.
2. Tap **Enable & pair computer**. Run the displayed `adb pair` and `adb connect`
   commands before the two-minute deadline, then confirm `adb devices` shows the
   glasses without a cable.
3. Start a second pairing window, cancel it, and confirm its pairing command no
   longer succeeds. Start another window, let it expire, and confirm the same while
   ordinary wireless debugging remains enabled.
4. Restart the glasses hub during an active window. Confirm the code is not restored
   or displayed again, but the persisted deadline still stops the pairing service.
   Inject a pairing-stop failure: status must remain active, the expiry path must
   attempt a fail-closed transport disable, and a double failure must retain the
   session and retry rather than claim it closed.
5. Leave the settings screen idle for at least four five-second status cycles.
   **Glasses** and **Controls** must not flash, controls must remain enabled, and the
   transient `Checking glasses…` state must not appear for background refreshes.
   Tap a control while a status request is still in flight: the user action must run
   immediately after that status request rather than fail as busy. A real state
   change must still redraw once.
6. Tap **Disable wireless debugging** and confirm the existing `adb connect`
   endpoint closes and status reports disabled while the normal Wi-Fi connection
   remains up. Turn the Wi-Fi radio off, repeat **Enable & pair computer**, and
   confirm Nexus restores Wi-Fi, reconnects to a saved network, and produces a
   working pairing window. Repeat with no saved network available; expect
   `WIFI_REQUIRED` with no partial pairing state.
7. Verify unsupported API-level fixtures fail before any Binder transaction, every
   command-bridge argument remains fixed-input, malformed hosts/ports/codes are
   rejected, and stale or wrong-id replies cannot replace the active request.
8. Inspect phone and glasses logs after every path. They may contain action, result,
   and redacted state, but never the six-digit code, BSSID, device identity, full
   request/reply JSON, or an executable pairing command. Confirm Android blocks
   screenshots and screen recording while the settings screen displays the code.

## Ink Surface v1 validation

Software gate:

```powershell
.\gradlew.bat :shared:test :ink-engine:test :bus-client:testDebugUnitTest :plugin-sample:testDebugUnitTest :plugin-sample:assembleDebug -PskipCxrGlobal=true
.\gradlew.bat :phone-hub:testDebugUnitTest :phone-hub:assembleDebug :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug
```

On both devices:

- Install current hubs and Sample, request/approve `ink_surface`, open Sample,
  and confirm its Ink page appears before the card fallback. Revoke only
  `ink_surface`: the page must be rejected while ordinary `surfaces` still
  works. Adding the grant back requires explicit re-approval.
- Ask Assistant for a result that uses `render_template` or `render_ink_page`.
  Once the Ink page reports ready, confirm the drawing/listening notice retires
  and no stale `Thinking…` band or notice keepalive reappears over the page when
  the tool returns. A rejected render must restore progress for the text fallback.
- Activate **Tap to update** repeatedly. Confirm one action callback per tap,
  the metric/row/chart patch in place without a full surface flash, monotonically
  increasing revisions, and no stale patch repaint after hide/reopen.
- Force a lost patch or inject a debug `resync` and confirm the phone resends one
  complete current document, no more than once per second. A document-id or
  revision mismatch must request resync rather than mutate the wrong page.
- Open another plugin while Ink is visible, then return. Confirm the old owner
  receives `REPLACED`, cannot repaint after replacement, and a competing
  show/update receives `SURFACE_BUSY` without retry churn.
- Drop SPP while Ink is visible. Confirm `LINK_LOST`, local compiler-session
  cleanup, and a card fallback on the next open until `supportsInkSurface`
  becomes true again.
- Exercise chart animation, inline Lottie, progress, canvas, DPAD focus/scroll,
  tap dataset, and BACK on the 480×640 HUD. Record frame time, CPU, PSS, and
  display/battery behavior for M5; do not mark hardware conformance complete
  from JVM screenshots alone.
- Submit pages over every source/data/node/depth/chart/canvas/Lottie limit, plus
  `<script setup>`, a URL as a Lottie source, unsupported selectors/styles,
  malformed expressions, and unknown wire fields. Confirm typed `INK_*`
  problems, no crash, no network request, and no partial page left visible.
  An `image` URL is a separate negative case: it may label the v1 placeholder
  but must never be fetched or decoded.

## Native apps, remote input, and navigation validation

Software gate:

```powershell
.\gradlew.bat :shared:test :phone-hub:testDebugUnitTest :phone-hub:assembleDebug :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug
```

On both devices:

- Open **Glasses apps** on the phone. Confirm the list contains launchable
  native packages only, excludes the Nexus glasses hub, is label-sorted, and
  opens the selected installed app. An unknown/non-launchable package must fail
  visibly. Confirm there is no install action or APK transfer.
- Open **Keyboard & remote** and exercise previous, next, select, and back in a
  native app. Each unique request must perform at most once; replay the same
  request id and confirm the cached result returns without a second action.
  Stop the glasses AccessibilityService and confirm `service_unavailable`
  rather than a silent success.
- Focus an ordinary text field on the glasses. Confirm a new random session
  opens on the phone, commit/composition/delete/editor-action deltas apply in
  sequence, duplicate sequence values are not applied twice, and an out-of-order
  command reports the expected sequence. Change focus, close the phone screen,
  and drop transport; each must end/clear the old session.
- Focus a password or other sensitive editor. Confirm the phone window sets
  `FLAG_SECURE`, the UI identifies the sensitive session, screenshots are
  blocked, and neither existing field text nor surrounding/selected/extracted
  text appears in bus inspection, preferences, saved state, or logs. Only the
  newly typed transient delta may cross to the glasses.
- From a test plugin, attempt every `/core/native-apps/*`,
  `/core/remote-input/*`, `/core/navigation/*`, and `/core/pointer/*` path and
  receive prefix. Confirm the hub rejects them before routing regardless of the
  plugin's grants.

## Remote pointer and trackpad validation

Software gate:

```powershell
.\gradlew.bat :shared:test :phone-hub:testDebugUnitTest :phone-hub:assembleDebug :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug
```

On both devices:

- Open **Keyboard & remote** and drag on the trackpad with CXR-L up. Confirm the
  glasses' own system pointer moves with the finger, that Nexus draws no cursor
  of its own on this path, and that the behaviour is identical in the Rokid
  launcher and in a third-party app. Tap must click and hold must long-press
  where the pointer sits.
- Sustain a fast drag and confirm movement is coalesced to at most one update
  every 34 ms, with no queue growth or lag that outlives the gesture.
- Force the vendor `Tools` custom command to be unavailable. Confirm the phone
  falls back to the versioned `/core/pointer/*` contract with the Nexus-drawn
  cursor and an absolute normalized position, and that the wearer is never left
  without a pointer.
- On the fallback path, exercise the error contract: a stale sequence reports
  `stale_sequence`, a command after `hide` reports `stream_retired` or
  `stream_not_started`, an interrupted gesture reports `gesture_cancelled`, and
  stopping the glasses AccessibilityService reports `service_unavailable`
  rather than a silent success.
- Exercise the directional cross: up, down, left, right, select, and back.
  Confirm focus moves in the direction pressed, falls back to the next or
  previous item when the screen has nothing that way, and that no press is
  silently ignored.
- Focus a text field on the glasses and confirm the phone keyboard does **not**
  open on its own. It opens when the field is tapped on the phone.
- Let the glasses display sleep, then press a direction. Confirm the panel wakes
  instead of navigating a screen the wearer cannot see.

## Reader start anchor validation

Software gate:

```powershell
.\gradlew.bat :bus-client:testDebugUnitTest :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug
```

On both devices:

- Show a reader with no anchor and confirm today's behaviour is unchanged: it
  opens at the bottom, an update stays pinned there when the wearer was already
  near the end, and otherwise restores the previous offset.
- Show a reader with `NexusReaderAnchor.TOP`. Confirm it opens at the first
  segment. Scroll to the middle, replace the document with the same
  `contentKey`, and confirm the wearer keeps their offset and is never pulled
  to the new end.
- Send an update that omits `readerAnchor` with the same `surfaceId` and a
  matching or blank `contentKey`: the anchor is inherited. Send one with a
  different `contentKey` and confirm it falls back to the bottom.
- Send an unrecognised anchor value and confirm it is read as `bottom` rather
  than rejected. Against a pre-1.4.3 glasses hub, confirm `top` is ignored and
  the surface opens at the bottom as before.

## Assistant Hermes provider validation

Software gate:

```powershell
.\gradlew.bat :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug -PskipCxrGlobal=true
```

On the phone and glasses:

1. In Assistant settings, pick **Hermes**, enter the `/v1` root of a Hermes API
   server and its key, and confirm an ordinary spoken question streams back an
   answer.
2. Ask several questions in one conversation and confirm every request carries
   the same `X-Hermes-Session-Id`. Confirm a conversation older than seven days
   starts a fresh session.
3. Point an existing **Custom** connection at a Hermes server and confirm it
   recognises itself as Hermes from the capability manifest's own object name.
   Point it at a plain OpenAI-compatible server and confirm it stays Custom.
4. Ask what you are looking at. Confirm the private photo token never reaches
   the wearer's band or card, the glasses take the shot, and the turn is
   replayed with the image attached.
5. Ask for a reminder, a timer, a note, and a calendar add/list/delete. Confirm
   the `[[NEXUS_TOOL]]` control line is filtered out of the stream, the calls
   run in order, and the turn is replayed with their results as plain text. The
   calendar fail-closed cases above must behave identically on this path.
6. Confirm Ink is absent from the Hermes bridge: no `render_ink_page` or
   `render_template` is offered, and answers land on the ordinary text/card
   path.
