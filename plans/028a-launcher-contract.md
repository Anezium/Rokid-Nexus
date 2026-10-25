# Plan 028a — Frozen implementation contract for the launcher completion

Status: frozen by the implementation lead on 2026-10-10 before lane fan-out. It turns
plans/028-launcher-completion.md and docs/LAUNCHER_INTERACTIVE_ACTIVITIES.md into one wire,
SDK and ownership shape that every lane codes against. BUSSPEC.md and docs/PLUGIN_SDK.md
remain the published authorities: the contract lane writes them from this file, and later
lanes keep them true. A lane that finds this file wrong stops and reports; it does not
silently diverge.

Precedence: owner direction > plan 028 + interactive-activities doc + r17 mockup > this file >
plan 027. Delivered PR1-PR3a safeguards (owner stamping, revisions, leases, tombstones, frozen
root membership, retained-body unavailable states, no replay) are never relaxed.

## 0. Global decisions

D0.1 Negotiation. One additive launcher bundle, never a bump of an existing version:
- `BusCapabilityBits.LAUNCHER_INTERACTIVE = 1 shl 16` (amended 2026-10-23: bits 13–15 shipped first in Nexus 1.8 as the
  `/core/files` explorer, lane and hotspot bits; rendered maps moved to `1 shl 17`).
- `launcherInteractiveVersion: Int` (absent = 0) in both `GlassesHubCapabilities` and
  `PhoneHubCapabilities`; current value 1. Negotiated version = `min(local, remote)` when both
  are >= 1, else 0. Unlike the older exact-match fields, every future version must be a
  superset of the previous one. `LauncherInteractiveContract` (shared) holds the constant and
  the `negotiated(local, remote)` helper.
- Everything new in this file is active only when the negotiated version is >= 1 between the
  two hubs. Plugins learn it through the plugin-visible bit `LAUNCHER_INTERACTIVE` (exposed
  like `ACTIVITY_EXTRAS`, only when both hubs negotiated it) and through
  `launcherInteractiveVersion` on the owner-scoped `/system/hub/capabilities` provider message.
- `pageSessionVersion`, `activitySurfaceVersion`, `NoticeSurfaceContract.VERSION` and every
  existing exact-match field stay unchanged.

D0.2 Old peers. New fields are optional everywhere. A new phone strips every new activity,
notice and page field before forwarding to glasses that did not negotiate the bundle, and keeps
the v1 behaviours (eviction at 2, end on disconnect, every page action confirmed). New glasses
never act on a new field unless the phone negotiated the bundle. Old plugins keep working
unchanged; nothing new is required of them.

D0.3 HUD copy is English, like the shipped session chrome. The r17 mockup's French strings are
presentation for the owner; section 9 maps them. No localization infrastructure in this task.

D0.4 Trusted routes. New hub-to-hub controls live under `/core/` (reserved root: plugins can
never send or receive them, no capability unlocks them): `/core/compose/*`, `/core/ambient/*`,
`/core/policy/quick-actions`, `/core/notice/purge`. Never expose them as plugin capabilities.

D0.5 No new `PluginCapability` value (it would invalidate descriptors on older hubs and reset
grants). Plugin opt-ins are descriptor metadata keys, ignored by older parsers.

## 1. Activities (shared `ActivitySurfaceContract`, SDK `NexusActivity`)

1.1 New optional plugin fields on start/update (patch semantics like the rest):
- `destination`: `{ "pageId": String, "params"?: Object }`. `pageId` must satisfy
  `PageTemplateContract.isProviderPageId(pageId, owner)`; params is a JSON object of at most
  `PageSurfaceContract.MAX_PARAMS_BYTES` (2048 B) under the existing nesting/number rules. The
  owner check runs where the owner is authenticated: phone `PhoneActivityState` (authoritative)
  and glasses `ActivityController` (defence in depth).
- `activation`: `"destination" | "commands"`. Default when absent: destination present ->
  `destination`; else at least one action -> `commands`; else no activation (the card opens the
  hub summary `nexus:activity:<owner>`). An explicit mode without its prerequisite is
  `INVALID_ACTIVITY`.
- `defaultActionId`: must name one of the actions; initial focus in commands mode. Default =
  first action.
- `persistence`: `"session" | "ongoing"`, default `session`. `ongoing` declares a user-enabled
  standing entry (the plugin's own "Show in activities" setting is on): it survives owner
  disconnect as an inert offline snapshot (section 1.4).
- Each action gains optional `typed` (section 3.1). Allowed for activities: `media.previous`,
  `media.play`, `media.pause`, `media.next`. Any other typed value on an activity action is
  `INVALID_ACTIVITY`.
- Interactive id rule, enforced only when any of the new fields is present: every action id
  satisfies `PageSurfaceContract.isValidId` and ids are unique. Old payloads keep their
  existing permissive rules (NexusActivityTest pins that).

1.2 Hub-stamped fields (phone -> glasses only; the phone deletes any plugin-supplied copy and
writes its own, like `ownerPluginId` and `seq`):
- `state`: `"live" | "offline"`.
- `userPinned`: Boolean (section 1.5).
- `knownAtWallMs`: Long, wall-clock time the content was last confirmed live (for "last known
  HH:MM" on offline entries).
- per action `hubQuick`: Boolean (section 3.2).

1.3 Collection versus ambient slots.
- `MAX_AMBIENT_ACTIVITIES = 2` (corner slots/islands, unchanged), `MAX_COLLECTION_ACTIVITIES = 8`
  (logical entries, one per owner). `MAX_ACTIVE_ACTIVITIES` stays 2 as the v1 floor alias.
- Effective collection capacity = 8 when the bundle is negotiated, else 2. The phone is
  authoritative: on a capacity drop (re-announce without the bundle) it trims to 2
  deterministically and sends `closed(replaced)` to evicted live owners BEFORE resending.
- Eviction when a new owner starts at capacity, first match wins: offline entries (oldest
  `knownAtWallMs`), then unpinned live non-primary (least recently updated), then unpinned
  primary, then pinned (least recently updated). Evicted live owners get `closed(replaced)`.
- Glasses: the collection holds up to the capacity; only the primary plus one other get corner
  islands (`allocateActivityCorners` unchanged). Offline entries never get an island.
- Ordering (glasses, deterministic): userPinned first; then live before offline; then primary;
  then latest significant update; then earliest start; then owner id. Root promotes at most 2
  (`VISIBLE_ACTIVITY_STOPS`), membership frozen per session as today; the Activities page lists
  them all.

1.4 Offline snapshots and lifetime.
- On owner disconnect (last registration leaves, not revoke/disable/uninstall), an entry with
  `persistence=ongoing` or `userPinned` becomes `state=offline`: content kept, actions dropped,
  `destination` kept, new `seq` (so every old revision is stale), no corner island. Other
  entries end as today.
- Offline entries persist in a bounded phone store (<= 8, JSON, own prefs file) and are
  restored as offline after a hub restart if the grant is still Approved with `surfaces`.
- An offline entry's destination stays openable: opening a page is navigation, and the page
  broker's cold bind revalidates grant and registration. Commands are inert while offline.
- Revival only through a fresh `/activity/start` from a new approved registration; the entry
  becomes live with a new seq. No service is held alive to keep an entry.
- Removal: owner `end`, the user's hub disable/unpin of a non-ongoing offline entry, grant
  revoke, plugin disable, uninstall, signer/id change. Removal is prompt and also clears the
  persisted snapshot.

1.5 User preferences (phone, `PhoneActivityPreferences`, keyed by `PluginGrantKey`, own store,
separate from grants and live state; purged with the grant):
- `enabled` (default true). When false the phone rejects that owner's starts with
  `ACTIVITY_DISABLED` and ends its current entry.
- `pinned` (default false). Stamped as `userPinned`; protected from eviction before unpinned
  entries; retained offline.
- Edited in the phone plugin page (PluginPermissionsActivity) without going through
  `applyDecision`/`authorizationChanged` (that path tears the plugin down). A change re-sends the
  affected entry.

1.6 Activity commands.
- Glasses -> phone `/activity/action` gains `invocationId` (fresh, `isValidId`), `seq` (the
  visible revision) and `mode: "confirmed" | "quick"`. With the bundle negotiated the phone
  requires all three and checks: owner live and single registration, entry live (not offline),
  `seq` equals the canonical seq, action still declared, `mode=quick` only if the action carries
  a quick-eligible `typed` value and the owner's quick policy approves its family now, one
  pending command per owner, 8 s deadline. Then it delivers
  `{activityId, id, invocationId, seq, typed?}` to the owner (the `typed` value comes from the
  phone's canonical copy, never from the glasses).
- Without the bundle (old glasses) the phone keeps the legacy `{activityId, id}` delivery.
- New plugin -> phone path `/activity/result {activityId, invocationId, status, message?}`;
  status `done | rejected | stale`; message <= 64 chars. Owner-scoped, requires `surfaces`. The
  phone forwards it to the glasses that hold the pending invocation; late or unknown results are
  dropped. A lost result is shown as unconfirmed by the glasses and never resent.
- SDK: `NexusActivityCommand(activityId, actionId, invocationId, seq, typed)` delivered to
  `onActivityCommand(command): NexusActivityCommandResult?` (callbacks) /
  `onNexusActivityCommand` (service). Returning null sends no result. The default implementation
  calls the legacy `onActivityAction(id)` and returns null: the glasses then show unconfirmed,
  never an invented success.

1.7 Route geometry for activities (navigation).
- New plugin -> phone -> glasses paths `/activity/geometry` and `/activity/heading`,
  owner-scoped, requiring `surfaces`, only with the bundle. Geometry: `{revision, geometry}` with
  the section 4 schema; latest wins; data plane only (the phone drops it when only CXR control
  is up — never queue it, never make text guidance depend on it); cleared when the activity
  ends or goes offline. Heading: `{deg 0..<360, accuracyDeg?, atWallMs}`, at most 5/s per
  owner. The glasses smooth heading and treat a heading older than 3 s as absent.

## 2. Pages (shared `PageSurfaceContract`/`PageTemplateContract`, SDK `PageModels`)

2.1 New bodies, decoded only when the bundle is negotiated (`supportedTemplates(extended)`);
otherwise they stay reserved and the glasses show "needs a newer Nexus". Each gets an explicit
`decode`/`encode` branch (never the catch-all) and phone-side typed validation once supported.
`ink` stays reserved.
- `conversation`: `{ threadKey: String<=128 printable, caption?: String<=64,
  messages: [{ id: String<=128, role: "received"|"ours"|"unknown", sender?: String<=48,
  text: String<=2000, status?: "handed_off"|"unconfirmed" }] (<=64, oldest first),
  composer?: { recipient: String<=48, sendActionId: String, maxChars: Int 1..1000,
  replyable: Boolean, unavailableText?: String<=64, unavailableSub?: String<=64 } }`.
  `sendActionId` must name a page action whose `typed` is `compose.send`. Sides come only from
  `role`; `unknown` gets no side. Capture time lives in `caption` (one per page); no per-message
  times, no read/delivery marks.
- `media`: `{ title: String<=120, artist?: String<=120, album?: String<=120, source?: String<=48,
  state: "playing"|"paused"|"stopped"|"none"|"unavailable", status?: String<=120,
  controls: [actionId] (<=3, swipe order), defaultControl?: actionId }`. Control ids must name
  page actions typed `media.*`.
- `route`: `{ maneuver?: { glyph: String<=32, distance: String<=16, street?: String<=64,
  eta?: String<=32 }, facts: [PageFact] (<=6), follow?: Geometry, overview?: Geometry,
  ribbon?: { line?: String<=16, stops: [{ name: String<=32, time?: String<=8,
  state: "passed"|"upcoming"|"alight" }] (<=16), between?: { after: Int, fraction: 0..1 },
  note?: String<=160 }, position: "live"|"stale"|"none", fixAgeS?: Int }`. Total page bytes stay
  <= 64 KiB.

2.2 Rows: `PageRow.checked: Boolean?` (optional; draws a checkbox mark; meant for rows whose
target is an action typed `setChecked`).

2.3 Actions: `PageSurfaceAction.typed?` (section 3.1) and phone-stamped `hubQuick`. Unknown
`typed.type` is ignored (the action is an ordinary confirmed plugin action). Action targets
still carry no params (old hubs reject them).

2.4 Invocations (`/page/action`, glasses -> phone -> provider) gain `mode: "confirmed" |
"quick" | "compose"` (absent = confirmed) and `text` (compose only, 1..`maxChars` UTF-16, at
most 1000). The phone keeps the full action map per frame (not ids only) and checks: `quick`
only for a quick-eligible typed action whose family the owner's policy approves now; `compose`
only for an action typed `compose.send` on a `conversation` page whose composer names it, with
non-blank text within limits; `confirmed` never for `compose.send` (a send is only reachable
through the composer). All existing checks stay (negotiated, frame, owner, epoch, shown
revision, declared, one pending, lease, deadline, re-check before delivery). The provider
receives `mode`, `text` and the phone's own copy of `typed`.

2.5 Results gain optional `code` (printable ASCII <= 32). The hub interprets only
`authority_lost` (composer: reply authority is gone; keep the text, stop offering Send for it).

2.6 SDK: `NexusPageActionSpec.typed`, `NexusPageAction.mode/text/typed`,
`NexusPageResult.code` (helper `rejected(code = NexusPageResult.CODE_AUTHORITY_LOST)`),
`NexusPage.Content` accepts the new bodies; `NexusPluginClient.supportsLauncherInteractive()`.
Providers must fall back to the base four templates when it is false.

## 3. Typed actions and the quick-action policy

3.1 Hub vocabulary (`PageActionType`, shared; SDK `NexusActionType`):
- `{"type":"setChecked","checked":Boolean}` — family `checklist`, quick-eligible. The desired
  state is explicit; never a toggle.
- `{"type":"media.previous"|"media.play"|"media.pause"|"media.next"}` — family `media`,
  quick-eligible. Play and pause are separate desired states bound to the visible revision.
- `{"type":"compose.send"}` — never quick; only the composer's deliberate Send reaches it.

3.2 Policy (phone authoritative, `PluginQuickActionPolicyStore`, keyed by `PluginGrantKey`):
- A plugin declares families in descriptor metadata `rokidbus.plugin.quickActions`
  (comma-separated; known: `checklist`, `media`; unknown ignored). Not a capability.
- The user approves families per plugin in the phone plugin page with honest copy: one tap runs
  these actions without a confirmation; Nexus checks owner, revision and type but cannot verify
  what the plugin's code does. Default: not approved. A newly declared family is never
  grandfathered. Revoke, disable, uninstall or signer change clears it. Toggling does not use the
  grant teardown path; turning it off immediately rejects pending quick invocations.
- The phone stamps `hubQuick: true` on a page or activity action only when its typed value is
  quick-eligible and the family is approved, and deletes any plugin-supplied `hubQuick`. It
  also sends `/core/policy/quick-actions {revision, approvals: {pluginId: [family]}}` to the
  glasses on link-up and on every change.
- Glasses execute directly only if: bundle negotiated, `hubQuick` stamped, the latest
  `/core/policy/quick-actions` approves the family for the owner, and the fresh-contact rule
  (section 5.3) holds. Otherwise the existing confirm panel (Cancel preselected) runs.
  The phone re-checks at invocation; the glasses check is never the only gate.
- Sends, deletes, external effects, untyped actions, immersion and full-app launches keep their
  explicit gates. No plugin field can make itself quick.

## 4. Route geometry schema (shared `RouteGeometryContract`, SDK model)

`{ spanMeters: Number (0 < x <= 50000, metres represented by 1000 units),
  paths: [{ role: "street"|"major"|"route"|"completed"|"stop", points: [Int] (x0,y0,x1,y1,...,
  each 0..1000, >= 2 points) }] (<= 24 paths, <= 1024 points in total),
  labels?: [{ text: String<=24, x, y, priority: 0..3 }] (<= 4),
  position?: { x, y, quality: "live"|"stale"|"none", fixAgeS?: Int },
  turn?: { x, y }, destination?: { x, y }, stops?: [{ x, y, label: String<=4 }] (<= 4) }`.
The frame is north-up in a local metric projection; the hub rotates for heading-up views.
Serialized size <= 16 KiB. Providers clip with Liang-Barsky (no vertex clamping) to a window that
covers the rotated viewport. Degraded position: `stale` keeps the last fix with its age, `none`
has no position; the hub then draws north-up, a dashed route, a dot without arrow and prefixes
distances with "~".

## 5. Glasses session behaviour (reducer/input; see the interactive-activities doc §2)

5.1 Activity cards: destination-first opens the destination page directly (params passed,
no detail page, no onNexusOpen/onNexusClose, no change underneath). Commands-first: first tap
enters command focus on `defaultActionId`, never executes. Focus mode: Step moves along plugin
commands then a hub-owned `Open app` slot (shown when the owner is launchable in the
Applications catalog); Enter executes the focused one (quick or through the confirm panel per
section 3); Back leaves focus first. Offline entries: destination still opens; commands inert;
Open app still available. Activities page lists every collection entry with the same
behaviours.

5.2 `Open app` is hub-owned, outside the three plugin slots, and always goes through the
existing labelled launch step. Destination pages opened from an activity get a final hub row
`Open app` for a launchable owner.

5.3 Fresh-contact rule. An Enter that would cause an effect (quick or confirmed plugin action,
activity command, composer Send, launch Open) is accepted only if its press began strictly
after the focused target was displayed. Displayed = the host's draw acknowledgement of the frame
that showed that focus (a new revision, a new selection, a panel, or a focus mode each reset
it). Press start = the touchpad contact-down time paired with that Enter; for the ring, the ring
tap's down time. A rejected Enter is absorbed silently.

5.4 Capture: while a composer capture runs (dictation or typing), triple tap is not recognised
(InputArbiter gains `captureActive`, alongside `editableFocused` and `noticeArmed`), and the
session never opens on top of it.

5.5 Full map: Enter on the compact map opens a chrome-less full-map mode; Step switches views
(Follow heading-up / Whole route north-up, two views, no wrap); Enter opens the hub commands;
Back returns to the page with the map selected. The chosen view is kept for the session and
never written to the ambient size preference.

## 6. Notifications (notice wire, history, root)

6.1 Notice fields (with the bundle; the phone strips them otherwise): `destination
{pageId, params?}` (owner-namespace provider page, owner-validated on the phone), `eventKey`
(owner-scoped opaque id, printable ASCII <= 128), `conversation: Boolean` (the event is a message
in the thread at `destination`). A conversation provider uses the same value for `eventKey` and
the conversation `threadKey`.

6.2 `/notice/withdraw {eventKey}` plugin -> phone (owner-scoped, `surfaces`); the phone stamps
the owner and forwards `{ownerPluginId, eventKey}` to the glasses. Meaning: the event is no
longer on the phone. It is not proof of read or delivery. `/core/notice/purge {ownerPluginId}`
phone -> glasses on revoke, disable, uninstall or signer change.

6.3 Glasses history: in memory, never persisted, newest first, at most 30 entries and 24 h
display age (a display bound, not a reply lease). Entry: owner, eventKey?, destination?,
conversation flag, label, text as shown on the band (owner redaction and hide choices
preserved), arrivedAt, local state `new | seen | handed_off | withdrawn`. Withdraw marks
matching entries withdrawn; purge removes the owner's entries; link loss keeps entries but they
are read-only until the phone is back.

6.4 Root order (<= 6 stops, frozen per session): up to 2 activity cards, then one notification
stop, then `Activities · n` when the collection has more than the promoted cards, then
`Notifications · n`, then `Applications`. The notification stop: if the newest live conversation
entries come from more than one thread of one owner, an aggregate card (`3 conversations`,
names) opening the owner's main page; else a conversation card for the newest conversation
entry (opens its exact `destination` directly; identity never swaps under focus; resolves in
place to handed-off or removed); else the generic preview of the newest entry. A conversation
card shows the entry's title over its message; when every line of the message starts
`<title>: ` (a one-to-one thread) the card drops that prefix, and a group's lines keep their
senders. The armed band (NoticeController) is unchanged.

## 7. Composer (glasses-owned, generic, used by conversation pages)

7.1 One global draft in glasses hub memory: `{ownerPluginId, threadKey, destination, recipient,
text, state: ready | unconfirmed | authorityLost}`. It survives root, Back and session close; it
is replaced only when a new capture completes (another recipient shows the hint `replaces the
draft for <recipient>` before completion); cancel restores the previous text; empty text
committed from Type is the only explicit discard. Never persisted to disk.

7.2 Flow (one conversation screen, Dictate first): reading -> tap -> focus (Dictate; Send when a
ready draft for this thread exists; Type after an unconfirmed send or an unavailable mic) ->
Step along the inline buttons (no wrap) -> Enter starts the capture or sends -> capture ends on
tap (draft, back to reading, nothing sent) or Back (cancelled, old draft kept). Send needs a new
focus and the fresh-contact rule. Results: `done` -> bubble `handed to the app`, draft cleared;
the sent text stays on the frame it was sent from, through revisions computed before the provider
recorded it or bringing other messages, until that page carries it (it shows more ours messages in
that text, counted from the oldest message shown when it was sent that is still there, than it
showed then plus the earlier identical replies still waiting; ids and order never enter the count,
so a reply is never hidden before a copy is shown for it, and a provider dropping earlier copies
can only leave it drawn twice until the frame ends); it ends with that frame, an
answer from another provider registration, or the owner's purge or invalidation, and a page
answered later says what the provider knows;
`authority_lost` code or the composer's `replyable=false` -> text kept, Send withheld,
`Reply unavailable here`; `stale` -> draft kept, new Send needed after the new revision is shown;
no result in time -> `unconfirmed · check on the phone`, text kept, next focus on Type; never an
automatic retry or resend on reconnect.

7.3 Capture engines are hub-owned, trusted, and need no plugin capability:
- Dictate: glasses -> phone `/core/compose/dictate/start {captureId, language?}`, phone runs the
  hub STT on the glasses PCM only (no phone-microphone fallback), sends `/core/compose/dictate/partial
  {captureId, text}` and `/core/compose/dictate/final {captureId, text?, reason}` (reason
  `completed | cancelled | no_speech | busy | mic_unavailable | link_lost | error`);
  `/core/compose/dictate/stop {captureId, cancel}` finishes (`cancel=false`) or cancels. Any
  non-completed reason shows `Glasses mic unavailable` / `use Type` and focuses Type.
- Type: glasses -> phone `/core/compose/type/start {captureId, initialText, maxChars}`; the phone
  opens its keyboard prompt (RemoteKeyboardPrompt behaviour: direct if possible, else heads-up);
  `/core/compose/type/text {captureId, text}` mirrors the field; `/core/compose/type/done
  {captureId, text?, committed}` ends it; glasses `/core/compose/type/finish {captureId}` (tap)
  or `/core/compose/type/cancel {captureId}` (Back). Typing never starts the glasses mic.
- Link loss during a capture cancels it and keeps the previous draft.

7.4 Authority: Send is offered only while the current page is a live conversation whose
`threadKey` and owner equal the draft's and whose composer is `replyable`. Pure link loss keeps
the draft as is; after return a fresh page and a new deliberate Send are required. Lost authority
(withdrawn event for the draft's thread, `authority_lost`, `replyable=false`, page gone) keeps
the text but never sends it as is; when a new replyable notice for the same owner and thread
arrives, Type reopens prefilled as a new capture, then OK, then a new Send. Never another
recipient, never a guess.

## 8. Navigation, ambient preferences, backend

Owner correction, 2026-10-18: the user-facing size choice below is currently for
Navigation only. Other plugins retain their developer-defined presentation; no
universal per-plugin Text/Minimap/Panel editor is offered. Previously saved
non-Navigation overrides remain stored for compatibility but do not override
their rendering. The existing owner-scoped wire format is unchanged.

8.1 Per-owner ambient size `text | minimap | panel` (absent = today's automatic behaviour).
The phone is authoritative and stores it; glasses edits (display page) apply locally and are
sent with `/core/ambient/sizes/set {ownerPluginId, size|null}`; the phone broadcasts the full map
`/core/ambient/sizes {revision, sizes}` on link-up and change; offline glasses edits are queued
(latest per owner) and sent before the phone snapshot is applied. The full map never writes it.
The display page offers Minimap and Panel only for a route with geometry, its page's or its
owner's live `/activity/geometry`. Without one (guidance read from another app's notification,
such as Google Maps or Citymapper) they show unavailable, `no map for this route`, swipe holds
on Text and they cannot be saved; a saved map size stays marked and the page opens on Text.

8.2 Foreground policy: a glasses `ForegroundResolver` (accessibility window events, Nexus
overlays and IMEs excluded) splits home from native apps. Per-app mode `residents | signals |
nothing` (unknown app -> `signals`; home -> `residents`), edited on the phone Native apps page and
sent as `/core/ambient/policy {revision, modes: {package: mode}}`. In an app the hub caps islands
at `text`. Islands stay passive; native apps and surfaces keep their input.

8.3 Launcher backend: `PhoneHubCapabilities.launcherBackend` = `"session" | "legacy"` when the
user chose explicitly on the phone (absent = no opinion). Glasses precedence: phone explicit >
glasses stored `launcher.backend` > default. The default is SESSION when the phone's last
announcement negotiated the bundle (the persisted announced version, never the live link) and
LEGACY otherwise; LEGACY stays the rollback and the fallback for old phones. No settings reset.
The exported backend receivers require `android.permission.DUMP` (adb shell keeps it).
Delivered: the acceptance flip (`LauncherBackend.BUNDLE_DEFAULT = SESSION`) landed after the
independent reviews of 2026-10-12; see docs/LAUNCHER_COMPLETION_REPORT.md.

## 9. English HUD copy for the r17 French strings (lanes use these exactly)

Relay/composer: `Dictate`, `Type`, `Send`; reading `tap reply` / `back root`; multipage
`swipe page · tap reply`; focus `swipe choose · tap start` / `back reading`; typing line
`Phone keyboard` / `typing`, hints `tap finish` / `back cancel typing`; dictation line
`Glasses mic` / `listening`, hints `tap finish` / `back cancel dictation`; mic unavailable
`Glasses mic unavailable` / `use Type`; draft meta `draft · not sent yet`, back
`back root · draft kept`; send focus `swipe choose · tap send to <recipient>`; handed off
`handed to the app`; unconfirmed `unconfirmed · check on the phone`; unavailable line
`Reply unavailable here` / `reply from the phone`, draft meta `not sent · can't be sent from here`;
replacement hint `replaces the draft for <recipient>`; header `<SOURCE> · <RECIPIENT>` (one form).
Root: conversation card labels `<SOURCE> · UNANSWERED`, `<SOURCE> · REPLY HANDED OFF`,
`<SOURCE> · NOTIFICATION REMOVED`; tertiary `captured HH:MM`, `handed to the app · delivery not
confirmed`, `removed from the phone · nothing is proven`; aggregate `<n> conversations`; generic
`NOTIFICATION · <SOURCE>`. Notifications page tags `SEEN IN NEXUS`, `HANDED OFF`, `REMOVED`;
ages `2 min`, `1 h`. Command focus hint `swipe choose · tap run` / `back cards`; hub slot
`Open app`; offline hint `offline · last known HH:MM`. Navigation: `MAP · FOLLOW` /
`MAP · WHOLE ROUTE`, `Commands`, `View · Follow`, `View · Whole route`, `Full screen`,
`Display · <size>`, sizes `Text`, `Minimap`, `Panel`, unavailable size `no map for this route`,
`GPS lost · last fix <n> s`.

## 10. Lane ownership (one owner per file; integration is serialized)

- A0 contract: shared contracts/constants/PathRules/descriptor metadata, bus-client SDK,
  glasses screen-model types (`session/SessionScreens.kt` data types only), BUSSPEC.md and
  docs/PLUGIN_SDK.md sections, their tests.
- P1 phone activities/pages/policy, then P2 phone notices/compose/ambient/editors: all
  `phone-hub/**` (BusHubService.kt single owner per phase).
- G1 glasses activities/cards/focus/quick/fresh-contact/collection, then G2 notifications/
  history/composer/conversation, then G3 navigation/ambient/foreground/backend: glasses logic
  (reducer, state, input, host wiring, stores).
- GR glasses rendering: `glasses-hub/.../page/**`, island views, icons; UI authored by Opus.
- S sample todo, M Media Deck, R Relay, N Navigation: their plugin directories only.
- Docs changes after A0 go through the integrator; plugins/AGENTS.md gains the sanctioned
  exceptions (user-enabled ongoing activities, Media Deck activity monitoring, Navigation
  walking-journey location service).
