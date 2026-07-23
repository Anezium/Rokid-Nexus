# RokidBus — current bus specification

Status: API version 3. This main text describes the current contract. Superseded
Round A/API v1 and API v2 details are retained only in the historical appendix.

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
- Glasses-side internet goes through the phone hub HTTP proxy over the bus. The
  protected camera workflow may temporarily request hub-owned Wi-Fi changes through
  `/glasses/wifi/request`; ordinary clients cannot use that control path.
- Reference for CXR-L auth + lifecycle patterns: `E:\Tools\Rokid\Rokid Relay\phone\src\main\java\com\anezium\rokidrelay\phone\CxrLAuth.kt`
  (Hi Rokid AuthorizationActivity → token) and `RelayBridge.kt` (CXRLink lifecycle,
  reconnect, `ICXRLinkCbk`). Glasses CXR-S pattern: `Rokid Relay\glasses\...\RelayBridge.kt`
  (`CXRServiceBridge.subscribe(key, cb)`, `onReceive(msgType, caps, data)`).

## Modules

| Module | Type | Package | Contents |
|---|---|---|---|
| `:shared` | kotlin lib | `com.anezium.rokidbus.shared` | envelope + frame codec |
| `:bus-client` | android lib | `com.anezium.rokidbus.client` | AIDL files + `BusClient` wrapper + `BusClientService` base |
| `:phone-hub` | app | `com.anezium.rokidbus.phone` | FGS hub: CXR-L owner, SPP client, AIDL server, HTTP proxy, auth UI |
| `:glasses-hub` | app | `com.anezium.rokidbus.glasses` | a11y anchor, CXR-S owner, SPP server, AIDL server, supervisor |
| `:phone-client-probe` | app | `com.anezium.rokidbus.phoneprobe` | sample client using `:bus-client` |
| `:glasses-client-probe` | app | `com.anezium.rokidbus.clientprobe` | sample client using `:bus-client` |
| `:plugin-feeds`, `:plugin-lens`, `:plugin-transit`, `:plugin-lyrics`, `:plugin-media`, `:plugin-sample` | apps | `com.anezium.rokidbus.plugin.*` | external headless plugin APKs built on `:bus-client` (sources under `plugins/` and `plugin-feeds/`) |

## Wire envelope and binary frames

JSON uses `{ "v":1, "path":"/x/y", "id":"<uuid>", "payload":{...} }`.
SPP keeps a 4-byte big-endian length prefix (length = body bytes, max 2 MiB).
The first body byte selects the current frame format:

- `0x7B` (`{`) → JSON envelope, with the whole body parsed as JSON.
- `0x01` → binary frame: `[0x01][u16 BE headerLen][header JSON UTF-8][raw data]`.
  The header is `{"v":1,"path":"...","id":"...","meta":{...}}`; `meta` is
  optional and becomes `BusEnvelope.payload`, while the raw body becomes
  `BusEnvelope.binary`.

CXR control plane: the same JSON bytes as a custom-cmd payload under the single key
`"rokidbus"` in both directions (phone: CXRLink custom cmd / `onCustomCmdResult`;
glasses: `CXRServiceBridge.subscribe("rokidbus", …)` / its send-command counterpart —
copy the exact API usage from Relay's bridges).

Binary envelopes are SPP-only and never use the CXR control plane. Remote binary
delivery fails with `NO_DATA_PLANE` while SPP is down, never wake-binds a sleeping
client, and is not queued. Local Binder delivery is capped at 512 KiB; larger frames
remain hub-internal. JSON keeps the 3 KiB CXR-else-SPP routing rule.

## Binder plugin registration v3

Bus API v3 preserves the first six AIDL transactions in their original order
and appends `registerPlugin(packageName, pluginId, callback)` and `capabilities()`.
Phone plugins declare one exported service for
`com.anezium.rokidbus.action.PLUGIN`. The hub derives the principal from the
Binder calling UID, package ownership, the service manifest, and the current
signing-certificate SHA-256 digest. Client payloads never supply trusted UID,
certificate, route prefixes, or surface ownership.

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
- `/glasses/device-info`

Lifecycle payloads include `version`, `type`, `id`, and `pluginId`. Input also
includes the plugin-local `localSurfaceId`, `keyCode`, and `action`. Version 1
receivers ignore unknown fields and ignore duplicate event IDs. SDK lifecycle
callbacks are serialized on the Android application main thread.

`/glasses/device-info` is a zero-capability, phone-hub-to-plugin version-1 JSON
message carrying `type=glasses_device_info`, `id`, `pluginId`, `deviceName`,
`batteryLevel`, `sound`, `brightness`, `systemVersion`, `isCharging`, and
`wearingStatus` — the hardware serial number (`GlassInfo.sn`) is deliberately
never included, matching `GlassInfo`'s own `redactedSn` precedent for this
sensitive field. The AI-assist start/stop edges use the direct callback below
rather than a bus path.

Plugins send only local surface IDs such as `main`. After capability and
principal checks, the phone hub injects `ownerPluginId`, rewrites the wire ID to
`pluginId:localSurfaceId`, and assigns the monotonic sequence. Plugins never
supply a trusted owner or global sequence.

## Surface protocol v1

Plugins do not install glasses APKs. All phone plugins, including Lens, run as
external headless APKs; the phone registry contains no built-ins. Plugins push
declarative surfaces over the existing bus, and the glasses hub renders them locally
with the shared Rokid Nexus phosphor visual language.

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

`seq` is monotonic per `surfaceId`. Because there is no ordering guarantee across
CXR-L and SPP, the glasses renderer MUST drop any show, update or
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

The bus carries control only. The heavy data path is out-of-band: during a
session the glasses encode the camera as H.264 and serve it over a link
negotiated by `/camera/link/offer`; the consumer plugin joins with the
credentials it carries, decodes on the phone, and runs its processing (Lens:
ML Kit OCR + translation) there. Frozen captures ride the same link as full
JPEGs, with `/camera/freeze/image/chunk` over SPP as the fallback when the
link is down.

The link has two modes, chosen by the phone from its own Wi-Fi state at
session start (`PhoneLensTransportModePolicy`) and carried in the offer's
`mode` field:

- `p2p` (default when the field is absent, for backward compatibility): the
  glasses are Group Owner of a Wi-Fi Direct group; the phone joins it.
- `lohs_reverse`: used when the phone's own Wi-Fi is off (it cannot enable its
  own Wi-Fi from user-space). The phone hosts a `LocalOnlyHotspot` itself and
  sends a reverse offer; the glasses enable their Wi-Fi (self-arm command
  bridge, falling back to the accessibility automator) and join the phone's
  hotspot by credentials, then connect as the TCP client — the transport
  roles invert, but `CameraLinkProtocol`'s wire framing is unchanged either
  way. The glasses skip Wi-Fi Direct group setup entirely when they already
  know (from the phone's last capabilities announcement, see below) that
  `lohs_reverse` is likely, falling back to the normal `p2p` startup after a
  bounded wait if no reverse offer arrives (`CameraLinkStartupPolicy`).

Glasses to phone:

- `/camera/session/state` carries `sessionId`, `state` (`opened` or `closed`),
  and, when opened, `config` with `width`, `height`, `fps`, and
  `protocolVersion`.
- `/camera/link/offer` carries `sessionId`, `ssid`, `passphrase`, `port`,
  `token`, `goIp` (required for `p2p`, absent for `lohs_reverse`), and two
  fields that default when absent for backward compatibility: `mode` (`p2p` or
  `lohs_reverse`) and, for `lohs_reverse` only, `security` (`open`, `wpa2_psk`,
  or `wpa3_sae` — the phone's actual `LocalOnlyHotspot` security type, so the
  glasses associate on the first attempt instead of a rejection-then-retry).
  This same path carries the reverse offer in `lohs_reverse` mode (phone to
  glasses) — the envelope shape is identical, only `CameraLinkOfferContract`'s
  `mode`/`security` fields and the missing `goIp` distinguish it.
- `/camera/freeze/image/chunk` carries the raw SPP frozen-image fallback as
  binary chunks.

Phone to glasses:

- `/camera/freeze/result` carries processing results for a frozen frame.
- `/camera/overlay` carries structured live-view overlay content; each item may include an
  optional string `id` (at most 64 characters) for stable live-item reuse.

The protected camera set contains exactly six paths: `/camera/session/state`,
`/camera/link/offer`, `/camera/freeze/result`, `/camera/freeze/image/chunk`,
`/camera/freeze/image/ack`, and `/camera/overlay`. The phone hub itself may send
or receive them; an external principal may receive session state, link offers,
and frozen-image chunks and may send freeze results and overlays only after the
current signer-bound `camera` grant is checked. `/glasses/wifi/request` is a
separate trusted path carrying `{enabled: Boolean}` for hub-owned camera Wi-Fi
changes; untrusted callers are rejected. The glasses hub applies a Wi-Fi enable
through the self-arm command bridge first (silent, nonce/replay-checked keyed
SHA-256 requests to a persistent shell-uid helper) and falls back to the
accessibility automator's Wi-Fi toggle; when the hub turned Wi-Fi on for a
session, it schedules a silent disable 40 s after the session closes. Camera-session open binds the selected
consumer with important process priority, sends `/system/plugin/open`, and
forwards the opening state and subsequent offers. The matching close state sends
`/system/plugin/close` and unbinds. Link loss, grant revocation, package removal,
binder death, and registration timeout perform the same idempotent teardown.
Duplicate and stale open/close events are ignored by `sessionId`.

`IBusService.capabilities()` bit `4` is `CAMERA_CONSUMER_READY`, bit `8` is
`CAMERA_FROZEN_SPP`, and bit `16` is `CAMERA_LOHS_REVERSE_REQUIRED`. The phone
hub sets readiness while at least one installed camera principal has an
approved, enabled `camera` grant; it adds `CAMERA_FROZEN_SPP` while that
consumer receives frozen chunks and SPP is live, and it adds
`CAMERA_LOHS_REVERSE_REQUIRED` whenever its own Wi-Fi is off (re-announced
immediately on the phone's own Wi-Fi state changes, not just on grant/package/
link changes, so the glasses learn it as early as possible — ideally before a
camera session even starts, letting them skip straight to the `lohs_reverse`
startup path instead of standing up a Wi-Fi Direct group that would only be
torn down). Grant, package, and link changes recompute the bits. Bit `1` is
retired and is no longer advertised by either hub.

## Hub capabilities announcements

Both hubs announce an additive JSON payload on `/system/hub/capabilities`;
unknown fields are ignorable in both directions, so fields only ever get added.

- Glasses → phone (`GlassesHubCapabilitiesContract`): `version`, renderer
  `features` bits, `imageSurfaceVersion`, `maxImageBytes`, the glasses app
  `versionName` (drives the phone-side glasses update checker), and
  `setupComplete` (self-arm onboarding state; the phone preserves the last
  known value across link loss — only a live announcement can lower it).
- Phone → glasses (`PhoneHubCapabilitiesContract`): `version`, `features` bits
  (including `CAMERA_CONSUMER_READY`), and `cameraConsumerName` — the display
  name the glasses launcher uses for the synthesized camera entry (present
  only while a consumer is ready, ≤ 80 chars).

## Transport selection (hub-side routing)

1. Destination local (a client on the same side registered the path) → deliver directly;
   binary delivery is capped at 512 KiB.
2. Remote binary envelope → SPP only; if SPP is down, reply `/error`
   `{code:"NO_DATA_PLANE", forId:<id>}` to the sender.
3. Remote JSON envelope ≤ 3 KB → CXR control plane if link up, else SPP.
4. Remote JSON envelope > 3 KB → SPP only; if SPP down, reply `/error`
   `{code:"NO_DATA_PLANE", forId:<id>}` to the sender.
5. Nothing up → `/error` `{code:"NO_LINK", forId:<id>}`.

## AIDL contract (in `:bus-client`, package `com.anezium.rokidbus.client`)

```aidl
// IBusCallback.aidl
oneway interface IBusCallback {
    void onMessage(String path, String id, in byte[] payload); // payload = JSON bytes
    void onLinkState(int state); // bitmask below
    void onBinaryMessage(String path, String id, in byte[] meta, in byte[] data);
    void onGlassesAiButton(boolean active);
}

// IBusService.aidl
interface IBusService {
    int apiVersion();                       // returns 3
    void register(String clientId, in String[] pathPrefixes, IBusCallback cb);
    void unregister(in IBusCallback cb);
    oneway void send(String path, String id, in byte[] payload);
    int linkState();
    oneway void sendBinary(String path, String id, in byte[] meta, in byte[] data);
    int registerPlugin(String packageName, String pluginId, IBusCallback cb);
    int capabilities();
}
```

The method order is append-only so transaction codes remain stable. Link-state
bits are `1 = CXR_CONTROL_UP`, `2 = SPP_DATA_UP`, and
`4 = GLASSES_BT_BONDED_OR_PHONE_CONNECTED`, and `8 = GLASSES_WORN`.

Hub feature bits are returned by `IBusService.capabilities()`. Bit `2` is
`IMAGE_SURFACE`, bit `4` is `CAMERA_CONSUMER_READY`, and bit `8` is
`CAMERA_FROZEN_SPP`. The glasses hub announces its renderer after either remote link
connects by sending `/system/hub/capabilities` with
`{"version":1,"features":2,"imageSurfaceVersion":1,"maxImageBytes":65536,"versionName":"1.0.0","setupComplete":true}`.
`versionName` is the optional glasses app `BuildConfig.VERSION_NAME`; older glasses
omit it and newer phones treat the missing field as an unknown installed version.
`setupComplete` reports whether the on-device self-arm onboarding state is `COMPLETE`;
older payloads omit it and newer phones default the missing field to `false`. A glasses
hub linked during the transition re-announces capabilities so the phone sees it live.
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

## Client wrapper API (Kotlin, `:bus-client`)

```kotlin
class BusClient(context, clientId, pathPrefixes: List<String>, listener: (BusEvent) -> Unit)
    fun connect()                     // binds the local hub (action, see below), auto-reconnects
    fun send(path, payload: JSONObject)
    fun sendBinary(path, meta: JSONObject, data: ByteArray)
    fun request(path, payload, timeoutMs = 15_000): JSONObject   // suspend + callback overloads
    fun linkState(): Int
    fun capabilities(): Int
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
  `{url, method?, headers?, body?}`. Every `/http/request/reply` chunk, terminal
  marker, and error is a binary frame with raw response bytes in `data` (empty
  for terminal/error frames) and JSON metadata
  `{status, bytes, done, totalBytes?, error?}`. Remote replies retain the request
  `id` and stay on SPP, preserving FIFO order; local callers receive the same
  binary shape over Binder. The allowlist currently contains `api.transitous.org`.
- CXR link state changes broadcast to all registered clients via `onLinkState`;
  AI-assist start/stop edges broadcast via `onGlassesAiButton` with no capability
  gate and no assistant side effect.

## Glasses hub specifics

- AccessibilityService anchor + BootReceiver (exists). Owns: SPP server (exists),
  CXR-S subscription (key `rokidbus`), AIDL `BusHubService`, supervisor above.
- `/hub/probe` is an internal diagnostic envelope sent by the glasses CXR bridge
  after connection and consumed by the phone hub.
- `ProbeBroadcastReceiver` remains a debug entry point for component-targeted broadcasts.

## Audio lease v1

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
  `{leaseId, seq, elapsedRealtime, pluginId}`, data = raw PCM buffer as received.
  `seq` monotonic; receiver detects gaps. Each frame envelope's `id` MUST be
  unique (`leaseId:seq`) — the plugin client dedups inbound events by envelope
  `id`, so a constant `id` collapses the whole stream to a single frame. For a
  local plugin holder the payload also carries `pluginId` (the client drops
  events whose `pluginId` does not match).
- `/audio/lease/revoked` `{leaseId, reason:"LINK_DOWN"}` — hub → holder when
  CXR-L drops mid-lease (hub stops the stream).

Audio request replies use the request path with `/reply` appended:
`/audio/lease/acquire/reply` and `/audio/lease/release/reply`.

Hub lifecycle: acquire → `setInterruptAiWake(true)`, `setCXRAudioCbk(cbk)`,
`startAudioStream(1)`; release / holder binder death / CXR drop →
`stopAudioStream()`, `setCXRAudioCbk(null)`, `setInterruptAiWake(false)`.
Binder-death auto-release is mandatory (no orphan stream). No phone
`RECORD_AUDIO` needed for the CXR PCM path (validated by Relay). The
glasses-side mic DSP beamforms toward the wearer and gates when the glasses are
unworn, so a lease acquired while unworn streams near-silence — this is a
hardware property, not a bus fault. Plugins consume this through the SDK's
`nexusAudioSession(callbacks)`; the raw `/audio/*` paths above are the wire
contract behind it.

## Appendix: historical protocol versions

Everything in this appendix is historical and must not be implemented as the
current contract. API version 3 and the main sections above are authoritative.

### Historical Round A / API v1

The first contract returned API version 1. `IBusCallback` exposed only
`onMessage` and `onLinkState`; `IBusService` exposed only `apiVersion`,
`register`, `unregister`, `send`, and `linkState`. Binary was a temporary
`payload.bin` base64 placeholder, raw binary frames were explicitly out of
scope, and the HTTP proxy described base64 chunks in JSON. Those forms are
superseded.

### Historical API v2

API version 2 appended `onBinaryMessage` and `sendBinary` without changing the
existing Binder transaction order. It introduced the raw SPP binary frame and
moved every HTTP reply chunk, terminal marker, and error to raw binary data with
JSON metadata. API version 3 later appended plugin registration and capability
reporting; the full current AIDL appears in the main contract.
