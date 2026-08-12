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
| `:ink-engine` | kotlin lib | `com.anezium.rokidbus.ink` | bounded `.ink` compiler, binding engine, render document/patch codec, and wire validation |
| `:bus-client` | android lib | `com.anezium.rokidbus.client` | AIDL files + `BusClient` wrapper + `BusClientService` base |
| `:phone-hub` | app | `com.anezium.rokidbus.phone` | FGS hub: CXR-L owner, SPP client, AIDL server, HTTP proxy, auth UI |
| `:glasses-hub` | app | `com.anezium.rokidbus.glasses` | a11y anchor, CXR-S owner, SPP server, AIDL server, supervisor |
| `:phone-client-probe` | app | `com.anezium.rokidbus.phoneprobe` | sample client using `:bus-client` |
| `:glasses-client-probe` | app | `com.anezium.rokidbus.clientprobe` | sample client using `:bus-client` |
| `:plugin-assistant`, `:plugin-relay`, `:plugin-tasker`, `:plugin-feeds`, `:plugin-lens`, `:plugin-transit`, `:plugin-lyrics`, `:plugin-media`, `:plugin-photosync`, `:plugin-wireless-adb`, `:plugin-sample` | apps | `com.anezium.rokidbus.plugin.*` | external headless plugin APKs built on `:bus-client` (sources under `plugins/` and `plugin-feeds/`) |

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
and appends `registerPlugin(packageName, pluginId, callback)`, `capabilities()`,
and `approvedCapabilities(pluginId)`.
Phone plugins declare one exported service for
`com.anezium.rokidbus.action.PLUGIN`. The hub derives the principal from the
Binder calling UID, package ownership, the service manifest, and the current
signing-certificate SHA-256 digest. Client payloads never supply trusted UID,
certificate, route prefixes, or surface ownership.

Descriptor metadata keys are `com.anezium.rokidbus.plugin.ID`,
`.DISPLAY_NAME`, `.API_VERSION`, `.CAPABILITIES`, `.RECEIVE_PREFIXES`,
`.SETTINGS_ACTIVITY`, and `.LAUNCHABLE`. Plugin IDs match
`[a-z][a-z0-9._-]{2,63}`. Capability values are `surfaces`, `ink_surface`,
`microphone`, `stt`, `tts`, `http_proxy`, `camera`, `mediasync`, `assistant`,
and `wireless_debugging`; unknown values invalidate the
descriptor. Grants are keyed by package, plugin ID, and signing digest and are
never implied by installation.

Phone-local Android platform access is outside this bus contract. For example,
Assistant talks directly to the phone's Calendar Provider under its own Android
runtime permissions; those calls never cross a hub and add no path, feature bit,
receive prefix, or descriptor capability. A future calendar exchange involving
the glasses or either hub would require a separately specified and authorized
wire contract; the current provider integration must not be represented as one.

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

- `card`: `title`, optional `subtitle`, optional `footer`, and `lines` as an
  array of strings or row objects. A row object carries `text` plus any of
  `badge`, `trail`, `sub`, `tone`, and `selected`. A card whose rows use `sub`,
  `tone`, or `selected` renders as a **list** — secondary lines, a weight per
  row, and a selection rail the glasses draw — rather than as plain text.
  `tone` is one of `alert`, `normal` (the default), `dim`, or `body`; a `body`
  row wraps as prose and puts its `badge` in a label column beside it. See
  [surface-list-rows.html](docs/surface-list-rows.html).
- `reader`: `title`, optional `subtitle`/`footer`/`contentKey`, a complete
  `segments` document, and an optional `readerAnchor`. The renderer owns
  wrapping and scrolling; see the reader contract below.
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

### Reader surface

A reader is a continuous long-form document rather than a table of card rows.
The plugin sends the whole document and does not pre-wrap, clamp, window, or
page it:

```json
{
  "surfaceId": "conversation",
  "kind": "reader",
  "title": "Conversation",
  "subtitle": "3 turns",
  "footer": "tap · back",
  "contentKey": "conversation-42",
  "readerAnchor": "top",
  "segments": [
    { "kind": "header", "text": "CX · 5 min ago", "emphasis": true },
    { "kind": "prose", "text": "The complete response wraps on the glasses." },
    { "kind": "aside", "text": "⋯ searched 3 sources" }
  ]
}
```

`title` is required, non-blank, and at most 120 characters. `subtitle` and
`footer` are optional and at most 240 characters each; `contentKey` is optional
and at most 128 characters. `segments` contains 1 through 240 objects. Every
segment has a known `kind` and `text` of at most 4,096 characters, and the sum
of all segment text is at most 40,000 characters. Null shell fields are omitted,
as is `emphasis` when false. The phone adds verified ownership and the monotonic
wire `seq` in the same way it does for a card.

`readerAnchor` is optional and says where reading begins. It is `bottom` or
`top`, and the distinction it draws is stream-shaped versus document-shaped
content. `bottom` is the default and is omitted from the wire payload: the
surface opens at the end, an update stays pinned there when the wearer was
already near the end, and otherwise restores the previous offset — a chat, a
log, an agent transcript. `top` opens the surface at the start and never
follows the tail, so an update always restores where the wearer had scrolled
to — an article, a recipe, a saved note. An absent or unrecognised value is
read as `bottom`, so a glasses hub older than 1.4.3 opens at the bottom as
before. Like a timed-line or media anchor, an update that omits the field
inherits the active surface's anchor when it merges into a reader with the same
`surfaceId` and a matching or blank `contentKey`, and otherwise falls back to
`bottom`.

Segment kinds are:

- `header`: a quiet line introducing a turn. When `emphasis` is true, the
  leading token before the first `·` is rendered bright; otherwise it uses the
  normal text colour. The leading token is bold in both cases.
- `prose`: full-width body text with renderer-owned wrapping and no line clamp.
  An empty prose segment is retained as a paragraph break.
- `aside`: a small muted event line. It has no renderer-added prefix.

Receivers skip unknown segment kinds for forward compatibility and defensively
truncate all reader fields to these caps rather than rejecting or crashing the
active HUD. Reader surfaces carry no card lines, timed lines, media, artwork, or
image. `contentKey`, replacement, sequencing, and stale-message handling are the
same as for `card`.

## Ink surface protocol v1

Ink is a foreground surface that shares the ordinary surface owner's single
slot but has a distinct signer-bound `ink_surface` plugin grant. `surfaces`
does not authorize Ink, and `ink_surface` does not authorize cards, notices,
pins, activities, or images. A plugin uses local ids matching
`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`; the phone injects the authenticated plugin id
and rewrites the wire id to `<pluginId>:<localSurfaceId>`.

The plugin-facing JSON paths are:

- `/ink/show`: `{surfaceId,page,data?,handlesBack}`. `page` is UTF-8 `.ink`
  source, `data` is an optional JSON object, and `handlesBack` defaults false.
- `/ink/update`: `{surfaceId,data}`. `data` is a set-data-style patch whose keys
  may be paths such as `metrics[0].value`.
- `/ink/hide`: `{surfaceId}`. Hiding a missing/already-closing session is
  idempotent.
- `/ink/event`: phone-to-owner direct events. This path is owner-scoped and
  direct-reply routed; it is not declared in plugin receive prefixes.

External plugins SHOULD use `NexusInkSurfaceSession`, not construct these
payloads. The phone compiles every show on one dedicated Ink thread. It sends
the glasses an ordinary `/surface/show` with `kind:"ink"` and
`ink.document` containing the validated `INK_DOC_V1` wire JSON. Updates apply
to the phone-owned `InkSession` and become `/surface/update` with
`ink.patch`; a glasses `resync` event causes a full document update, rate
limited to one per second. Hide becomes `/surface/hide`. This separation keeps
authored source and mutable compiler state off the glasses.

Ink shares `ForegroundSurfacePathPolicy` with `/surface/show` and
`/surface/update`. A show/update from a non-owner while another plugin owns the
foreground returns a typed `SURFACE_BUSY` problem. A successful show replaces
any other Ink session; the previous owner receives `closed` with reason
`replaced`. Link loss clears compiler sessions and closes them with `link_lost`.

`/ink/event` payloads carry the plugin-local `surfaceId`, authenticated
`pluginId`, and one type:

| Type | Additional fields | Meaning |
|---|---|---|
| `ready` | — | Initial document or a full resync is attached on the glasses |
| `action` | `actionId`, `dataset` | The wearer activated a `bindtap`/`catchtap` node; `data-*` values form the dataset |
| `closed` | `reason` | Session ended; reason is `user`, `plugin`, `replaced`, `link_lost`, or `renderer_error` |
| `error` | `problems[]` | Compiler, policy, transport, or renderer rejection; each problem has `code`, `message`, `severity`, and optional location/feature |

`resync` is glasses-to-phone only and never forwarded to the plugin. Action,
ready, closed, and error events are delivered only to the verified live owner.
Binary `/ink/*` commands and events are rejected.

An Ink SFC contains one JSON `<script def>` block, one `<page>`, and an optional
`<style>`. `<script setup>` and all executable scripts are rejected. V1 accepts
bounded interpolation/expressions, conditionals, `wx:for`, class selectors,
flex/box/text styles, transforms/transitions, `rpx`/percent units, tap actions,
and the native components `view`, `text`, `scroll-view`, `chart`, `progress`,
inline-JSON `lottie-view`, and declarative `nx-canvas`. `image` currently
projects a placeholder reference; the public session has no asset-transfer
field. There is no WebView, URL load, page-side network, or arbitrary code
execution.

The rejecting budgets are 32 KiB authored page, 16 KiB merged data or update
patch, 64 KiB compiled document, 64 KiB render patch, 256 nodes, render depth
32, 1,024 patch changes, four chart series with 256 points per series, 512
canvas commands at most 30 fps, and 32 KiB inline Lottie JSON. The revisioned
wire decoder additionally rejects unknown fields, document-id mismatches,
non-monotonic revisions, malformed datasets, and out-of-matrix components,
attributes, selectors, styles, or expressions with typed `INK_*` problems.

## Pin protocol v1

Pins are a separate persistent text surface, not part of the active
`/surface/*` lifecycle. A plugin sends `/pin/show` to upsert the single global
pin slot and `/pin/hide` to clear it. Pins reuse the existing `surfaces` grant;
there is no pin descriptor capability and the plugin API version remains 3.

The plugin sends local `surfaceId` `pin`. The phone hub injects
`ownerPluginId`, rewrites the wire id to `<pluginId>:pin`, and assigns a
monotonic sequence:

```json
{
  "surfaceId": "rides:pin",
  "ownerPluginId": "rides",
  "seq": 7,
  "kind": "pin",
  "title": "AB-123-CD",
  "lines": ["Grey Toyota Prius"],
  "position": "top-right",
  "ttlMs": 1800000
}
```

`size` is optional and is one of `small` or `medium`; `small` is the default and
is what a payload without the field has always meant. The tier sets every text
cap: `small` allows a 24-character title and up to two lines of 28 characters,
`medium` a 28-character title and up to three lines of 32 characters. Every cap
is measured after trimming, and a payload that exceeds its tier is rejected
rather than truncated.

`title` is optional. `lines` is an optional array whose entries are either a
plain string or an object `{"text": "…", "emphasis": "bright" | "dim"}`, the
same string-or-object shape card rows use. Omitted emphasis keeps the default
tone: the title renders in the bright phosphor colour and lines render muted.
`bright` promotes a line to the title colour, `dim` states the muted tone
explicitly, and the title is always bright. At least one title or line must be
non-empty. `position` is optional and is one of `top-left`, `top-right`,
`bottom-left`, or `bottom-right`; `top-right` is the default. `ttlMs` is
optional and is clamped to `1,000..86,400,000`; omission means 30 minutes, and
the hub writes that default onto the normalized payload so the glasses-side
timer never has to know it. The glasses drop stale or duplicate `seq` values
and defensively ellipsize every rendered row.

The hub normalizes an accepted pin before forwarding it: trimmed text, the
resolved `position`, `size` only when it is not `small`, and each line back to a
plain string unless it carries an emphasis. A medium pin therefore looks like:

```json
{
  "surfaceId": "transit:pin",
  "ownerPluginId": "transit",
  "seq": 8,
  "kind": "pin",
  "size": "medium",
  "title": "Bus 42 · Central",
  "lines": [
    { "text": "arrives in 4 min", "emphasis": "bright" },
    "then 11 min · 26 min",
    { "text": "platform 2", "emphasis": "dim" }
  ],
  "position": "top-right"
}
```

The slot is last-writer-wins across plugins. A show may replace another
plugin's pin without an eviction callback. Hide is honored only for the current
owner; another plugin's hide is logged and ignored without an error. The pin
survives surface replacement/hide, launcher changes, foreground native apps,
and its owner disconnecting from the bus — a background plugin is expected to
push a pin and go dormant again. It is cleared by an owner hide, replacement,
TTL expiry, or the owner losing its grant (revoked or uninstalled). The phone
hub owns canonical state, tracks the TTL deadline, sends a synthetic hide for
expiry/revocation while linked, and resends the active complete pin after a
valid glasses capability re-announcement.

The glasses render the pin in a small independent, non-focusable and
non-touchable accessibility-overlay window above fullscreen surface and
launcher windows. A `small` pin uses a 13sp title over 11sp lines and never
exceeds 45% of the screen width; a `medium` pin uses 15sp over 12sp with three
line slots and never exceeds 60%. It never wakes or keeps the display on. An
active camera overlay temporarily hides the pin and detaching the camera
overlay restores it.

Stable pin errors returned on `/error` are:

- `INVALID_PIN`: field shape, local id, per-tier text cap, or enum validation
  (`position`, `size`, `emphasis`) failed.
- `PIN_RATE_LIMITED`: a plugin's previous accepted show was less than 500 ms ago.
- `CAPABILITY_NOT_AVAILABLE`: pin v1 was never announced by these glasses. Not
  returned merely because the link is down — a show sent while the glasses are
  asleep is accepted and delivered on the next announce.

Timed-line anchor:

```json
{
  "positionMs": 62840,
  "playing": true,
  "sentAtElapsedRealtime": 123456789
}
```

The phone sends a full timed-lines surface for the current track, then only re-sends
an anchor on play, pause, seek, track change, or active-line change. The glasses hub
advances highlighting locally from the last accepted anchor using its own monotonic
clock, while line-boundary anchors correct residual transport delay without
retransmitting the full script.

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

For a `reader`, the hub owns reader scrolling; plugins receive ENTER (including
the DPAD_CENTER confirmation alias) and BACK only. DPAD directions and
MEDIA_NEXT/MEDIA_PREVIOUS scroll by a renderer-defined viewport step and are
consumed on the glasses instead of producing `/surface/input` events.

## Notice protocol v1

A notice is a transient band across the top of the wearer's view: one
real-world event, briefly, and then gone. It is one of four HUD kinds, and the
boundaries between them are the point.

- **activity** — an ongoing process the wearer follows.
- **notice** — a discrete event needing attention or a response.
- **surface** — an engaged interaction the wearer is driving.
- **pin** — a trivial static fact that just needs to stay put.

If there is a state machine behind a persistent value, use an activity rather
than repeatedly replacing a pin.

Notices reuse the `surfaces` grant; there is no notice capability and the
plugin API version remains 3. Glasses announce support with feature bit 64
(`NOTICE_SURFACE`) and `noticeSurfaceVersion`, which is **3**: v1 was the
single-page band, v2 paged it and gave it an image, v3 added structured
`lines`. Both hubs gate the tier on an exact match, so a pair speaking a
different version declines the capability outright and the plugin hears
`CAPABILITY_NOT_AVAILABLE` — which it can act on — instead of having its band
accepted and then silently dropped.

### Paths

Phone to glasses:

- `/notice/show` — shows or replaces the band. Full state every time. It is
  the only notice path that may carry a binary frame, and only when the payload
  describes a valid JPEG or PNG.
- `/notice/update` — refreshes the visible band. Fields present replace their
  value; fields absent keep it; a field sent empty clears it. Honored only for
  the plugin that owns the slot and only while a band is actually visible,
  otherwise ignored with a log rather than an error — an update racing a
  deadline that fired a frame earlier is ordinary. Updates are always
  text-only; a binary frame is `INVALID_NOTICE`. `wakeDisplay` and `backdrop`
  are show-only; supplying either on an update is also `INVALID_NOTICE` and is
  logged by the hub.

  **The phone relays the owner's validated patch**, stamped with the hub's own
  fields — the wire `surfaceId` `<pluginId>:notice`, `localSurfaceId`,
  `ownerPluginId`, and a fresh `seq` — rather than re-serialising its canonical
  state. Absent-versus-present is therefore end-to-end: what the owner left out
  is what the glasses leave alone, and what the owner sent empty is what the
  glasses clear. Re-serialising could not express a clear at all, because full
  state omits an empty footer, a false flag, and an empty row, and an absent key
  on a patch means "leave it". The phone still validates first and still rejects
  an invalid patch before anything travels; authority did not move, only the
  shape of what it forwards.
- `/notice/hide` — clears it. Owner only.

Glasses to phone to plugin:

- `/notice/input` — `{noticeId, keyCode, action}`. The single confirming
  gesture, sent only by a band that carries no actions, and **at most once per
  question**.
- `/notice/action` — `{noticeId, id}`, where `id` is the selected action's
  plugin-supplied identifier. Sent instead of `/notice/input` whenever the band
  carries actions, and **at most once per question**.

- `/notice/closed` — `{noticeId, reason}` with `reason` in
  `user | timeout | owner | replaced | disconnect`. Delivered exactly once per
  notice, including when the owner hid it itself. Not delivered when the owner
  is what disappeared.

Both replies go through the same gate on the phone hub: the notice must be the
one it currently holds, it must actually have asked for a gesture, and it only
answers once — an action id it never offered, a pick that raced a replacement,
and a second reply of either kind are all refused. The refusals log distinct
reasons, `not_current` and `already_answered`, because they mean different
things.

Notice traffic coming back is **owner-scoped**: the hub delivers it only to the
plugin named by `pluginId` in the payload, so nothing else subscribed to the
path learns that this plugin had a banner dismissed.

The plugin sends local `surfaceId` `notice`; the phone hub injects
`ownerPluginId` and rewrites the id to `<pluginId>:notice`, exactly as it does
for pins.

### Payload

```json
{
  "surfaceId": "relay:notice",
  "ownerPluginId": "relay",
  "seq": 12,
  "kind": "notice",
  "title": "Marie",
  "body": "On my way, ten minutes out.",
  "footer": "scroll to choose · back to dismiss",
  "interactive": true,
  "actions": [
    {"id": "reply", "glyph": "phone", "label": "Reply"},
    {"id": "later", "glyph": "timer", "label": "Later"}
  ],
  "ttlMs": 8000,
  "wakeDisplay": true,
  "backdrop": true
}
```

The alternate structured body keeps one event's parts distinct. It never
appears alongside `body`:

```json
{
  "surfaceId": "relay:notice",
  "ownerPluginId": "relay",
  "seq": 13,
  "kind": "notice",
  "title": "Mika",
  "lines": [
    "Can you check the build when you have a minute?",
    "I added a second message to exercise thread extraction.",
    "Reply from the glasses when you are ready."
  ]
}
```

The same band carrying a picture instead of a question. Note what is absent:
an image notice asks nothing, so it offers no actions and its body is free to
run long enough to page.

```json
{
  "surfaceId": "relay:notice",
  "ownerPluginId": "relay",
  "seq": 14,
  "kind": "notice",
  "title": "Marie",
  "body": "Look at the view from the hotel window this morning.",
  "footer": "back to dismiss",
  "imageVersion": 1,
  "contentKey": "message-photo-12",
  "mimeType": "image/jpeg",
  "pixelWidth": 480,
  "pixelHeight": 160,
  "sha256": "64 lowercase hex characters"
}
```

- `title` optional, 32 chars after trim. `body` optional, 1024. `lines`
  optional, at most 16 strings. `footer` optional, 40. At least one of title,
  body, or lines must survive normalization. `body` and `lines` are mutually
  exclusive on both show and update; supplying both is `INVALID_NOTICE`.
- Newlines in the body collapse to spaces. The renderer wraps; a plugin does
  not lay the band out by hand.
- Every lines entry is trimmed and has its own newlines collapsed to spaces;
  normalized empty entries are dropped. The remaining text plus one separator
  per line must fit the same 1024-character body budget. More than 16 input
  entries is rejected before empty entries are dropped. The array is the only
  supported way to request a hard break: the renderer breaks between entries,
  then wraps overflow at the same left edge with no marker or indent.
- `interactive` optional, default false.
- `wakeDisplay` optional boolean, default false. It is honored only on
  `/notice/show` and omitted from normalized payloads when false.
- `backdrop` optional boolean, default false. It is honored only on
  `/notice/show` and omitted from normalized payloads when false. When true,
  an opaque black scrim hides the rest of the glasses display behind the band.
- `actions` optional and **omitted entirely when empty**. At most three, and a
  fourth is rejected rather than dropped. Every action has nonblank `id`,
  `glyph`, and `label`, with the same rules as an activity's: the glyph name is
  shape-validated, not membership-checked, so a name from a newer platform
  degrades to `dot` on an older one, and there is no numeric cap on an id or
  label beyond nonblank and the three-action limit.
- `ttlMs` optional. When absent, the hub computes `2000 ms + 45 ms` per
  normalized title/body/footer character and clamps the result to
  `[4000, 45000]`. An explicit value is clamped to `[2000, 45000]`. Every
  accepted show or update restarts the ordinary countdown.
- Image metadata is optional and uses the image-surface fields shown above.
  The binary frame is required when they are present. JPEG and PNG are
  accepted up to 64 KiB, each decoded edge is at most 512 px, and the total
  decoded area is at most 512 x 512. Signature, dimensions, and SHA-256 must
  agree with the frame. A sender should aim near 480 x 160. An update preserves
  the current image; replacing or removing it requires a fresh show.

A notice that offers no actions sends no `actions` key. That is the
compatibility rule, not an optimisation: every band written before actions
existed serialises exactly as it did, and a hub or SDK that predates them sees
nothing new. It is also the one place the notice deliberately departs from the
activity payload, which always sends its array.

`/notice/update` may replace the whole row by sending `actions`; leaving the key
out keeps the current row, and an empty array clears it. The wearer's selection
follows its action id across a replacement, so a plugin reordering its answers
does not move the wearer's finger onto a different one. When the selected id is
gone the selection falls back to the first action.

`/notice/update` also keeps body and lines as alternate representations. A
`lines` field replaces and clears the current body, including when normalization
drops every entry; a `body` field replaces and clears the current lines. An
absent key keeps the current representation. The normalized patch, including an
empty lines array, is relayed as sent so those replacement semantics survive the
phone hop.

**An update that carries the `actions` key or the `interactive` key is a new
question** and reopens the band for another answer; one that carries neither is
the owner driving an already-answered band as a display and does not. Clearing
either — an empty array, or `interactive: false` — resets the flag as well:
there is then nothing left to answer, and a flag left set would only be
inherited by whatever the owner asks next.

Because the phone relays the owner's patch rather than re-serialising its state,
this falls out rather than needing enforcement: a text-only update simply does
not carry `actions` or `interactive`, so there is nothing there to reopen the
question with. An earlier build re-serialised full state and had to strip both
fields by hand to stop an ordinary text update putting an answered question back
in front of the wearer.

Actions buy the band nothing else. They do not extend the TTL, they do not
touch the 90 s absolute lifetime, and they do not change what BACK does.

**A band pages unless its row needs the directions to choose along.** Forward
and backward step a row of two or more, and such a notice draws a single page.
With one action or none there is nothing to step along, so the directions turn
pages while the tap still answers and BACK still dismisses — a long message
worth exactly one reply is both readable and answerable. No gesture ever
carries two meanings, which is the rule this serves.

### Two limits that are not the TTL

**An absolute lifetime of 90 s** from the first accepted show. Because every
update restarts the TTL — which is what keeps a band alive while someone
dictates into it — a plugin could otherwise hold a banner in the wearer's eye
forever by updating it.

The first real page turn ends both that lifetime and the TTL. The glasses enter
an engaged reading state with one 30 s inactivity timeout, restarted by every
page gesture including a gesture held at the first or last page. BACK, thirty
seconds without a gesture, or another notice taking the tier ends engagement.
The phone does not run a competing notice timer: page count and engagement
exist only on the glasses, where the text was measured.

**Five accepted messages per second per plugin**, shared between show and
update so the budget cannot be dodged by alternating. Sized so a transcript can
refresh a body a few times a second without any plugin driving the renderer.

### Errors

- `INVALID_NOTICE` — shape, id, cap, image, or enum validation failed. A fourth
  action, a blank action id or label, a malformed action glyph name, and an
  `actions` value that is not an array all land here, as do an invalid image
  frame and any binary frame on update or hide.
- `NOTICE_RATE_LIMITED` — over the per-second budget.
- `CAPABILITY_NOT_AVAILABLE` — notice v1 was not announced, or the glasses
  cannot be reached.

That last one is a real difference from pins. **A notice is never held for a
link that is down.** A pin is a standing fact and is worth delivering late; a
notice is a moment, and one delivered thirty seconds after the event is a lie
about the present. The plugin is told and decides for itself.

### Rendering

Geometry is platform-owned; a plugin sends content, never layout. Top band,
92% of screen width, pure black with the hairline border — the additive optics
emit nothing for black, so the fill reads as transparent and only the border
and content light up. The band is capped at 65% of screen height.

The renderer measures the complete normalized body representation once with the
real `StaticLayout`, width, typeface, and text size. A body string enters that
path unchanged; structured lines enter it with one hard break between entries.
Eight measured lines form a page; the controller owns the current page and the
renderer draws that exact line window. There is no scroll offset and no
upstream page calculation. A multi-page footer draws the plugin footer at the
start and muted platform text such as `2/4` at the end. One page adds no
indicator and no extra row.

An image is full content width below the title, aspect-preserved and capped at
150 physical pixels high. It appears on page one only, where the body window is
three lines; later pages recover all eight lines. Decode uses the same RGB-565
worker path as image surfaces and never runs on the main thread. Text and image
are published together only after decode succeeds.

Actions render as a row of glyph-and-label chips under the footer, the selected
one outlined in phosphor. It is the same row the activity panel draws, from the
same view: one affordance, drawn once, so the wearer learns it once.

**The row leaves the band the moment it is answered.** The question has been
answered, so the choices have no reason to stay in the wearer's eye, and what
remains is whatever text the band is carrying — an inert display the owner can
keep updating until it expires.

The band arrives and leaves through the shared motion layer (plan 013) rather
than blinking into place.

By default the band is superimposed over whatever is already on screen. A show
with `backdrop: true` fades the platform's opaque black scrim with the band and
hides everything behind it until that notice leaves or is replaced.

The window is never focusable and never touchable, and it never keeps the
screen on. A show with `wakeDisplay: true` may wake a dark display through the
global policy below. Updates and hides never wake it.

### Input claim

While a notice that expects input is visible, and only then. A notice expects
input when `interactive` is true **or** it carries actions: offering answers is
already asking for one, so a plugin shipping a choice does not also have to set
the flag.

- **Confirm** — ENTER or centre, meaning the firmware's *classification* of a
  touch and never the raw contact that opens one — is claimed and forwarded to
  the owner once: as `/notice/action` carrying the selected action's id when
  the band has a row, and as `/notice/input` when it does not. Every touch
  begins with a `KEYCODE_NOTIFICATION` contact and is only classified 300-500 ms
  later, so accepting that contact made the *start of a swipe* answer the band.
  A band is answered once and cannot take it back, which is why it waits for the
  verdict where a surface does not. Both are
  owner-scoped like `/notice/closed`, and both spend the band's one answer; see
  below. This works with no surface open, which is the capability the tier
  adds: until now every input route in the glasses hub was gated on an active
  surface, so a dormant plugin could be shown but never answered.
- **Forward and backward** — touchpad swipe or ring scroll — move the selection
  along an action row, wrapping at both ends, or replace the body with the
  previous/next measured page. They are claimed only while the band has **two
  or more** live actions — a row of one has nothing to step along — or is
  pageable. Which of the two they do follows the same test: a pageable band
  turns pages, anything else moves the selection. A plain one-page notice
  claims no direction, so every swipe keeps reaching the surface, activity, or
  launcher underneath.
  Page indices clamp at the ends; once engaged, a held-end gesture still
  restarts inactivity. The swipe-pair dedupe is the one the rest of the hub
  uses, because the hardware emits each direction twice and one step must not
  travel two.
- **BACK always dismisses**, platform-side, and is never forwarded. There is no
  `handlesBack` for notices and there never will be: a plugin must not be able
  to hold the wearer inside a banner, and giving a band answers does not change
  that. It runs ahead of the surface for the same reason. A double tap on the
  ring means the same.
- **Everything else passes through unchanged** — the launcher gesture, every
  other key. A band claims the gestures it can act on, not the glasses. The
  DOWN/UP bookkeeping that consumes an orphaned UP applies here too, because a
  notice routinely expires between the two halves of a press.

### One question, one answer

**A notice takes exactly one answer, of either kind.** Measured on device, two
temple taps 188 ms apart fired the same reply twice; for a messaging plugin
that is two messages sent. So the first confirm answers the band — firing
`/notice/action` when it has a row, `/notice/input` when it does not — and from
then on:

- the row, if there was one, leaves the band and the band becomes an inert
  display;
- forward and backward stop being claimed;
- confirm stops being claimed and **fires nothing at all** — not another
  action, and not `/notice/input` either, even when `interactive` is still
  true. Taps and swipes fall through beneath it exactly as they do beneath a
  plain banner.

**This changed behaviour that shipped in 1.0.46.** A band with
`interactive: true` used to fire `/notice/input` on every confirm for as long
as it was visible; it now fires on the first one only. The rule is the same one
the action row needed, and a question that could be answered twice was the same
bug whichever way it was asked.

The glasses hold the flag and the phone hub holds it too. That is deliberate
rather than redundant: the thing being defended against is a race, and a race
is precisely what survives one side losing its state.

Asking again means sending a new question — a `/notice/update` carrying
`actions` or `interactive`, or a fresh `/notice/show`. Answering changes nothing
about the band's life: the TTL and the 90 s absolute lifetime run exactly as
they would have.

A notice with actions still dies on its TTL. There is no hold-open rule, no
scrolling inside the band, and no text entry: a notice is a question with a
short life, not a menu. Anything the wearer needs to browse is a surface.

## Activity protocol v1

An activity is a structured, live description of an ongoing real-world process:
a route, delivery, ride, workout, or timer. The plugin declares what is
happening; the glasses hub chooses how prominently to present it. Plugins cannot
supply a layout, image, animation, color, timing, form factor, or presentation.

Use the four HUD kinds this way:

- **activity** — an ongoing process the wearer follows.
- **notice** — a discrete event needing attention or a response.
- **surface** — an engaged interaction the wearer is driving.
- **pin** — a trivial static fact that just needs to stay put.

Activities reuse the `surfaces` grant and plugin API version 3. There is no
activity descriptor capability or grant UI. Glasses announce support with
feature bit 128 (`ACTIVITY_SURFACE`, `1 shl 7`) and
`activitySurfaceVersion: 1`. A plugin connected to a hub that did not announce
activity v1 receives `CAPABILITY_NOT_AVAILABLE`; the hub does not send traffic
that the old glasses cannot understand.

### Paths and ownership

Phone to glasses:

- `/activity/start` — starts the owner's activity, or replaces its current
  state. It carries full state.
- `/activity/update` — patches an existing activity. It is owner-only.
- `/activity/end` — ends an existing activity. It is owner-only.

Glasses to phone to the owning plugin:

- `/activity/action` — `{activityId, id}`, where `id` is the selected action's
  plugin-supplied identifier.
- `/activity/closed` — `{activityId, reason}`, where `reason` is one of
  `owner | replaced | disconnect | max-duration`.

Returned activity traffic is owner-scoped, like notice input: the phone hub
delivers it only to the activity owner. The plugin uses the local surface ID
`activity`; after principal and grant checks, the phone hub injects
`ownerPluginId`, rewrites the wire ID to `<pluginId>:activity`, and assigns a
monotonic `seq`. A plugin cannot supply a trusted owner, global ID, or sequence.

### Payload

A normalized start payload on the phone-to-glasses wire is:

```json
{
  "surfaceId": "maps:activity",
  "ownerPluginId": "maps",
  "seq": 31,
  "kind": "activity",
  "glyph": "turn-left",
  "primary": "300 m",
  "secondary": "Rue de la Paix",
  "progress": 42,
  "eta": "12:41",
  "detail": ["then right on Av. de l'Opera"],
  "actions": [
    {"id": "mute", "glyph": "pause", "label": "Mute"}
  ],
  "wakeDisplay": true
}
```

Payload caps are checked after trimming. Violations are rejected, not
truncated:

- `glyph` is required and must be a well-formed glyph name. It may name a
  platform glyph or one registered by the plugin. The set is open and additive:
  an unknown but well-formed name renders as `dot` rather than being rejected.
  A plugin supplies geometry only through the existing custom-glyph contract,
  never an activity image.
- `primary` is required and is at most 12 characters.
- `secondary` is optional and is at most 28 characters.
- `progress` is optional. It is an integer from 0 through 100, or the string
  `"indeterminate"`. Absence means no progress affordance.
- `eta` is optional and is at most 8 characters.
- `detail` is optional and contains at most two strings, each at most 32
  characters.
- `actions` is optional and contains at most three actions. Every action has
  nonblank `id`, `glyph`, and `label` fields. Plugins must choose action glyphs
  from the shared platform vocabulary; the wire validates name shape so a
  well-formed glyph added by a newer platform can still degrade to `dot` on an
  older one. Activity v1 deliberately adds no numeric length cap to an action
  ID or label beyond the nonblank requirement and the three-action limit.
- `maxDurationMs` is optional on `/activity/start` and is clamped to
  `[60_000, 43_200_000]`. Absence means the activity has no deadline.
- `wakeDisplay` is an optional boolean on `/activity/start`, default false. It
  is stored with the activity and omitted when false.

`/activity/update` has patch semantics: a present field replaces its value, an
absent field keeps the current value, and JSON `null` clears an optional scalar.
JSON `null` or an empty `detail` or `actions` array clears that list. `glyph`
and `primary` remain required in the resulting state. The typed SDK sends the complete
mutable activity state on update, including empty lists and explicit nulls for
cleared optional values. It does not change `maxDurationMs`; that safety
deadline belongs to the start.

`significant` is an update-only transient boolean and defaults to false. It
requests attention from the platform policy, not a particular presentation. It
is not stored as activity state and is not replayed after a camera overlay or a
reconnect. When the activity was started with `wakeDisplay: true`, and only
then, a significant update may also ask the global policy to wake a dark
display. A non-significant update never wakes it.

The glasses hub drops a stale or duplicate `seq`. The phone hub accepts at most
four `/activity/update` messages per second per plugin. Start and end are not
charged to that update budget; they retain their validation and ownership
checks.

### Platform presentations

The same activity state can appear in five ways:

- **chip** — the ambient corner form, delegated to the medium pin panel view:
  glyph plus `primary` on the title row and `secondary` below.
- **panel** — the expanded form: large glyph, `primary` at 24sp,
  `secondary` at 13sp, trailing `eta`, progress when present, detail at 11sp,
  and the action row. It uses a pure-black background and the shared hairline
  border.
- **flare** — a significant update morphs the chip from its corner into the
  shared notice-band geometry over about 280 ms, holds for about 3.5 s, and
  reverse-collapses over about 240 ms.
- **pulse** — a minor or throttled update scales the chip
  `1.0 -> 1.12 -> 1.0` over about 180 ms.
- **hidden** — while the camera overlay is visible.

Presentation selection is a pure hub policy. In priority order:

| Context | `significant` | Flare budget | Collapse state | Result |
|---|---:|---:|---|---|
| Camera overlay visible | either | either | either | hidden |
| Any non-camera context | true | available | either | flare |
| Any non-camera context | true | exhausted | either | pulse |
| Another surface active | false | either | either | pulse |
| Nexus launcher visible | false | either | either | pulse |
| Idle/native home | false | either | running or always expanded | panel |
| Idle/native home | false | either | elapsed | chip |

The panel collapses to its chip after about 10 seconds without activity, unless
the wearer enabled the always-expanded setting. There is at most one flare per
10 seconds per activity. A significant update inside the budget window becomes
a pulse immediately; it is not queued. A camera-hidden update is retained as
state but its flare or pulse is not replayed when the camera disappears.

The renderer owns one fixed full-screen transparent,
`FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` window. Child views move, resize,
scale, fade, clip, and crossfade inside it. It never animates window layout
parameters, requests focus, claims touch, keeps the screen on, or wakes the
display outside the global wake policy. Activity v1 has no plan-014 glance
layer.

### Display wake policy

The glasses hub owns one display-wake budget across all plugins and all kinds.
At most one lock may be acquired in any 5,000 ms window. Surfaces always
request a wake when published; notice shows and significant activity updates
request one only through their start/show-time `wakeDisplay` opt-in. Pins,
notice updates and hides, activity starts and non-significant updates never do.

If the display is already interactive, the request is unnecessary rather than
throttled: no lock is acquired and the budget is unchanged. An admitted wake
uses the existing three-second `SCREEN_BRIGHT_WAKE_LOCK` with
`ACQUIRE_CAUSES_WAKEUP`. The budget is neither visible nor controllable by
plugins.

Waking is not holding. No plugin can ask the platform to keep the display lit
through any tier, and no wake outlives its three-second lock. That is a separate
matter from the screen the hub holds while the wearer is actively looking at it
— an engaged surface, the camera viewfinder, the launcher, the manual pairing
flow — which keep their own `FLAG_KEEP_SCREEN_ON` for as long as the wearer is
there and are not governed by this budget.

### Capacity, corners, and primary selection

Activity v1 holds at most two activities alongside the existing one pin. The
pin keeps its chosen corner and new activities take free corners. Even with all
three residents, one of the four corners remains unused.

At activity capacity, a third start replaces the least-recently-updated
non-primary activity. If there is no non-primary candidate, the oldest started
activity is the deterministic fallback. The replaced owner receives
`/activity/closed` with reason `replaced`. Restarting or updating an existing
owner retains its corner rather than making the HUD jump.

Exactly one activity is primary because only one expanded panel and action row
can be active. The activity with the latest significant update is primary; if
none has a significant update, the oldest started activity is primary.
Significant updates affect primary selection even when their flare is throttled
to a pulse or hidden by the camera. Non-primary activities remain chips.

### Actions and input arbitration

With no actions, center tap opens the owner through the standard
`/system/plugin/open` route, just like a launcher tile. With one to three
actions, forward/backward selects among the platform-rendered glyphs and center
tap emits `/activity/action`. Actions are one-shot commands only: there is no
text entry, scrolling, plugin layout, or fourth action. Anything more involved
opens a surface.

An activity may claim those keys only on the idle layer: there must be no active
surface, no visible notice, no visible launcher, and no camera overlay. Under
any of those higher-priority contexts the activity remains passive. Only the
primary activity can claim input. BACK is never claimed or forwarded by an
activity, and activity windows never take focus.

### Lifecycle, reconnect, and errors

Activities have no TTL or keep-alive requirement. An activity ends when its
owner ends it, the owner disconnects, it is replaced, or its optional maximum
duration expires. The phone hub owns canonical state and is the single place
that emits `/activity/closed`.

On a glasses capability re-announce, the phone first sends a fresh,
hub-generated clear-all sentinel on `/activity/end` for wire ID
`@nexus-hub:activity`, before resending canonical activities:

```json
{
  "surfaceId": "@nexus-hub:activity",
  "localSurfaceId": "activity",
  "ownerPluginId": "@nexus-hub",
  "seq": 104
}
```

The owner starts with `@`, which is outside the plugin-id grammar, so a plugin
end cannot collide with the empty-slot assertion. The glasses clears all
rendered activities and advances the sequence floor before accepting the
resends. Each resend carries the remaining `maxDurationMs`, not the original
duration; a sub-minute remainder uses the start contract's 60-second wire floor
while the phone retains and enforces the exact original deadline. This prevents
a ghost activity from surviving a phone-hub restart or continuing to claim
idle-layer input.

Activity errors mirror pins and notices:

- `INVALID_ACTIVITY` — shape, ID, cap, type, or resulting-state validation
  failed.
- `ACTIVITY_RATE_LIMITED` — more than four accepted updates per second for the
  plugin.
- `CAPABILITY_NOT_AVAILABLE` — activity v1 was not announced or the glasses
  cannot accept the activity.

## Camera contract

The generic camera contract is available only to an installed plugin whose exact
package, descriptor ID, and signing digest have an approved, enabled `camera`
grant. Installation or a shared signer alone never grants access.

The bus carries control only. The heavy data path is out-of-band: during a
normal session the glasses encode the camera as H.264 over Core Bulk Link; the
consumer plugin receives only an authorized local descriptor, decodes on the
phone, and runs its processing (Lens: ML Kit OCR + translation) there. P2P
credentials remain between the hubs. Frozen captures ride the same descriptor
as full JPEGs, with `/camera/freeze/image/chunk` over SPP as the fallback when
the link is down.

The link has two modes, chosen by the phone from its own Wi-Fi state at
session start (`PhoneLensTransportModePolicy`) and carried in the offer's
`mode` field:

- normal P2P: the glasses core is Group Owner, the phone core joins it, and the
  `linkMode` in session config is `bulk`.
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
  `protocolVersion`, and `linkMode` (`bulk` or `lohs_reverse`).
- For `bulk`, `/camera/link/offer` carries only `sessionId`, `state=link_ready`,
  `mode=nexus_p2p`, and the non-secret epoch from the phone hub to the owner.
- For the explicit legacy `lohs_reverse` session only, `/camera/link/offer`
  retains `CameraLinkOfferContract`: `sessionId`, `ssid`, `passphrase`, `port`,
  `token`, optional `goIp`, `mode`, and `security`. It carries the phone's
  reverse LOHS offer and the bounded legacy P2P fallback if reverse setup fails.
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

In the phone-to-glasses capability direction, bit `4` is
`CAMERA_CONSUMER_READY`, bit `8` is `CAMERA_FROZEN_SPP`, and bit `16` is
`CAMERA_LOHS_REVERSE_REQUIRED`. The phone
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

## Video playback contract (`/video/*`, experimental MVP)

Video playback requires an approved `video_playback` grant. The phone hub
rebuilds every plugin-originated video control envelope with the authenticated
`pluginId` and `ownerPluginId`; the glasses bind the session and controls to that
owner. `/video/session/state` is a direct, owner-scoped reply and never reaches
another plugin.

## Core Bulk Link v1 (`/core/bulk-link/*`)

Bulk Link is the shared, hub-owned P2P lease used only for `camera` and `video`.
`/core/bulk-link/offer` and `/core/bulk-link/state` are trusted hub-to-hub paths:
they are under `/core`, are never plugin capabilities or receive prefixes, and are
never forwarded to a plugin. A v1 offer is
`{version:1,sessionId,purpose,ownerPluginId?,epoch,token,ssid,passphrase,goIp,port}`.
`purpose` is `camera` or `video`; `epoch` is positive; `port` is fixed at **38400**.
The receiver rejects unknown versions, malformed UUID session IDs, invalid plugin
owners, non-positive epochs, malformed IPs, any other port, and fields over their
contract bounds. Credentials and tokens are secrets: they must not be logged,
persisted, or delivered beyond the two hubs.

State is `{version:1,sessionId,purpose,epoch,state,reason?}` where `state` is
`offered`, `connecting`, `open`, `released`, or `error`; `reason` is bounded to
160 characters and carries no credential or token material.

Before either endpoint accepts raw bytes, it exchanges a core handshake metadata
object `{version:1,sessionId,purpose,epoch,token}` framed as a four-byte big-endian
length followed by UTF-8 JSON. The JSON is capped at 4096 bytes and must exactly
match the live offer. A lease may remain warm for at
most 40000 ms after its user leaves; a newer epoch always supersedes it.

Plugins access a live, owner-authorized lease only through the appended Binder call
`openBulkChannel(sessionId, purpose)`, which returns a local
`ParcelFileDescriptor` or null. The hub authenticates the calling UID and checks
the session, purpose and capability at that boundary. Plugins receive neither the
offer, credentials nor token.

The bus is control-only:

| Path | Direction | Payload |
|---|---|---|
| `/video/session/open` | plugin → glasses | `sessionId` (UUID), local `surfaceId`, `mediaType`, `loop`, `muted`; identity fields are overwritten by the phone hub |
| `/video/session/control` | plugin → glasses | matching `sessionId` and `action` (`pause`, `resume`, `stop`) |
| `/video/session/state` | hubs → owner plugin | matching `sessionId`, hub-stamped `pluginId`, and `state` (`link_ready`, `opened`, `playing`, `paused`, `ended`, `busy`, `error`, `closed`); `link_ready` also carries the non-secret lease epoch |

Compressed media uses the distinct `MediaLinkProtocol` v1 TCP framing (`MLNK`),
not `CameraLinkProtocol` and never SPP. Its fixed header carries type, owner-lease
epoch, sequence, presentation timestamp and flags, followed by at most 64 KiB of
UTF-8 metadata and 8 MiB of payload. Packet types are `HELLO`, `VIDEO_CONFIG`,
`VIDEO_SAMPLE`, `AUDIO_CONFIG`, `AUDIO_SAMPLE`, `WINDOW_UPDATE`, `EOS`, and
`ERROR`. The core handshake validates the random token before feature bytes are
bridged; the media receiver then accepts packets only after `HELLO` matches the
session and epoch. Video samples are AVC with key-frame flags and PTS; audio
samples are optional AAC with PTS. V1 pacing is sender-side and socket-bounded;
`WINDOW_UPDATE` reserves an additive explicit receive window for a later revision.

The glasses run a foreground `VideoActivity` in `:video`, decode AVC with
`MediaCodec` onto a `SurfaceView`, and decode optional AAC through `MediaCodec`
to `AudioTrack`. BACK, stop, link loss, plugin death, revocation and package loss
all close the session and release the P2P group. Camera and MediaSync exclude
video from their high-bandwidth window; an explicit Camera launch preempts video,
while an ordinary video open receives `busy` instead of silently evicting them.
The video endpoint never removes a P2P group it did not create.

Feeds prefetches a selected AVC asset into bounded private cache before joining
Wi-Fi Direct, so media download does not depend on concurrent Internet routing.
X MP4 is copied directly. Bluesky VOD HLS accepts one bounded public HTTPS
playlist, selects an AVC rendition no larger than 1280×720/4.5 Mbit/s, rejects
live, encrypted, byte-range, oversized and overlong playlists, and concatenates
the init/segments for local `MediaExtractor` demux without transcoding. Temporary
files are deleted on teardown and pruned on the next video session. Redirects and
every HLS resource remain restricted to public `bsky.app`/`twimg.com` hosts.

Glasses capability bit `2048` (`1 << 11`) advertises this MVP. It is not a
hardware-readiness claim: the decoder surface, audio route, A/V sync, thermals,
battery, P2P behavior and HLS container path remain release-gated on the physical
Rokid device.

## Photo sync contract (`/mediasync/*`)

Photo sync copies the captures the native camera button writes to the glasses'
`/sdcard/DCIM/Camera` into the phone gallery, under `Download/Hi Rokid/` with the
original filenames (the same MediaStore bucket Hi Rokid's own manual imports land
in). Every `/mediasync/...` path is protected: it requires an approved, enabled
`mediasync` grant, and the grant is also the *consent* — the hub engine stays
dormant until at least one approved plugin holds it.

**Transport: the Bluetooth bus itself.** Bytes ride in `BusEnvelope.binary`, the
same SPP binary frame the HUD image channel uses, so there is no separate data
plane to negotiate and photo sync needs no Wi-Fi at all. Measured ceiling is
~64 KiB per ~180 ms (~0.36 MB/s): a photo takes 4-5 s, a video minutes. That is
the deliberate trade — photo sync is a passive, charge-anchored background
feature, so it may be slow, but it must never be fragile and must never crowd out
whatever else the link is carrying. (The Wi-Fi Direct transport v1 started with
was abandoned; the hardware findings are preserved at the end of this section.)

Plugin-facing paths:

| Path | Direction | Payload |
|---|---|---|
| `/mediasync/status` | hub → plugin (receive-only) | `MediaSyncStatusContract`: `state` (`idle`/`preparing`/`transferring`), optional `blocker`, `syncMode`, `deleteAfterSync`, `progress`, `history` (≤ 8 runs), `syncedTotal`, optional `deletionSupported` |
| `/mediasync/settings` | plugin → hub | partial update `{version, syncMode?, deleteAfterSync?}`; an empty request is a refresh, answered with a `/mediasync/status` push |
| `/mediasync/now` | plugin → hub | `{version}`; relays a manual trigger to the glasses |

Hub-to-hub paths, rejected outright when a plugin tries to originate them
(`isHubOnlyMediaSyncPath`): `/mediasync/config` (phone → glasses: `syncMode` and
`consented`), `/mediasync/config/request` (glasses → phone), `/mediasync/trigger`
(phone → glasses), `/mediasync/state` (glasses → phone), and the data plane under
`/mediasync/xfer/…`.

**Sync modes.** `syncMode` is one of `always` (auto whenever the link is up and
captures are pending), `charging` (auto only while the glasses charge — the
default) and `manual` (no auto triggers). "Sync now" works in every mode, at any
time. Triggers are evaluated glasses-side as one pure policy
(`MediaSyncTriggerPolicy`): charging edge, bus connect, **new capture** (a
debounced `FileObserver` on the capture directory), or manual — each gated on a
non-empty stable catalog, no live camera session, and glasses storage access.

A capture only enters the catalog once two scans at least 3 s apart agree on its
size and mtime *and* the mtime is at least 5 s old, so an in-progress video
recording can never be transferred.

**The data plane** (`/mediasync/xfer/…`, `MediaSyncTransferContract`): the phone
pulls. It asks for the catalog, diffs it against its ledger, then requests one
file at a time **with the byte offset it already holds**, and acks each file only
after the bytes are staged, hashed against the trailing whole-file SHA-256 and
published out of `IS_PENDING`. Partial files are staged in the hub's private
storage rather than into the pending MediaStore row — the staged file's length
*is* the resume offset, so there is no second bookkeeping to drift, and a
multi-minute video survives interruption instead of restarting.

**The politeness layer** is the heart of the feature, because the link is shared:

- Chunks are 32 KiB (half the image channel's proven 64 KiB), which halves how
  long any other message can sit behind ours, for ~0.4% header overhead.
- **Windowed acks, because the transport acknowledges too early.**
  `SppServerManager.send` returns once the frame reaches the socket, not once it
  reaches the air — measured on device as a ~41 ms enqueue cadence against a
  ~90 ms wire time per chunk. The kernel queue therefore ran several chunks deep,
  which broke two things at once: `FILE_END` (control channel) overtook chunks
  still queued on SPP so the receiver verified a short file, and the politeness
  layer was pacing *enqueues* while the radio kept draining a backlog for seconds
  after a yield or an abort. The receiver now acks its staged offset every 2
  chunks (`/mediasync/xfer/file/progress`), the sender may run at most
  `ACK_WINDOW_BYTES` (128 KiB, four chunks ≈ 360 ms of air time) ahead of the
  last ack, and **`FILE_END` is sent only once the whole file is acked** — so
  `staged == expected` holds before the terminator is even written, and the
  staged/expected log line at verification is an invariant check rather than a
  diagnostic. Ordering guarantees per message type are documented in
  `MediaSyncTransferContract`.
- Before every chunk, `MediaSyncPolitenessPolicy` is consulted: a live camera
  session aborts the session outright, a dropped link ends it, foreign traffic
  seen in the last 400 ms yields for 1.5 s, and otherwise a chunk goes out
  followed by a 40 ms idle gap. "Foreign" is every envelope crossing the link in
  either direction whose path is not `/mediasync/xfer/…`, tracked by
  `MediaSyncTrafficMonitor` from both hubs' send and receive paths.
- Everything a pause interrupts is resumable, and status pushes are themselves
  throttled (per file, otherwise at most every 2 s) so reporting never floods the
  link the transfer is being careful with.

Delete-after-sync is opt-in and off by default. The phone carries the flag in
each file ack; the glasses attempt `File.delete()`, then a MediaStore delete,
then the command bridge, and report `deleted`, `already_gone`, `not_permitted` or
`failed`. The first two routes are refused on this hardware — the capture belongs
to the camera app and scoped storage yields only to an all-files grant or an
interactive consent dialog a headless hub has no screen for — so the bridge's
`delete_capture` is what actually removes the file: a shell-uid process may, and
the name is re-validated there against a fixed capture directory rather than
trusted. When the bridge is absent the outcome stays the honest `not_permitted`,
which surfaces as `deletionSupported: false` in the status rather than being
silently swallowed. Adding another privileged command follows the recipe in
`docs/SELF_ARM_ONBOARDING.md`.

Because the `:camera` process can die without ever sending `closed`, the main
process reconciles a stale session lazily: the moment a sync would skip with
`camera_active` it checks whether a `:camera` process actually exists and, only
if it provably does not, releases the flag and re-evaluates. An unreadable
process list counts as "still alive" — an unknown answer must never cancel a real
camera session.

A glasses hub restart wipes its in-memory consent while the CXR transport keeps
running, so the phone would see no edge on which to re-push it. The glasses
therefore ask (`/mediasync/config/request`) on engine start and on every link-up,
and the phone also re-pushes config whenever the glasses re-announce
`/system/hub/capabilities`. Fail-closed throughout: no consent, no sync.

### Wi-Fi Direct on this hardware — findings (not used by photo sync v1)

Photo sync originally moved bytes over an app-owned Wi-Fi Direct group, and that
transport was abandoned after three stacked ROM landmines. None of this affects
photo sync any more, but it will bite any future P2P feature on these glasses:

1. **Config-based `createGroup` is rejected** (caller-chosen SSID/passphrase),
   `reason=0`. The camera link only ever works here through the no-config
   `createGroup(channel, listener)` overload, taking framework-generated
   credentials. Any P2P feature needs that fallback, not just the builder.
2. **The P2P framework powers up lazily and drops when idle.** It came up ~288 ms
   *after* station Wi-Fi in measurement, and `createGroup` into
   `P2pDisabledState` returns `reason=0` even with station Wi-Fi on, location on
   and no existing group. Creation must be gated on the
   `WIFI_P2P_STATE_CHANGED_ACTION` → `WIFI_P2P_STATE_ENABLED` broadcast, never on
   `isWifiEnabled`.
3. **Background callers need the location appop, not just the permission.**
   `FINE_LOCATION` granted is not enough: the appop mode is `foreground`, so
   `WifiP2pService` rejects `createGroup` from a background process with generic
   `ERROR`. Confirmed by the same call succeeding with the hub's activity in the
   foreground, and by `appops get FINE_LOCATION` showing a `rejectTime` matching
   the failures. The camera link never hit this because `CameraActivity` is always
   foreground while it creates its group. `appops set <pkg> FINE_LOCATION allow`
   (plus `COARSE_LOCATION`) is the lever.
4. Even with all three addressed, a final probe still returned `reason=2` (BUSY).

Because framework-generated SSIDs make prefix-based ownership meaningless, such a
feature must also never remove a group it did not create — the camera link's
parked group (kept ~40 s so warm reopens cost 1.4 s instead of 5-7 s) is
indistinguishable from a stranger's.


## Wireless ADB control v1

Wireless ADB exposes Android's real ADB-over-Wi-Fi transport to one authenticated
phone plugin without giving that plugin a shell or letting it drive Settings. The
route is available on phone and glasses hubs 1.3.0 or newer. The
plugin must be approved and hold the high-risk `wireless_debugging` grant before it
can send `/debug/adb/request`. The phone hub derives the principal from Binder,
discards caller-supplied identity and unknown fields, rebuilds the canonical request
with its verified `pluginId`, and forwards it to the glasses. The reply returns on
`/debug/adb/reply` with the same
envelope id and is delivered only to that owner; it is a direct reply and requires
no receive prefix.

Request payload:

```json
{ "version": 1, "action": "start_pairing" }
```

`action` is exactly one of `status`, `enable`, `start_pairing`,
`cancel_pairing`, or `disable`. Unknown versions and actions fail closed. Plugins
must use `WirelessAdbContract.request(action)` rather than hand-building payloads.
On glasses hub 1.4.1 or newer, `enable` and `start_pairing` turn the Wi-Fi radio
on when necessary and wait for a saved network before enabling ADB. They never
choose or configure a network. `disable` remains scoped to wireless ADB and does
not turn Wi-Fi off.

Every reply contains `version`, the hub-stamped `pluginId`, `action`, `success`,
`wifiConnected`, `enabled`, and `pairingActive`. It may also contain `host`,
`connectPort`, `pairingPort`, `pairingCode`, `expiresAtMillis`, `errorCode`, and
`message`. A successful `start_pairing` reply supplies a validated IPv4 address,
ports in `1..65535`, a six-digit code, and an expiry so the client can form:

```text
adb pair <host>:<pairingPort> <pairingCode>
adb connect <host>:<connectPort>
```

Stable v1 failure codes are `INVALID_REQUEST`, `HUB_NOT_READY`,
`WIRELESS_ADB_BUSY`, `UNSUPPORTED_ANDROID_VERSION`,
`DEVELOPER_OPTIONS_DISABLED`, `WIFI_ENABLE_FAILED`, `WIFI_REQUIRED`,
`PRIVILEGED_BRIDGE_UNAVAILABLE`, `NO_IPV4_ADDRESS`,
`PAIRING_CANCEL_FAILED`, `PAIRING_SERVICE_NOT_FOUND`,
`PAIRING_CLEANUP_FAILED`, `WIRELESS_DEBUGGING_STOPPED`, `DISABLE_FAILED`, and
`INTERNAL_ERROR`; a phone-to-glasses transport failure may instead preserve the
existing bus error code. `message` is display text, while `errorCode` is the
machine-readable branch.

The glasses trust only the current Wi-Fi network through a fixed privileged bridge
operation. No payload field is interpolated into a shell command. The hidden Binder
transactions are limited to the validated Rokid Android 12L/API 32 firmware; other
API levels return an unsupported-version failure. Nexus never logs or persists the
pairing code; only an explicit user copy action may place the generated command on
the Android clipboard. The two-minute deadline is persisted without the code so a
hub restart can still stop the temporary pairing service on time. `cancel_pairing`
clears that window, while `disable` also turns off the wireless debugging transport.
The hub clears its active state only after it observes a successful stop or a closed
transport. At expiry, a failed pairing-stop command triggers a fail-closed transport
disable; if both operations fail, the hub retains the session and retries instead of
reporting it inactive. Logs must redact the pairing code, BSSID, device identity,
and full reply payload.

## Hub capabilities announcements

Both hubs announce an additive JSON payload on `/system/hub/capabilities`;
unknown fields are ignorable in both directions, so fields only ever get added.

- Glasses → phone (`GlassesHubCapabilitiesContract`): `version`, renderer
  `features` bits, `imageSurfaceVersion`, `pinSurfaceVersion`,
  `noticeSurfaceVersion`, `activitySurfaceVersion`, `inkSurfaceVersion`,
  `ttsVersion`, `maxImageBytes`, the glasses app
  `versionName` (drives the phone-side glasses update checker), and
  `setupComplete` (self-arm onboarding state; the phone preserves the last
  known value across link loss — only a live announcement can lower it).
- Phone → glasses (`PhoneHubCapabilitiesContract`): `version`, `features` bits
  (including `CAMERA_CONSUMER_READY`), and `cameraConsumerName` — the display
  name the glasses launcher uses for the synthesized camera entry (present
  only while a consumer is ready, ≤ 80 chars). The additive
  `activityAlwaysExpanded` boolean carries the wearer's platform setting; it
  defaults to `false` when absent and is never plugin-controlled.

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
    String approvedCapabilities(String pluginId);   // the caller's own grants
    ParcelFileDescriptor openBulkChannel(String sessionId, String purpose); // appended last
}
```

`approvedCapabilities` answers the caller's own grants as a comma-separated
list, and only ever the caller's: it is resolved from the registration the
calling UID already holds, so an unknown plugin id, another app's id, or a
caller with no live registration all get `""` rather than somebody else's
grants. It exists because `registerPlugin` answers APPROVED synchronously while
the grant list follows behind it as a `/plugin/registration` message — measured
on hardware at 16 ms apart — so a plugin acting the instant it is approved read
an empty grant set and was refused a capability the wearer had approved. A hub
too old to answer fails this one call, the SDK reads null, and the message path
stays in charge exactly as before.

`openBulkChannel` is an authenticated, capability-gated local endpoint for a live
Core Bulk Link lease. It returns null if the caller is not the live owner or if
the requested purpose (`camera` or `video`) is not authorized. It exposes no
network offer metadata or secrets.

The method order is append-only so transaction codes remain stable. Link-state
bits are `1 = CXR_CONTROL_UP`, `2 = SPP_DATA_UP`, and
`4 = GLASSES_BT_BONDED_OR_PHONE_CONNECTED`, and `8 = GLASSES_WORN`.

Hub feature bits share one value space regardless of direction. Bit `2` is
`IMAGE_SURFACE`, bit `4` is `CAMERA_CONSUMER_READY`, bit `8` is
`CAMERA_FROZEN_SPP`, bit `16` is `CAMERA_LOHS_REVERSE_REQUIRED` (sent only in
phone-to-glasses camera announcements), bit `32` is `PIN_SURFACE`, bit `64` is
`NOTICE_SURFACE`, bit `128` is `ACTIVITY_SURFACE`, bit `256` is
`PHONE_ASSISTED_SETUP`, bit `512` is `TTS`, and bit `1024` is `INK_SURFACE`.
The phone does not
include renderer bits in camera announcements. The glasses hub announces its
renderer after either remote link connects by sending
`/system/hub/capabilities` with
`{"version":1,"features":1762,"imageSurfaceVersion":1,"pinSurfaceVersion":1,"noticeSurfaceVersion":3,"activitySurfaceVersion":1,"inkSurfaceVersion":1,"ttsVersion":1,"maxImageBytes":65536,"versionName":"1.0.0","setupComplete":true}`
when every current renderer feature, including runtime TTS, is available. The
`features` value is the bitwise sum; TTS may be absent at runtime.
`versionName` is the optional glasses app `BuildConfig.VERSION_NAME`; older glasses
omit it and newer phones treat the missing field as an unknown installed version.
`setupComplete` reports whether the on-device self-arm onboarding state is `COMPLETE`;
older payloads omit it and newer phones default the missing field to `false`. A glasses
hub linked during the transition re-announces capabilities so the phone sees it live.
The phone hub exposes renderer features to local plugins only after receiving
their valid versioned announcements. `IMAGE_SURFACE` additionally requires
`SPP_DATA_UP` and is cleared when all glasses links drop. `PIN_SURFACE` is not:
it survives link drops, because a pin has canonical phone-side state and an
announce-time resend, so one pushed while the glasses are asleep is held and
delivered on reconnect rather than refused. Activities likewise have canonical
phone-side state and reconnect resends, but owner disconnect still ends them;
notices remain live moments and are never held for a down link. A later
announcement overwrites the remembered feature value. Capability changes are surfaced by
another link-state callback so clients refresh `capabilities()`; callers must not
cache a one-time Binder result. Old glasses hubs do not announce the bit, so the
plugin API version remains 3 and typed image, pin, notice, activity, and Ink
calls fail locally with `CAPABILITY_NOT_AVAILABLE`. Image surfaces, pins,
notices, and activities remain covered by the existing `surfaces` user grant;
their feature bits are not plugin descriptor capabilities. Ink requires both
the `INK_SURFACE` bit with `inkSurfaceVersion == 1` and the separate
`ink_surface` plugin grant.

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
    fun approvedCapabilities(): String?   // null when the hub predates the call
    fun openBulkChannel(sessionId: String, purpose: BulkLinkPurpose): ParcelFileDescriptor?
    fun close()
```

The typed plugin wrapper adds pin and activity methods directly to
`NexusPluginClient`, because both are independent from any
`NexusSurfaceSession`:

```kotlin
fun showPin(pin: NexusPin): NexusSdkResult
fun hidePin(): NexusSdkResult
val supportsPinSurface: Boolean

fun startActivity(activity: NexusActivity): NexusSdkResult
fun updateActivity(
    activity: NexusActivity,
    significant: Boolean = false,
): NexusSdkResult
fun endActivity(): NexusSdkResult
val supportsActivitySurface: Boolean
```

`NexusPluginCallbacks` receives `onActivityAction(id: String)` and
`onActivityClosed(reason: String)`. `NexusPluginService` exposes them as
`onNexusActivityAction(id: String)` and
`onNexusActivityClosed(reason: String)`.

The typed Ink wrapper is session-scoped because the phone retains compiled
state between data patches:

```kotlin
fun NexusPluginClient.inkSurfaceSession(localSurfaceId: String): NexusInkSurfaceSession

class NexusInkSurfaceSession {
    fun show(page: String, data: JSONObject? = null, handlesBack: Boolean = false): NexusSdkResult
    fun update(data: JSONObject): NexusSdkResult
    fun hide(): NexusSdkResult
}

val NexusPluginClient.supportsInkSurface: Boolean
```

`NexusPluginCallbacks` receives `onInkReady`, `onInkAction`, `onInkClosed`, and
`onInkError`; `NexusPluginService` exposes the corresponding
`onNexusInk*` overrides and `nexusInkSurfaceSession(localSurfaceId)` factory.
Local size failures and remote compiler/renderer failures use the same typed
`NexusInkProblem` callback path.

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

## Trusted core phone controls v1

Native-app discovery, remote text input, and system navigation are hub-owned
features. Every path begins `/core/`, is reserved by `PathRules`, and is
consumed before plugin routing. No plugin capability authorizes sending or
subscribing to these paths, and no external registration can impersonate either
hub. All payloads are versioned JSON; binary bodies are invalid.

### Native apps

- Phone → glasses `/core/native-apps/request`: a `list_request`, or a
  `launch_request` naming one package.
- Glasses → phone `/core/native-apps/result`: matching `list_result` or
  `launch_result`. The response preserves the bus-envelope id; the phone accepts
  state changes only for a still-pending contract `requestId`.

The glasses enumerate `ACTION_MAIN` + `CATEGORY_LAUNCHER` activities, exclude
the glasses hub itself, deduplicate by package, sort by label/package, and cap
the result at 64 entries. Labels are trimmed/control-stripped and limited to 96
characters; packages are validated before launch. If a full result does not fit
the current transport, the glasses send the largest valid prefix that does.
This protocol lists and opens apps that are already installed. It has no APK
download, transfer, install, uninstall, or privilege-elevation operation.

### Remote input

- Glasses → phone `/core/remote-input/session`: `session_open` and
  `session_closed` from the Nexus IME.
- Phone → glasses `/core/remote-input/command`: `commit_text`,
  `set_composing_text`, `finish_composing_text`, `delete_surrounding_text`,
  `perform_editor_action`, or `close`.
- Glasses → phone `/core/remote-input/status`: readiness, cumulative applied
  sequence, rejection with expected sequence, or closure.

Only the editor-owned active session accepts commands. Session ids are random,
commands carry a monotonically increasing safe-integer sequence, duplicates are
acknowledged without applying twice, and out-of-order commands are rejected with
the next expected sequence. A text-bearing delta is at most 256 UTF-16 code
units and 512 UTF-8 bytes; messages are at most the 3 KiB CXR-control budget.

The protocol deliberately carries editing deltas, never an editor snapshot.
The glasses send input type, IME options, target package, and a derived
`sensitive` flag, but never the field's current text, selection, surrounding
text, password, or extracted document. The phone keeps typed/composing text
ephemeral, redacts text-bearing model strings, sets `FLAG_SECURE` for sensitive
sessions, and clears state on close or transport loss. Implementations must not
persist or log command JSON.

### Remote navigation

- Phone → glasses `/core/navigation/request`: one of `previous`, `next`,
  `select`, or `back`, with a unique request id.
- Glasses → phone `/core/navigation/result`: the same id/action with success or
  `service_unavailable`, `action_unavailable`, `invalid_request`, or `internal`.

The glasses AccessibilityService moves accessibility/input focus through the
current readable window, clicks the focused node or a clickable ancestor, and
uses Android's global BACK action. It does not inject shell or ADB commands.
Results are kept in a bounded replay cache, so a transport retry with the same
request id returns the prior result instead of performing the action twice.

### Remote pointer

- Phone → glasses `/core/pointer/command`: `show`, `move`, `move_end`,
  `click`, `long_press`, or `hide`.
- Glasses → phone `/core/pointer/result`: the same stream id, sequence, and
  action with success or `service_unavailable`, `action_unavailable`,
  `gesture_cancelled`, `stale_sequence`, `stream_retired`,
  `stream_not_started`, or `internal`.

The phone-side API accepts relative normalized trackpad deltas and coalesces
movement to at most one update every 34 ms (29.4 updates/s). With CXR-L up, the
phone hub normally translates those updates to the ROM's `Tools` custom-command
protocol: `enterTouch`, relative `moving` deltas in the nominal 480×640 display,
`move_end`, `click`, `long_press`, and `exitTouch`. Rokid's system assistserver
draws its cursor and injects the resulting touchscreen events. This direct path
does not enter the Nexus bus.

The versioned hub-to-hub contract above is the fallback when the vendor command
cannot be submitted. It carries the phone hub's absolute normalized position so
a duplicate or delayed move is idempotent. Every action except `hide` carries
`x` and `y` numbers in `[0,1]`. A click or long press always carries its own
position, so it cannot land at an older cursor location if CXR and SPP delivery
cross during a transport change.

Every command carries a random `streamId` and a monotonically increasing
safe-integer `sequence`. A stream begins with `show`. The glasses reserve a
sequence before performing its effect, cache completed results, reject stale
sequences, and retire the old stream when a new one begins. A replay therefore
cannot move twice or synthesize a second tap, including while the original tap
gesture is still in flight.

For fallback commands the glasses draw the cursor in a non-focusable,
non-touchable `TYPE_ACCESSIBILITY_OVERLAY` owned by the already-enabled Nexus
accessibility service. `click` and `long_press` use
`AccessibilityService.dispatchGesture` at the displayed cursor centre. Link
loss and inactivity hide the overlay. Neither pointer path runs a shell command,
writes a setting, or requests an additional permission. The fallback is a
trusted hub-to-hub control under `/core`; it has no plugin capability, receive
prefix, or public SDK surface.

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

## STT v1

Speech-to-text is a separate derived-sensitive-data capability. A plugin may
request `stt` without requesting `microphone`: the hub owns the engine,
credentials, glasses PCM, and raw audio lease, while the plugin receives text
only. Both plugin-to-hub request paths require an approved `stt` grant:

Which engine transcribes is the user's business, not the plugin's. A session
may be served by a cloud provider or by the phone's own recognizer — which
takes no credentials and is what a fresh install starts on. The wire contract
below is identical either way, so plugins must not infer engine, cost, or
where the audio went from anything they receive.

- `/stt/session/start` payload
  `{"version":1,"mode":"utterance","language":"fr"}`. `language` is optional
  and, when recognized, is a `TranscriptionLanguage` ID. An absent or unknown
  ID uses the hub's configured language. Version 1 supports only `utterance`;
  `continuous` is reserved.
- `/stt/session/stop` payload `{"sessionId":"<uuid>"}`. Stop is idempotent. A
  missing, wrong, stale, or differently owned session ID has no effect and
  still receives `{"stopped":true}`.

Replies append `/reply` and retain the request envelope ID:

```json
{"accepted":true,"sessionId":"<uuid>","realtime":true,"pluginId":"holder"}
```

or:

```json
{"accepted":false,"reason":"BUSY","pluginId":"holder"}
```

Start denial reasons are exactly `BUSY`, `NO_LINK`, `NOT_READY`,
`START_FAILED`, and `INVALID_REQUEST`. Unknown `version` or `mode` produces
`INVALID_REQUEST`. `realtime` tells the client whether partial hypotheses will
stream; buffered engines normally emit only the final result. Stop replies are
`{"stopped":true,"pluginId":"holder"}`.

The hub sends the following JSON events only to the callback binder that owns
the session. Every payload includes `pluginId` matching that verified holder:

- `/stt/state`
  `{"version":1,"sessionId":"<uuid>","state":"listening","pluginId":"holder"}`.
  State is `listening`, `recognizing`, or `processing`; event IDs are
  `<sessionId>:s<n>`.
- `/stt/partial`
  `{"version":1,"sessionId":"<uuid>","text":"...","seq":0,"pluginId":"holder"}`.
  Realtime engines only; `seq` is monotonic and the event ID is
  `<sessionId>:p<seq>`.
- `/stt/final`
  `{"version":1,"sessionId":"<uuid>","text":"...","pluginId":"holder"}` with
  event ID `<sessionId>:final`.
- `/stt/session/ended`
  `{"version":1,"sessionId":"<uuid>","reason":"completed","pluginId":"holder"}`
  with event ID `<sessionId>:ended`.

Ended reasons are exactly `completed`, `cancelled`, `no_speech`, `error`,
`link_lost`, and `revoked`. An ended event may add:

```json
{
  "error": {
    "kind": "NETWORK",
    "provider": "OpenAI",
    "detail": "Provider network request failed"
  }
}
```

`error.kind` is the corresponding `SttErrorKind` enum name. `provider` and
`detail` are optional, and detail is diagnostic-only and transcript-free.

There is one speech session globally. Plugin sessions and the hub-owned Speech
settings dictation test use the same `SpeechSessionManager`, so either makes a
start from the other return `BUSY`. Speech also acquires the existing raw audio
lease internally: an active `microphone` holder makes STT return `BUSY`, and a
capturing speech session makes raw audio acquisition return `BUSY`.

The internal lease is released at the voice endpoint rather than at the end of
the session, so the microphone stops the moment the speaker stops. A session in
`processing` no longer holds the lease: raw audio acquisition succeeds again
while the transcript is still in flight, and losing the glasses link at that
point ends neither the session nor the pending result. Starting another speech
session still returns `BUSY` until the current one ends.

Ownership is the verified plugin principal plus callback binder, never a
caller-supplied plugin ID. A stop from another principal is treated as stale.
Binder death, unregister, or grant revocation cancels the session. Grant
revocation attempts a final targeted `revoked` event while the binder is still
alive; dead/unregistered binders are only cleaned up. Link loss ends the
session with `link_lost`.

Transcript privacy is a routing invariant. Partial and final text is never
broadcast, forwarded to glasses, queued for a sleeping client, or written to
the hub log. The developer `PluginBusJournal` records only direction, path,
size, verdict, and bounded routing reason, never JSON payload contents.

STT is additive and the plugin API remains version 3; there is no AIDL change.
Older hubs do not know the strict `stt` descriptor value and therefore reject
such plugins rather than degrading transparently. Adding `stt` to an existing
plugin's requested capability set also returns its signer-bound grant to
Pending until the user re-approves it.

## TTS protocol v1

Text-to-speech is a separate capability whose renderer is the phone's own
speech engine: the hub synthesizes every utterance with the voice and speed
the wearer picked in Settings → Voice, and the sound reaches the wearer over
the Bluetooth audio route — the glasses' link, or earbuds if any are in. The
phone's own loudspeaker never plays speech; when no external ear is available
the hub waits a few seconds for the audio link to wake, then drops the
utterance with an `UNAVAILABLE` done event rather than talking into the room.
A plugin requests `tts` and may receive `/tts/started` and `/tts/done`; both
command paths require an approved `tts` grant.

The plugin sends JSON with no binary attachment:

- `/tts/speak` payload `{"utteranceId":"<opaque>","text":"Hello"}`.
  `utteranceId` is the sender's name for this utterance — a message key, a row
  id, anything it already uses — and comes back on every event about it, so an
  answer never has to be matched by timing. It must be nonblank and at most 64
  characters; the SDK invents one when the caller does not care. Newline runs
  in `text` become spaces, then the text is trimmed. The normalized text must
  be nonblank and at most 1024 characters — the same budget a notice body
  gets, so anything a plugin may put on the display it may also read out.
  Treat it as a ceiling rather than an invitation: a full one is about a
  minute of talking at someone who is doing something else.
- `/tts/stop` payload `{"utteranceId":"<opaque>"}`. Only the plugin that owns
  the current matching utterance can stop it; stale, wrong, or differently
  owned IDs have no effect.

The phone validates and normalizes each command, injects the verified
`ownerPluginId`, and dispatches it to its own engine under a private engine
ID, so a plugin utterance ID can never address another plugin's engine
request. A plugin never supplies or controls that owner.

`/tts/cancel` is an empty phone-hub-to-glasses-hub command and is neither a
plugin send path nor a plugin receive prefix. Current phones no longer send
it — speech no longer plays on the glasses' engine — but the glasses hub
keeps honoring it from older phones. The phone-local equivalent still holds:
an audio-lease grant silences current speech, because an open microphone
would hear the voice and feed it into dictation.

The phone produces owner-scoped events and delivers each one only to the live
callback binder for that approved plugin:

- `/tts/started` payload `{"utteranceId":"<opaque>"}`.
- `/tts/done` payload
  `{"utteranceId":"<opaque>","reason":"COMPLETED"}`. Reason is exactly
  `COMPLETED`, `STOPPED`, `PREEMPTED`, `CANCELLED`, or `UNAVAILABLE`.

Every accepted speak produces exactly one `/tts/done`, including a request
the phone engine cannot render or that dies during playback. A new accepted
speak while one is current reports `PREEMPTED` for the old utterance; the
engine holds a single slot. A successful owner stop reports `STOPPED`. Normal
completion reports `COMPLETED`. An utterance that never finds a safe audio
route reports `UNAVAILABLE`. An audio-lease grant reports `CANCELLED` for the
current utterance: the platform needed the microphone, so the utterance is
discarded rather than paused and releasing the lease does not resume it.

The glasses hub still carries its own renderer — a lazy binding to
`com.rokid.os.sprite.assistserver/com.rokid.os.sprite.tts.TtsService` with
action `com.rokid.os.sprite.tts.TTS_SERVICE`, package visibility for which is
a required part of the glasses manifest — but current phones never use it:
its voice only really speaks English and Chinese and spells everything else
out. It remains for older phone hubs that still forward speech. That hub does
not call `updateTtsParam`, change voice or speed, or write system properties;
those settings belong to the device assistant.

Speak and stop share a per-plugin budget of five commands in any one-second
window. Invalid shapes, binary payloads, overlong ids, or invalid normalized
text return `INVALID_TTS`; budget exhaustion returns `TTS_RATE_LIMITED`. A
plugin whose `tts` grant is missing or not yet approved is denied
`CAPABILITY_REQUIRED_TTS`, exactly as every other capability answers, so a
forgotten grant never reads as broken hardware. `CAPABILITY_NOT_AVAILABLE` no
longer occurs on current hubs — speaking does not require the glasses — but
phones older than 1.2.3 still answer it when no glasses renderer is
available, so clients keep handling it.

TTS is additive and the plugin API remains version 3; there is no AIDL change.
The glasses capability bit is `512`, and the capabilities payload still
carries `ttsVersion`, which current phones ignore: the contract always
promised speech could be produced somewhere else without a plugin noticing,
and since 1.2.3 it is — on the phone, unconditionally.

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
