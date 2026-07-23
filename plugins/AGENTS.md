# Building a Rokid Nexus plugin

This is the complete, self-contained contract for writing a Nexus plugin. It is
written to be read by a developer or handed verbatim to a coding agent. Where this
document states a limit or a rule, the hub or SDK enforces it in code — violating it
does not degrade gracefully, it gets your traffic rejected or your process crashed.

Companion documents: [docs/PLUGIN_SDK.md](../docs/PLUGIN_SDK.md) (wire/AIDL
specification), [docs/PLUGINS.md](../docs/PLUGINS.md) (visual design & settings-screen
kit). This file is the contract; those are the deep dives.

## 1. What a plugin is

A Nexus plugin is a **headless phone APK**. It has no launcher icon and no visible
identity outside Rokid Nexus. The Nexus phone hub discovers it, the user approves its
capabilities once, and from then on it is launched from the glasses launcher, renders
on the glasses HUD through the hub, and is configured from a settings screen the hub
opens by explicit component.

Plugins are **dormant unless open**: the hub initiates everything. Your process runs
only between `PLUGIN_OPEN` and `PLUGIN_CLOSE`. Do not register yourself at boot, do
not poll in the background, do not post notifications. The SDK holds a
foreground-service session while you are open and drops it on close; the
user-facing notification that names the live plugin belongs to the hub. The one
sanctioned exception: a capability that Android forces into its own foreground
service *while your surface is open* (Feeds' overlay WebView host is the
precedent) may run that service with its own minimal notification — it must
start with your surface, die with it, and never outlive a close.

## 2. Project setup

```kotlin
// settings.gradle.kts of your own project
// SDK artifacts: bus-client (+ its transitive `shared`) — see docs/PLUGIN_SDK.md
// for the current published coordinate and version.
dependencies { implementation("com.github.Anezium.Rokid-Nexus:bus-client:<version>") }
```

- `applicationId` / namespace: `com.<you>.<something>.plugin.<id>` (first-party
  plugins use `com.anezium.rokidbus.plugin.<id>`).
- `minSdk 31`, `targetSdk 36`, `versionName` semver (`1.0.0`).
- Sign every build with **one** certificate. Multi-signer APKs are rejected outright
  (`SIGNER_SET_UNSUPPORTED`). Your signing certificate is part of your identity: the
  user's approval is keyed to `package + pluginId + signerSha256`, so switching from
  a debug key to a release key means uninstall + reinstall + re-approval.

## 3. Manifest contract (the headless rules)

Copy `plugins/sample` as the canonical template. The hard rules:

1. **Exactly one exported service** with an intent filter for action
   `com.anezium.rokidbus.action.PLUGIN`, extending `NexusPluginService`, carrying the
   descriptor `<meta-data>` (plugin id, API version, capabilities, receive prefixes,
   optional settings activity).
2. **No activity with a MAIN/LAUNCHER intent filter.** This is what keeps the plugin
   invisible. The registry CI rejects APKs that violate it.
3. The settings activity (if any) is `exported="true"` **without** any intent filter —
   the hub opens it by explicit component; nothing else can find it.
4. `<queries>` for `com.anezium.rokidbus.action.HUB` (inherited from the bus-client
   AAR manifest, but declare it in your own manifest too for clarity).
5. Foreground-service permissions: `FOREGROUND_SERVICE` +
   `FOREGROUND_SERVICE_SPECIAL_USE`. Do **not** declare `POST_NOTIFICATIONS` — the
   session FGS runs fine with its notification suppressed on Android 13+.
6. `REQUEST_DELETE_PACKAGES` — required for the in-app Uninstall row
   (`NexusUi.uninstallCard`) to open the system uninstall dialog. Without it the
   `ACTION_DELETE` intent is silently rejected.

## 4. Descriptor rules (validated at discovery)

| Rule | Enforced value |
|---|---|
| Plugin id | 3–64 chars, `[a-z][a-z0-9._-]{2,63}` (lowercase start), unique on the device |
| Display name | ≤ 80 chars |
| API version | exactly **3** |
| Capabilities | subset of `surfaces`, `http_proxy`, `microphone`, `camera` (microphone is user-approvable; no Android `RECORD_AUDIO` needed — PCM arrives over the hub) |
| Receive prefixes | non-empty, normalized, within your authorized namespace `/plugin/<id>/…` |
| Signer | exactly one current signing certificate |
| UID | not shared with another discovered plugin |

Any violation makes the plugin an *invalid candidate*: it appears in Plugin access
with the concrete reason visible when Developer details is enabled.

## 5. Lifecycle

```
install → discovery → user approval (Plugin access) → glasses launcher open
  → hub binds service → registration (5 s timeout)
  → PLUGIN_OPEN (ack within 4 s or the hub cold-rebinds once, then gives up)
  → /surface/show → input events → /surface/hide (self-close) or PLUGIN_CLOSE
  → unbind, FGS dropped, back to dormant
```

Facts you must build around:

- **Installation never grants access.** Registration before approval returns
  `PENDING_USER_APPROVAL`. Changing your requested capability set resets the grant
  to Pending — the user must re-approve.
- **Open is re-entrant.** A fresh `PLUGIN_OPEN` re-invokes `onNexusOpen` even if the
  SDK thought you were open: reset your state and re-show. The SDK dedupes the most
  recent 128 lifecycle ids, and callbacks are serialized on the main thread.
- **Hiding your last visible surface is a close.** The hub treats it as self-close,
  delivers `PLUGIN_CLOSE`, and unbinds you. That is the correct way to exit on BACK
  from your root view.
- **One plugin owns the HUD at a time.** While another plugin is foreground, your
  `show`/`update` returns `SURFACE_BUSY` — handle it by giving up quietly, never by
  retry-looping. A `show` on an *idle* HUD adopts you as foreground with a real
  `PLUGIN_OPEN`.
- The hub rewrites your local surface id to `pluginId:localSurfaceId` and assigns
  sequence numbers; never hardcode the namespaced form.
- Revocation, binder death, package removal, or link loss close you and hide your
  surfaces. Requests time out after 15 s by default.

## 6. Bus endpoints

Paths a plugin can **send to** (gated by capability):

| Path | Capability | Purpose |
|---|---|---|
| `/surface/show`, `/surface/update`, `/surface/hide` | `surfaces` | HUD surface lifecycle (typed models: card, timed lines, media, image) |
| `/http/request` → `/http/request/reply` | `http_proxy` | Phone-side HTTP proxy (strict policy, §9) |
| `/audio/lease/acquire`, `/audio/lease/release` (+ `/reply` suffixes), `/audio/frames`, `/audio/lease/revoked` | `microphone` | Glasses mic lease + 16 kHz mono PCM frames. Use the SDK's `nexusAudioSession(callbacks)` rather than these paths directly. |
| `/camera/freeze/result`, `/camera/overlay`, `/camera/link/offer` | `camera` | Camera platform sends (signer/grant-bound). `/camera/link/offer` is bidirectional so an approved camera plugin can advertise a reverse transport role. `/camera/session/state` and `/camera/freeze/image/chunk` remain **receive-only** (declare them in RECEIVE_PREFIXES); sending them is rejected |
| `/plugin/<yourId>/…` | — | Your private namespace (must match your declared receive prefixes) |

Paths a plugin **receives** (reserved, hub-generated — you never send these):
`/system/plugin/registration`, `/system/plugin/open`, `/system/plugin/close`,
`/system/plugin/input`, `/glasses/device-info`, plus deliveries into your
`/plugin/<id>/…` namespace.
Reserved sender roots you can never use: `/launcher`, `/surface/input`, `/system`,
`/security`, `/error`. Rejections and undeliverable traffic come back on `/error`.

Every approved, live registration receives glasses hardware signals without an
additional capability grant. `onNexusLinkState` includes
`LinkStateBits.GLASSES_WORN`; `onNexusGlassesAiButton(active)` reports the AI
button start/stop edges; and `/glasses/device-info` reaches `onNexusMessage` with
the versioned `GlassInfo` fields.

Transport is the hub's business: local delivery first, CXR for JSON ≤ 3 KiB,
SPP otherwise. Binary is never queued for a sleeping client — an undeliverable
binary frame is dropped, not retried.

## 7. Enforced limits (the ones that reject or crash)

| Area | Limit |
|---|---|
| Surface JSON payload | 64 KiB (SDK preflight — fails locally) |
| Local surface id | `[A-Za-z0-9][A-Za-z0-9._-]{0,63}` |
| Card | ≤ 64 rows; title ≤ 120; line/subtitle/footer ≤ 240; **contentKey ≤ 128** (hash long keys!); badge ≤ 24; ≤ 8 trail entries of ≤ 24 |
| Timed lines | ≤ 2 000 entries, non-negative times |
| Image surface | JPEG/PNG ≤ 64 KiB compressed, edges ≤ 512 px, ≤ 512² total px, ≥ 150 ms between updates |
| Mono artwork | 16–192 px per edge (the glasses renderer floor is 16 even though the SDK accepts 1) |
| Media artwork (binary) | image rules with 256 px edge cap |
| Local binder binary | 512 KiB per frame |
| SPP frame | 2 MiB body; binary metadata header ≤ 64 KiB |
| Offline JSON queue | 32 messages / 512 KiB / 30 s TTL — binary never queued |
| Request timeout | 15 s default |

Typed-model violations throw `IllegalArgumentException` **in your process** at
construction time (this has crashed real plugins — a `contentKey` built by
concatenating card content blew the 128-char cap on real data; hash instead).

## 8. Rendering rules

### Plugin identity icon

Both icon declarations are optional. Prefer a Nexus built-in by declaring
`com.anezium.rokidbus.plugin.ICON` with one of these keys: `music`, `disc`,
`bus`, `lens`, `mic`, `send`, `feed`, `weather`, `chat`, `calendar`, `clock`,
`star`, `heart`, `game`, `globe`, `bell`, `terminal`, `grid`, `map`, `bolt`, or
`bookmark`.

If the built-in set does not fit your identity, declare your own drawable
resource instead:

```xml
<meta-data
    android:name="com.anezium.rokidbus.plugin.ICON_DRAWABLE"
    android:resource="@drawable/my_glyph" />
```

The custom drawable must be a monochrome silhouette: use a `VectorDrawable`
with alpha and a single-color shape on transparency. Nexus loads it from your
package and tints it green. A full-color logo loses its colors and renders as a
green blob. If both fields are present and `ICON` is a recognized built-in key,
the built-in wins; otherwise Nexus tries `ICON_DRAWABLE`, then falls back to the
grid glyph.

The HUD renders **structured rows**, not free text: build `NexusCard` /
`NexusTimedLines` / `NexusMedia` / image surfaces and let the glasses lay them out.
Never pre-format monospace strings. Input arrives as DPAD-style events
(`/system/plugin/input`): forward/back swipes, tap (ENTER), BACK. BACK at your root
= hide your surface (self-close, §5).

## 9. HTTP proxy policy

`/http/request` is deliberately narrow: HTTPS to allowlisted hosts only (currently
`api.transitous.org`), GET/POST only, five permitted request headers, request body
≤ 64 KiB, response budget 4 MiB. It exists for glasses-side fetches; phone-side
plugins with `INTERNET` permission should just use their own network stack. Adding a
host to the allowlist is a hub change — open an issue.

## 10. Debugging your plugin

- **Developer mode** — Nexus app → Settings → Advanced → *Developer mode*. While on:
  sideloaded plugins get a DEV badge, installing a new ungranted plugin raises a
  "New plugin detected" notification that jumps straight to the approval screen, and
  the **Bus inspector** unlocks below the toggle.
- **Bus inspector** (Settings → Advanced → Bus inspector) — a live journal of the
  last 500 bus events, filterable per plugin: registrations (including every
  rejection code), opens/closes, surface show/update/hide, input, transport choice,
  binary drops — rejections show in amber with their reason (`SURFACE_BUSY`,
  `PENDING_USER_APPROVAL`, capability denied, payload too large, …). The journal
  records only while developer mode is on.
- **What the inspector cannot see**: SDK-side preflight failures never reach the hub
  — typed-model `require()`s crash your process at construction time, and the 64 KiB
  surface ceiling / image preflight / offline-queue evictions fail locally. Watch
  your own logcat for those.
- Plugin access → Developer details shows why an invalid candidate was rejected and
  the signer digest the hub sees for your APK.

## 11. Test loop

```
./gradlew assembleDebug
adb install -r your-plugin.apk        # grant survives -r (same signer + capabilities)
# phone: Nexus → Plugins → your plugin → approve capabilities (first time only)
# glasses: launcher → your plugin     # binding happens on open, not at boot
```

The phone hub watches package added/changed/removed broadcasts and reconciles
grants, readiness, and the glasses launcher list on its own — no hub restart
needed after (un)installing a plugin. `adb uninstall` revokes the grant (by
design — reinstall means re-approval).

## 12. Publishing

Distribution is F-Droid-like — you host, the registry indexes:

1. Publish the release APK on **your own** GitHub repository's releases.
2. Add `plugins-nexus/<id>.json` to
   [RokidBrew-Registry](https://github.com/Anezium/RokidBrew-Registry) via PR — copy
   `EXAMPLE.template.json`. Required alongside the usual metadata:
   `artifact.sha256`, **`artifact.signerSha256`** (lowercase hex SHA-256 of your
   signing certificate — `apksigner verify --print-certs your.apk`), HTTPS URLs,
   `id == nexus.pluginId`, `apiVersion: 3`, capabilities from the allowlist.
3. CI downloads your APK and verifies everything: hashes, signer, package/version
   metadata, exactly one exported plugin service, no MAIN/LAUNCHER activity. A human
   merges after CI is green.
4. Updates: publish the new APK on your releases, PR the manifest bump. Keep the
   same signing certificate forever — Android and the grant system both pin it.
