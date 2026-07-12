# Plan 007: Camera platform in the glasses hub + Lens as an installable plugin

> **Executor instructions**: Follow every step and verification gate in order.
> This plan absorbs the `lens-glasses` companion APK into `glasses-hub` as a
> **generic camera platform** (camera capture + high-bandwidth link exposed as
> standardized, grantable bus endpoints), removes on-glasses OCR entirely
> (all OCR moves to the phone — live via H.264 streaming, freeze via the
> existing frame path), and extracts the phone-side Lens code into
> `plugins/lens`, installable from the Nexus Store. Phase A is a hardware
> spike that gates everything else — do not start Phase B before its numbers
> are accepted by the owner. Stop on any listed STOP condition. Reuse the
> Plan 002 grant boundary verbatim — do not invent a second identity, consent,
> or trust model. Update `plans/README.md` when complete.

## Status

- **Status**: TODO (Phase A spike in flight)
- **Priority**: P1
- **Effort**: XL
- **Risk**: HIGH (hardware-gated live-OCR latency/thermals; protected-path
  security rework)
- **Depends on**: `plans/003-external-plugin-sdk.md` (DONE),
  `plans/005-nexus-store-registry.md` (Part B DONE)
- **Shares surface with**: `plans/006-video-playback-on-glasses.md` (the
  hub-owned link becomes the transport both features use)
- **Category**: architecture, platform, distribution
- **Planned at**: main `e78a6ce`, 2026-07-12

## Why this matters

Lens is the last in-hub built-in, and `lens-glasses` is the only separate
glasses APK — an arrangement that exists for two historical reasons: the five
bundled ML Kit OCR models made it too heavy to merge (56 MB vs the hub's
18 MB), and general glasses-companion approval was deliberately deferred by
plans 002/003. Both reasons dissolve under this plan: with OCR moved to the
phone the glasses side is just camera + encoder + link (a few MB), and with
the hub as the only glasses app there is no companion trust model to design
at all. What remains becomes a platform asset: a camera/link capability any
approved plugin can consume — Lens first, Plan 006 video next.

## Product decisions (locked with the owner, 2026-07-12)

1. **`lens-glasses` is absorbed into `glasses-hub`.** No separate glasses APK
   remains; one glasses install/update path.
2. **On-glasses OCR is removed entirely** (all five ML Kit models). Live mode
   becomes: glasses stream H.264 over the Wi-Fi Direct link, the phone decodes
   and runs OCR + translation, structured overlays come back over the bus.
   The Live Studio `CameraH264Streamer` (hardware MediaCodec encode, CBR) is
   the proven reference for the streaming half. Accepted consequence: live
   and freeze both require the P2P link; there is no local fallback. If the
   Phase A spike fails its gate, the fallback decision (e.g. keep the Latin
   model on glasses) returns to the owner — it is not the executor's call.
3. **The contract is generic (`camera`), not lens-private.** New capability
   and `/camera/*` paths replace the `/lens/link/*` + frozen-OCR protected
   paths. Lens holds no special-cased identity in the hub once this lands.
4. **Cutover is direct.** No built-in/external coexistence release, no
   settings migration: the owner re-enters the DeepL/Gemini keys and target
   language in the plugin's settings once; ML Kit translation packs
   redownload. (Repo is private; there are no external users.)
5. **Security posture for the camera capability**: standard Plan 002 grant
   flow (explicit user approval per plugin, signer-bound grant) plus one
   structural guarantee — frames only flow while the hub's camera activity is
   foreground on the glasses, so camera access is always user-visible. No
   same-signature requirement: that is what makes the capability generic.
   Revisit this posture before the registry ever opens to third parties.

## Target architecture

**glasses-hub** gains a camera domain (isolated in its own process,
`android:process=":camera"`, so a camera/encoder crash cannot take down the
launcher):

- Camera activity: ported from `lens-glasses` `LensActivity`, minus all OCR
  (recognizers, cadence loop, fallback paths) and minus the five ML Kit
  dependencies. Hub APK must stay under ~25 MB.
- Link ownership: autonomous P2P group owner, credentials generation, token
  handshake, server socket — ported from `lens-glasses` `LensImageLink`.
- Live streaming: H.264 hardware encode following
  `RokidLiveStudioAndroid/glasses-helper/.../CameraH264Streamer.kt`
  (MediaCodec surface input, CBR, runtime bitrate/fps control).
- Freeze frames: existing JPEG `FROZEN_IMAGE` packets, protocol renamed
  `LensLinkProtocol` → `CameraLinkProtocol` (wire-compatible evolution or
  clean break — executor's choice, there is no cross-version fleet).
- Overlay rendering: structured overlay content from the plugin
  (text + bounding box + role), rendered by the hub. Never pre-formatted
  strings — same principle as HUD boards.

**Bus contract** (all gated on the new `camera` capability):

- `/camera/session/state` (glasses → phone): opened/closed/config, session id.
- `/camera/link/offer` (glasses → phone): SSID/passphrase/port/token/GO IP —
  the current protected offer, generalized.
- `/camera/freeze/result`-class paths (phone → glasses): OCR/processing
  results tied to a frozen frame (replaces `/lens/frozen/ocr/result`).
- `/camera/overlay` (phone → glasses): structured overlay for the live view.
- Translation request/reply stay ordinary JSON bus messages as today.

**Phone hub**:

- `PluginCapability.CAMERA` (wire value `camera`); `PathRules`,
  descriptor receive-prefixes, `PluginRoutePolicy` extended with the
  directions above.
- The UID-only protected gates in `BusHubService` (`routeLocal`,
  `registrationMatches`) are replaced by: hub UID **or** an approved, enabled
  principal holding the `camera` grant. Applied consistently to JSON and
  binary entry points.
- Companion-session lifecycle: when a camera session opens on the glasses,
  the hub binds the granted camera plugin with `BIND_IMPORTANT` in a
  dedicated controller (NOT the single foreground-surface
  `ExternalPluginController.active` slot) and delivers `onNexusOpen`; unbind
  and `onNexusClose` on session close, link loss, revocation, package
  removal, binder death, or timeout. This fixes the 60-second cold-wake reap
  that would otherwise kill an idle-socket Lens.
- Readiness: a feature bit (or versioned status reply) meaning "an approved,
  compatible camera consumer is installed", so the glasses camera UI can
  show a real empty state instead of offering credentials to nobody.
  `PROTECTED_LENS_LINK` advertising must not survive as-is.

**plugins/lens** (headless phone APK, per `docs/PLUGINS.md`):

- Moves as-is: translation engines/router/parsers, `PhoneFrozenOcr`,
  `PhoneLensImageLink` (P2P join by credentials + socket client),
  `LensSettingsActivity` (NexusUi shell, + permission onboarding for
  `NEARBY_WIFI_DEVICES`/location, + uninstall row).
- New: live consumer — H.264 decode + ML Kit OCR at an adaptive cadence +
  overlay emission. The OCR cadence logic can be lifted from the removed
  glasses analyzer.
- Descriptor: id `lens`, non-launchable, requests `camera` (+ HTTP as
  needed); engines started in `onNexusOpen`, torn down on every terminal
  path — dormant when closed, per `f44bd28`.

## Phase A — Live-OCR spike (hardware gate; blocks B–E)

Standalone throwaway project `spikes/live-ocr-link-spike/` (own Gradle build,
like `spikes/lens-ocr-spike`): a glasses app that owns the P2P group and
streams camera H.264 (Live Studio pattern), and a phone app that joins by
credentials, decodes, and runs an ML Kit OCR loop, logging measurements.

Measure on real hardware:

1. End-to-end latency, capture timestamp → OCR text available on phone
   (embed capture timestamps in the stream; clocks: same-device timestamps
   echoed back, not cross-device clock comparison).
2. Sustainable OCR cadence on the phone at 720p-class frames.
3. Glasses thermals + battery over a 10-minute continuous stream.

**Gate (owner decides on the numbers)**: median e2e latency ≤ 600 ms
(on-glasses OCR today: 330–548 ms), no thermal throttling over a session
matching the real usage profile (owner: live sessions last 2–5 minutes).
**STOP** if latency > 800 ms or thermals degrade: report, and the owner
re-decides between keeping a Latin-only on-glasses model or accepting the
regression.

**RESULT (measured 2026-07-12, RG glasses + Galaxy S23+): GATE PASSED.**
3 min 43 s continuous, n=2174: median **423 ms**, p95 566 ms, p99 635 ms,
max 1179 ms; OCR cadence **9.7/s** (vs 3.7/s on-glasses); battery temp flat
at 33 °C, thermal status NONE throughout (glasses were USB-powered during
the run). Mild latency drift (+68 ms avg between run halves) — acceptable
for short sessions; re-check during Phase D with buffer pooling on the
phone decode path (1.4 MB/frame NV21 allocations are the suspect).

Hardware findings the production phases must absorb:

- The camera rejects 960x1280; use 720x1280 (in the camera's stream map).
- Glasses Wi-Fi is off by default; the camera domain must ensure Wi-Fi
  before group creation (the existing `/glasses/wifi/request` flow).
- Samsung C2 decoders cannot feed an `ImageReader(YUV_420_888)` from a
  decoder surface (JNI abort on plane access). The plugin must use the
  no-surface ByteBuffer path: `COLOR_FormatYUV420Flexible` +
  `getOutputImage` → stride-aware NV21 → `InputImage.fromByteArray`.
- Camera reopen right after a force-stop fails with error 3 on the
  glasses; the camera activity needs open-retry with backoff.
- The hub's link server must survive client death and re-accept (the
  spike's accept loop died after a client crash).

## Phase B — Generic camera contract (shared + bus-client + phone-hub)

1. Add `PluginCapability.CAMERA`, the `/camera/*` path set, `PathRules` +
   descriptor + `PluginRoutePolicy` extensions, and the readiness bit.
2. Replace the UID-only protected gates with the granted-principal check.
3. Companion-session controller with `BIND_IMPORTANT` and full teardown
   matrix.
4. Tests, including adversarial: wrong plugin id, unapproved same-signed APK,
   revoked grant, protected send from an ungranted registration, reordered
   open/close, duplicate sessions.

## Phase C — glasses-hub camera domain

1. Port camera activity, link GO, and freeze path from `lens-glasses`;
   add the H.264 streamer validated in Phase A; `:camera` process isolation.
2. Structured overlay renderer; launcher tile; empty state when readiness
   bit is absent.
3. Delete the `lens-glasses` module and its Gradle mapping. Hub APK size
   check < ~25 MB. Remove `/glasses/wifi/request` special-casing if it was
   lens-only (it stays glasses-local either way).

## Phase D — plugins/lens

1. Scaffold per `docs/PLUGINS.md` (headless manifest, `:plugin-lens` mapping,
   signing script, README, CHANGELOG; sample's manifest is NOT a safe
   template — it still has a launcher activity; use transit's).
2. Move the phone lens implementation; add the live consumer; wire lifecycle
   to companion open/close; settings + permission onboarding.
3. Unit tests moved/extended; plugin builds and registers dormant.

## Phase E — Cutover + distribution

1. Remove Lens from `PhoneBuiltInPlugins` (leaving the built-in machinery
   empty — keep or delete it, executor documents the choice), remove the
   hub's `LensSettingsActivity`, Lens ML Kit/Wi-Fi permissions and
   dependencies from `phone-hub`.
2. Store/catalog behavior verified for: plugin absent (readiness off,
   store shows AVAILABLE), pending, approved, revoked.
3. Release integration: `lens` case in `.github/workflows/plugin-release.yml`,
   registry `plugins-nexus/lens.json` (`--kind nexus-plugin`),
   `minHostVersionCode` = first host build carrying the camera contract,
   tag `lens-v1.0.0`.
4. Docs: `BUSSPEC.md` camera contract section, `plugins/README.md` catalogue
   row + remove the stale "needs a glasses-side companion API" note,
   `TESTPLAN.md` additions.

## On-device validation matrix (Phase E exit)

- Permission absent/present on the plugin at cold start.
- Plugin absent / pending / approved / revoked — glasses camera UI state
  matches readiness in all four.
- Force-stopped plugin, then camera session open on glasses → cold bind,
  `BIND_IMPORTANT`, live within one session.
- Live translation (latency spot-check vs Phase A numbers), freeze
  translation, multi-script freeze (zh/ko/hi), P2P drop mid-session +
  reconnect, session idle > 60 s (no reap), glasses activity pause/resume,
  phone hub restart mid-session.
- Store: install → approve → open → update → uninstall; CXR/SPP transport
  continuity throughout.

## Out of scope

- Plugin-initiated camera sessions (no glasses-side UI): future extension;
  the session model must not preclude it.
- Third-party camera threat model beyond the grant + foreground-visible
  posture: revisit before third-party registry access.
- Plan 006 implementation (it consumes the same hub-owned link; only keep
  the link/protocol design compatible with a second consumer).
- Any migration of `lens_translation` preferences (decision 4: reset).
