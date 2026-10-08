# Plan 027 — The Nexus stack: one glasses home for activities, notifications, pages and the map

Status: Design agreed with the owner on 2026-10-08. Nothing implemented. This plan
supersedes the Navigation-only map work on `dev/nav-activity-widgets` (its contract,
Transitous routing and lifecycle fixes are inputs, its presentation is not) and the
grid-home direction of the alvarosw fork (its `hud/` core is an input, its grid and
tile lease are not part of the first deliveries).

Design record: `E:\Tools\Rokid\design\nav-map-mockups\` (owner's machine, not in the
repo): five GPT-6 Astra reports (`astra-input-ideas.md`, `astra-shade-round2.md`,
`astra-fork-launcher-round3.md`, `astra-home-model-round4.md`, `astra-pages-round5.md`),
the GPT-6.1 Sol review (`sol-review-round6.md`, whose edits are folded in here) and the
mockups. The full-UI mockup lives in `docs/mockups/glasses-home/index.html`.

## 1. Why

Nexus was built around notices, activities and pins: information that comes to the
wearer over whatever is on the glasses. Two things were missing and are the subject of
this plan:

- a way to **act** on that information from any context (native home, native app,
  plugin surface) with the only input Nexus owns on the glasses;
- a presentation model for **consulting detail** that does not open a plugin as an
  exclusive foreground surface, close the previous one and lose the context underneath.

Navigation's map is the first demanding client (minimap in the island, bigger map on
demand, zoom), not the reason for the architecture.

## 2. Hard constraints (verified)

| Fact | Consequence |
|---|---|
| HUD 480×640 portrait, 240 dpi, green monochrome, additive optics (black = transparent) | Hierarchy by brightness only. Line art for maps. No filled bright areas. |
| Touchpad: tap, one-axis swipe, double tap = BACK, triple tap = Nexus (the only free gesture). Long press = Rokid AI. Two-finger = brightness/volume. AI and camera buttons not rebindable | Everything Nexus does from any context starts with the triple tap. No new gesture may be invented. |
| R08 ring: tap, double tap = back, scroll. Most wearers have no ring | The ring is an accelerator, never a requirement. |
| Image surface ≤ 480×480 px, ≤ 64 KiB, SPP only, 150 ms min interval | The map is vector geometry drawn by the hub, not a JPEG. |
| Ink: source ≤ 32 KiB, data ≤ 16 KiB, document/patch ≤ 64 KiB, 256 nodes; 26 KB ≈ 180 ms over SPP | Hub templates (data only on the link) are the default page body; Ink is the optional rich body. |
| `/core/*` routes are trusted hub-to-hub; calendar and Wireless ADB rules of `AGENTS.md` are untouched | No new plugin power over control routes. |

## 3. Decisions

1. **One global key.** Triple tap opens the Nexus session over any context after a
   visible gate (the current 800 ms post-triple suppression, made visible as
   "Opening…" and covering every touchpad classification and the contacts begun inside
   it). While the session is open it owns every touchpad and ring gesture; a notice
   that arrives then only updates snapshots and a shell counter, keeps its deadline,
   and can neither answer nor dismiss while hidden. BACK at the root closes the
   session and restores the layer underneath without sending it any key. The triple
   tap is **not recognised while a visible notice can claim input**: the firmware
   classifies each contact as ENTER about 300 ms later, so the first two taps of a
   triple would spend the notice's preselected answer before the third contact, and
   the 800 ms suppression starts only after recognition and cannot retract a reply.
   The wearer dismisses with a double tap, then triple taps; the dismissed notice
   stays in the root as an unarmed preview. The existing editable-surface exception
   (detector disabled while a field is focused) stays.
2. **Ambient stays; the notice window is armed as today, islands and pins are
   passive.** Notices, activity islands and pins keep living over the Rokid home and
   over native apps. The notice input window is kept exactly as shipped, because
   wearers answer messages from the band and every plugin with notice actions depends
   on it: the band keeps **every claim `NoticeController` makes today, verbatim**:
   interactive or action-bearing notices claim confirm, multi-action or paged notices
   claim the directions, BACK dismisses any visible notice, backdrop notices claim
   every otherwise unhandled touchpad classification, a camera overlay suppresses
   notice input, and an editable card takes confirm and the directions back while
   notice BACK still precedes editor BACK (`BUSSPEC.md` notice section,
   `NoticeController.kt`, `NoticeKeyDispatcher.kt`). A notice whose window has
   passed owns nothing: the band fades out within a second, it does not become a
   passive window, and the session keeps a bounded snapshot as its preview. **Activity islands stop claiming input everywhere**, including the Rokid
   home. Today's rule (`ActivityController.handleKeyEvent`) swallows every centre tap
   while a primary activity is shown and opens the owner plugin, so a wearer with a
   Navigation island cannot tap a home tile; with one to three actions the swipes
   wrap inside the island and the home cannot be navigated at all; and the claim does
   not check the foreground native app, so the same happens inside Teleprompter. No
   shipped plugin publishes activity actions. The island's actions move to its page in
   the session (commands template); an old glasses hub keeps the old behaviour under
   capability negotiation. Nothing else about today's notice contract changes (reply
   tokens, durations, redaction, typed reply).
   Implementation: one arbiter for every entry path, not only `claimsInput()`. Order:
   source identification (reserved hardware keys pass; captured press ownership
   settled first by device and press identity, repeats and UP included, never
   retargeting a dying gesture) → opening gate (absorbs contacts and the
   classifications that complete after its deadline, ring suppressed too) → GLOBAL
   recognition from validated touchpad contacts only, with the editable exception and
   the armed-notice exclusion above → open session owns navigation → session closed:
   notice dispatcher → legacy launcher when selected → surface/editor → native
   pass-through. The activity branch of `RokidBusAccessibilityService.onKeyEvent`,
   `handlePendingTempleTap`, the ring activation path in `ActivityController` and
   the direct window dispatch in `LauncherOverlayRenderer` and `SurfaceActivity` all
   go through it; the R08 ring passes the gate and session checks before its
   existing notice-owned policy. Budgets: 3 residents on the recognized home
   (one primary activity at its preferred size, one secondary activity as a chip, one
   compact pin), at most 2 compact residents when a native app or a Nexus immersion is
   in front (primary activity as text, one short pin), one temporary signal in the band
   zone at a time, 0 ambient overlays while the session is open.
   **Inside a native app the ambient mode is per app**, because the hub cannot read
   a native layout to know whether there is room: `Residents` (compact text island
   top-left, short pin bottom-right, flares in the band zone), `Signals only` (no
   resident at all; the activity speaks only through its flares, each shown about
   3.5 s then gone: "300 m, turn right", "2 stops left", then the next one), or
   `Nothing`. The glasses hub needs a dedicated **foreground resolver** (package and
   component from accessibility window events, Nexus overlays and the IME excluded;
   `NativeAppsController` only catalogs and launches, and the service's cached
   package is private and updated from any event). The stock home, camera and
   Teleprompter are scenes of one launcher package on this firmware, so defaults are
   curated per component and validated on the target firmware, overrides are explicit
   on the phone's Native apps page, and an uncertain foreground resolves to `Signals
   only`. Defaults: text scenes (Teleprompter, Settings) `Residents`; camera, video,
   games and unknown `Signals only`. The mode travels as a trusted, versioned
   phone→glasses preference message. `Signals only` changes no activity payload:
   flares keep today's budgets (one per 10 s per activity, a separate urgent budget),
   are event-driven and never replayed, are dropped while a notice holds the band,
   and pulses are suppressed without a resident. Providers must emit the thresholds
   they want heard: Navigation today marks only key, arrival and imminence changes
   significant and Citymapper's ride key is constant until the destination changes,
   so "2 stops left" needs provider-side threshold emission (plugin change, no wire
   change). `Nothing` suppresses activity and pin presentation only; notices keep
   their behaviour. The island reappears on the home and the full state is always
   one triple tap away.
3. **The home model is the anchored stack.** One vertical, one-axis root of at most
   six focus stops: the pinned activity (Navigation), a second activity, a preview of
   the latest notification, `Notifications · n`, `Applications`. Order and card sizes
   are frozen while the session is open; relevance may reorder between sessions only.
   A finished activity stays in place as "Ended" until the wearer moves. The ambient
   island is the same activity instance as the head card, rendered for its size.
4. **Consultation is pages, never "open the plugin".** Tapping a root card opens a
   page drawn by the hub inside the session: Navigation → route preview (swipe = bounded
   zoom, tap = commands) → commands (Follow / Whole route / Display size / plugin actions
   with confirmation); Relay → conversation detail → thread → reply. Pages are pulled
   from the provider on demand and updated live while visible. Opening a page never
   calls `/launcher/open`, `/surface/show` or `/ink/show`, never emits
   `PLUGIN_CLOSE(switch)`. Up to 6 frames; BACK pops one frame.
5. **Templates first, Ink as the rich body.** A common `NexusPage` contract with typed
   bodies rendered by the hub: `summary`, `selectableList`, `document`, `commands`,
   `conversation`, `media`, `route`. The plugin names the template and sends data
   (parameters ≤ 2 KiB). An Ink body stays available under the existing `ink_surface`
   grant, with a page profile (list / reader / bounded-adjustment interaction, no
   nested scroll, no Lottie, no canvas animation, ≤ 2 content revisions per second).
6. **Immersion is the exception.** The current exclusive foreground surface remains
   for experiences that need a continuous rhythm or an exclusive resource: Lens live
   camera/OCR, continuous reader, games, rich editors, and unchanged third-party
   plugins ("Open the full app", always labelled). Immersions are not stacked in v1.
7. **Preference ≠ presentation ≠ excursion.** The ambient size (Text / Minimap / Panel)
   is a per-plugin preference stored by the hub and mirrored on the phone. The hub may
   cap it (text only while an app is in front). The full map is an excursion inside the
   session that always returns to the preferred size. No automatic 10 s panel near a
   turn in v1; flares keep today's rules (≈ 3.5 s, one per 10 s per activity).
8. **Navigation geometry is vector and hub-rendered at three sizes.** The activity
   widget carries bounded geometry (streets as context paths, route, walked part, turn
   marker, stop markers, ≤ 4 labels), the hub rotates it heading-up from a separately
   sent smoothed bearing, anchors the wearer at the lower third, and draws chip, panel
   and full page from the same data. Transit rides use a stop ribbon, not a geographic
   map. GPS loss is shown honestly (north-up, dot without arrow, dashed route, "~").
9. **Adopt the fork's `hud/` core selectively**, with attribution to alvarosw and
   upstream signer/registry kept: reducer, effects, runner, host, `beneath` context,
   property tests. Replace its interaction rules that contradict this plan
   (notice-first input, island capture, ENTER/BACK-only gate, neighbour re-selection,
   translucent host). Tiles, grid, weather, fork registry and signer switch are out of
   scope here.

## 4. Gesture grammar

Primitives: `STEP ±1`, `SELECT`, `BACK`, `GLOBAL`. Touchpad: swipe, tap, double tap,
triple tap. Ring: scroll, tap, double tap, no global. Future EMG/hand tracking map onto
the same four. The UI must never require a second axis, pointing, hold, chords, two
fingers, colour, haptics, voice or a ring.

| Context | tap | swipe | back | triple tap |
|---|---|---|---|---|
| Native app / Nexus immersion, session closed (islands passive, residents or signals only per app) | underneath | underneath | underneath | opens session root |
| Native home with a primary activity island, session closed | underneath (today: opens the owner plugin) | underneath (today: wraps the island's actions) | underneath | opens session root, island selected |
| Notice that claims input today (actions, interactive, paged, backdrop), within its window (unchanged) | preselected action | other action, or page with a single action | dismiss the band | not recognised: dismiss with double tap first, then triple tap; the dismissed notice is an unarmed preview at the root |
| Notice without actions, or window passed | underneath | underneath | underneath | opens session root; the notice is consultable there |
| Gate after triple tap (≈ 800 ms) | absorbed | absorbed | absorbed | absorbed |
| Session root | open selected card / enter its page | move selection, no wrap | close session, restore underneath | stay at root |
| Page (list / document / commands) | activate selected item or open next level | move selection / page the document / adjust bounded value | pop one frame | back to root, same session |
| Composer (reply) | pause editing → review | consumed | leave to mode choice, keep draft | suspend draft, root |
| Editable field of an immersion | field | field | field | disabled (BT keyboard protection), unchanged |

## 5. Contract sketch

- **Pages** (new, hub-only authority, plugin as provider): `onNexusPageRequest(request)
  → NexusPage`, `onNexusPageAction(action) → result`, `onNexusPageVisibility`,
  `onNexusPageClosed`. Request/response are small JSON frames; the hub validates ids
  (≤ 128 chars), action count (≤ 64), parameters and datasets (≤ 2 KiB each), one
  pending request per frame, six frames including the root (overflow rejected).
  Sizes: a complete serialized page ≤ 64 KiB, cumulative retained snapshots ≤ 512 KiB,
  measured on decoded memory; a document above the control-plane limit goes over SPP.
  Timeouts: "still loading" at 2 s, absolute 8 s measured from the initiating
  selection and including cold registration (≤ 5 s), compilation and transport; on
  failure "Detail unavailable" with the last known summary dated, Retry and Back,
  never an automatic fallback to immersion. Requests are cancelled or invalidated on
  cover, pop, retry, revocation and disconnect; responses are correlated with request,
  frame and session generation and with the authenticated registration; returning to
  the top of a frame starts a fresh validation. Live updates only to the visible
  frame, 120 s lease renewed every 30 s; covered frames keep their snapshot without a
  lease. Actions carry an invocation id and a target revision; a lost acknowledgement
  shows "unconfirmed", never an automatic retry; notice answers reuse the notice reply
  tokens. Provider binding is separate from immersion so closing a page never closes
  its base surface (`ExternalPluginController`).
- **Activity widget v2**: 24 paths, 1 024 points, 16 KiB, roles `street` / `major` /
  `route` / `completed` / `stop`, labels ≤ 4, separate heading message, stop ribbon
  body. Logical activity collection of 8 (one per plugin) with 2 ambient slots,
  negotiated end-to-end: both the canonical phone store and the glasses store evict
  at two today (`ActivitySurfaceContract.kt`, `PhoneActivityState.kt`,
  `ActivityController.kt`), so capacity is announced by both hubs with explicit
  downgrade and reconnect behaviour; old peers keep 2. More than two live activities
  add an `Activities · n` root stop instead of hiding them.
- **Notifications**: the shipped notice window is untouched (plugins change nothing); the
  current notice is also consultable from the session; history (proposed 30
  entries or 24 h, off switch) added later; a stored entry never revives an expired
  reply token; late reply requires a provider-revalidated message reference and a fresh
  session. Every notice action from the session is confirmed (Cancel preselected).
- **Capabilities**: hub-to-hub versions `pageSessionVersion`,
  `inkPageProfileVersion` and activity capacity are negotiated between the two hubs;
  provider opt-in is **additive descriptor metadata**, never a new capability string,
  because unknown capability values invalidate a descriptor today and there is no
  optional/required distinction (`PluginDescriptor.kt`, `PluginCapability.kt`).
  Template pages require the `surfaces` capability, authored Ink bodies require
  `ink_surface`; API v3 and the old-plugin immersion entry points are preserved. Mixed
  hub pairs and unchanged plugins are tested explicitly.
- **Link loss mid-page**: leases and pending actions are invalidated, the page
  becomes inert "Unavailable", local snapshots and BACK survive, the session never
  closes by itself, no action is replayed on reconnect. SPP loss is distinguished from
  total disconnection; reconnection requires fresh authorization; an Ink session
  closes on link loss as today, so restoration promises apply only to a still-live
  base.

## 6. Deliveries

| PR | Content | Exit condition |
|---|---|---|
| 1 — Interaction contract + pages v1 | BUSSPEC/SDK text for base / session / frames, gate, gesture owner, page request/response, timeouts, compat; an isolated contract reducer with pure tests of the transitions (late page after BACK, single action per gesture, notice arriving during a selection, armed notice vs triple tap) | Spec merged, reducer and tests green in isolation, runtime behaviour disabled |
| 2 — HUD core + provider binding | Selective import of the fork's reducer/effects/runner/host with attribution, deletions not imported: upstream `LauncherOverlayRenderer` is retained behind one mutually exclusive backend switch (the fork routes all raw input through `HudController` and hooks notices before normalisation); switching detaches windows and cancels pending gestures and bindings; page host separate from `AppLayer`; `beneath` preserved; no `open()` to read a page; native apps and upstream protections kept | Both hub suites green, old launcher reachable behind the switch |
| 3 — Shippable stack | Root, typed pages, loading/error states, stable selection, current notice, mixed Applications, third-party plugins as labelled immersions; foreground resolver, per-app ambient modes and curated defaults; ambient budgets, notice windows unchanged, islands passive everywhere; Navigation text page + Relay reading as first providers | From Teleprompter: triple tap → Navigation detail → back to the same card → Relay notice → thread → back to Teleprompter intact; a Nexus surface underneath receives no `PLUGIN_CLOSE(switch)` |
| 4 — Notification centre + composer | Bounded history, fresh-reply validation, suspendable hub composer, Relay/Agents/Assistant adaptation, activity collection of 8 | Reply from the session without replacing another surface |
| 5 — Preferences, media, map | Text/Minimap/Panel per owner and the phone editor for the per-app ambient mode (Residents / Signals only / Nothing) with phone sync, page image assets, Navigation vector widget v2 (streets, heading-up, labels, ribbon), route preview with scale and zoom, OSM street source on the phone | Device-validated readability walking |

Estimates (Astra, person-days, not calendar, provisional): first slice (PR1–3) 16–26
for text and reading pages only; the full scope is re-estimated after PR1 rather than
carried as "26–41 plus pages"; Rokid HOME replacement rejected as a prerequisite (2–4
days of device feasibility, then 45–75 if the firmware cooperates).

No device phase before PR1: the touchpad traces are already documented (contact
scancode 204 → KEYCODE_NOTIFICATION, tap 28 → ENTER, double tap 158 → BACK, swipes
103/105/106/108, one-finger hold 148 → Rokid AI, two-finger 149 → native AI; see the
R08 Access Bridge README and the Nexus input notes) and the native-app input leak is
read from `ActivityController.claimsInput()`. The only untraced gesture is the
two-finger swipe (firmware brightness/volume), which the filter passes through and
which has no design impact. Recipe for later checks: `sendevent` on
`/dev/input/event1`; `adb shell input keyevent` does not traverse the accessibility
filter.

## 7. Open questions for the owner

1. Relay root card: short state ("Mika · reply expected") as mocked, or the last
   message in clear.
2. Reply from the session in PR4: phone keyboard and dictation only, or also a
   suggested-replies list from the provider.
3. Whether the Applications page offers the grid as an optional presentation later.
4. Whether to approach alvarosw before PR2 (credit, co-review, single registry).
