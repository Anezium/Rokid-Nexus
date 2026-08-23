# Plan 021 — Daily-driver platform

Status: TODO. Decisions below ratified by the owner 2026-08-23 (with
amendments; the drafted text predates that review). Depends on the
shipped display tiers (010–018, 020) and speech slices 1–3 (009). This
is the next chapter after the plugin boundary: the platform exists, and
several plugins will now share one eye.

The public story lives in [ROADMAP.md](../ROADMAP.md). This file is the
execution spec. Do not start a later wave until the earlier one has run
on hardware, except the hygiene slice, which may land at any time.

No dates. Waves are ordered by the problem they close.

---

## Why this chapter exists

The hard architectural bets already held: empty hub, phone-only plugins,
signer-bound grants, declarative surfaces, one CXR/SPP link. What is
left is the first problem two chatty plugins create, the first problem a
commute creates, and the first problem a voice that can only take short
turns creates.

Until those close, every new plugin is a debt. After they close, Assistant,
Relay, Lyrics, a navigation plugin, and an agent board can coexist
without inventing another special case.

## Decisions already made

These are settled here, not left for the implementer:

1. **The five tiers are the class.** Do not add a per-message
   `ambient | toast | actionable` field. That vocabulary predates pin,
   activity, and notice. A notice *is* the actionable interruption; a
   pin or ambient widget *is* the silent fact; a surface *is* the
   engaged case. `VISION.md` §2 is updated to say so.
2. **Slots stay slots.** One pin, one notice, one activity, one
   foreground surface (card or Ink), one ambient widget. The arbiter
   ranks *within* a slot and decides wake. It does not flatten every
   tier into a single newest-wins queue. Overlay is not eviction: a
   notice still *draws over* whatever is beneath for its few seconds —
   the temporary passes in front of the near-permanent — and the stack
   beneath survives untouched. What a slot can never do is destroy or
   take ownership of another slot's content.
3. **Mute, demote, and notices-only are display policy, not
   capabilities.** They live on the grant record. Changing them does
   not send the grant back to Pending. They do not exist in the phone
   hub today — the public roadmap had claimed they did.
4. **Plugins still do not talk to each other.** Composition goes through
   a hub-mediated skill registry (wave E), or through a phone-local
   Android API the way Assistant already uses Calendar.
5. **T3code is deprecated outright; Agents reworks around litter.**
   T3code was an experiment, not a product — it is not a row, not
   even an idea. The Agents plugin drops its hand-rolled protocol
   instead of hardening it; see the parallel track below.
6. **Do not grow Assistant into the OS — and native-first for tools.**
   When Android exposes a capability to any app (MediaSession
   transport controls, the calendar provider), Assistant uses the
   native path directly. A skill exists only for data or capability
   that lives *inside* a plugin (Transit's favourite stops). Assistant
   never reimplements another plugin.

## Non-goals (this chapter)

- Porting or installing native glasses APKs.
- Glasses-side plugin code.
- A signature-only plugin permission.
- Other hosts or platforms.
- A new display tier (`nav` is a later *kind* of the existing surface
  slot, not a sixth layer).
- Maven Central (hygiene for third parties, after the AIDL/path surface
  this chapter adds has settled).
- A HUD simulator, a glanceable home, FoodFacts, a sport HUD, or a
  Home Assistant plugin. Those wait until the daily-driver waves ship.
- A big-bang rewrite of `BusHubService`. Wave B extracts the handlers
  wave A and wave C will touch. It does not rename the service.

## Current state the waves start from

- Foreground gate: `PhonePluginRegistry.allowExternalSurface` — one
  owner, idle `show` adopts, otherwise `SURFACE_BUSY`.
- Per-surface seq drop: `SurfaceOrderingCoordinator` — not
  cross-plugin epochs.
- Lyrics `/widget/*`: ambient overlay that deliberately bypasses
  `SURFACE_BUSY` (`contracts/2026-08-16-lyrics-home-widget.contract.md`).
- Grants: `PluginGrantStore` has approve/deny/enabled and per-capability
  sets. No display policy field.
- Speech: utterance mode shipped; continuous is plan 009 slice 4.
- `BusHubService.kt` is the phone-side router (~6k lines, no direct
  tests).
- `plugins/agents/` exists and is not in the root Gradle tree.
- `plans/020-ink-surface.md` is implemented on `main`; the plans index
  still said TODO.

---

## Wave 0 — Hygiene

Cheap, no protocol. Land first or in the same PR as wave A slice 1.

- Mark plan 020 `DONE` in `plans/README.md` (M5 hardware measurement
  may remain a note on that plan; the public path has shipped).
- Delete or ignore the empty root `plugin-sample/` if it is still
  empty; `plugins/sample/` is the template.
- Fix the stale comment in `settings.gradle.kts` about Feeds "moving
  once the branch lands".
- Keep `ROADMAP.md` and `site/index.html` on the same story. The site
  is hand-copied, not generated; both files change together.

Do not start a docs-only rewrite of BUSSPEC or PLUGIN_SDK in this
wave. Those update with the wave that changes the wire.

---

## Wave A — Display arbitration

**Problem:** two chatty plugins, one eye, newest-wins, and a widget
contract that has to special-case the gate.

**Outcome:** a late frame cannot repaint a superseded owner; a muted
plugin is silent; a demoted plugin cannot take the foreground or wake
the panel; the lyrics widget is the ambient slot, not an exemption.

### Policy

Display policy per grant, default `normal`:

| Policy | Pin | Notice | Activity | Surface / Ink | Ambient widget | Wake |
|---|---|---|---|---|---|---|
| `normal` | yes | yes | yes | yes | yes | notice rules |
| `demote` | yes | yes | yes | **no** | yes | **no** |
| `notices` | no | yes | no | no | no | notice rules |
| `mute` | no | no | no | no | no | no |

Rejected traffic returns a stable `/error` code and does not retry:

- `SURFACE_BUSY` — another plugin owns the foreground slot (unchanged
  meaning). A demoted or notices-only plugin that tries a surface
  `show` also gets this, not a new code: the wearer-facing fact is
  "you may not take the display".
- `DISPLAY_MUTED` — policy is `mute` (or `notices`/`demote` for a
  path that policy forbids other than foreground). Use this when the
  plugin is not allowed to paint *this* slot at all, so it can give
  up quietly.

Do not invent `DISPLAY_DEMOTED`. One extra code is enough; the rest
is `SURFACE_BUSY` vs `DISPLAY_MUTED`.

Wake stays the plan 016 rule: notices only, one pulse per five
seconds across every plugin, never held. Demote and mute refuse wake
even if the notice itself would have been accepted. That is the
point of demote.

Ranking *inside* a slot:

- **Foreground surface:** current owner keeps it until hide, BACK,
  revoke, link loss, or an explicit user open of another plugin.
  Background auto-`show` still fails `SURFACE_BUSY`. Idle HUD still
  adopts on `show`. Epoch increments on every owner change.
- **Notice:** newest accepted notice replaces the current one. The
  previous owner gets `closed` / `replaced`, as today. Mute/notices
  policy applies before this ranking.
- **Pin / activity / ambient:** one global slot each; newest accepted
  replace. A muted plugin cannot replace someone else's pin.

Ownership epochs (foreground only in v1):

- Phone stamps `epoch` (monotonic `Long`, hub-owned) on every
  `/surface/show|update` and the Ink compile path that becomes
  `/surface/show|update` with `kind:"ink"`.
- Glasses drop a frame whose `epoch` is less than the live epoch for
  that slot, even if `seq` is newer. A superseded owner's late SPP
  chunk cannot repaint the new owner.
- Plugins never send `epoch`. The hub overwrites it the way it
  overwrites `ownerPluginId`.

The lyrics widget:

- Keep `/widget/show|update|hide`.
- Route them through the same display-policy check as other ambient
  paint.
- They still do not count as the foreground surface and still do not
  self-close the plugin.
- The written exemption "never blocked by `SURFACE_BUSY`" remains
  true; what they lose is the right to paint while muted.

### Phone UI

Plugin access (`PluginPermissionsActivity`) grows three wearer-facing
rows under an approved plugin, not under Developer details:

- Mute this plugin
- Demote — glanceables only, never the full display, never a wake
- Notices only

Copy is plain language, and every switch carries a one-line
explanation of what it actually does. Demote's line must say it will
never light the screen — its notices can go unseen on a dark display,
and the wearer should learn that from the switch, not from a missed
message. Developer details may show the enum. Revoke/deny stay as
they are.

### Slices

1. **Epochs + drop.** `epoch` on the wire, glasses drop, contract
   tests. No UI yet. Existing `SURFACE_BUSY` behaviour unchanged.
2. **Display policy store + enforcement.** `PluginGrant` field,
   route check in the phone hub, error codes, unit tests. Default
   `normal` so current grants do not change.
3. **Wearer switches.** Plugin access rows, persist, take effect on
   the next message without rebinding.
4. **Fold the widget.** Lyrics widget uses the arbiter; update the
   lyrics-home-widget contract so it is no longer a bypass story.

### Files that will move

- `shared` — grant/display-policy types if they belong on the wire
  snapshot; path/error constants; epoch field on surface payloads
- `phone-hub` — `PluginGrantStore`, `PluginRoutePolicy` or a new
  `DisplayArbiter`, `PhonePluginRegistry.allowExternalSurface`,
  `PluginPermissionsActivity`, the surface/Ink branch of
  `BusHubService`
- `glasses-hub` — `SurfaceOrderingCoordinator` or a sibling epoch
  gate in `SurfaceController`
- `bus-client` — only if the SDK should surface `DISPLAY_MUTED` as a
  typed result (prefer yes: `NexusSdkResult`)
- Docs: `BUSSPEC.md`, `docs/PLUGIN_SDK.md`, `plugins/AGENTS.md`

### Verification

```
./gradlew :shared:test :bus-client:testDebugUnitTest :phone-hub:testDebugUnitTest :glasses-hub:testDebugUnitTest
```

Hardware, after slice 4:

1. Assistant on the HUD, Lyrics playing: widget stays ambient; Lyrics
   full surface is `SURFACE_BUSY`.
2. Relay notice arrives over Assistant: notice overlays, Assistant
   stays owner, epoch does not change.
3. Mute Relay: notices stop; Assistant untouched.
4. Demote Lyrics: widget may stay; opening Lyrics from the launcher
   still works (user open is not demote). Auto-show of the full
   lyrics surface does not steal the HUD.
5. Kill the previous owner mid-SPP image: the new owner's card does
   not get painted over by the late frame.

### STOP

- Do not let a plugin set `epoch` or `ownerPluginId`.
- Do not add a sixth HUD tier.
- Do not make mute a capability. A muted plugin keeps its grants; it
  just cannot paint.
- Do not queue rejected surfaces for later. Give up quietly.

---

## Wave B — Split the phone router

**Problem:** wave A and wave C cannot keep landing as 400-line diffs
inside a 6 000-line `BusHubService` with no direct tests.

**Outcome:** surface, Ink, pin, notice, activity, and display-policy
routing live in dedicated types with their own tests. `BusHubService`
still owns the Binder and the link. Speech, camera, mediasync, and
wireless ADB may stay until the wave that next touches them.

### Constraints

- Append-only AIDL. No method reorder.
- Behaviour-preserving extract first; policy changes belong in wave A
  (do B slice 1 *after* A slice 1 if both are in flight, so epoch
  tests have a home).
- Do not rename public types the plugins import.

### Verification

Same Gradle suites as wave A, plus a before/after list of
`PluginBusJournal` verdicts on the existing unit fixtures. No new
user-visible behaviour.

---

## Wave C — Continuous speech and captions

**Deferred (owner, 2026-08-23): no shipped consumer needs continuous
mode yet. Waves D and E run first; this spec stays so plan 009
slice 4 has a home the day a consumer appears.**

**Problem:** STT can only take short turns. Live captions, long
dictation, and any assistant that stays listening are blocked on
plan 009 slice 4.

**Outcome:** a held lease, segmented partials, and one hub-owned
caption band that survives the surface underneath it changing.

### Spec additions on top of plan 009 slice 4

- `mode:"continuous"` on `/stt/session/start` as 009 already sketched.
- One caption slot, hub-rendered, not a plugin surface. The current
  STT holder may request it; nobody else paints it.
- Caption is not a notice: it does not wake, it does not take
  answers, it does not replace a notice.
- Opening the microphone still silences TTS (already shipped).
- Continuous mode still uses the single audio lease. A second
  plugin gets `BUSY`.
- Never log transcript text.

Caption presentation:

- Two lines: current partial (bright), last final (dim), or a single
  scrolling line if that fits the existing timed-line renderer.
- Hidden on launcher overlay the way the lyrics widget hides.
- Cleared on `session/ended`.

First consumer: the Speech settings test row, then Assistant (hold
becomes hold-and-keep, with an explicit stop). Live-translation
plugins wait; they are not this wave.

### Verification

Plan 009's engine tests, plus:

```
./gradlew :phone-hub:testDebugUnitTest :glasses-hub:testDebugUnitTest :plugin-assistant:testDebugUnitTest
```

Hardware: a continuous session of several minutes, caption visible
over the home screen and over a Transit card, BACK on the card does
not kill the caption, stop does.

### STOP

- Do not give plugins a caption layout API.
- Do not play TTS on the phone speaker.
- Do not persist transcripts.

---

## Wave D — Navigation plugin

**Problem:** a commute degrades into a text card. The daily driver
is missing.

**Outcome:** a headless phone plugin that reads turn-by-turn
notifications (Google Maps, Citymapper first), keeps the live route
as an **activity**, pins nothing unless the activity tier is
unavailable, and raises notices only for the moments that matter
(now-turn, reroute, arrival).

### Why activity, not pin

Plan 012 already named a route as an activity: a state machine the
wearer follows for minutes. A pin is a plate or a door code. Do not
teach Navigation to fake a pin-on-every-update.

### Platform `nav` kind

Not in this wave. The plugin ships against activity + notice. A later
plan adds `kind:"nav"` (maneuver glyph, distance, street, ETA drawn
by the platform) when the notification extract has proven which
fields are actually stable. Putting the kind first is how you draw
a HUD for data you do not have.

The long-term bar is higher than a text kind: a real navigation HUD —
map, maneuver glyphs, the way Rokid's native navigation and the Meta
Ray-Ban Display do it. Before designing `kind:"nav"`, survey how the
Meta Ray-Ban Display renders guidance (what they draw, what they
deliberately omit at that field of view). That survey belongs to the
later `nav` plan, not to this wave.

### Plugin rules

- Ordinary APK, `surfaces` only, no new capability.
- Notification listener idle when the plugin is closed, same shape
  as Relay/Lyrics: the listener may exist; it must not poll.
- While a route is live the plugin may keep a one-shot or
  guardian-style presence only if the activity update path requires
  it — prefer wake-on-notification, send activity update, go quiet.
- Fail closed on ambiguous notification payloads; show nothing
  rather than a wrong street.
- No glasses-side code. No Maps SDK on the glasses.

### Verification

```
./gradlew :plugin-nav:testDebugUnitTest :plugin-nav:assembleDebug -PskipCxrGlobal=true
```

Hardware: a real Maps walk, activity chip while Assistant is open,
notice on the now-turn, BACK on Assistant does not kill the route
chip, mute Navigation silences it (wave A).

---

## Wave E — Skills and Assistant keyboard

**Problem:** the roadmap owes Assistant "control the music, ask
Transit" and a typed request. Direct plugin-to-plugin paths would
break isolation. Reimplementing Transit inside Assistant would
break the empty-hub thesis.

**Outcome:** a hub-mediated skill registry, plus a phone keyboard
mode that uses the surfaces Assistant already has.

### Skills

Advertise at registration, from descriptor metadata (string list,
same splitting rules as capabilities):

```
com.anezium.rokidbus.plugin.SKILLS = pause,next,now_playing
com.anezium.rokidbus.plugin.SKILL_pause = Pause the phone's media playback
```

Skill ids: `[a-z][a-z0-9_]{1,31}`, unique *within* the plugin.
The hub addresses them as `pluginId.skillId` (`media.pause`).
Each skill should carry a one-line human description
(`SKILL_<id>` metadata); the hub shows it in settings and returns
it on discovery, so a caller knows what it is invoking and where
that comes from.

Well-known aliases (hub allowlist, v1):

| Alias | Resolves to |
|---|---|
| `media.pause` / `media.next` / `media.now_playing` | the granted Media Deck plugin, if exactly one |
| `transit.next_departure` | the granted Transit plugin, if exactly one |

Provider resolution, in order (owner, 2026-08-23):

1. The caller named a `provider` explicitly — honour it if that
   plugin is granted and advertises the skill.
2. The wearer picked a default provider for this alias in Plugin
   access (the Android default-app pattern: "Music: handled by
   Media Deck ▾", shown only when two granted plugins collide).
3. Exactly one granted provider — use it.
4. Otherwise fail `SKILL_UNAVAILABLE`. The hub never picks a
   winner on its own.

Wire:

- `/skill/invoke` `{version:1, skill, provider?, args}` from the
  caller.
  Requires the caller to be approved. No new capability in v1 —
  an approved plugin may ask; the hub decides whether anyone
  may answer.
- Hub delivers `/skill/request` to the provider only (direct
  reply, stamped provider id, no receive-prefix required).
- Provider answers `/skill/reply` `{ok, result? | error?}`.
- Args and result each ≤ 4 KiB JSON. Five invokes per second
  per caller.

`/skill/*` is reserved. Plugins cannot subscribe to someone else's
skill traffic.

First real case: Transit (`next_departure` for a favourite or the
nearest stop) — no Android API knows the wearer's stops, so a skill
is the only route. Media control is *not* the motivating case:
decision 6 says Assistant pauses music through MediaSession
natively. Media Deck may still advertise `pause`/`next`/
`now_playing` for other callers, but wave E does not depend on it.
First caller: Assistant tools that invoke aliases, not plugin ids,
so a fork of a provider can take the alias by being the only
granted one.

Calendar stays a phone-local API. It is not a skill.

### Assistant keyboard

- A phone activity, opened from Assistant settings or a home-row
  action, that sends the typed question through the existing
  ask path.
- Glasses show the same listening/answer episode as voice.
- No `stt` required. TTS still follows the wearer grant.
- Places where speaking is not an option are the whole point;
  do not auto-open the mic.

### STOP

- No raw bind from Assistant to Transit.
- No `/plugin/<otherId>/…` send from a peer.
- No skill that reaches `/core/*`.
- Hermes may see tools as text, the way it already does; it does
  not get a second bus.

---

## Parallel track — Agents, reworked around litter

Direction decided 2026-08-23: the Agents plugin drops the
hand-rolled protocol (agentd pairing, homemade Ed25519) instead of
hardening it, and becomes a compatible client of
[litter](https://github.com/0xSero/litter) — the native iOS/Android
client for Codex and Local Studio (shared Rust core, Slingshot
HTTP/stream transport). The glasses become one more client of that
stack, not the maintainer of a private one.

The investigation ran 2026-08-23. Key corrections: there is no
`npx litter` — the daemon is **Alleycat**, installed and paired via
the `kittylitter` CLI; pairing is a QR-carried bearer token, not
key exchange; and the code is GPLv3, so nothing of theirs can be
vendored — we implement the documented wire ourselves. The
execution spec is [plan 022](022-agents-alleycat.md).

The old gates still apply before any Store or root-catalogue
mention:

1. Decide: include `:plugin-agents` in the root Gradle tree, or
   move the folder to its own repository like Lume. Do not leave
   an unbuildable module in `plugins/`.
2. Write the background exception into `plugins/AGENTS.md`: a
   wearer-enabled monitor FGS is a third sanctioned exception,
   next to overlay-while-open and scheduled delivery. If that
   sentence cannot be written honestly, the FGS does not ship.
3. Fix the README so it matches approve/deny and thread start.
4. No `POST_NOTIFICATIONS` surprise. If the phone must ring, say
   so and request the permission at the point of use.

T3code is deprecated outright — it was an experiment, and it is
not coming back as a row or an idea.

---

## Later, not this chapter

Keep them on the public roadmap, after the waves above:

1. **Native apps in the glasses menu.** Phone catalogue already
   ships. Small, correct, does not make the daily driver.
2. **`nav` surface kind.** After wave D has real payloads.
3. **Control-plane ACK.** MediaSync already acks chunks.
   `GlassesOutboundTransportPolicy` is still SPP-first because
   glasses→phone CXR lies. An ACK is the real fix; another
   CXR/SPP flip is not. Own plan, after or beside wave C if
   photo-sync vs notice latency shows up on hardware.
4. **Maven Central.** After skills (and any other path/AIDL
   additions in this chapter) have been stable across one SDK
   tag.
5. **Video surface (plan 006).** Feeds' remaining row.

Ideas, still uncommitted: glanceable home, HUD simulator, live
conversation translation (wave C + Lens), FoodFacts, sport HUD,
Home Assistant as a Tasker-shaped plugin, `nexusSecretStore` in
the SDK (the hub already has Keystore-backed STT keys; plugins
still use plain `SharedPreferences`).

---

## Suggested execution order

```
0 hygiene
A1 epochs
B1 extract surface/notice/pin/activity out of BusHubService
A2–A4 policy + UI + widget
D  navigation plugin
E  skills + Assistant keyboard
C  continuous speech + caption          (009 slice 4; deferred —
                                         no consumer needs it yet)
   litter investigation, immediately (read-only, blocks nothing)
   Agents rework, any time after A (it needs mute)
```

A1 before B1 is acceptable if B1 is not ready; do not implement
policy (A2) as more methods on the 6 000-line file if B1 can
land the same week.

## Definition of done for this chapter

On one pair of glasses, with Assistant, Relay, Lyrics, Media Deck,
Transit, and Navigation installed:

- A route activity and a lyrics widget survive an Assistant
  answer.
- A Relay notice can overlay that stack and take one answer.
- Mute on Relay is silent; demote on Lyrics keeps the widget
  and refuses an auto full-surface.
- Hold-to-talk can run long enough to dictate a paragraph, with
  captions over whatever was already on the HUD. (Wave C — lands
  whenever C does; the chapter does not wait for it.)
- "When is my bus?" is an Assistant tool that hits Transit through
  the hub; "pause" goes through MediaSession natively. Neither is
  a local reimplementation of another plugin.
- Typed questions work on the phone without opening the mic.

Nothing in that list installs code on the glasses. Nothing in
that list is a sixth display tier. That is the point.
