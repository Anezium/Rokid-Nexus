# Plan 006: Real video playback on the glasses

> **Executor instructions**: This plan delivers *actual* video playback on the
> glasses — moving picture, and sound where the hardware allows it — not a still
> poster. It is deliberately phased behind a hardware spike (Phase 0) that GATES
> the audio work, because the repository proves no phone→glasses audio path
> exists and does not even prove the glasses can play application audio at all.
> Follow the phases and verification gates in order. Stop on any listed STOP
> condition. Do not ship a "preview" or frame-poster and call it video. Update
> `plans/README.md` when execution status changes.

## Status

- **Status**: TODO
- **Priority**: P2 (owner asked to plan it for later, after the current Feeds work ships)
- **Effort**: XL
- **Risk**: HIGH
- **Depends on**: the Feeds image surface + gallery (`feat/feeds-images`), the Lens Wi‑Fi Direct link (`LensImageLink` / `PhoneLensImageLink`)
- **Category**: media, transport, glasses-runtime
- **Planned at**: main `5e38ca6`, 2026-07-12

## Why this matters

Feeds now shows a photo, and for GIFs/videos it shows the **poster** — one still
frame, no motion, no sound. The owner is explicit: he wants **real video**, not a
preview ("ce serait une vraie vidéo, pas un simple preview"), and is willing to
change the architecture and deploy a new API to get it. Half of social media is
motion; a still poster is not the feature.

This is a large, multi-day change with two genuinely hard unknowns, so it is
planned separately from the incremental Feeds work rather than bolted on.

## The two hard constraints (measured 2026-07-12, read-only audit of the tree)

1. **Sound is the real blocker — and may be physically impossible.**
   - There is **no** `AudioTrack` / `MediaPlayer` / `ExoPlayer` / `SoundPool` /
     Bluetooth-SCO / A2DP-sink code anywhere in `glasses-hub` or `lens-glasses`.
   - The only Nexus audio path runs the **wrong way**: glasses microphone →
     phone, 16 kHz mono PCM16 (`phone-hub/.../BusHubService.kt` `IAudioStreamCbk`,
     format at `BusHubService.kt:76-79`). Nothing streams phone audio → glasses
     speaker.
   - The repository does **not** establish whether the glasses hardware/OS even
     exposes an application-usable speaker. So "son" is not just unimplemented —
     it is **unproven at the hardware level** and must be settled by a spike
     before any audio engineering is scheduled.

2. **Motion is feasible, over Wi‑Fi Direct, not the Bluetooth bus.**
   - SPP/RFCOMM is far too slow: ~1.5 Mbit/s real, ~1 JPEG frame/s, 2 MiB frame
     cap (`shared/.../FrameProtocol.kt:19-22`), 512 KiB local Binder cap
     (`BusHubService.kt:71`). Unusable for video.
   - The **Lens Wi‑Fi Direct link already exists** and is the right transport: the
     glasses create and own the P2P group and run a TCP server on port **38401**
     (`lens-glasses/.../LensImageLink.kt:147-176,204-259,418-421`); the phone
     joins as P2P client (`phone-hub/.../lens/PhoneLensImageLink.kt`). Earlier
     field runs measured **~73.6 Mbit/s** on this link — ample for compressed
     video (H.264 720p ≈ 2–5 Mbit/s).
   - The socket is **already bidirectional**: the phone today sends `HELLO` plus a
     512 KiB probe to the glasses (`PhoneLensImageLink.kt:468-503`). But the
     implemented *media* direction is glasses → phone (`FROZEN_IMAGE` JPEGs), the
     glasses only handle an incoming `PROBE`, and the protocol has **no video or
     audio packet type** (`shared/.../LensLinkProtocol.kt:9-14`). Lens packets
     already allow **8 MiB payloads / 64 KiB metadata** (`LensLinkProtocol.kt:31-42`).
   - The link is **activity-scoped to `LensActivity`**. Reusing it for Feeds means
     lifting it into a shared glasses media service and arbitrating with Lens —
     only one P2P group / high-bandwidth session can exist at a time.

3. **Green-mono is accepted.** The optics project green luminance; decoded video
   frames will render green-mono exactly like the image surface does today. The
   owner has confirmed this is fine.

## Architecture (target)

**Transport — generalize the Wi‑Fi Direct link.** Extract the P2P link out of
`LensActivity` into a shared, glasses-side "media link" service that either
feature (Lens, Feeds video) can lease, with explicit arbitration (a lease/owner
epoch; deny or preempt when the other holds it). Add phone→glasses media packet
types to `LensLinkProtocol`. Keep the existing Lens glasses→phone flow working.

**Video — compressed stream + hardware decode on the glasses.** The phone does
NOT re-encode frames; it *demuxes* the source video and forwards the compressed
**H.264** track. The glasses decode it with **`MediaCodec`** onto a `Surface`
rendered in the HUD overlay (a new video surface/overlay, sibling to the image
surface). This is true video at a few Mbit/s, not a frame-blitting hack. (A
JPEG-frame fallback over the same link is the contingency if MediaCodec-to-overlay
proves unviable — see STOP conditions.)

**Audio — gated on Phase 0.** If the spike proves the glasses can play app audio:
the phone forwards the compressed **AAC** track, the glasses decode it with
`MediaCodec` → `AudioTrack`, and A/V sync is driven by presentation timestamps.
If the spike proves it cannot: sound is out of scope, documented, and the feature
ships motion-only.

**Feeds integration.** In the gallery, a VIDEO/GIF item gains a "play" action
(tap) that leases the media link and starts playback; BACK stops playback and
releases the lease, returning to the gallery/poster. The poster stays the
fallback when the link is busy (Lens active) or playback fails.

## Phases and verification gates

### Phase 0 — Hardware spike (GATES the rest; do this first, alone)
- **Audio-out probe.** Minimal glasses build: run an `AudioTrack` playing a known
  PCM tone (and separately a `MediaPlayer` on a bundled clip). Determine
  definitively whether the glasses expose an app-usable speaker, at what
  sample rates, and the latency. Record the result in this plan.
- **Video-decode probe.** Confirm `MediaCodec` can hardware-decode H.264 to a
  `Surface` shown in the accessibility-overlay window context used by the HUD
  (a `SurfaceView`/`TextureView` inside the overlay). Measure decode fps for a
  720p clip.
- **Link-throughput probe.** Run the existing `PhoneLensImageLink` 512 KiB probe
  and capture the real Mbit/s on this exact hardware (the repo has no captured
  figure yet).
- **GATE**: if audio-out is impossible → the feature is **video-only**; strike
  Phase 3. If MediaCodec-to-overlay is unviable → switch the video plan to the
  JPEG-frame fallback before Phase 2. If throughput < ~8 Mbit/s sustained →
  re-scope resolution/bitrate.

### Phase 1 — Generalize the Wi‑Fi Direct media link
- Lift the P2P link into a shared glasses media-link service; add a lease/owner
  epoch and Lens↔Feeds arbitration. Add phone→glasses `VIDEO_INIT` / `VIDEO_DATA`
  (and later `AUDIO_*`) packet types to `LensLinkProtocol` with the existing
  8 MiB payload envelope. Prove Lens still works unchanged; prove Feeds can lease
  the link and round-trip a test payload phone→glasses.

### Phase 2 — Video-only playback (no sound)
- Phone: demux the source (X `video_info` best variant / Bluesky playlist),
  forward the H.264 track over the link with timestamps and backpressure.
- Glasses: `MediaCodec` decode → overlay `Surface`; new video HUD surface with
  play/pause and BACK wired to the touchpad; green-mono render.
- Feeds: gallery "play" on VIDEO/GIF leases the link, starts playback; BACK stops
  and releases; poster remains the fallback.
- **Gate**: motion plays smoothly on real hardware for X and Bluesky video; Lens
  still works; releasing the lease restores normal Feeds nav.

### Phase 3 — Audio + A/V sync (ONLY if Phase 0 proved audio-out)
- Phone forwards the AAC track; glasses decode → `AudioTrack`; sync on PTS.
- Add mute/volume via the touchpad; handle the public-context ergonomics
  (default muted? owner decides).
- **Gate**: lip-sync within tolerance on real hardware; graceful when audio
  underruns.

## MUST NOT

- MUST NOT regress the Lens experience or its glasses→phone `FROZEN_IMAGE` flow;
  the shared link must arbitrate, not evict Lens silently.
- MUST NOT run video over the Bluetooth/SPP bus or the CxR control path.
- MUST NOT ship a still poster relabeled as video; that is the status quo this
  plan replaces.
- MUST NOT add audio engineering before Phase 0 proves the glasses can play audio.
- MUST NOT re-encode every frame on the phone as the primary path (bandwidth and
  battery); forward the compressed track and decode on the glasses.

## Open owner decisions

- **Default audio state** in public (muted-by-default with tap-to-unmute?), if
  Phase 0 unlocks sound.
- **GIF handling**: treat animated GIF as short looping video over the same path,
  or keep GIFs as the existing poster? (Recommend: same video path, looped.)
- **Autoplay vs tap-to-play** in the gallery (Recommend: tap-to-play; autoplay
  drains battery and holds the P2P link).

## Risks

- Audio-out may be physically unavailable on the glasses → sound impossible
  (Phase 0 settles this; the plan degrades cleanly to video-only).
- Wi‑Fi Direct contention with Lens and with the normal Bluetooth bus (the group
  owner is the glasses; joining/leaving must not drop the control link).
- `MediaCodec`-to-accessibility-overlay Surface may have compositor limitations
  → JPEG-frame fallback.
- Battery/thermals during sustained decode on the glasses (the Lens M0 audit
  showed OCR thermals were fine, but continuous video decode is heavier).
