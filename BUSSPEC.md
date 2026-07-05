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
  (`listenUsingInsecureRfcommWithServiceRecord`), phone = client, target by MAC
  `AC:86:D1:55:1E:ED` first (bonded name is `Glasses_3723`, NOT "Rokid").
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

## Surface protocol v1 (Round B)

Plugins do not install glasses APKs. The phone hub hosts plugin logic in-process and
pushes declarative surfaces over the existing bus. The glasses hub renders those
surfaces locally with the shared Rokid Nexus phosphor visual language.

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
Timed-line anchor-only updates may also include a `contentKey`; the glasses hub merges
such updates only into an active surface with the same key, so an anchor that overtakes
the full lyrics payload cannot make the later full payload stale.

Surface kinds v1:

- `card`: `title`, `lines` as an array of strings or `{text}`, and optional `footer`.
- `timed-lines`: `title`, optional `subtitle`/`footer`, full `lines` as
  `{ "timeMs": 1234, "text": "..." }`, and an `anchor`.

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
