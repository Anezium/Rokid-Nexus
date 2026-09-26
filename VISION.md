# Rokid Nexus — Product Vision

Status: 2026-08-10 — sections 1–4 are the founding vision (consolidated
2026-07-07 from the QUESTIONS.md working session) and still govern every design
decision. Sections 5–6 track where the platform stands and what remains. Wire
and protocol details live in BUSSPEC.md.

## 1. What Nexus is

**Nexus is the Even Realities Hub of the Rokid ecosystem.**

One permanent anchor lives on the glasses (the glasses hub with its overlay launcher).
The user never installs anything on the glasses again. All value ships as ordinary
**phone APKs** that plug into the bus: install a phone app, its plugin appears on the
glasses. The original pain this kills: launching apps one by one on the glasses.

Seamlessness comes from three properties, all validated on hardware:

- **Wake-on-message** — a plugin does not need to be running to receive traffic; the
  hub binds it awake.
- **A single hub-owned CXR-L session** — no more apps fighting over the link.
- **Declarative surfaces** — plugins push structured content or inert compiled
  Ink pages; the glasses hub renders them locally. Zero glasses-side plugin deployment.

Because plugins are plain phone APKs against a published `:bus-client` library, Nexus
is not an app — it is a **platform** third-party developers can target, with RokidBrew
closing the distribution loop.

## 2. The layer model

Everything the user sees on the HUD belongs to one of four layers:

| Layer | What it is | Who controls it |
|---|---|---|
| **Ambient** | Silent surface updates (lyrics advancing, glanceables refreshing) | Plugin, continuously |
| **Toast** | Brief, non-blocking notice; latest wins, hub rate-limited | Plugin, throttled by hub |
| **Surface** | Full interactive surface (card, reader, timed-lines, media, image, compiled Ink) | Plugin via launcher or user action |
| **Native apps** | Real glasses APKs (Scouter, NewPipe…) — outside Nexus, but launchable from the menu | User |

Interruption rules:

- Plugins declare a class per message: `ambient`, `toast`, or `actionable`. The
  vocabulary is in the protocol **now** so third-party plugins code against the final
  shape; v1 renders `actionable` as `toast` until display arbitration ships.
- The hub enforces per-plugin rate limits, and the phone hub offers a per-plugin user
  override (mute, demote).
- Two toasts colliding: the newest replaces the oldest. No queue in v1.
- Back on any Nexus surface hides it and hands the display back to whatever is beneath
  — it never leaks to the native app below.

## 3. Interaction model

- **Triple-tap** on the touchpad opens the Nexus menu from anywhere. It is the only
  gesture Nexus claims; single/double taps and swipes pass through to the OS untouched.
- The glasses menu is **the single plugin entry point**: plugins first
  (user-ordered, favorites pinned, plain text rows — icons can come later, text
  is king on a monochrome HUD). The phone already lists and launches installed
  native glasses apps; a glasses-side Apps section remains phase 2.
- The Rokid launcher and its widgets stay in place; Nexus lives above them, not instead
  of them.
- When the phone is unreachable, Nexus stays silent until asked: triple-tap shows
  "Phone disconnected — reconnecting…" in place of the plugin list. The display is
  never spent on connection state the user didn't ask for.

## 4. Trust and privacy model

Public release makes this non-negotiable:

- **Open platform, user-gated.** Any APK may request bus access; the user approves it
  in the phone hub. The "custom signature permission" idea from BUSSPEC is dropped —
  it would kill third-party plugins. The model is Android's notification-listener
  pattern: request, then explicit user grant.
- **Granular consent** approved separately per plugin. The current vocabulary is
  `surfaces`, `ink_surface`, `microphone`, `stt`, `tts`, `http_proxy`, `camera`,
  `mediasync`, `assistant`, and `wireless_debugging`. A richer layout does not
  ride the ordinary surface grant: compiled Ink pages require their own visible
  `ink_surface` approval. Every grant is signer-bound and revocable.
- Protocol hygiene: an unknown surface `kind` (old glasses hub, newer plugin) is
  ignored and answered with a "update required" toast — graceful degradation is a spec
  rule, not a courtesy.

## 5. Where the platform stands (2026-08-10)

Every gate through the current shipped release has been cleared and validated on
real hardware. Ink and the trusted phone controls described below are implemented
for the next release; their remaining device gates are explicit in TESTPLAN.md.

- **The public beta gate is passed.** A stranger sets up Nexus with nothing but
  their phone: seven onboarding steps on the phone, glasses app installed over
  the Rokid CXR link from GitHub releases, then a two-card no-PC self-arm on the
  glasses that enables accessibility and bootstraps its own privileged shell
  (Wireless Debugging self-pairing, detached watchdog, hardened network
  posture). No ADB, no PC, at any point.
- **The distribution loop is closed.** The SDK publishes on JitPack from
  `sdk-v*` tags; plugins release as namespaced GitHub tags, are ingested by the
  public [RokidBrew-Registry](https://github.com/Anezium/RokidBrew-Registry),
  and install from the in-app Store with SHA-256 and signer pinning. Installed
  plugins surface update badges; the apps themselves self-update (phone from
  GitHub releases, glasses over CXR).
- **Twelve plugins ship**: Assistant, Relay, Navigation, Lens, Feeds, Transit,
  Lyrics, Media Deck, Photos Sync, Wireless ADB, Tasker, and the copyable
  Sample. All are external headless APKs; the hubs contain no built-ins.
- **Surfaces grew past text.** The image surface (v1) puts real photos on the
  HUD over the SPP binary path — Feeds renders tweet and Bluesky photos
  full-screen. The optics are green-mono; photos land as green luminance, and
  that is fine in practice.
- **Ink makes authored rich pages declarative too.** A strict subset of Rokid's
  published `.ink` format is compiled on the phone into bounded revisioned
  documents and rendered with native Views on the glasses. It brings data
  binding, lists, charts, progress, inline Lottie, declarative canvas, and tap
  actions without a WebView, JavaScript, URL loading, or page-side network.
  Assistant is the first consumer and the same typed session is public in the
  plugin SDK under a separate `ink_surface` grant.
- **The phone can drive software already on the glasses.** It can list and open
  launchable native APKs, move accessibility focus with previous/next/select/back,
  and provide an ephemeral keyboard to the focused glasses editor. Those
  versioned `/core/*` paths are hub-only. Password state is signalled without
  mirroring field contents, and native APK installation is deliberately absent.
- **Lens shipped as the flagship — with a better architecture than §8 of the
  original vision imagined.** Instead of ~1 fps JPEG over SPP, the platform
  grew a generic `camera` capability: the glasses stream live H.264 over a
  Wi-Fi Direct link normally, the consumer plugin decodes and runs ML Kit
  OCR + translation on the phone (offline, zero recurring cost), and structured
  overlays come back over the bus. Freeze mode captures a full-FOV still
  through the same link. The "two routes, decided later" question resolved into
  a third: no glasses-side plugin code at all — the glasses half lives in the
  hub as a platform capability, and Lens is an ordinary phone APK that the
  launcher exposes under the consumer's own name.
- **Camera works even with the phone's Wi-Fi off.** A phone app can't enable its
  own Wi-Fi, so the glasses-owned Wi-Fi Direct link above only works if the
  phone's Wi-Fi is already on. When it's off, the phone hosts a
  `LocalOnlyHotspot` itself and the glasses join it instead — the transport
  roles invert (phone becomes server, glasses become client) but the wire
  protocol and everything downstream (OCR, overlays, freeze) is unchanged.
  This closes what used to be a hard dead end for Lens.

The founding claims held up: wake-on-message, the single hub-owned link, and
declarative surfaces are exactly why none of the above required a user to ever
touch the glasses again.

## 6. What remains

Ordered by the problem it solves, not by ambition:

1. **Display arbitration** — the toast layer and a real `actionable` class
   (§2's vocabulary is in the protocol; v1 still renders `actionable` as
   `toast`). This is the first problem two chatty plugins will create.
2. **Native-apps section in the glasses menu** — phase 2 of §3. The trusted
   phone screen already lists and launches installed apps; this remaining step
   puts the same catalogue under the triple-tap launcher.
3. **Continuous speech** — short STT takes and real consumers ship; held leases,
   long-running partials, and a caption presentation still do not.
4. **Relay breadth** — notification listener + direct reply ship for supported
   messengers; ordinary-app selection and broader extraction remain.
5. **`nav` surface kind** — real navigation HUD (GMaps degrades to a text card
   until then).
6. **Maven Central** — once the AIDL surface is stable; JitPack carries the
   SDK until then.

Future app families, by capability tier: live captions/translation and voice
assistant (audio lease); teleprompter and glanceables (today's kinds); sport
HUD, CGM glucose, nav (small protocol additions); visual assistant and
FoodFacts (camera capability, now shipped). Non-fits stay non-fits: native
glasses apps are launched by the menu, not ported into it. Host app: Hi Rokid
Global only for now. Android only for now.
