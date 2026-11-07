# Plan 032 — Glasses sound in the phone's earbuds (glasses audio relay v1)

Status: TODO. Hub-to-hub feature; no plugin, SDK, AIDL or `/core` plugin surface. Both hubs change; `:shared` carries the contract; BUSSPEC.md is the wire authority.

## 1. Summary

When the phone's active audio output is a non-glasses headset (earbuds, wired, USB), the glasses hub starts a shell-uid capture of the glasses' whole output mix (`AudioRecord` on `REMOTE_SUBMIX`, which on fw 1.28.016 is measured to mute the glasses speaker while active), Opus-encodes it, and streams it to the phone over a **second, dedicated, authenticated insecure RFCOMM socket** on the same Bluetooth link the bus already uses. The phone decodes and plays it with an `AudioTrack` on its current media output. One phone-side toggle, off by default. The capture is bound to the phone's liveness through layered keepalives so a dead phone, a dead link, unplugged buds, a dead hub or a toggled-off switch always gives the glasses their speaker back within seconds.

Measured facts this plan relies on (2026-11-07, glasses fw 1.28.016, Android 12 / API 32; do not re-derive): glasses have no A2DP source role (only A2dpSink, AvrcpController, HeadsetClient); `com.android.shell` holds `CAPTURE_AUDIO_OUTPUT`; scrcpy's `app_process` server (shell uid, `REMOTE_SUBMIX`, Opus encoded on the glasses via MediaCodec — an `.opus` recording was produced) captured the Hi Rokid assistant voice and silenced the glasses speaker while active (owner-confirmed).

## 2. Transport decision

**Chosen: (b) a second insecure RFCOMM socket, new UUID, glasses = server, same pairing key and the existing `SppAuthProtocol` handshake + authenticated frame format.** Not CXR, not Wi-Fi.

Why not (a) multiplexing on the bus SPP socket:
- Bus writes are strictly serialized per session (`SppAuthProtocol.Session.write` holds one `writeLock`, allocates one sequence counter) and the bus carries 32 KiB photo/file chunks (`MediaSyncTransferContract.CHUNK_BYTES`, comment measures ≈90 ms wire time each), 192 KiB frozen-image chunks and frames up to 2 MiB. A 40 ms audio frame queued behind one of those waits 90 ms to seconds. Audio and the two bulk uploads (photo sync, files) all flow glasses→phone, so this head-of-line blocking is the common case.
- Every bus frame on the phone goes through `readSppLoop → routeRemote` (plugin routing) on the single reader thread; 25 audio frames/s do not belong there.
- `MediaSyncEngine.trafficMonitor.note(path)` treats any non-mediasync path as foreign traffic and pauses photo sync around it; continuous audio would starve photo sync.
- A separate RFCOMM channel on the **same ACL link** costs no new radio connection; L2CAP interleaves the two channels at roughly 1 KB granularity.

Why not (c) LE L2CAP CoC: neither hub has LE code; it needs `BLUETOOTH_ADVERTISE`/`BLUETOOTH_SCAN` on the glasses (BUSSPEC deliberately avoids `BLUETOOTH_SCAN`), LE peripheral support on fw 1.28 is unmeasured, and it adds a dual-mode LE connection to a radio already carrying A2DP sink, CXR and the bus. No benefit for a ~50 kbps stream.

Airtime: Opus 48 kHz mono at 32 kbps + framing ≈ 50 kbps total; RFCOMM delivers ≥1 Mbps on this link.

Framing reuse: the audio socket speaks exactly the bus's authenticated frame format (`length:u32 || 0x02 || seq:u64 || innerBody || tag`), so `AuthenticatedSppServer` (glasses) and `PhoneSppPairing.authenticate` + `SppAuthProtocol.Session` (phone) are reused unchanged. Per-frame overhead ≈ 130 bytes; at 40 ms per frame ≈ 26 kbps, accepted to reuse tested code.

**Which process opens the BT socket: the glasses-hub app, never the shell-uid capture process.** The app holds `BLUETOOTH_CONNECT`, the `SppKeyStore`, and the tested admission/handshake code; a bare `app_process` has no app identity for the Bluetooth service. The capture process is a dumb producer whose life is bound to a loopback TCP connection the app owns — which is also what makes the fail-safe trivial.

**Local IPC capture process → app: loopback TCP (127.0.0.1), app = server on an ephemeral port, capture = client.** Not files (the app must never depend on reading a shell-created file), not an abstract `LocalSocket` (shell↔untrusted_app `unix_stream_socket connectto` is unverified on this ROM). Loopback TCP is already the proven app↔other-uid path here (`AdbLoopbackClient`, `SelfArmController.adbLoopbackListening`, `GlassesFilesLaneServer`'s `ServerSocket(0, …)`). The app binds first, so the port is unforgeable; the client proves itself with a 32-byte token derived from the bridge secret.

## 3. Architecture

### 3.1 Processes and sockets (glasses)

```
phone-hub ──(RFCOMM, AUDIO_SPP_UUID, SppAuthProtocol)──► glasses-hub app: GlassesAudioRelayServer
                                                               │ GlassesAudioRelaySession (one at a time)
                                                               │   ├─ ServerSocket(127.0.0.1:0)
                                                               │   ├─ bridge request audio_capture_start:<nonce>:<port>:<token>
                                                               │   └─ reads HEADER/PACKET/STATUS records, writes KEEPALIVE
                                                               ▼
                       shell uid: rokid-nexus-cmd-bridge.sh ──spawns──► app_process GlassesAudioCaptureMain
                                                                         (AudioRecord REMOTE_SUBMIX 48k stereo → mono → MediaCodec audio/opus)
```

- `GlassesAudioRelayServer` (new, `glasses-hub`): mirror of `SppServerManager` — accept loop on `listenUsingInsecureRfcommWithServiceRecord("RokidBusAudio", BusConstants.AUDIO_SPP_UUID)`, a second `AuthenticatedSppServer` over the same `SppKeyStore(context)`. Its `onConnected` drives the session only; it MUST NOT call `GlassesHub.onSppConnected` or touch link state. `installPairingKey` is forwarded here from wherever `SppServerManager.installPairingKey` is called (grep the call site).
- `GlassesAudioRelaySession` (new): state machine `AWAITING_START → ATTACHING_CAPTURE → STREAMING → CLOSED`, with injected clock/scheduler/executor (pattern: `AuthenticatedSppServer` constructor). Validates `start`, binds the loopback server, submits the bridge command, accepts exactly one client whose first 32 bytes equal `audioSessionToken(secret, nonce)`, forwards the `HEADER` as `/core/audio-relay/ready`, packs `PACKET`s two per `/core/audio-relay/frame`, sends `/core/audio-relay/status` every 2 s, writes a local `KEEPALIVE` every 1 s, and tears everything down on any failure or timeout. Teardown = close local socket (capture exits), submit `audio_capture_stop` (best effort), close the RFCOMM socket.
- `GlassesAudioCaptureMain` (new, `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/audio/`): `object` with `@JvmStatic fun main(args)`; args `<port> <nonce>`, env `NEXUS_AUDIO_TOKEN`. Runs under `app_process` with the installed APK as `CLASSPATH`. Uses **only** what scrcpy's `AudioDirectCapture`/`Workarounds` do for API 31/32 (fake `ActivityThread`/app info for package `com.android.shell`, `AttributionSource` for uid 2000, reflective `AudioRecord` construction, `REMOTE_SUBMIX`, 48 000 Hz, `CHANNEL_IN_STEREO`, PCM16). Mono downmix `(L+R)/2`, 20 ms blocks (960 frames), MediaCodec `audio/opus` encoder (48 kHz, 1 ch, `KEY_BIT_RATE` 32 000, synchronous mode, no Looper). Sends the three CSD buffers from `INFO_OUTPUT_FORMAT_CHANGED` as the `HEADER`. Silence suppression: a 20 ms block with peak |sample| < 16 for 25 consecutive blocks enters SILENT; while SILENT no `PACKET`s are sent (encoder keeps running); any block ≥ 16 leaves SILENT. Exits on: socket EOF/IOException, no `KEEPALIVE` for 5 s, `STOP` record, `AudioRecord`/codec failure (after logging exception class+message to logcat tag `NexusAudioCapture`). No wake locks, no files except the pidfile the bridge writes.
- Bridge script (`rokid-nexus-cmd-bridge.sh`): two new commands, `VERSION` bumped (and `SelfArmConstants.BRIDGE_VERSION` in lockstep). The bridge self-updates from the installed APK within `SELF_UPDATE_INTERVAL` (300 s), so an APK install is enough.
  - `audio_capture_start:<nonce>:<port>:<token>` — `port` decimal 1024..65535; kills any previous capture process (pidfile `$BASE/rokid-nexus-audio.pid` + cmdline check like `is_bridge_pid`), computes `audio_token="$(printf '%s' "${SECRET}:audio-session:${nonce}" | sha256sum | cut -d' ' -f1)"`, resolves `APK="$(pm path "$PACKAGE" | sed -n 's/^package://p' | head -n 1)"`, spawns `NEXUS_AUDIO_TOKEN="$audio_token" CLASSPATH="$APK" nohup app_process /system/bin --nice-name=rokid-nexus-audio com.anezium.rokidbus.glasses.audio.GlassesAudioCaptureMain "$port" "$nonce" >/dev/null 2>&1 </dev/null &`, writes the pidfile, writes the usual response. Success is observed by the app as "a client with the right token connected within 5 s", never by reading the response file.
  - `audio_capture_stop:<nonce>:<token>` — kills the pidfile process if its cmdline contains `GlassesAudioCaptureMain`; always answers ok.
  - Neither command may affect `prepare`/`arm` success.

### 3.2 Processes (phone)

- `PhoneAudioRelayStore` (new): pref `glasses_audio_relay` (default `false`) + last status string, change listener (pattern: `PhoneBatteryBadgeStore`).
- `PhoneAudioRelayGate` (new, pure): `desired(enabled, route: PhoneTtsRoute, glassesCapable, sppDataUp) = enabled && route == EXTERNAL_SINK && glassesCapable && sppDataUp`. Route comes from the existing `PhoneSpeakerRouteProbe.classifyRoute()` (which already excludes the glasses by `PREF_LAST_GLASSES_ADDRESS`); re-evaluated on `AudioManager.registerAudioDeviceCallback` events (debounced 500 ms), on capability announcements, on link state changes, on the pref, and every 15 s while connected.
- `PhoneAudioRelayClient` (new): own thread `rokidbus-audio`; while `desired`: `pickBondedDevice()` (reuse), `sppPairing.prepare(address, ::sendSppProvisioning)`, `createInsecureRfcommSocketToServiceRecord(AUDIO_SPP_UUID)`, `connect()`, `SppHandshakeDeadline`, `sppPairing.authenticate(...)` with its **own** `RecentNonces`, then send `start`, expect `ready` within 3 s, then read loop; send `keepalive` every 2 s; close if no glasses envelope for 6 s. Backoff on failure: reuse `SppReconnectBackoff` (separate instance), cap 30 s. When `desired` flips false: close the socket at once (`stop` first, best effort). MUST NOT touch `notifyLinkState`, `SPP_DATA_UP`, `sppSession`, or route any audio-socket envelope through `routeRemote`.
- `AudioRelayJitterBuffer` (new, pure): packets keyed by `seq`; prime depth 2 frames (80 ms) before output; max depth 8 frames (320 ms) → drop oldest; on empty → re-prime; seq gaps skipped.
- `PhoneAudioRelayPlayer` (new): MediaCodec `audio/opus` decoder configured from `ready` (`csd-0/1/2`), `AudioTrack` 48 kHz mono PCM16, `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`, `MODE_STREAM`, `PERFORMANCE_MODE_LOW_LATENCY`, buffer 4 × 20 ms. Routing guard: `addOnRoutingChangedListener` — if `routedDevice.address` equals the glasses address, stop playback and report `ROUTE_IS_GLASSES` to the gate (the gate re-evaluates and closes).
- Settings (owner decision: accepted default): one inline switch row in `SettingsActivity` directly under `voiceRow()`, built like `glassesRepairToggleRow()` (title + `rowSub` + `NexusUi.switch`), plus a status `rowSub` below rendered from the store's status and refreshed via the pref listener and `onResume`. Title **"Glasses sound in earbuds"**; subtitle **"When earbuds or headphones are your phone's output, what the glasses play — Hi Rokid's voice included — plays there and the glasses stay quiet."** Status labels: `Off`, `Waiting for earbuds`, `Waiting for glasses`, `Connecting…`, `Active`, `Glasses setup needed — run Repair now`, `Glasses hub too old`.

### 3.3 Wire format (BUSSPEC addition: "## Glasses audio relay v1")

Constants in `:shared`:
- `BusConstants.AUDIO_SPP_UUID_STRING = "3fd6b1e2-9a47-4c0b-8e5d-71c2a9f0b4d8"`, `AUDIO_SPP_UUID`, `AUDIO_SERVICE_NAME = "RokidBusAudio"`.
- `BusCapabilityBits.GLASSES_AUDIO_RELAY = 1 shl 18` (glasses-only; verify bit 18 is free on origin/main and take the next free bit otherwise; phone attempts the audio socket only when the current announcement carries it and the bus data plane is up).
- `BusPaths.CORE_AUDIO_RELAY_PREFIX = "/core/audio-relay"` plus `…_START`, `…_READY`, `…_FRAME`, `…_STATUS`, `…_KEEPALIVE`, `…_STOP`, `…_ERROR`. `/core` is already reserved by `PathRules`; both hubs additionally **drop** any `/core/audio-relay/*` envelope that arrives on the **bus** socket (log once, no dispatch).
- `GlassesAudioRelayContract` (new, `:shared`): `VERSION = 1`, the timeouts below, payload builders and `validate*()` returning sealed `Valid/Invalid` (pattern: `PhoneBatteryContract`), frame data pack/unpack.

Socket: insecure RFCOMM on `AUDIO_SPP_UUID`, glasses server, phone client, the "Mutual handshake" and "Authenticated frames" of *Authenticated SPP transport v1* verbatim with the same pairing key (fresh nonces, independent frame keys per socket). The glasses admit one authenticated audio session at a time; additional sockets are closed. This socket never carries bus traffic and never changes `SPP_DATA_UP`.

Envelopes (JSON unless stated; all `v:1`):

| Direction | Path | Payload |
|---|---|---|
| phone→glasses | `/core/audio-relay/start` | `{"v":1,"codecs":["opus","pcm16"],"keepaliveMs":2000}` — must be the first envelope within 3 s of Ready |
| glasses→phone | `/core/audio-relay/ready` | `{"v":1,"codec":"opus","sampleRate":48000,"channels":1,"frameMs":20,"packetsPerFrame":2,"csd0":"<b64>","csd1":"<b64>","csd2":"<b64>"}`; for `pcm16`: `sampleRate:16000`, no csd |
| glasses→phone | `/core/audio-relay/frame` | **binary**; meta `{"seq":<int, from 0, +1 per frame>,"ms":<elapsedRealtime of first packet>}`; data = 1..4 × `[u16 BE len][packet]`, total ≤ 4096 B; envelope `id` = `"ar-<seq>"` |
| glasses→phone | `/core/audio-relay/status` | `{"seq":<last seq>,"silent":bool}` every 2 s and on every silent transition |
| glasses→phone | `/core/audio-relay/error` | `{"code":"BRIDGE_UNAVAILABLE"\|"CAPTURE_FAILED"\|"ENCODER_UNAVAILABLE"\|"BUSY"\|"UNSUPPORTED"}` then close |
| phone→glasses | `/core/audio-relay/keepalive` | `{}` every 2 s |
| phone→glasses | `/core/audio-relay/stop` | `{}` then close (closing alone is also a stop) |

Timeouts (contract constants): `START_TIMEOUT_MS 3000`, `CAPTURE_ATTACH_TIMEOUT_MS 5000`, `KEEPALIVE_INTERVAL_MS 2000`, `PEER_TIMEOUT_MS 6000` (both directions), `LOCAL_KEEPALIVE_INTERVAL_MS 1000`, `LOCAL_KEEPALIVE_TIMEOUT_MS 5000`, `JITTER_PRIME_FRAMES 2`, `JITTER_MAX_FRAMES 8`, `SILENCE_PEAK 16`, `SILENCE_HOLD_BLOCKS 25`.

Local capture protocol (loopback TCP, internal to the glasses, documented in the contract's KDoc, not in BUSSPEC): client first writes the 32-byte token (hex-decoded `sha256("<secretHex>:audio-session:<nonce>")`); then records `[type:u8][len:u16 BE][body ≤ 4096]`: `0x01 HEADER` (the `ready` JSON minus `v`), `0x02 PACKET` (one Opus packet, or one 20 ms PCM16 block), `0x03 STATUS` (`{"silent":bool}`); app→capture `0x10 KEEPALIVE` (len 0), `0x11 STOP`.

### 3.4 Fail-safe matrix

| Event | Detected by | Speaker back within |
|---|---|---|
| Toggle off / buds unplugged / route becomes glasses | phone gate → closes socket | ≈ 0.5 s debounce + EOF → capture exits on local close: ≤ 2 s |
| Phone hub dies | kernel closes RFCOMM → glasses EOF | ≤ 2 s |
| Phone hub hung, or BT link lost | glasses `PEER_TIMEOUT_MS` watchdog (scheduled timer closes the socket; never relies on a blocking read waking) | ≤ 6 s + ~1 s |
| Glasses hub dies | kernel closes loopback → capture EOF | ≤ 1 s |
| Glasses hub hung | capture `LOCAL_KEEPALIVE_TIMEOUT_MS` | ≤ 5 s |
| Orphan from a previous hub incarnation | hub start sends `audio_capture_stop` once (best effort, only if a bridge secret exists) and every new `audio_capture_start` kills the previous pid | at next hub start / next session |

### 3.5 Behaviour decisions

- **Gating**: relay only when the phone's winning media output is an external non-glasses sink (`PhoneTtsRoute.EXTERNAL_SINK`). Phone speaker → off (owner default for v1). Glasses as the phone's active A2DP → off (loop prevention). Known limitation inherited from the classifier: below API 33 the scan heuristic reports `GLASSES_LINK` whenever the glasses are A2DP-connected at all. Do not change the classifier in this plan.
- **Continuous vs on-demand capture**: continuous while gated on. On-demand is not viable: `REMOTE_SUBMIX` mutes the speaker only while recording, `AudioRecord` start takes 100–300 ms, and there is no cross-uid "playback is about to start" signal. Costs are bounded by silence suppression and by the gate.
- **Volume**: phone media volume sets loudness in the buds. Glasses volume keys are not intercepted and the glasses volume is not written. Whether the glasses' own volume scales the captured mix is a device check (D9).
- **Latency budget** (Nexus-controlled part): ≈ 200–230 ms. The earbuds' own A2DP latency (typically 100–200 ms) is additive; state this in the CHANGELOG line.
- **Codec**: Opus is confirmed available on the glasses (scrcpy encoded Opus there). The `pcm16` path exists only at contract-validation level; do not implement a pcm16 capture/playback pipeline in v1.

## 4. Milestones

Each milestone ends with the exact commands in §6 green and the listed acceptance criteria met. Commit at the end of every milestone (at minimum). Do not start the next milestone with a red suite. Do not widen scope.

### M0 — Owner pre-checks (owner only, not the executor)

Opus encoder: DONE (present). Still open for the owner, during a scrcpy capture: which sources are relayed (assistant, notification sound, glasses UI sound, `rfm-tts`, phone→glasses A2DP music), and whether glasses volume keys change captured loudness. The executor does not wait for these.

### M1 — Shared contract and spec

Files: `shared/src/main/java/com/anezium/rokidbus/shared/BusConstants.kt` (UUID, service name, capability bit, paths), new `shared/src/main/java/com/anezium/rokidbus/shared/GlassesAudioRelayContract.kt`, `BUSSPEC.md` (new section "Glasses audio relay v1" after "TTS protocol v1"; add the capability bit wherever capability bits are listed), tests `shared/src/test/java/com/anezium/rokidbus/shared/GlassesAudioRelayContractTest.kt`, extend `PathRulesTest` (`/core/audio-relay/frame` is reserved for plugins) and `GlassesHubCapabilitiesContractTest` (bit round-trips).

Tests MUST cover: start/ready/status/error validation (version, codec list, csd presence per codec, bounds), frame pack/unpack (1..4 packets, ≤ 4096, rejects zero-length and overflow), envelope id derivation, constants equal the table in §3.3.

Acceptance: `:shared:test` green; BUSSPEC section consistent with the contract; no change to `SppAuthProtocol`, `FrameProtocol`, the bus UUID or `API_VERSION`.

### M2 — Glasses capture process and bridge commands

Files: new `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/audio/GlassesAudioCaptureMain.kt` (+ `AudioCaptureWorkarounds.kt`, `OpusEncoderPump.kt`, `LocalCaptureProtocol.kt` as needed, all under `glasses/audio/`), `glasses-hub/src/main/assets/rokid-nexus-cmd-bridge.sh` (two commands, `VERSION` bump), `SelfArmConstants.kt` (`BRIDGE_VERSION`), `SelfArmCommandBridgeClient.kt` (`AUDIO_CAPTURE_START/STOP` in `SelfArmCommandBridgeProtocol` with shape validation, `audioSessionToken(secretHex, nonce)`, `startAudioCapture(context, port, nonce)` / `stopAudioCapture(context)` that observe no response file), `glasses-hub/proguard-rules.pro` (`-keep class com.anezium.rokidbus.glasses.audio.GlassesAudioCaptureMain { *; }`), tests `SelfArmCommandBridgeProtocolTest` (new shapes, token, rejects port 0/70000/non-numeric, replay), new `LocalCaptureProtocolTest` (record framing, silence detector truth table on synthetic PCM, mono downmix), new `glasses-hub/src/test/bridge/audio_capture_test.py` (bridge parses the two commands, kills a stale pid, spawns with the expected env/argv — `app_process` and `pm` mocked like `files_cleanup_test.py` mocks `cmd`), dev tool `glasses-hub/src/test/bridge/audio_capture_probe.py` (PC listener: accepts one TCP client, prints token hex, HEADER JSON and per-second packet/byte counts, writes KEEPALIVE every second).

Rules: port only the API 31/32 branch of scrcpy's `AudioDirectCapture`/`Workarounds`/`FakeContext`; put `// Derived from scrcpy (Apache-2.0), <file>` on each ported function; synchronous MediaCodec.

Device check M2-D (executor MAY run it: the glasses `1901092534053723` are on adb; read-only except pushing nothing — use the installed debug APK only if the owner installed it; otherwise leave M2-D for the owner and say so): with a PC, `adb reverse tcp:47000 tcp:47000`, run `audio_capture_probe.py 47000`, then on the glasses run the exact spawn line from the script with `port=47000`: the probe prints a HEADER with `"codec":"opus"` and three csd fields, packet counters rise while audio plays, stay at 0 while silent, the process exits within 5 s after the probe stops sending keepalives. Stop condition: if `AudioRecord` creation fails in our process while scrcpy works, report the exact exception and stop — do not fall back to app-side capture, `MediaProjection`, or `pm grant`.

Acceptance: suites and builds green; `python -m unittest discover -s glasses-hub/src/test/bridge -p "*_test.py"` green (existing tests included).

### M3 — Glasses relay server and session

Files: new `GlassesAudioRelayServer.kt`, `GlassesAudioRelaySession.kt` (glasses-hub), edits to `GlassesHub.kt` (start the server next to `SppServerManager.ensureStarted`; add `GLASSES_AUDIO_RELAY` to the features in the capabilities announcement; drop `/core/audio-relay/*` in `onRemoteEnvelope` before controllers; one best-effort `stopAudioCapture` at start), the `installPairingKey` call site (forward to the audio server), tests `GlassesAudioRelaySessionTest` (fake peer via `PipedInputStream/PipedOutputStream` like `AuthenticatedSppServerTest`, fake local socket pair, fake clock/scheduler) and `GlassesAudioRelayServerTest` (second socket refused while one is active; audio server never calls the hub's link-state callback).

Session tests MUST cover: no `start` within 3 s → close, nothing spawned; bad `start` → `error UNSUPPORTED` + close; bridge failure → `error BRIDGE_UNAVAILABLE`; no token within 5 s → `BRIDGE_UNAVAILABLE`, local server closed, `stop` submitted; wrong token → connection closed; happy path: HEADER → `ready`, 2 PACKETs → 1 `frame` `seq 0`, next 2 → `seq 1`, STATUS silent → `status`; capture EOF → `error CAPTURE_FAILED` + close; no phone envelope for 6 s → local + RFCOMM closed via the scheduled watchdog with the peer read still blocked; phone close → local socket closed within one tick and `stop` submitted; a second session after the first closes works.

Acceptance: `:glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug` green; bus ingress of `/core/audio-relay/*` dropped (test); `SppServerManager`, `AuthenticatedSppServer`, `SppAuthProtocol` unchanged except the key-forwarding call.

### M4 — Phone client, gate, player, settings

Files: new `PhoneAudioRelayStore.kt`, `PhoneAudioRelayGate.kt`, `PhoneAudioRelayClient.kt`, `AudioRelayJitterBuffer.kt`, `PhoneAudioRelayPlayer.kt` (phone-hub); edits `BusHubService.kt` (construct store/gate/client after `phoneSpeakerRouteProbe`; `AudioDeviceCallback` registration/unregistration; feed capability bit and `SPP_DATA_UP` into the gate; drop `/core/audio-relay/*` in `routeRemote`; static `onGlassesAudioRelaySettingChanged()` like the other preference hooks; stop client in `onDestroy`), `SettingsActivity.kt` (row + status under `voiceRow()`), tests `PhoneAudioRelayGateTest` (full truth table incl. `GLASSES_LINK`, `PHONE_SPEAKER`, capability missing, SPP down), `AudioRelayJitterBufferTest` (prime, drop-oldest, re-prime after underrun, gap skip), `PhoneAudioRelayClientTest` (fake socket streams: sends `start` first, closes if no `ready` in 3 s, keepalive cadence with fake clock, closes after 6 s silence, closes immediately when gate flips off, backoff after failure, never calls bus `routeRemote`), `PhoneAudioRelayStoreTest` (default off; status round-trip).

Rules: reuse `pickBondedDevice`, `sppPairing.prepare/authenticate`, `SppHandshakeDeadline`, `SppReconnectBackoff`; no new manifest permissions; player decodes in its own thread; `AudioTrack` never created before `ready`.

Acceptance: `:phone-hub:testDebugUnitTest :phone-hub:assembleDebug` green; toggle off by default; toggle off → nothing connects (test); `SPP_DATA_UP` semantics and `notifyLinkState` untouched.

### M5 — Docs

Files: `CHANGELOG.md` (unreleased entry under both hubs: "Glasses sound in earbuds", one sentence on the buds' own A2DP latency), `README.md` (one line in the phone-app feature list), `plans/README.md` (table row 032), `docs/SELF_ARM_ONBOARDING.md` (wherever bridge commands are listed, add the two; bump the quoted bridge version). No version-code/version-name bump; no release claims.

## 5. Device test protocol (owner, real glasses + phone + earbuds)

Setup: install both debug hubs; wait ≤ 5 min or run Settings → Repair now so the bridge version shows the new version. Earbuds paired to the phone.

| # | Steps | Pass criteria |
|---|---|---|
| D0 | M0 results recorded | source table filled (assistant / notif / UI / rfm-tts / A2DP music) |
| D1 | Buds active on phone; toggle on | Status `Active` ≤ 5 s; "Hi Rokid" reply heard in buds; glasses speaker silent |
| D2 | While `Active`: assistant reply, notification sound, glasses UI sound, Rokid music app | Each noted relayed / not relayed / still audible; nothing heard twice |
| D3 | Speak to the assistant 10× over 10 min | Delay ≤ ~0.5 s and not growing; jitter depth stable (≤ 8), no decoder errors |
| D4 | Buds in case | Glasses speaker back ≤ 3 s; `Waiting for earbuds`. Buds out → `Active` ≤ 10 s |
| D5 | Toggle off | Speaker back ≤ 2 s; `ps -A \| grep rokid-nexus-audio` empty ≤ 5 s |
| D6 | Force-stop the phone app | Speaker back ≤ 8 s; capture process gone |
| D7 | Out of BT range (or phone airplane mode) | Speaker back ≤ 10 s; on return `Active` ≤ 30 s unattended |
| D8 | `am force-stop com.anezium.rokidbus.glasses` from PC adb | Capture gone ≤ 6 s; after hub returns, `Active` ≤ 30 s |
| D9 | Phone media volume; glasses volume keys | Buds follow the phone; record glasses-key effect; speaker silent throughout |
| D10 | Photo sync / file download during `Active`; plugin notice | No dropout > 1 s; transfer completes; notice arrives |
| D11 | Glasses become the phone's active A2DP output | `Waiting for earbuds`; no capture; no echo; back to buds → `Active` |
| D12 | Phone music during `Active` | Music in buds only; glasses silent; no doubling |
| D13 | `Active` idle 30 min | Glasses battery within a few % of a 30-min baseline; `silent:true` statuses, zero frames |

## 6. Commands (exact; repo root; PowerShell `.\gradlew.bat`, Git Bash `./gradlew`)

```
./gradlew :shared:test
./gradlew :phone-hub:testDebugUnitTest :phone-hub:assembleDebug
./gradlew :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug
python -m unittest discover -s glasses-hub/src/test/bridge -p "*_test.py"
```

Hubs are built **without** `-PskipCxrGlobal=true`. Never modify or regenerate `local.properties`; an environment failure is reported, not repaired. Paste the real tail of each command's output in the milestone report.

## 7. MUST-NOTs

- MUST NOT send audio, keepalives or any `/core/audio-relay/*` envelope over the bus SPP socket, CXR, Wi-Fi, or the files lane.
- MUST NOT add a plugin capability, SDK API, AIDL change, receive prefix, or any route reachable by a plugin; `/core/audio-relay/*` arriving on the bus is dropped by both hubs. MUST NOT edit `docs/PLUGIN_SDK.md` or `bus-client`.
- MUST NOT change `SppAuthProtocol`, its domain string, `FrameProtocol`, `BusConstants.SPP_UUID`, `API_VERSION`, `SPP_DATA_UP` semantics, `notifyLinkState`, or `GlassesHub.onSppConnected` behaviour.
- MUST NOT let the shell process open the Bluetooth socket, and MUST NOT make the app read any file the shell process creates (pidfile, logs, responses); success is observed through the loopback connection.
- MUST NOT use `MediaProjection`/`AudioPlaybackCapture`, app-side `RECORD_AUDIO`, or attempt `pm grant … CAPTURE_AUDIO_OUTPUT`; MUST NOT add `RECORD_AUDIO` to the glasses manifest or any permission to the phone manifest.
- MUST NOT let `audio_capture_*` failures affect bridge `prepare`/`arm` success; MUST keep the bridge's fixed command whitelist shape (exact field count checks, nonce replay, SHA-256 token) for the new commands.
- MUST NOT capture when the toggle is off, when the phone's route is not `EXTERNAL_SINK`, when the capability bit is absent, or when the bus data plane is down; MUST NOT write the glasses volume.
- MUST NOT log the pairing key, bridge secret, session token, Bluetooth addresses, or raw audio.
- MUST NOT call `cancelDiscovery()`, use secure RFCOMM, or add LE code.
- MUST NOT change defaults (toggle is off), bump version codes, write release notes claiming hardware validation, or touch `ROADMAP.md`.
- MUST NOT restyle settings code or refactor `BusHubService`/`GlassesHub` beyond the listed insertions.
- MUST NOT install APKs on the phone or glasses, push, open PRs, or merge. Owner go is required for those.
- MUST NOT continue past a failed device stop condition (M2-D) by inventing an alternative capture path; report and stop.

## 8. Risks

- `AudioRecord` construction under `app_process` on API 32 needs scrcpy's reflective workaround; mitigated by porting the measured code path and by M2-D before any BT plumbing.
- Opus CSDs: Android's decoder needs csd-0/1/2; the `ready` payload carries all three.
- Bridge rollout: the self-updater only installs a candidate whose shape and `sh -n` pass; keep the new code POSIX-sh and placeholder-count-safe (exactly one secret slot).
- RFCOMM channel count: the glasses now expose two Nexus SDP records; D1 confirms both connect concurrently.
