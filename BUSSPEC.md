# RokidBus — Bus Specification v1 (Round A)

Status: 2026-07-04 — probe project validated both hardware gates (see TESTPLAN.md header).
This spec turns the probe into the real hub + client library. Round A does NOT touch
Rokid Relay yet; it must end with a bus a real app could ride.

## Non-negotiable constraints (validated on hardware, do not re-derive)

- **CXR-M is banned.** Phone side rides **CXR-L only** (AIDL bind into Hi Rokid's
  `com.rokid.sprite.aiapp...MEDIA_STREAM_SERVICE` via the CxrGlobal wrapper). Exactly
  **one** CXR-L session may exist on the phone: the phone hub owns it. No client app
  ever links CXR-L/CXR-M directly.
- Glasses side uses **CXR-S** (`com.rokid.cxr.CXRServiceBridge`) — the glasses hub owns
  the subscription. Clients never subscribe themselves.
- **Data plane** = the hub-owned custom-UUID RFCOMM SPP socket already validated:
  UUID `0b005957-ec6d-4af5-bcba-6c786c46634e`, glasses = server
  (`listenUsingInsecureRfcommWithServiceRecord`), phone = client. The current
  validated device-selection logic tries its configured bonded device address
  first and its configured bonded name second; public docs do not retain either value.
  Never call `cancelDiscovery()` (needs BLUETOOTH_SCAN).
- Glasses hub is anchored on an **AccessibilityService** (armed once via ADB, appended
  to Relay's service — never overwrite the secure setting). `startService` on an idle
  package is blocked (Android 12 bg limits): the supervisor mechanism is
  **bindService(BIND_AUTO_CREATE)**. Package visibility: the hub needs a `<queries>`
  entry (use the intent-action form below, not per-package).
- Glasses Wi-Fi stays OFF. All glasses-side internet goes through the phone hub HTTP
  proxy over the bus.
- Reference for CXR-L auth + lifecycle patterns: `E:\Tools\Rokid\Rokid Relay\phone\src\main\java\com\anezium\rokidrelay\phone\CxrLAuth.kt`
  (Hi Rokid AuthorizationActivity → token) and `RelayBridge.kt` (CXRLink lifecycle,
  reconnect, `ICXRLinkCbk`). Glasses CXR-S pattern: `Rokid Relay\glasses\...\RelayBridge.kt`
  (`CXRServiceBridge.subscribe(key, cb)`, `onReceive(msgType, caps, data)`).

## Modules (evolve the existing repo in place)

| Module | Type | Package | Contents |
|---|---|---|---|
| `:shared` | kotlin lib | `com.anezium.rokidbus.shared` | envelope + frame codec (exists — keep) |
| `:bus-client` | android lib | `com.anezium.rokidbus.client` | AIDL files + `BusClient` wrapper + `BusClientService` base |
| `:phone-hub` | app | `com.anezium.rokidbus.phone` | FGS hub: CXR-L owner, SPP client, AIDL server, HTTP proxy, auth UI |
| `:glasses-hub` | app | `com.anezium.rokidbus.glasses` | a11y anchor, CXR-S owner, SPP server, AIDL server, supervisor |
| `:phone-client-probe` | app | `com.anezium.rokidbus.phoneprobe` | sample client using `:bus-client` |
| `:glasses-client-probe` | app | `com.anezium.rokidbus.clientprobe` | rework to use `:bus-client` |

**Build system: switch to standard AGP** (the hand-rolled aapt2/d8 pipeline was a
sandbox workaround — delete it). Mirror the toolchain of `E:\Tools\Rokid\Rokid Relay`
(AGP + Kotlin versions, `settings.gradle.kts` with google/mavenCentral +
`maven.rokid.com`, and `includeBuild("../CxrGlobal")` dependency substitution for the
phone hub). Do not build-verify in the sandbox if AGP can't run there — the operator
builds locally with Gradle 9.5.1.

## Wire envelope (unchanged, both planes)

JSON `{ "v":1, "path":"/x/y", "id":"<uuid>", "payload":{...} }`.
SPP: 4-byte big-endian length prefix + JSON bytes, max 2 MB (exists in `:shared`).
CXR control plane: the same JSON bytes as a custom-cmd payload under the single key
`"rokidbus"` in both directions (phone: CXRLink custom cmd / `onCustomCmdResult`;
glasses: `CXRServiceBridge.subscribe("rokidbus", …)` / its send-command counterpart —
copy the exact API usage from Relay's bridges).

Binary payloads (images, audio later) ride SPP as `payload: {"bin": "<base64>"}` for
now; leave a `// TODO raw binary frames` marker.

## Binder plugin registration v3

Bus API v3 preserves the six v2 AIDL transactions in their original order and
appends `registerPlugin(packageName, pluginId, callback)`. Phone plugins declare
one exported service for `com.anezium.rokidbus.action.PLUGIN`. The hub derives the
principal from the Binder calling UID, package ownership, the service manifest,
and the current signing-certificate SHA-256 digest. Client payloads never supply
trusted UID, certificate, route prefixes, or surface ownership.

Descriptor metadata keys are `com.anezium.rokidbus.plugin.ID`,
`.DISPLAY_NAME`, `.API_VERSION`, `.CAPABILITIES`, `.RECEIVE_PREFIXES`,
`.SETTINGS_ACTIVITY`, and `.LAUNCHABLE`. Plugin IDs match
`[a-z][a-z0-9._-]{2,63}`. Capability values are `surfaces`, `microphone`,
`http_proxy`, and `camera`; unknown values invalidate the descriptor. Grants are keyed by
package, plugin ID, and signing digest and are never implied by installation.

Legacy `register(clientId, prefixes, callback)` remains ABI-compatible for
same-UID hub internals and explicit debug-probe compatibility. Release hubs
reject unknown external legacy callers. Phone approval does not authorize an
arbitrary glasses-side companion; release glasses hubs remain closed to those
clients until companion provisioning has its own identity design.

## External plugin lifecycle v1

The public SDK cold-starts through the exported plugin service; it does not use a
process-local factory or require an Activity to run first. The hub sends these
reserved, hub-to-plugin paths only to the verified principal:

- `/system/plugin/registration`
- `/system/plugin/open`
- `/system/plugin/close`
- `/system/plugin/input`

Lifecycle payloads include `version`, `type`, `id`, and `pluginId`. Input also
includes the plugin-local `localSurfaceId`, `keyCode`, and `action`. Version 1
receivers ignore unknown fields and ignore duplicate event IDs. SDK lifecycle
callbacks are serialized on the Android application main thread.

Plugins send only local surface IDs such as `main`. After capability and
principal checks, the phone hub injects `ownerPluginId`, rewrites the wire ID to
`pluginId:localSurfaceId`, and assigns the monotonic sequence. Plugins never
supply a trusted owner or global sequence.

## Transit external plugin and one-release migration

Transit is an independent phone APK with plugin ID `transit`. It requests only
Nexus `surfaces`; its own manifest owns `INTERNET`, foreground location, runtime
location, and notification permissions. No Transit implementation, location
permission, or location foreground-service type remains in the phone hub.

For one compatibility release, an approved, signer-bound Transit principal may
receive `/plugin/transit/migration/legacy`. The version 1 payload contains only
validated favorite stops and the last selected mode plus a count/checksum; it
never contains current coordinates or recent boards. Transit acknowledges on
`/plugin/transit/migration/ack`. The hub clears legacy state and records
completion only after the verified principal returns the exact migration ID,
count, and checksum. Both sides deduplicate replay. These paths are internal to
the verified callback/owned namespace and are not exported providers or general
migration APIs.

## Surface protocol v1 (Round B)

Plugins do not install glasses APKs. External phone plugins run in their own APK
process; temporary built-in adapters remain during migration. Both paths push
declarative surfaces over the existing bus. The glasses hub renders those surfaces
locally with the shared Rokid Nexus phosphor visual language.

Phone to glasses:

- `/surface/show` shows or replaces a surface.
- `/surface/update` updates an existing surface idempotently.
- `/surface/hide` hides a surface.
- `/launcher/list` sends the available phone-side plugins to the glasses launcher.

Glasses to phone:

- `/surface/input` reports key input while a surface is visible.
- `/launcher/open` asks the phone hub to open a plugin.

Every surface payload carries:

```json
{
  "surfaceId": "lyrics",
  "seq": 42,
  "kind": "card"
}
```

`seq` is monotonic per `surfaceId`. Because Round A proved there is no ordering
guarantee across CXR-L and SPP, the glasses renderer MUST drop any show, update or
hide whose `seq` is not newer than the last accepted sequence for that surface.
Messages are idempotent: the phone can resend the latest complete state at any time.
Timed-line and media anchor-only updates may also include a `contentKey`; the glasses
hub merges such updates only into an active surface with the same kind and key, so an
anchor that overtakes a full payload cannot replace it with an incomplete surface.

Surface kinds v1:

- `card`: `title`, `lines` as an array of strings or `{text}`, and optional `footer`.
- `timed-lines`: `title`, optional `subtitle`/`footer`, full `lines` as
  `{ "timeMs": 1234, "text": "..." }`, and an `anchor`.
- `media`: `title`/`subtitle` shell labels, `mediaTitle`, optional
  `mediaArtist`/`mediaAlbum`, optional mono or binary `artwork`, and an `anchor`.
- `image`: a real JPEG or PNG carried as an SPP binary frame. The binary-frame
  `meta`/`BusEnvelope.payload` object is:

```json
{
  "surfaceId": "feed:main",
  "seq": 43,
  "kind": "image",
  "imageVersion": 1,
  "contentKey": "tweet-123-photo-1",
  "mimeType": "image/jpeg",
  "pixelWidth": 480,
  "pixelHeight": 320,
  "sha256": "64-lowercase-hex-characters",
  "title": "Optional title",
  "caption": "Optional caption",
  "footer": "Optional footer",
  "handlesBack": false
}
```

`imageVersion` is exactly `1`. `contentKey` is required, non-empty, and at most
128 characters. `mimeType` is exactly `image/jpeg` or `image/png`. `pixelWidth`
and `pixelHeight` are the actual decoded dimensions: each is in `1..512`, and
their product is at most `512 * 512`. `sha256` is the lowercase hexadecimal
SHA-256 of the compressed binary bytes. `title` follows the card title limit
(120 characters); `caption` and `footer` follow the card line limit (240
characters). `handlesBack` has the same semantics as on a card.

The compressed image is required and is carried only in `BusEnvelope.binary`.
An `image` show/update sent as JSON, with a null or empty binary body, with a body
larger than 65,536 bytes, with a mismatched MIME/dimension/hash, or with invalid
metadata is rejected. The 2 MiB general SPP frame ceiling and 512 KiB Binder
ceiling do not enlarge this public image allowance. Producers SHOULD downscale
and compress on the phone and target 20--40 KiB.

Image lifecycle is otherwise identical to `card`: `/surface/show` shows or
replaces, `/surface/update` replaces the current image, and `/surface/hide`
hides it. The same phone-assigned monotonic per-`surfaceId` `seq` rule applies.
An async decode result may be published only while its `surfaceId`, `seq`, and
`contentKey` are still current; replacement or hide invalidates older work.

The phone hub enforces a minimum 150 ms interval between accepted image frames
for each wire `surfaceId`. Faster frames are rejected, never silently dropped.
Stable image error codes returned on `/error` are:

- `CAPABILITY_NOT_AVAILABLE`: the renderer announcement is absent or SPP is down.
- `INVALID_IMAGE`: metadata, MIME, dimensions, body, or SHA-256 validation failed.
- `IMAGE_TOO_LARGE`: the compressed body exceeds 65,536 bytes.
- `IMAGE_RATE_LIMITED`: the per-surface 150 ms interval has not elapsed.

Timed-line anchor:

```json
{
  "positionMs": 62840,
  "playing": true,
  "sentAtElapsedRealtime": 123456789
}
```

The phone sends a full timed-lines surface for the current track, then only re-sends
an anchor on play, pause, seek or track change. The glasses hub advances highlighting
locally from the last accepted anchor using its own monotonic clock, so lyric line
progress does not depend on repeated phone updates or bus latency.

Media surface v1:

```json
{
  "surfaceId": "media",
  "kind": "media",
  "mediaVersion": 1,
  "contentKey": "5d94a53f3a8e6d1b",
  "title": "MEDIA DECK",
  "subtitle": "SPOTIFY",
  "mediaTitle": "Track title",
  "mediaArtist": "Artist",
  "mediaAlbum": "Album",
  "artwork": {
    "encoding": "mono1",
    "width": 96,
    "height": 96,
    "hash": "38c8c4b94c44f7ba",
    "data": "<base64 packed bits>"
  },
  "anchor": {
    "positionMs": 62840,
    "durationMs": 241000,
    "playing": true,
    "playbackSpeed": 1.0,
    "sentAtElapsedRealtime": 123456789
  }
}
```

When the image-surface capability is available, the `artwork` object instead describes
the compressed body carried only in `BusEnvelope.binary`:

```json
"artwork": {
  "encoding": "binary",
  "mimeType": "image/jpeg",
  "pixelWidth": 256,
  "pixelHeight": 256,
  "sha256": "64-lowercase-hex-characters"
}
```

`encoding` is exactly `binary`; `mimeType` is `image/jpeg` or `image/png`; both
decoded edges are in `1..256`; and `sha256` covers the compressed envelope body.
The body is required, non-empty, and at most 65,536 bytes. The hub applies the same
signature, decoded-dimension, hash, capability, and per-surface 150 ms rate-limit
checks as an image surface before forwarding. `mediaVersion` remains `1`, and
receivers ignore unknown fields.

`mono1` is row-major, most-significant bit first; set bits render in Nexus phosphor
and unset bits stay transparent. Renderers accept at most 192 x 192 and require the
decoded byte count to equal `ceil(width * height / 8)`. Media Deck emits 96 x 96
(1,152 raw bytes). Clients without the image capability emit this exact legacy shape;
binary-capable clients scale the longest artwork edge to at most 256 pixels, re-encode
JPEG under the binary cap, and omit artwork if it cannot fit.

After the complete surface, the plugin sends anchor-only updates on play, pause, seek,
or track state changes. Glasses animate the progress bar from their local monotonic
clock. Swipe aliases select previous/next, tap aliases toggle play/pause, and BACK
hides the surface. Phone-side metadata and artwork MUST NOT be written to production
logs.

Launcher list payload:

```json
{
  "plugins": [
    { "id": "lyrics", "displayName": "Lyrics" }
  ]
}
```

Launcher open payload:

```json
{ "pluginId": "lyrics" }
```

Surface input payload:

```json
{
  "surfaceId": "lyrics",
  "keyCode": 23,
  "action": 0
}
```

The back key hides the surface locally on glasses and is still reported to the phone
as `/surface/input` so the active plugin can close its own state.

## Camera contract

The generic camera contract is available only to an installed plugin whose exact
package, descriptor ID, and signing digest have an approved, enabled `camera`
grant. Installation or a shared signer alone never grants access.

Glasses to phone:

- `/camera/session/state` carries `sessionId`, `state` (`opened` or `closed`),
  and, when opened, `config` with `width`, `height`, `fps`, and
  `protocolVersion`.
- `/camera/link/offer` carries `sessionId`, `ssid`, `passphrase`, `port`,
  `token`, and `goIp`.

Phone to glasses:

- `/camera/freeze/result` carries processing results for a frozen frame.
- `/camera/overlay` carries structured live-view overlay content.

All four paths are protected. The phone hub itself may send or receive them; an
external principal may receive only session state and link offers and may send
only freeze results and overlays, after the current signer-bound `camera` grant
is checked. Camera-session open binds the selected consumer with important
process priority, sends `/system/plugin/open`, and forwards the opening state
and subsequent offers. The matching close state sends `/system/plugin/close`
and unbinds. Link loss, grant revocation, package removal, binder death, and
registration timeout perform the same idempotent teardown. Duplicate and stale
open/close events are ignored by `sessionId`.

`IBusService.capabilities()` bit `4` is `CAMERA_CONSUMER_READY`. The phone hub
sets it exactly while at least one installed camera principal has an approved,
enabled `camera` grant. Grant and package changes recompute the bit. Bit `1` is
retired and is no longer advertised by either hub.

## Transport selection (hub-side routing)

1. Destination local (a client on the same side registered the path) → deliver directly.
2. Remote + envelope ≤ 3 KB → CXR control plane if link up, else SPP.
3. Remote + envelope > 3 KB → SPP only; if SPP down, reply `/error`
   `{code:"NO_DATA_PLANE", forId:<id>}` to the sender.
4. Nothing up → `/error` `{code:"NO_LINK", forId:<id>}`.

## AIDL contract (in `:bus-client`, package `com.anezium.rokidbus.client`)

```aidl
// IBusCallback.aidl
oneway interface IBusCallback {
    void onMessage(String path, String id, in byte[] payload); // payload = JSON bytes
    void onLinkState(int state); // bitmask below
}

// IBusService.aidl
interface IBusService {
    int apiVersion();                       // returns 1
    void register(String clientId, in String[] pathPrefixes, IBusCallback cb);
    void unregister(in IBusCallback cb);
    oneway void send(String path, String id, in byte[] payload);
    int linkState();
}
```

Link-state bits: `1 = CXR_CONTROL_UP`, `2 = SPP_DATA_UP`, `4 = GLASSES_BT_BONDED`
(phone) / `4 = PHONE_CONNECTED` (glasses).

Hub feature bits are returned by `IBusService.capabilities()`. Bit `2` is
`IMAGE_SURFACE` and bit `4` is `CAMERA_CONSUMER_READY`. The glasses hub announces its renderer after either remote link
connects by sending `/system/hub/capabilities` with
`{"version":1,"features":2,"imageSurfaceVersion":1,"maxImageBytes":65536}`.
The phone hub exposes `IMAGE_SURFACE` to local plugins only after receiving a
valid announcement and only while `SPP_DATA_UP` is live. It clears the remote
announcement when all glasses links are down. Capability changes are surfaced by
another link-state callback so clients refresh `capabilities()`; callers must not
cache a one-time Binder result. Old glasses hubs do not announce the bit, so the
plugin API version remains 3 and image calls fail locally with
`CAPABILITY_NOT_AVAILABLE`. Image rendering remains covered by the existing
`surfaces` user grant; it is not a plugin descriptor capability.

Request/response is NOT in AIDL: the `BusClient` wrapper implements it — a request is
`send(path, id, payload)` + a pending map keyed by `id`; any reply is delivered by the
responder to path `<request-path>/reply` carrying the same `id`. Timeout default 15 s.

Audio (glasses mic via CXR-L `startAudioStream`) is **Round B** — leave the surface out
of AIDL v1 entirely; bump `apiVersion` when it lands.

## Client wrapper API (Kotlin, `:bus-client`)

```kotlin
class BusClient(context, clientId, pathPrefixes: List<String>, listener: (BusEvent) -> Unit)
    fun connect()                     // binds the local hub (action, see below), auto-reconnects
    fun send(path, payload: JSONObject)
    fun request(path, payload, timeoutMs = 15_000): JSONObject   // suspend + callback overloads
    fun linkState(): Int
    fun close()
```

The hub service is discovered by **intent action** `com.anezium.rokidbus.action.HUB`
(each hub app exports a `BusHubService` with that action; the lib resolves it via
PackageManager — same binary works on phone and glasses).

## Wake-on-message (glasses supervisor; symmetric code, phone rarely needs it)

Client apps that must be wakeable declare in their manifest:

```xml
<service android:name="com.anezium.rokidbus.client.BusClientService"
    android:exported="true">
    <intent-filter>
        <action android:name="com.anezium.rokidbus.action.CLIENT" />
    </intent-filter>
    <meta-data android:name="com.anezium.rokidbus.paths" android:value="/probe" />
</service>
```

`BusClientService` lives in `:bus-client`: on bind it calls an app-supplied factory
(abstract method or registered singleton) so the app process boots its `BusClient`.

Hub flow for a message whose path has no live registration:
`queryIntentServices(action CLIENT)` → match `meta-data` path prefix →
`bindService(BIND_AUTO_CREATE)` → wait for the client's `register()` (max 5 s) →
flush queue (per-path queue, cap 32 msgs / 512 KB, TTL 30 s) → keep the bind while
traffic flows, unbind after 60 s idle (that's the reaper).

Hub manifests use `<queries><intent><action android:name="com.anezium.rokidbus.action.CLIENT"/></intent></queries>`.

## Phone hub specifics

- Foreground service (connectedDevice type, exists). Owns: CXRLink (auth token flow
  copied from Relay's `CxrLAuth` — a small activity with "Authorize with Hi Rokid"
  button storing the token in prefs), SPP client with reconnect/backoff (exists),
  AIDL `BusHubService`, HTTP proxy.
- HTTP proxy service listens on bus path `/http/request`
  `{url, method?, headers?, body?}` → streams `/http/request/reply` chunks
  `{id-correlated, status, chunk(base64), done, totalBytes}`. Keep the
  `api.transitous.org` allowlist for now, make the list a constant.
- CXR link state changes broadcast to all registered clients via `onLinkState`.

## Glasses hub specifics

- AccessibilityService anchor + BootReceiver (exists). Owns: SPP server (exists),
  CXR-S subscription (key `rokidbus`), AIDL `BusHubService`, supervisor above.
- Keep the `ProbeBroadcastReceiver` debug entry point (component-targeted broadcasts).

## Probe clients (acceptance demo)

Both probes use ONLY `:bus-client` (no direct BT/CXR imports). Phone probe app has
buttons: Echo (small → control plane), Echo-big 64 KB (→ SPP), HTTP via bus. Glasses
probe: headless `BusClientService` that answers `/probe/echo` and `/probe/echo/reply`
logs; it must be woken by the hub from dead.

## Acceptance criteria (operator validates on hardware)

1. `gradle assembleDebug` green locally (AGP), 4 APKs + 1 AAR-equivalent lib.
2. Phone probe → phone hub → **CXR-L** → glasses hub → **wakes dead glasses probe by
   bind** → reply travels back → phone probe logs the reply. (End-to-end small path.)
3. 64 KB echo takes the **SPP** route automatically, same client API.
4. Glasses probe fetches a Transitous URL through `/http/request`, Wi-Fi OFF.
5. Hi Rokid link stays connected throughout (no channel reset).
6. TESTPLAN.md updated with the new adb/test steps (component-targeted broadcasts).

## Out of scope for Round A

Audio lease (mic streaming), Relay migration, install/bootstrap of glasses apps via
Hi Rokid, permission hardening (custom signature permission), raw binary frames.

## Binary frames v1 (Round B slice 2)

Replaces the `payload.bin` base64 placeholder. The SPP frame keeps its 4-byte
big-endian length prefix (length = body bytes, max 2 MiB); the first body byte now
selects the format:

- `0x7B` (`{`) → legacy JSON envelope, whole body parsed as before. Old peers are
  wire-compatible for JSON traffic.
- `0x01` → binary frame: `[0x01][u16 BE headerLen][header JSON UTF-8][raw data]`.
  Header is `{"v":1,"path":"...","id":"...","meta":{...}}` (`meta` optional).

`BusEnvelope` gains `binary: ByteArray? = null`; for binary envelopes the existing
`payload` JSONObject carries the `meta`. Routing rules:

- Binary envelopes are **SPP-only** — never CXR control plane regardless of size.
  SPP down → `/error` `{code:"NO_DATA_PLANE", forId:<id>}` to the sender.
- JSON envelopes keep the existing ≤ 3 KB CXR-else-SPP rule.
- Binary envelopes never trigger wake-bind: no live registration → drop + log
  (they are realtime/stream data; the supervisor queue stays JSON-only).
- Local delivery of a binary message is capped at 512 KiB (binder transaction
  headroom); bigger frames are hub-internal only.

### AIDL v2 (append-only, transaction codes stable)

`API_VERSION = 2`. New methods appended at the END of the interfaces:

```aidl
// IBusCallback.aidl (+)
void onBinaryMessage(String path, String id, in byte[] meta, in byte[] data);
// IBusService.aidl (+)
oneway void sendBinary(String path, String id, in byte[] meta, in byte[] data);
```

`BusClient` adds `sendBinary(path, meta: JSONObject, data: ByteArray)` and emits
`BusEvent.Binary(path, id, meta: JSONObject, data: ByteArray)`. Request/response
convention is unchanged (a binary reply carrying the request `id` resolves a
pending `request()` too).

### HTTP proxy on binary frames (fixes the cross-plane ordering bug)

ALL `/http/request/reply` envelopes — chunks, `done`, and errors — become binary
frames: raw body bytes in `data` (empty for `done`/error), JSON meta
`{status, bytes, done, totalBytes?, error?}`. No more base64. Because every reply
of a request now rides the same SPP socket, replies are FIFO end-to-end and the
Round A "done overtakes chunk" race disappears by construction. Local requesters
receive the same shape via `onBinaryMessage`. Probes updated accordingly.

## Audio lease v1 (Round B slice 2)

Glasses mic PCM arrives ON THE PHONE via CXR-L (`setCXRAudioCbk` +
`startAudioStream(CXR_AUDIO_PCM=1)`, format 16 kHz / mono / PCM16 LE, variable
buffer sizes ~3.2 KB ≈ 100 ms). The phone hub owns the stream; the primary
consumer is a phone-side client — delivery is then local AIDL (`onBinaryMessage`,
zero bus transport). A glasses-side leaseholder is allowed and rides SPP binary
frames. Copy the exact CxrGlobal usage from Relay's `CxrBufferedAudioCapture.kt`.

Paths (single leaseholder at a time):

- `/audio/lease/acquire` `{}` → reply `{granted:true, leaseId, sampleRate:16000,
  channels:1, encoding:"pcm16le"}` or `{granted:false, reason:"BUSY"|"NO_CXR"|"START_FAILED"}`.
- `/audio/lease/release` `{leaseId}` → reply `{released:true}`.
- `/audio/frames` — binary frames to the leaseholder only: meta
  `{leaseId, seq, elapsedRealtime}`, data = raw PCM buffer as received.
  `seq` monotonic; receiver detects gaps.
- `/audio/lease/revoked` `{leaseId, reason:"LINK_DOWN"}` — hub → holder when
  CXR-L drops mid-lease (hub stops the stream).

Hub lifecycle: acquire → `setInterruptAiWake(true)`, `setCXRAudioCbk(cbk)`,
`startAudioStream(1)`; release / holder binder death / CXR drop →
`stopAudioStream()`, `setCXRAudioCbk(null)`, `setInterruptAiWake(false)`.
Binder-death auto-release is mandatory (no orphan stream). No phone
`RECORD_AUDIO` needed for the CXR PCM path (validated by Relay).

Phone probe gains a `Mic 5 s` button: acquire → count frames/bytes for 5 s →
release → log `frames=N bytes=M gaps=K`.

### Acceptance criteria (slice 2, operator validates on hardware)

1. `./gradlew assembleDebug` green; `apiVersion()` returns 2 on both hubs.
2. Phone probe `Mic 5 s`: PCM frames flow (~50 frames / ~160 KB per 5 s), zero
   seq gaps, lease released, second acquire while held returns `BUSY`.
3. Glasses probe `wake-http`: binary-chunk replies, `done` arrives strictly after
   all chunks (repeat 5×), Wi-Fi OFF.
4. 64 KB echo and Lyrics surfaces unaffected (JSON path regression check).
5. Hi Rokid/CXR-L stays connected through start/stop of the audio stream.
6. TESTPLAN.md updated with the slice 2 steps.
