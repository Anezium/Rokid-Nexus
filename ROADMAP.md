# Rokid Nexus — Roadmap

Status: 2026-09-24. This file is the public roadmap and the source the
[project site](https://rokid-nexus.anezium.me) renders. The founding product
argument lives in [VISION.md](VISION.md); what actually shipped in each release
lives in [CHANGELOG.md](CHANGELOG.md).

No dates. Items are ordered by the problem they solve, and nothing is listed as
shipped until it has run on real hardware.

---

## Shipped

### The bus, and the identity it enforces

Any APK may bind to the hub; installing one grants it nothing. A plugin is
identified by **package + plugin id + signing certificate**, and each capability
— `surfaces`, `ink_surface`, `microphone`, `stt`, `tts`, `camera`, `http_proxy`,
`mediasync`, `assistant`, `wireless_debugging` —
is a separate user grant, checked at the hub on every message rather than once
at install time.

Wake-on-message means a plugin does not have to be running: the hub binds it
awake when traffic arrives (measured at ~1.6 s including cold start) and it goes
back to nothing afterwards.

### One link, two paths, and the hub picks

Control messages ride CXR-L; anything binary — images, photo sync — goes over
SPP. A plugin never chooses, and never learns which path its bytes took.

1.1.1 flipped that order to route around a link that reported sends it had not
delivered, and 1.1.2 flipped it back: SPP is a single RFCOMM channel with one
write lock, so a control message queued behind a photo chunk waits for the whole
chunk. The real fix is an acknowledgement, not a different running order.

### Setup without a computer

Seven steps on the phone, the glasses app pushed over the Rokid link straight
from GitHub releases, then a two-card self-arm on the glasses: accessibility on,
then the hub bootstraps its own privileged shell — Wireless Debugging
self-pairing with an app-private KADB TLS identity and a detached watchdog. It
never touches the classic ADB key, so nothing on a PC is ever enrolled.
Navigation reads the firmware's own localized labels, so it works in every
language the ROM ships.

### The distribution loop, closed

The SDK publishes to JitPack from `sdk-v*` tags. A plugin releases under its own
namespaced tag, a manifest PR lands it in the public
[RokidBrew-Registry](https://github.com/Anezium/RokidBrew-Registry), and the
in-app Store verifies SHA-256 and signer *before* the install runs. Provenance
is checked in a fixed order — commit, tag, build, publish — because the registry
refuses a manifest whose artifact it cannot tie back to the tag.

Everything self-updates afterwards: phone from releases, glasses over CXR,
plugins from the Store. Since 1.4.7 the unit tests of both hubs and every
plugin run on each push, and a release that fails them is not published.

### All five display tiers, and the motion under them

| Tier | What it is |
|---|---|
| **Ambient** | Nothing is asked of you: a value changing in place, never moving the layout |
| **Pin** | One global slot, text only — a plate, a gate, a door code — surviving across surfaces and native screens |
| **Activity** | An ongoing process, idling as a chip and morphing in place into a panel when something significant happens |
| **Notice** | A discrete event wanting an answer: up to sixteen structured lines paged on the glasses, up to three glyph answers, exactly one answer taken |
| **Surface** | The engaged case: cards, readers, timed lines, media decks, list rows, real images |

Plus the shared glyph set, plugin marks travelling to the glasses as bare
geometry, the phone's own battery in the ROM status row, and — since 1.4.8 — a
status row along the bottom of every plugin surface: phone charge, the date,
the glasses' own charge.

A reader says where reading begins, because the answer is not the same for a
conversation and for an article: a stream opens on its last line, a document
opens on its first and keeps the wearer's place across updates. The plugin
knows which one it is sending, so the plugin chooses; the hub used to guess
from the surface id alone (1.4.3).

One motion layer sits under all of it: three duration tokens (180 ms in place,
280 ms arriving or changing shape, 240 ms leaving) and two interpolators, none
of it dialable by a plugin. Native Views, not a WebView — the WebView spike
rendered the same motion for ~1.2 cores, +88 MB PSS and 2.2 s to first paint
against 7.7 % CPU, and its one real advantage (plugin-authored layout) is
something the activity tier refuses by design.

### The camera capability

The glasses stream live H.264 over a Wi-Fi Direct link and the consumer plugin
decodes on the phone, where ML Kit runs OCR and translation offline. No
glasses-side plugin code exists: the glasses half is a platform capability.

A phone app cannot switch its own Wi-Fi on, which used to be a dead end for
this. Now the roles invert — the phone hosts a `LocalOnlyHotspot` and the
glasses join it — and the wire protocol, the decode, the overlays and freeze are
unchanged.

### Speech, in both directions

Speech-to-text was half a conversation: a plugin could take words from the
wearer's mouth and put words on their display, but it could not say anything.
`tts` is the other half — a capability, granted per plugin and revocable like
the rest, that reads text aloud on the glasses.

The speech is synthesized by the phone, with a voice and a speed the wearer
picks once in Settings → Voice, and carried over the Bluetooth audio the
glasses already wear — earbuds, if any are in, keep priority. The phone's own
loudspeaker never plays a word: when no ear is available the answer stays on
the display instead. Voice and speed are one choice for everything that
speaks, so no plugin — and not the hub either — may change them per utterance.

Reading and dictating share one pair of ears, so opening the microphone
silences whatever is being spoken. Otherwise the glasses record their own voice
into the transcript, and a plugin answering a message would be answering itself.

How long the glasses wait for you is yours too (1.4.9): a Patience setting
covers both the wait for the first word and the pause that ends a sentence.
The built-in Android recognizer now starts only once you begin speaking, so it
no longer gives up after three seconds of thinking.

### Listening with the display asleep

A microphone plugin that holds an active audio lease may detach its last
surface and keep listening (1.4.10). The display goes back to sleep, the
launcher is free, reopening the plugin resumes it, and the phone's plugin row
says *Listening in the background* with a **Stop** next to it. Releasing,
revoking or losing the lease always ends it. This is not a background-work
permission: without an open microphone there is nothing to keep alive. Asked
for in #37.

### The assist button, on the wearer's terms

A plugin that replaces the glasses assistant no longer has to own the button
to be reachable. A second switch hands the assist button back to Rokid's
assistant while the plugin stays one pick away in the Nexus launcher, and
Assistant can flip it from the glasses (1.4.8, asked for in #35). Plugins are
told why they were opened — a launcher pick, the assist button, a re-adoption
after a restart — so each can answer the way that entry deserves.

### Waking a dark display, without owning it

A notice worth it can pulse the display awake: at most one wake every five
seconds *across every plugin*, always a short pulse, never held on. No other
tier may do it at all, including activities.

### Ink Surface

The public SDK has a typed, separately granted `ink_surface` session. A plugin
submits a strict subset of Rokid's `.ink` format; the phone compiles it into
bounded revisioned documents and the glasses project them to native Views. Data
patches, tap actions, charts, progress, inline Lottie, and declarative canvas
are implemented without WebView, JavaScript, URL loading, or page-side network.
Assistant is the first production consumer and Sample is the copyable SDK
reference (1.4.1).

### Phone control for native glasses apps

The phone lists and opens launchable APKs already installed on the glasses,
moves focus with previous/next/select/back, and supplies an ephemeral keyboard
to the focused glasses editor (1.4.1) — a plugin's own text field included,
since 1.4.6, below. The phone is also a trackpad: a drag
moves the pointer the glasses system already has rather than one of ours, so it
behaves the same in the Rokid launcher and in any third-party app, and Nexus
draws its own cursor only when that path is unavailable (1.4.2). The four
versioned `/core/*` protocol families are hub-only and replay-safe; no plugin
grant reaches them. Sensitive editors secure the phone window, and existing
field contents never cross back to the phone. Installing native APKs is not
part of this slice.

### Typed input for plugins

A card can carry one focusable text field, and what the wearer types comes
back to the plugin once, on submit or cancel. The field is ordinary Android
input, so a keyboard bonded to the glasses works and so does the phone's
Keyboard & remote screen; the glasses hub announces support as a feature bit,
and a plugin that offers typing falls back when it is absent. Relay's *Reply by
typing* and Assistant's typed notes are the first two consumers (1.4.6, with
Relay 1.2.2 and Assistant 1.4.4). Contributed by ruruw, along with a
Maintenance check that asks the glasses which accessibility services besides
Nexus's own are enabled — a foreign one in front of Nexus's key handling has
been the cause behind more than one "input stopped working" report.

### A notice answers the question you saw

Replies, gestures and dismissals are bound to the notice instance and the
question on screen, in both hubs, so a delayed event cannot act on a
replacement from the same plugin (1.4.11). An interactive notice takes
direction, confirm and Back before the launcher or surface beneath it, and a
reply the transport rejected shows *Delivery not confirmed* rather than risking
a duplicate effect after a partial write. Notice protocol v5 needs both hubs on
the same version; plugins rebuilt on SDK 0.19.0 also filter stale callbacks
already queued, and an SPP interruption no longer disables a live notice's
callbacks.

Underneath, the phone hub now hands notices, pins, activities, ordinary
surfaces and Ink to separate routers, each with its own regression tests. The
split preserves behavior; it adds no display policy.

### Eleven plugins, none of them built in

Relay · Assistant · Lens · Feeds · Transit · Lyrics · Media Deck · Photos Sync
· Wireless ADB · Tasker · Sample

---

## Deferred

Designed, and parked on purpose until a concrete case asks for them.

### Display policies and ownership epochs

Foreground ownership, per-surface ordering, sequenced hides, and image-decode
invalidation already protect the normal handoff. Newest accepted notice
replacement is an intentional single-slot policy, not a missing queue.

Per-plugin display policies — mute, demote, notices-only — and hub-stamped
ownership epochs have implementations on development branches, but are not the
current integration priority. A narrow phone-side check/stamp race remains a
hypothesis to reproduce; it is not evidence that an ordinary late image can
repaint a new owner, or a reason to require a global display rework. This work
comes back against a concrete scenario.

### Continuous speech

Speech-to-text ships, in short takes: the audio lease is specified,
hardware-validated, and has a real consumer. Since 1.4.10 a raw microphone
lease can also outlive its surface, but that lifecycle is not continuous
transcription.

The missing slice is a held lease with partial results streaming to the HUD,
and a caption presentation that survives the surface underneath it changing.
Live captions, translation, and an assistant that stays listening need that
slice. It is deferred until a consumer needs it.

---

## Next

Committed, not started, in this order.

1. **A navigation plugin.** Google Maps and Citymapper already emit
   turn-by-turn as notifications. The plugin reads those and keeps the route as
   an activity, with notices for the moments that matter. A platform `nav`
   kind comes after those payloads have proven which fields are stable — not
   before.
2. **Skills, and a keyboard for Assistant.** A hub-mediated registry so
   Assistant can pause music or ask Transit without binding another plugin or
   reimplementing it. Plus a phone-typed ask, for the places where talking to
   your glasses is not an option.
3. **Native apps in the glasses menu.** The phone-side catalogue and launch
   path now exist. Phase two puts that catalogue behind the same triple-tap that
   lists plugins, with a back path that lands where the wearer started. Nexus
   still does not port, wrap, or install those apps.
4. **A `nav` surface kind.** Maneuver glyph, distance, street, ETA, drawn by
   the platform — after the navigation plugin has something real to draw.
5. **Maven Central.** JitPack builds the SDK from tags and is fine for early
   adopters, but it is not something a serious app should depend on. Central
   goes out once the AIDL surface is a promise rather than a snapshot.
6. **A control-plane acknowledgement.** MediaSync already acks photo chunks.
   Glasses→phone CXR still reports success for frames the third-party client
   never sees, which is why outbound traffic prefers SPP. Another flip of
   running order is not the fix.

---

## Plugins

Everything above is the platform's roadmap; this is the ecosystem's. The rule
does not change down here — each of these is an ordinary phone APK against a
capability that already exists or is named above, and none of them puts code on
the glasses. One of the old explorations already made the crossing: "a voice
assistant" was a table row on this page, and it shipped as Assistant.

### Shipped, and what each one still owes

| Plugin | Still owed |
|---|---|
| Relay | Notifications from ordinary apps, not just messengers · an app picker, so the wearer chooses which apps may reach the eye. Typed replies shipped in 1.2.2 |
| Assistant | More tools that act — control the music, ask Transit — through hub-mediated skills, not by becoming those plugins · a keyboard mode — the request typed on the phone instead of spoken, for the places where talking to your glasses is not an option. Providers beyond ChatGPT shipped in 1.1.0 — MiniMax, DeepSeek, GLM, OpenRouter, or any OpenAI-compatible server; reminders, timers and notes shipped in 1.3.0, on every provider; phone-calendar creation, listing, and safe deletion in 1.4.0; Hermes, which runs its agent on its own side, in 1.4.1, with the phone tools bridged to it in plain text in 1.4.2; typed notes in 1.4.4 |
| Feeds | Posting and replying by voice · sources beyond Bluesky and X · video in the timeline |
| Media Deck | Voice control — "next" and "pause" said instead of tapped |
| Photos Sync | A Wi-Fi-only rule · a video's location tag, which Android strips on the way out. Capture-type filters shipped in 1.1.0; optional deletion after sync already shipped in 1.0.0 |
| Lens · Transit · Lyrics | Complete as they stand |

Navigation is deliberately absent from Transit's row: it deserves a plugin of
its own, below.

Ten plugins in the Store were written by someone else, by four different
authors: [Lume](https://github.com/beyondlevi/lume-nexus), a wearable RSVP speed
reader; Agenda, News and Tuya Smart Home, also by beyondlevi;
[Shopping List](https://github.com/beyondlevi/nexus-shoplist) by Volund, ticked off with
the R08 ring; Home Assistant, OTPs and Taxi Plate by Zhilin; and RokidHub's
Codex and Yandex bridges. They are not on this page because they are not mine
to plan, which is the point: they install, are granted, and run exactly like
the rows above.

### Next

In order.

1. **Navigation.** Same row as the platform list above: activity + notices
   first, `nav` kind later.
2. **Agents, as a private alpha.** Already in the tree (`plugins/agents`):
   Claude Code, Codex, and OpenClaw sessions on the HUD, notices for
   permission prompts, a pin for progress, the next task dictated. It is the
   Terminal/Agent product, and it is being reworked to speak the protocol of
   Alleycat — the daemon behind [litter](https://github.com/0xSero/litter) —
   instead of a hand-rolled one. It is not Store-listed until that rework
   lands.

### Ideas

Not committed.

| Idea | What it needs |
|---|---|
| A visual assistant, FoodFacts | camera capability, shipped |
| Sport HUD | activity tier + a small protocol addition · possibly fed by the R08 ring |

---

## Not on the roadmap

Being explicit about non-goals is how a platform stays one.

- **Porting native glasses apps into Nexus.** They are launched from the menu,
  never absorbed — porting them would make the platform responsible for software
  it did not write.
- **Glasses-side plugin code.** The glasses half of any capability lives in the
  hub; plugins stay phone APKs. This is exactly what makes zero glasses-side
  deployment possible, and it is not negotiable.
- **A signature-only plugin permission.** It would be the strongest trust model
  available and it would also make third-party plugins impossible, because no
  external developer can be signed with the Nexus key.
- **Other hosts and platforms.** Hi Rokid Global, Android, for now.
