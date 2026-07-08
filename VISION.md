# Rokid Nexus — Product Vision

Status: 2026-07-07 — consolidated from the QUESTIONS.md working session. Wire and
protocol details live in BUSSPEC.md; this document says what Nexus *is*, how it should
feel, and in what order it gets built.

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
- **Declarative surfaces** — plugins push content descriptions; the glasses hub renders
  them locally. Zero glasses-side deployment.

Because plugins are plain phone APKs against a published `:bus-client` library, Nexus
is not an app — it is a **platform** third-party developers can target, with RokidBrew
closing the distribution loop.

## 2. The layer model

Everything the user sees on the HUD belongs to one of four layers:

| Layer | What it is | Who controls it |
|---|---|---|
| **Ambient** | Silent surface updates (lyrics advancing, glanceables refreshing) | Plugin, continuously |
| **Toast** | Brief, non-blocking notice; latest wins, hub rate-limited | Plugin, throttled by hub |
| **Surface** | Full interactive surface (card, timed-lines, future kinds) | Plugin via launcher or user action |
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
- The menu is **the single entry point**: plugins first (user-ordered, favorites
  pinned, plain text rows — icons can come later, text is king on a monochrome HUD),
  and in phase 2 an "Apps" section that lists and launches native glasses apps.
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
- **Granular consent, three capabilities** approved separately per plugin:
  1. *Surfaces* — may draw on the glasses.
  2. *Microphone* — may hold the audio lease.
  3. *HTTP proxy* — may send traffic out through the user's data plan.
- **Mic indicator on the HUD** whenever the audio lease is active — the Android "green
  dot", mandatory in the spec.
- Protocol hygiene: an unknown surface `kind` (old glasses hub, newer plugin) is
  ignored and answered with a "update required" toast — graceful degradation is a spec
  rule, not a courtesy.

## 5. Onboarding — the road off ADB

Today the glasses hub is armed once via ADB (APK install + accessibility secure
setting + `BLUETOOTH_CONNECT` grant). Verified on device: the stock Accessibility
settings screen launches by intent on the glasses, renders, and is touchpad-navigable
— so accessibility activation has a no-ADB path (guided screen, like the
Tasker-Bridge helper). `BLUETOOTH_CONNECT` is an ordinary runtime dialog. The one
remaining gap is **APK install without ADB**; Tasker-Bridge already proved
upload/install over CXR-L.

Decision: developer rounds stay on ADB. The full no-ADB bootstrap (install via
Hi Rokid/CXR-L + guided accessibility screen + runtime grants) is **the gate for the
public beta** — built after the first two plugins prove the platform, before anyone
outside power users touches it.

## 6. Distribution and ecosystem

- `:bus-client` publishes on **JitPack now** (zero setup, the GitHub repo suffices);
  Maven Central once the AIDL surface is stable.
- The phone hub gets an "install plugins" button pointing at RokidBrew; RokidBrew tags
  Nexus-compatible apps. Store distributes plugins → plugins light up the glasses →
  the ecosystem loop closes.
- Host app: Hi Rokid Global only for now. Android only for now.

## 7. Plugin roadmap

Ordered by leverage, not ambition:

1. **Transit plugin** (even-transit model, Motis/Transitous) — pure text `card`; the
   data path was already a Round A acceptance criterion. First plugin a stranger can
   actually use, so it leads.
2. **Rokid-Scribe** — first real audio-lease consumer (glasses mic → phone STT).
3. **Rokid-Relay migration** — notification listener + direct reply, per BUSSPEC.
4. **Tasker-Bridge port** — deferred: too niche (requires Tasker, power-user paid app)
   to justify even a cheap port right now. Remains the lowest-effort second plugin
   whenever multi-plugin coexistence needs proving; its no-ADB install proof is
   already banked either way.

Degraded-or-later: GMaps as text card now, real nav HUD needs a `nav` surface kind;
Live Studio needs a camera/media lease (Round C+). Non-fits stay non-fits: native
glasses apps are launched by the menu, not ported into it.

Future app families, by capability tier: live captions/translation and voice assistant
(audio lease); teleprompter and glanceables (today's kinds); sport HUD, CGM glucose,
nav (small protocol additions); visual assistant, FoodFacts, Lens (camera lease).

## 8. The Lens project (flagship, later)

Google-Lens-style live translation on the HUD, free and on-device: glasses camera →
JPEG frames over SPP binary frames (~1 fps suffices, text is static) → ML Kit OCR +
translation on the phone (offline, ~50 languages, zero recurring cost) → translated
blocks + normalized coordinates back to the glasses, which render and keep the overlay
alive locally between refreshes (IMU anchoring). Both display modes were proven by
DragonBallScouter (camera-preview overlay: certain; angular pseudo-AR: feasible with
per-user calibration).

Friction: it needs glasses-side code. The plugin model doesn't forbid that — it
defines what is *free* (surfaces = zero install). Two routes, decided later: a `lens`
surface kind rendered by the glasses hub, or an autonomous glasses-side bus client
installed through the same bootstrap channel as the hub itself.

## 9. Roadmap (reordered)

| Milestone | Contents | Gate |
|---|---|---|
| **Round B (in flight)** | Surfaces, binary frames, audio lease, wake supervisors | Hardware validation per TESTPLAN |
| **Round C — first plugins** | Transit plugin first, then a second plugin for the coexistence gate (Scribe pulled forward, or the Tasker-Bridge port as cheap filler); interruption classes (`actionable`→`toast`); launcher order/favorites; disconnect state in overlay; granular consent UI + mic indicator | Two plugins living side by side without stepping on each other |
| **Round D — platform hygiene** | Display arbitration (surface stack, toast layer, real `actionable`); native-apps section in menu; Scribe; unknown-kind degradation | A third-party dev could build a plugin from JitPack docs alone |
| **Public beta gate** | No-ADB bootstrap (install via Hi Rokid/CXR-L, guided a11y, runtime grants); JitPack publish; RokidBrew loop | A stranger sets up Nexus with nothing but their phone |
| **Beyond** | Relay migration, `nav` kind, camera lease, Live Studio, Lens | — |

The two items this session promoted: **onboarding without ADB** (from "Round C+ list
item" to public-beta gate with a validated a11y path) and **display arbitration**
(first problem the second shipped plugin will hit).
