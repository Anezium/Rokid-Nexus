# Launcher completion (plan 028) — implementation report

Date: 2026-10-12, amended 2026-10-17 after GPT-6 Astra's independent review (section 2). Branch `dev/launcher-complete` (local; integration checkout `dev/launcher-rework`
fast-forwarded to the same commit). Base `2d08f0a3` (approved plan 028, r17 mockup). Not pushed,
released or tagged. The later [2026-10-18 device smoke](LAUNCHER_DEVICE_SMOKE_20261018.md)
records signed in-place installs, three hardware-discovered fixes, targeted independent
reviews, and the precise subset of hardware acceptance now observed.

The [2026-10-25 freezer fix report](LAUNCHER_FREEZE_FIX_20261025.md) records
SDK session-foreground protection for launcher pages and delivered commands,
fresh binds before command/action delivery, and bounded independent hub holds.
The round-1 binding-only patch was insufficient per the independent review and
device history in `0404260e`. Round 3 also covers recovered page-bind release,
main-thread foreground refresh and deferred plugin stop requests. S23 confirmation,
including whether a fresh connection thaws an already-frozen plugin, remains
with the orchestrator.

**Status.** The whole approved scope of plans 028/028a is implemented in source, integrated, covered
by unit and Robolectric tests, and was accepted by the Sol review rounds (last: ACCEPT WITH ONE
NONBLOCKING FIXTURE NIT, since fixed). A later independent review by GPT-6 Astra found four blockers
and one should-fix (two of them LEGACY regressions); all five are fixed below and await a fresh
independent review. The acceptance flip is applied: the new launcher is the default
when the phone's last capabilities announcement negotiated the launcher bundle. **Hardware acceptance
is a remaining shipping gate**. The historical evidence below predates device work;
use the linked smoke report for current device results and remaining matrix gaps.

Authorities: `plans/028-launcher-completion.md` (scope), `docs/LAUNCHER_INTERACTIVE_ACTIVITIES.md`
(product decisions), `plans/028a-launcher-contract.md` (frozen implementation contract),
`BUSSPEC.md` (wire), `docs/PLUGIN_SDK.md` (typed API), r17 mockup (`docs/mockups/glasses-home/`).

## Current status (2026-10-25)

This section supersedes the status paragraphs above, which are kept as history.

- **Dense Navigation overview (2026-10-27, `fx/nav-overview-streets-r2`).**
  The public Paris reproduction's four tiles contain 46,828 vertices, exceeding
  the unchanged 16,000-vertex preparation cap. The decoder now also prepares
  spatially spread detail for each possible cover size; an overflowing view uses
  those bounded subsets instead of dropping every street. Demand and drawing
  keep the same tile keys and zoom, with unchanged render authority. Streets
  can use the optional label reserve when otherwise no street would fit the byte
  budget. The offline four-tile reproduction now draws 23 street paths with
  15,998 preparation vertices; release-device confirmation remains with the parent.

- **Navigation map acknowledgment startup (2026-10-27, `fx/nav-overview-streets`).**
  The publication gate now waits for the first draw acknowledgment too, rather
  than pacing only maps with an existing rendered lease. A five-second quiet
  window coalesces GPS changes before that acknowledgment and after lease loss;
  feedback releases the latest state immediately, while a missing report permits
  a deadline retry for Text/Commands/hidden maps. The phone still rejects every
  positive report superseded by a newer page or geometry revision. No hub wire
  fields, SDK types or network authorization rules change. Cold-start delayed
  Follow/Overview and ambient regressions complement the earlier seeded-lease
  tests; device verification and hub-suite execution remain with the parent.

- **Immediate launcher actions (2026-10-25, `fx/noconfirm`).** One fresh tap on a focused
  activity command, plugin page row or Open app now executes immediately. The confirmation and
  launch panels, their screens and their focus/step handling are removed; the route Display
  page stays. Quick eligibility still selects quick wire mode; every other ordinary action uses
  confirmed mode. Provider epoch, shown revision, pending-command, offline and link-loss guards
  stay in force. Activities command focus now includes the live sequence in its draw token, so
  an update needs a redraw and a new press before it can act. Plugin access drops the two
  quick-action family switches without changing stored approvals, their wire or phone validation.
  Old-phone page actions invoke on a fresh tap in confirmed mode, bound to the drawn revision.
  Native apps and Camera in Applications also require a fresh press. Composer Send and its
  visible-draft fresh-press rule are unchanged. Device verification belongs to the integrating orchestrator;
  this lane performs no install or ADB action.

- **Integrated with main.** `origin/main` at `a66f55e1` (Nexus 1.8.1, Relay 1.3.0, the
  `/core/files` explorer, independent speech languages) is merged into `dev/launcher-rework`. The
  launcher bundle moved to feature bit 16 and rendered maps to bit 17, because bits 13-15 shipped
  first in 1.8 as the files, lane and hotspot bits. Relay was merged by meaning: per-app policies,
  read-only alerts, echo suppression, the final-send policy check, pending eviction and the redacted
  image gate sit beside thread pages, handoffs and withdrawals.
- **Integrated with main again** at `15cd98ad` (CXR-L 1.1.2 scenes, CxrGlobal 0.3.0): the hub
  stop path clears the server-closed flag together with the CXR state and the rendered-map
  negotiation under `sppLock`. Builds need `../CxrGlobal` at `v0.3.0`, as CI checks out.
- **Fixed after three fresh independent reviews** (GPT-6.1 Sol, GPT-6 Astra, Claude Fable 5.1, all
  on `d005b069`): the 24-hour display bound now holds on frozen roots, covered roots, aggregates,
  named notice pages and after a wake; explicit LEGACY no longer selects guidance islands from live
  geometry; a newer revision of unchanged route geometry rebinds its rendered-map context;
  foreground window walks are coalesced per burst; a plugin can ask the glasses to forget every
  notification it showed (`/notice/forget`, a durable owner purge), which Relay does when an app is
  turned Off, Relay is switched off or its notification access is revoked; the owner's station and stop names were removed from device notes and fixtures.
- **Fixed after the second round** (Sol and Astra on `48c3f7fb`): notice pages and the
  Notifications list already open follow the 24-hour bound too, resolved aggregates keep only the
  names still within it, the per-event forget was replaced by the owner-scoped durable one above,
  and a read-only alert reposted as excluded stops being active.
- **Fixed after the third round** (Sol, Astra and Fable on `b4ef4623`): Relay decides a forget
  from the settings change itself (Relay off, or a source moved to a stricter policy), so a
  listener Android disconnected with access kept, or a restart, no longer hides it; the request
  stays owed until the phone answers `/notice/forget/accepted`, because `SENT` can mean a
  reconnect queue that dies with the client. The phone sends a budget-deferred forget one window
  later on a link that stays up, and before any newer show from the same owner. A frozen
  conversation evicted from the bounded history keeps its own expiry deadline, and the root is
  redrawn from the current history at draw time and on screen-on, so a staggered aggregate loses
  an expired thread after a deep sleep without waiting for a tick. A paused phone no longer skips
  ending a read-only alert whose repost the policy refuses. Each fix has a regression test that
  fails without it.
- **Fixed after the fourth round** (Sol, Astra and Fable on `cdc260bc`): an unaccepted forget goes
  again on a fresh registration of the same client, or once the hold window passes; the phone
  consumes an owed purge in one locked step with its sending, so a drain racing a same-owner show
  cannot purge after it, and a show whose owner's purge cannot be sent is refused instead of
  overtaking it.
- **Fixed after the fifth round** (Sol and Astra MERGE-READY on `f79e39ba`): Relay resends an
  unaccepted forget only on a new SDK registration generation, not on the repeated approval
  report of the same registration; a show the budget refuses no longer sends a deferred purge
  early. Added tests: an open Notifications list emptying on the timer alone, and an older
  acceptance that cannot settle a newer request.
- **Fixed from the 2026-10-24 device footage** (lane `fx/nav-misc`): a route without geometry
  (Google Maps or Citymapper guidance) shows Minimap and Panel unavailable on its Display page and
  cannot save them, and phone Settings says maps need a route Nexus plans (`RouteNavigationTest`);
  Media Deck no longer marks a new track or player as a significant update, so what plays stays
  below a running route (`MediaDeckActivityPublisherTest`); the hub now puts a newly started activity above the running
  ones, a restart of a resident keeps its place (`ActivityCollectionTest`);
  a one-to-one conversation card no longer repeats its sender before each line, a group card
  keeps its senders, and the notice band reads its lines the same way (`ComposerSessionTest`,
  `NoticeStateMachineTest`); the reply just sent no longer blinks out when a
  revision computed before Relay recorded it lands after the result, nor when someone else's
  message arrives meanwhile: the glasses keep it on its page until that page carries a copy of
  it, and drop it with its page, a new provider registration or the owner's purge
  (`ComposerSessionTest`). Copies are counted per text, not matched by id or place, so Relay
  replacing its record with the app's own copy (`h-<serial>` to a captured `m-...` id), copies
  arriving in any order, replies in other words between them and three or more identical replies
  all keep one bubble per reply. The guarantee is one-sided: a reply is never hidden before a
  copy is shown for it, but a provider dropping earlier copies in the same words (Relay's bounded
  handoff log) can leave it drawn twice until its frame ends (`ComposerSessionTest`).
  Known limit, accepted after four review rounds (Sol R4.1/R4.2): with identical words sent more
  than once on one page, copies the provider dropped and then shows again, or older history
  reusing Relay's occurrence ids, can still count as a newer identical reply's copy and hide its
  local bubble until the real copy arrives or the page closes. The reply itself is sent either way.
- **What the tests prove, and what they do not.** Component tests (reducer, router, Relay with a
  fake hub) cover each fix above and were each observed to fail with the fix reverted. Production
  session-runtime tests (real `NexusSession`, real wall clock, the runner's own timer, a real
  screen-on broadcast) cover open pages and a covered Notifications list crossing the bound, an
  evicted frozen conversation, and the wake path. A Relay end-to-end test (real listener, band
  and plugin service) holds a read-only alert behind a typed reply, refuses its repost while the
  paused phone is in use, and checks nothing is announced when the reply ends; with the earlier
  order of the pause check it is announced. Not covered by an automated sequence: the phone hub
  dying between accepting and persisting a forget.
  Hardware: the Relay-off forget was observed on the glasses on `cdc260bc`'s build (history
  emptied, phone log `notice history purged owner=relay reason=owner_forget`).
- **Versions prepared, not released.** Hubs 1.9.0 (10900), Relay 1.4.0 (15), Navigation 0.3.0 (4),
  Media Deck 1.0.4 (5), Sample 1.0.6 (7); SDK notes for 0.24.0 are in `CHANGELOG.md`. The SDK
  dependency pins in `README.md` and the SDK guide stay on `sdk-v0.23.0` until that tag exists.
  This replaces release gate F3 below.
- **Float over the glasses** (owner request, 2026-10-24): a per-plugin switch on Plugin access keeps
  that plugin's island and pin off the glasses' home and apps, carried as the ambient policy's
  `hiddenOwners`; its card, pages and notifications are unchanged. Covered by
  `KeptOffGlassesPresentationTest` (hub ingress to islands and pin), `KeptOffGlassesHubTest`
  (phone send and purge), each observed to fail with its fix reverted; not yet seen on hardware.
  The switch says it needs the new launcher (and names Classic when chosen on the phone), and a
  65th plugin cannot be kept off, so the 64-owner policy never drops an accepted choice
  (`KeptOffGlassesRowTest` drives the real Plugin access row; `PhoneLauncherStoresTest`).
- **Still open.** Hardware acceptance beyond the bounded 2026-10-18 smoke and the 2026-10-23 device
  pass (see the device reports); the review nits A6-A12; withdrawals queued on a phone that dies are
  still lost (purges are durable).

Paths below are abbreviated: `G/` = `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/`,
`P/` = `phone-hub/src/main/java/com/anezium/rokidbus/phone/`, `S/` =
`shared/src/main/java/com/anezium/rokidbus/shared/`, `B/` =
`bus-client/src/main/java/com/anezium/rokidbus/client/plugin/`. Tests sit in the matching `src/test`
trees.

## 1. Feature map

### A. Generic public plugin activities

| Feature | Implementation | Tests | State |
|---|---|---|---|
| Launcher bundle negotiation (`launcherInteractiveVersion`, `LAUNCHER_INTERACTIVE` bit 16, min-rule) | `S/LauncherInteractiveContract.kt`, `S/PhoneHubCapabilitiesContract.kt`, `S/GlassesHubCapabilitiesContract.kt`, `G/GlassesHub.kt` | `LauncherInteractiveContractTest` (shared) | Done |
| Owner-scoped destination (declared page + bounded params) and explicit first activation (`destination` / `commands` / summary) | `S/ActivitySurfaceContract.kt` (`destination`, `activation`, `defaultActionId`, `destinationAllowed`), `B/InteractiveModels.kt`, `B/NexusPluginClient.kt`; glasses `G/session/SessionReducer.kt` `activate()` | `ActivityInteractiveContractTest`, `NexusLauncherInteractiveTest`, `LauncherInteractiveTest`, `RouteNavigationTest` | Done |
| Destination-first card opens the plugin page inside the session (no detail/menu/immersion) | `G/session/SessionReducer.kt` (`activate`, `openFrame`), `G/session/RootStops.kt` | `LauncherInteractiveTest`, `TodoActivityTest` (sample), host `LauncherHostLinkLossTest` | Done |
| Quick-command card: first tap focuses Play/Pause, one swipe axis prev/play-pause/next, tap executes, Back exits | `G/session/LauncherInteractive.kt` (`ActivityCommands`, focus), `G/page/LauncherControls.kt` | `LauncherInteractiveTest`, `LauncherRenderingTest` | Done |
| Hub-owned Open app outside the 3-action plugin budget; card slot, page row and Applications launch on one fresh tap | `G/session/LauncherInteractive.kt` (`withOpenAppRows`, `OPEN_APP_KEY`), `G/session/SessionReducer.kt` (`launch`) | `LauncherInteractiveTest`, `RouteNavigationTest`, `SessionPagesTest`, `NexusSessionRuntimeTest` | Done |
| Page bodies for conversation, media, route (not stubs) | `S/PageTemplate.kt` (Conversation/Media/Route, decode/encode, limits), `G/session/TypedPageItems.kt`, `G/session/SessionScreens.kt`, `G/page/ConversationView.kt`, `G/page/LauncherBlocks.kt`, `G/page/RouteMapPainter.kt` | `PageInteractiveContractTest`, `RouteGeometryContractTest`, `ComposerSessionTest`, `RouteNavigationTest`, `LauncherRenderingTest` | Done |
| Collection: 8 entries over the bundle vs 2 ambient/v1 slots; deterministic membership, overflow and frozen root order | `G/ActivityController.kt` (`ActivityState.setCapacity`, eviction order, `onLauncherNegotiated`), `G/session/RootStops.kt` | `ActivityCollectionTest`, `ActivityStateMachineTest`, host test (five entries kept through outage, trimmed to two by an older phone) | Done |
| Enabled/pinned preferences separate from live binding and action authority; inert offline snapshot; disable/uninstall/revoke clears; fresh register/link revalidates | `P/PhoneActivityPreferences.kt`, `P/PhoneActivitySnapshotStore.kt`, `P/PhoneActivityState.kt`, `P/PhoneActivityRouter.kt`; hub stamps `state`/`userPinned`/`knownAtWallMs` | `PhoneActivityLauncherTest`, `PhoneLauncherStoresTest` | Done |
| Every ordinary action runs on a fresh tap; retained policy selects quick or confirmed wire mode; Plugin access has no quick-action switches; compose.send stays composer-only | `S/PageActionType.kt` (`PageActionType`, `QuickActionPolicyContract`), `P/PluginQuickActionPolicyStore.kt`, `P/PluginPermissionsActivity.kt`, `/core/policy/quick-actions`; glasses `QuickPolicyState`, `LauncherQuickGate`, `SessionReducer` | `PageActionTypeTest`, `PhoneLauncherStoresTest`, `PluginPermissionsActivityTest`, `LauncherInteractiveTest`, `SessionPagesTest`, `ComposerSessionTest` | Done |
| Owner/grant/registration/visible revision/lease/fresh invocation/contact checks kept | `P/PhonePageBroker.kt`, `P/PhoneActivityState.kt` (pending invocation/deadline), glasses reducer | `PhonePageBrokerLauncherTest`, `SessionReducerTest`, `ComposerSessionTest` | Done |
| Whole titles: optional bundle-only `headline` (40 printable) beside the unchanged 12-character `primary`; card and every island form (compact, measured chip, panel, flare) draw `headline ?: primary` (the headline only while the bundle is negotiated or held through a link loss, so an older phone's primary wins), scrolled in place only while shown on a lit display, including a line put back on its window after the glasses woke; a flare is held until its title's first pass has shown the end, never longer than that pass as measured at its usual fold plus 2 s, and a changing measure keeps the title's place, clamped at a nearer end; folded island without a measure = glyph beside title over secondary, nothing under (with a measure: the medium chip, unchanged; pins untouched); the panel's command strip never cuts a label | `S/ActivitySurfaceContract.kt` (`headline`, `MAX_HEADLINE_CHARS`, `stripLauncherFields`), `B/SurfaceModels.kt`, `G/session/SessionWire.kt` (`activitySourceOf`), `G/ActivityOverlayRenderer.kt` (`ActivityCompactView`), `G/PinOverlayRenderer.kt` / `G/NoticeOverlayRenderer.kt` (`movingTitle`), `G/page/OverflowLine.kt`, `G/HudActionRowView.kt` (`wholeLabels`) | `ActivityInteractiveContractTest`, `NexusLauncherInteractiveTest`, `PhoneActivityLauncherTest`, `ActivityIslandRenderingTest`, `OverflowLineTest`, `LauncherRenderingTest` | Done (device pending) |
| Islands sharing a row (top or bottom corners) never meet: a guidance form, an expanded activity panel or a drawn pin keeps its width and a folded island (compact or measured chip) takes the rest, two folded islands share the row evenly, the edge margin always between them; the title scrolls in the narrower line; a budget that leaves the title less than one character (beside a guidance or activity panel) folds the island to its glyph alone, and one too narrow for that glyph withholds the folded island until the room returns (card, panel and flare unchanged); a lone island, the panel and the flare keep their widths; the flare band is drawn opaque in front of the top row until it folds back | `G/ActivityOverlayRenderer.kt` (`rowBudgets`, `foldedWidthBudgets`, `ActivityIsland.render`/`withheld`, `ActivityGlyphView`, `ActivityCompactView.minWidthPx`), `G/PinOverlayRenderer.kt` (`drawnWidthPx`, chip `widthBudgetPx`, `minStackedWidthPx`) | `ActivityIslandRowTest` (480 px) | Done (device pending) |
| No public `/core/native-apps`, `/core/remote-input`, `/core/navigation`, `/core/pointer` capability | `S/plugin/PathRules.kt`, `S/plugin/PluginCapability.kt` | `LauncherPathAndDescriptorTest` | Done |

### B. Working todo example and Media Deck

| Feature | Implementation | Tests | State |
|---|---|---|---|
| Sample todo: opt-in Show in activities, stable task ids, explicit desired checked state, current revision, result state, no blind toggle or fake success; public SDK only | `plugins/sample/.../TodoActivity.kt`, `TodoControl.kt`, `TodoPages.kt`, `TodoSection.kt`, `TodoService.kt`, `TodoStore.kt` | `TodoActivityTest`, `TodoPagesTest`, `TodoStoreTest` | Done |
| Media Deck activity monitoring separate from the full surface; metadata / no session / access revoked / session changed / link loss; no stale command or auto retry | `plugins/media/.../MediaDeckActivityControl.kt`, `MediaDeckActivityModel.kt`, `MediaDeckActivityPublisher.kt`, `MediaDeckPages.kt`, `session/MediaDeckMonitorHub.kt`, `session/MediaDeckState.kt`, `session/MediaDeckCommand.kt` | `MediaDeckActivityPublisherTest`, `MediaDeckMonitorHubTest`, `MediaDeckStateMachineTest`, `MediaDeckPagesTest`, `MediaDeckRuntimeTest` | Done |
| Media Deck activity: whole title as `headline`; its own `disc` glyph in every state (no play or pause mark beside Rokid's media pill), "Paused"/"Stopped" before the artist, so a line too narrow cuts the artist, never the state | `plugins/media/.../MediaDeckActivityModel.kt`, `MediaText.kt`, `res/values/nexus_glyphs.xml` | `MediaDeckActivityPublisherTest`, `ActivityIslandRenderingTest` (480 px, font scale 1 and 1.5) | Done (device pending) |
| Media Skills opaque-player boundary ("Which player?" authorizes only that live reference/operation; never retargets HUD, monitoring, seen or quick approvals; stale fails stale) | `plugins/media/.../plugin/media/MediaSkills.kt`, `session/AndroidMediaSkillAccess.kt`, `session/MediaSkillReferences.kt` | `MediaSkillsTest`, `MediaSkillReferencesTest`, `MediaSkillsHudBoundaryTest` | Done (fake players; device pending) |

### C. Relay, notifications and the hub composer

| Feature | Implementation | Tests | State |
|---|---|---|---|
| Bounded notification history; root preview, promotion, resolution | `G/session/NotificationHistory.kt`, `G/session/RootStops.kt`, `G/session/LocalPages.kt` | `NoticeArrivalsTest`, `SessionPagesTest` | Done |
| Exact authenticated notice → thread destination; no 24 h reply lease after removal; `/notice/withdraw`, `/core/notice/purge` | `S/NoticeSurfaceContract.kt`, `P/PhoneNoticeRouter.kt`, `P/PhoneNoticeState.kt`, `P/NoticeEnvelopePolicy.kt`, glasses `NexusSession.onNoticeWithdraw/onNoticePurge` | `NoticeLauncherFieldsTest`, `PhoneNoticeLauncherTest`, `RelayLauncherThreadTest` | Done |
| Simple chat: newest captured page, received LEFT / ours RIGHT, group sender lines, capture time only | `plugins/relay/.../RelayConversation.kt`, `NotificationTextExtractor.kt`, `RelayPages.kt`; `G/page/ConversationView.kt`, `G/session/SessionScreens.kt` (`bubbles`) | `RelayConversationTest`, `NotificationTextRolesTest`, `LauncherRenderingTest`, `ComposerSessionTest` | Done |
| Reading pages; tap focuses Dictate first, then Type; buttons inline | `G/session/ComposerState.kt` (`ComposerRules`), `G/session/SessionScreens.kt`, `G/page/LauncherControls.kt` | `ComposerSessionTest`, `LauncherRenderingTest` | Done |
| Glasses mic only (no silent phone fallback); Type is the explicit phone keyboard | `S/ComposeContract.kt`, `P/PhoneComposeRouter.kt`, `P/ComposeKeyboardActivity.kt`, `P/ComposeKeyboardPrompt.kt`, `P/speech/*` | `ComposeAndAmbientContractTest`, `PhoneComposeRouterTest`, `AndroidSttSessionTest`, `SpeechSessionManagerTest` | Done |
| One global recipient-bound draft; cancel keeps text; replaced only at a completed explicit capture | `G/session/DraftStore.kt` | `ComposerSessionTest` | Done |
| Fresh-contact guard: Send needs a new press begun after the focus was drawn | `G/session/LauncherInteractive.kt` (`SessionFocus`), reducer `FocusShown`/`pressStartMs`, `NexusSession.acknowledgeFocus` | `ComposerSessionTest`, `LauncherInteractiveTest` (host draw ack: see A12) | Done (reducer); host draw-ack untested |
| Honest handoff vs unconfirmed, no delivered/read claim, no auto retry; unconfirmed recovery Type first | `plugins/relay/.../RelayCompose.kt`, `G/session/SessionScreens.kt` | `RelayComposeHandlerTest`, `ComposerSessionTest` | Done |
| Link loss keeps the draft and needs a new revalidated Send; lost reply authority keeps text, not sendable; a new replyable exact-thread notice or newer revision allows Type prefill as a new capture | `G/session/DraftStore.kt` (`authorityLost`, `pageReplyable`, `lostRevision`) | `ComposerSessionTest` (incl. F2 crossed-withdrawal case), host outage tests | Done |
| Notice Reply/Type semantics, armed band ownership, no triple during capture | `G/RokidBusAccessibilityService.kt` (`NexusInput`, `captureActive`), `G/input/InputArbiter.kt` | `InputArbiterTest`, `ComposerSessionTest` | Done |

### D. Navigation and all launcher views

| Feature | Implementation | Tests | State |
|---|---|---|---|
| Text / minimap / panel / full-map views, swap and full-map excursion, per-owner preference | `G/session/RouteNavigation.kt`, `G/session/TypedPageItems.kt` (route rows), `G/page/RouteMapPainter.kt`, `G/page/RouteMapMath.kt`, `G/page/NavIslandView.kt` | `RouteNavigationTest`, `RouteMapMathTest`, `LauncherRenderingTest` | Done |
| Bounded vector/street/route/heading data, three sizes, transit stop ribbon, honest GPS-loss north-up/degraded | `S/RouteGeometryContract.kt`, `G/RouteLive.kt`, `G/page/RibbonView.kt`; providers `plugins/nav/.../NavMapProjection.kt`, `NavMapPublisher.kt`, `NavRoute*.kt`, `TransitousPlanner.kt`, `plugins/transit/.../TransitJourney*.kt` | `RouteGeometryContractTest`, `RouteLiveTest`, `NavMapProjectionTest`, `NavMapPublisherTest`, `NavRouteTest`, `TransitJourneyGuideTest` | Done |
| Foreground ambient policy and preference editor sync; passive islands; native-app input untouched | `S/AmbientPreferencesContract.kt`, `G/AmbientPreferences.kt`, `G/ForegroundResolver.kt`, `G/ActivityPresentationPolicy.kt`, `P/PhoneAmbientPreferences.kt`, `P/AmbientChoiceRow.kt`, `P/NativeAppsActivity.kt` | `AmbientForegroundPolicyTest`, `AmbientPreferencesMirrorTest`, `PhoneAmbientPreferencesTest` | Done |
| Per-plugin "Float over the glasses": no island, flare, wake or drawn pin for a kept-off owner, card and pages kept, other islands expand; purged with the grant; row states its launcher prerequisite; at most 64 kept off, a 65th refused | `S/AmbientPreferencesContract.kt` (`hiddenOwners`), `P/PhoneActivityPreferences.kt`, `P/PluginPermissionsActivity.kt`, `P/BusHubService.kt`, `G/ActivityController.kt`, `G/PinController.kt`, `G/AmbientPreferences.kt` | `ComposeAndAmbientContractTest`, `PhoneLauncherStoresTest`, `PhoneAmbientPreferencesTest`, `KeptOffGlassesRowTest`, `KeptOffGlassesHubTest`, `ActivityStateMachineTest`, `AmbientPreferencesMirrorTest`, `KeptOffGlassesPresentationTest` | Done (device pending) |
| Full-map return keeps preference and base context | `G/session/RouteNavigation.kt`, reducer full-map modes | `RouteNavigationTest` | Done |
| Root/apps/notifications/activities icons; 480x640 black/outlined UI; measured list viewport and follow-selection | `G/page/HudIcons.kt`, `G/page/PageStyle.kt`, `G/page/SessionScreenView.kt`, `G/page/LauncherBlocks.kt` | `LauncherRenderingTest`, `SessionScreenViewTest`, `ReadingRunnerTest`, `DocumentPagingRunnerTest` | Done (Robolectric layout; optics pending) |
| Canonical touchpad/ring/accessibility selection through one arbiter | `G/input/InputArbiter.kt`, `G/RokidBusAccessibilityService.kt` (`NexusInput`) | `InputArbiterTest` | Done |

### Offline and outage behaviour (from the independent rounds)

| Behaviour | Implementation | Tests |
|---|---|---|
| Backend never re-resolved on link loss; persisted last-announced bundle decides | `G/input/LauncherBackendSelection.kt`, `G/ActivityController.kt` (`ActivityPresentationSettings` keys `launcher.backend`, `launcher.backend.phone`, `launcher.bundle.announced`), `G/GlassesHub.kt` | `LauncherBackendSelectionTest`, `LauncherBackendStoreTest`, host `LauncherHostLinkLossTest` |
| Open session, page stack, root and draft survive SPP/CXR loss in both orders; nothing replayed on return | reducer `LinkLost`/`LinkRestored`, `NexusSession.onPhoneLink` | host test (13 cases) |
| Retained rich pages (conversation, media, route) stay readable, dimmed, inert; a tall retained conversation is paged as a conversation | `G/session/SessionState.kt` (`Snapshot.extended`), `G/session/TypedPageItems.kt` (`retainedBody`), `G/session/SessionScreens.kt` (`unavailable`) | host test, `RetainedProvenanceTest` |
| Cards keep validated destination/activation/pin/offline time through an outage and until the next announcement; a held destination opens exactly and inert, asking nothing | `G/GlassesHub.kt` (`launcherBundleHeldThroughLoss`), `NexusSession.onActivities`, `ActivityStop.held`, `SessionReducer.activate/openFrame` | host test, `HeldDestinationTest` |
| Older phone's equal all-default announcement trims the collection to two | `G/GlassesHub.kt` (`updateRemotePhoneCapabilities`) | host test |

### Launcher backend default

`G/input/RawKeyEvent.kt`: `LauncherBackend.BUNDLE_DEFAULT = SESSION`, `DEFAULT = LEGACY`. Resolution
(`LauncherBackend.resolve` via `LauncherBackendSelection`): the phone's explicit choice
(`PhoneHubCapabilities.launcherBackend`, editor `P/GlassesDisplaySettingsActivity.kt`, store
`P/PhoneLauncherBackendStore.kt`), then the glasses' `launcher.backend`, then SESSION if the last
announcement negotiated the bundle, LEGACY otherwise (no announcement, older phone, invalid version).
Either explicit LEGACY rolls back; nothing is reset; link loss re-resolves nothing.
Tests: `GuidanceIslandTest` (shipped values and precedence), `LauncherBackendSelectionTest` (shipped
selection SESSION after a bundle announcement, LEGACY after an older phone's), host test (runs on the
shipped default; offline boot keeps SESSION; an older phone's fresh announcement returns LEGACY).

### Skills v1 (reconciled with upstream `8ed98f3a`)

- K1 core: `S/skills/SkillCatalog.kt`, `SkillLimits.kt`, `SkillSchema.kt`, `SkillsContract.kt`;
  `B/NexusSkills.kt`; `P/SkillsCoordinator.kt`, `P/SkillGrantStore.kt`, `P/AndroidSkillLeaseRuntime.kt`;
  `S/plugin/PluginDescriptor.kt` (`SKILLS`, `SKILLS_CLIENT` metadata). Tests `SkillCatalogParserTest`,
  `SkillSchemaTest`, `SkillsContractTest`, `NexusSkillsTest`, `SkillsCoordinatorTest`.
- K2 providers and caller: Transit (`TransitSkills.kt`, `TransitJourneySkills.kt`,
  `TransitSkillContract.kt`), Media (`MediaSkills.kt`), Sample (`HelloSkills.kt`), each with
  `res/raw/nexus_skills.json`; Assistant taken as the upstream unit (`AssistantSkills.kt`).
- Rules kept: phone-local traffic; the phone hub stamps both identities; approvals bound to caller,
  provider and operation digest (a changed contract starts disabled); grants requested only through
  `SKILLS`/`SKILLS_CLIENT`, never `.CAPABILITIES`. Launcher-specific delta: lease-only providers are
  restricted on launcher page paths (`SkillsCoordinator`).

## 2. Independent review rounds

| Round | Reviewer | Result | Disposition |
|---|---|---|---|
| R1 A/B (2026-10-11) | Opus 5.5 delegated | Failed on quota, no verdict | Partial leads fixed by the lead (`029af547`, `841b6b4d`, `447690fb`, `c50100e3`, `a59a1544`) |
| R1b-A code/security | Opus 5.5 delegated | NOT READY: A1 blocking, A2–A5 should | Fixed `d8266393`, `e9df7c2c`, `839d2420`, `8457d73b`, `004c6d22`; A6/A8/A9/A10/A12 open nits |
| R1b-B product/UI | Opus 5.5 delegated | NOT YET: B1–B2 blocking, B3–B9 should, B10–B14 nits | Fixed in `lc/r1b-ui-fixes`, integrated `6467a9b4` |
| R2 final | Opus 5.5 delegated | ACCEPT WITH FIXES: F1 blocking, F2 nit, F3 release nit | F1 `94c45ea7`, F2 `98efcdb3`, `d82ceaa1`; F3 open release gate |
| Sol R3 | GPT-6.1 Sol (parent) | NOT READY: R3-1 host test, R3-2 capacity | `f9632060`…`405b9198` |
| Sol R4 | GPT-6.1 Sol | NOT READY: R4-1 retained bodies, R4-2 held identity, R4-3 fixture | `e421bcd7`…`00b08203` |
| Sol R5 | GPT-6.1 Sol | NOT READY: R5-1 held destination before announcement, R5-2 tall retained chat, R5-3 hint | `3a33f2d3`…`bfde339f` |
| Sol R6 | GPT-6.1 Sol | ACCEPT WITH ONE NONBLOCKING FIXTURE NIT (R6-1) | R6-1 fixed `36dc167c`; flip `f103fe3e`; docs `c82c077a` |
| Astra R1 (2026-10-17) | GPT-6 Astra, independent of Sol | NOT OK: 4 blockers, 1 should-fix | 1 LEGACY islands `23f44c76`; 2 purge on frozen roots `e7bebd17`; 3 durable purge obligations `ed5d9cca`; 4 Relay classic inbox `f1d8c2c0`; 5 keyboard prompt on API 30-32 `4862d6a9`; record `ASTRA-R1-RESPONSE.md` |

Local audit records (ignored, not published) are in the integration checkout under
`build/launcher-complete/reviews/` (`R1B-A-CODE.md`, `R1B-B-PRODUCT-UI.md`, `R2-FINAL.md`,
`SOL-R3…R6`, and the author responses `R2-RESPONSE-F1-F2.md`, `R3…R5-RESPONSE.md`,
`ASTRA-R1-RESPONSE.md`).

## 3. Open items

Accepted nits, not closed:
- **A6** `PathRules.isLauncherOwnerPath` is used only by tests.
- **A8** the SDK does not check that an activity command result reaches this client; the phone binds
  owner, invocation, deadline and activity before delivering.
- **A9** `NexusPageDestination` does not validate its owner at construction; the SDK start/update and
  the phone do.
- **A10** a `stale` compose answer keeps Send on the same revision; the broker rejects it again.
- **A12** the host's draw acknowledgement (`FocusShown` after a real draw) has no test; the reducer side
  is tested and the host fixture dispatches `FocusShown` itself.

Release gate **F3**: plugin versions must go above their released builds with changelogs when shipping
(currently media 1.0.2/vc3, sample 1.0.4/vc5, transit 1.0.4/vc5).
**A5**: the Transitous request is documented as an AGENTS exception; the owner's confirmation status is
unchanged by this report.

Test-fixture boundaries (`LauncherHostLinkLossTest`): a Robolectric AccessibilityService stands in for
the full service start; notice arrival, `FocusShown` and key events are dispatched as session events;
inbound phone messages call `GlassesHub.onRemoteEnvelope` directly (the authenticated SPP socket carries
the handshake, loss and outbound writes); the outbound record pairs only a failed CXR send with the
session's drop on the next log line; the F1 link-loss mutation was not run.

Known limits after the Astra fixes:
- The phone's queued notice **withdrawals** are still memory-only (purges are now durable): a withdrawal
  owed when the phone hub dies is lost, and the glasses keep that entry, within the 24-hour display
  bound, until a purge of its owner, eviction by the 30-entry cap, or their own restart. A later notice
  from the same owner does not remove it.
- A thread answered from Relay leaves the classic inbox but keeps its reply authority for the
  launcher's thread page, as the accepted launcher design wants; it re-enters the classic inbox when the
  app posts it again.
- The phone-restart purge path is tested at the router with a ledger that outlives it and the exact
  production calls (`purgeOwnersWithoutAccess` without a changed owner, startup reconcile, announce);
  `BusHubService.onCreate` itself is not instantiated in a test.

## 4. Hardware acceptance matrix (historical pending list)

This is the pre-smoke matrix. The [2026-10-18 report](LAUNCHER_DEVICE_SMOKE_20261018.md)
records completed subsets and explicitly preserves untested combinations; the whole
list must not be treated as either fully pending or fully accepted.

1. A 60 s phone outage and return with an open conversation + draft, and with an open route page; both
   transport orders; a transport back before the phone announces.
2. Offline glasses boot; a replacement older phone; explicit phone and glasses choices; LEGACY rollback;
   R08 ring.
3. Touchpad and ring fresh contact on Send and on quick commands; input aliases.
4. Relay: withdrawal and repost, glasses microphone failure / timeout / cancel, explicit phone typing.
5. Navigation: live geometry, GPS degradation, viewport and 480x640 optics of map, panel, full map,
   ribbon and lists.
6. Media Deck monitoring across closing the full surface; todo opt-in and checked-state results.
7. Skills: Media "Which player?" choice, stale choice, lease-only behaviour.
8. From the Astra review: LEGACY island comparison (old phone, explicit LEGACY, saved SESSION sizes and
   policies, home / native app / surface / launcher); purge of every visible copy (conversation card,
   aggregate, generic preview, root visible and beneath a page); purge across phone process death
   (restart then uninstall; revoke while disconnected then kill); Relay classic inbox after band Reply and
   Type and full-app sends, clear-after-reply on and off; keyboard launch matrix on API 30/32 and 33+;
   Android recreation and phone hub death during typing and dictation.

## 5. Evidence actually observed

All builds ran in the configured integration checkout, hubs without `-PskipCxrGlobal`.

After merging `origin/main` (`ad9191d6`, hubs 1.7.1) in merge commit `5c560fbb`, at `13b696a6` (source
equal to the final commit except documentation), all ten suites re-executed with `--rerun-tasks`:

```
BUILD SUCCESSFUL in 56s
194 actionable tasks: 194 executed
```

```
BUILD SUCCESSFUL in 1m 56s
160 actionable tasks: 160 executed
```

JUnit XML: **3831 tests, 0 failures, 0 errors, 0 skipped** — shared 494, bus-client 192, phone-hub 933,
glasses-hub 1137, nav 129 (main moved six planner tests into the SDK's `NexusGuidancePlannerTest`), relay 128,
media 78, assistant 555, sample 55, transit 130. Debug APKs 1.7.1 (10701): phone hub 23,240,731 bytes, SHA-256
`9fb3c59467cfde86a82eae4945c7bd93a166c55a60b24d363982e6e7ec607767`; glasses hub 24,328,608 bytes, SHA-256
`cffd2111f68f70461f28128d6ed2f0b673914b4f7550ed9f6f1a37e8dda33fa3`. One earlier full run hit the known
`bus-client` `NexusPageTest` timing flake and passed when rerun. Resolution record:
`build/launcher-complete/reviews/MAIN-MERGE-20261017.md` in the integration checkout (local, not published).

Before the merge, after the Astra fixes, at `a2c46cc6` (source equal to the final commit except documentation), all ten
suites re-executed with `--rerun-tasks`:

`./gradlew :shared:test :bus-client:testDebugUnitTest :plugin-nav:testDebugUnitTest :plugin-relay:testDebugUnitTest :plugin-media:testDebugUnitTest :plugin-assistant:testDebugUnitTest :plugin-sample:testDebugUnitTest :plugin-transit:testDebugUnitTest -PskipCxrGlobal=true --rerun-tasks`

```
BUILD SUCCESSFUL in 1m 2s
194 actionable tasks: 194 executed
```

`./gradlew :phone-hub:testDebugUnitTest :phone-hub:assembleDebug :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug --rerun-tasks`

```
BUILD SUCCESSFUL in 1m 45s
160 actionable tasks: 160 executed
```

JUnit XML: **3700 tests, 0 failures, 0 errors, 0 skipped** — shared 485, bus-client 192, phone-hub 891,
glasses-hub 1055, nav 134, relay 128, media 78, assistant 552, sample 55, transit 130. Debug APKs:
phone hub 23,145,558 bytes, SHA-256 `1217840cf62cb866ec51aa604b766c586a4b964ef8b94cfb30633ba513cb3343`;
glasses hub 24,230,268 bytes, SHA-256 `785c5aca9dac17fe917044521291bc075c45fe2ce2a67e9ef44da574dee91a82`.

Earlier, at `f103fe3e`:
`./gradlew :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug`

```
BUILD SUCCESSFUL in 33s
97 actionable tasks: 8 executed, 89 up-to-date
```

`compileDebugKotlin`, `testDebugUnitTest`, `packageDebug` and `assembleDebug` executed. JUnit XML
(141 files): **1046 tests, 0 failures, 0 errors, 0 skipped**. Glasses debug APK 1.6.0 (vc 10600),
24,889,194 bytes, SHA-256 `682d822ba1ce942379a923a396a6f94e57320017547918309eafa9329c8f8f7f`.

Every other module is unchanged since `f8e02092`; its last full pass (`--rerun-tasks`, all modules,
2026-10-11) observed 4123 tests, 0 failures (shared 485, bus-client 192, ink 57, phone 881, glasses
1012, nav 134, relay 123, sample 55, media 78, transit 130, assistant 552, lyrics 54, tasker 16, lens 135,
photosync 13, wireless-adb 7, patcher 138 with 11 pre-existing skips, feeds 61) with tails
`BUILD SUCCESSFUL in 1m 29s` / `473 actionable tasks: 473 executed` and `BUILD SUCCESSFUL in 1m 27s` /
`160 actionable tasks: 160 executed`. That evidence is historical for those modules, not a re-run.
A pre-existing `bus-client` `NexusPageTest` timing flake failed once in a parallel run and passed alone.

No device, ADB, install, push, tag, release or API use was part of the historical
verification in this section. Subsequent authorized device work is recorded in the
[2026-10-18 smoke report](LAUNCHER_DEVICE_SMOKE_20261018.md).

## Immediate-action validation (2026-10-25)

Lane: `E:\Tools\Rokid\RokidNexus-fx-noconfirm`, branch `fx/noconfirm`, baseline `f651994c`.
The implementation is in `5db942ff` (glasses) and `0cfa3a8c` (phone UI).
Both hub commands ran without `-PskipCxrGlobal`, with output redirected to `build/noconfirm-*.log`.

```powershell
.\gradlew.bat :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug --console=plain
.\gradlew.bat :phone-hub:testDebugUnitTest :phone-hub:assembleDebug --console=plain
```

The initial successful glasses run observed 1,281 tests, zero failures, errors or skips. Its real tail:

```text
BUILD SUCCESSFUL in 1m 35s
116 actionable tasks: 3 executed, 113 up-to-date
```

The initial phone run observed 1,029 tests, zero failures, errors or skips. Its real tail:

```text
BUILD SUCCESSFUL in 1m 47s
134 actionable tasks: 25 executed, 20 from cache, 89 up-to-date
```

Before the successful glasses run, the first run observed `1281 tests completed, 1 failed`:
the Navigation Open app test lacked its draw acknowledgement. Adding that acknowledgement
fixed the fixture; the fresh-contact guard stayed intact.

After restoring the mutation, both full commands passed again. Gradle restored the identical
implementation's compiled classes and successful test results from its build cache; XML totals
were again 1,281 glasses and 1,029 phone tests, with zero failures, errors or skips. Real tails:

```text
Glasses:
BUILD SUCCESSFUL in 13s
116 actionable tasks: 2 executed, 5 from cache, 109 up-to-date
Phone:
BUILD SUCCESSFUL in 15s
134 actionable tasks: 2 executed, 5 from cache, 127 up-to-date
```

Mutation checks temporarily restored the eight changed glasses production files and the phone
activity from `f651994c`, keeping the new regression tests. The baseline `PageItem.Launch`
constructor accepted an unused optional label solely to compile the renamed resolver assertions;
its old behavior, including every dialog, was unchanged. All source bytes were restored in a
`finally` block, verified against the implementation commits with `git diff --exit-code`.
The commands executed by the temporary PowerShell runner were:

```powershell
.\gradlew.bat :glasses-hub:testDebugUnitTest --console=plain --tests '*.LauncherInteractiveTest' --tests '*.SessionPagesTest' --tests '*.SessionReducerTest' --tests '*.RouteNavigationTest' --tests '*.NexusSessionRuntimeTest' --tests '*.ComposerSessionTest'
.\gradlew.bat :phone-hub:testDebugUnitTest --console=plain --tests '*.PluginPermissionsActivityTest'
```

Observed mutation results: **169 glasses tests, 19 failed** (`BUILD FAILED in 43s`,
`87 actionable tasks: 5 executed, 82 up-to-date`); **one phone test, one failed**
(`BUILD FAILED in 23s`, `101 actionable tasks: 4 executed, 2 from cache, 95 up-to-date`).
`ComposerSessionTest` had no mutation failures. The failing test names were:

```text
LauncherInteractiveTest:
  a confirmed command is inert offline and reports NotAvailable when the link is lost
  an action the phone did not stamp quick is confirmed even when the policy approves
  a confirmed command waits for the new card revision to be drawn and a fresh press
  an Applications launch enters Launching on one fresh tap
  an old phone keeps the v1 card and a tap invokes a page action in confirmed mode
  without the policy a single fresh tap sends the focused command in confirmed mode
  Open app launches directly from a card slot on one fresh press
  the Activities page lists every entry and a row acts like its card
  a typed page row without a stamp or policy runs confirmed on one fresh tap
NexusSessionRuntimeTest:
  Applications lists the catalog and tries to launch an unchanged plugin on one tap
RouteNavigationTest:
  the real Navigation page shape lists one full-app row under the hub's commands
  Commands opens the command list in sections and Back returns to the page with Commands selected
SessionPagesTest:
  a plugin row tap sends exactly one confirmed action without a panel
  a page revision needs to be drawn before a new fresh press can invoke it
  a pages plugin entered from Applications ends with one hub Open app that launches on a fresh tap
  a page opened from a provider's own row, or for an owner with no full app, gets no hub Open app
  typed rows map local hub actions and keep provider ones in check
  an immersion row launches directly and a hub action from a provider does nothing
SessionReducerTest:
  rule 11 the default resolver invokes plugin actions directly and keeps hub and immersion rows inert
PluginPermissionsActivityTest:
  activity choices omit quick action switches and keep existing approvals
```

The obsolete panel types and dialog strings have no matches under `glasses-hub/src/main`.
All changes are inside the lane's allowed source/documentation globs. No build configuration,
SDK, quick-action wire, phone validation or composer Send implementation changed. No push,
merge, tag, ADB or device action was performed. Device checks remain with the orchestrator:
focused transport/custom commands and checklist rows, all three Open app entry points,
delayed ring presses after target changes, pending/offline/link-loss behavior, the phone's
remaining activity switches, and a separate fresh Send after a visible conversation draft.

### Immediate-action review fixes, round 2 (2026-10-25)

The independent Opus review's F1–F5 findings are addressed in the same lane:

- **F1:** plugin page actions require `SessionFocus.isFresh` even without the launcher bundle.
  A delayed tap cannot invoke a replacement revision before its draw acknowledgement, use an
  obsolete acknowledgement, or reuse a contact begun before that draw. The regression keeps
  the row key while changing its action from check to uncheck, then verifies one fresh tap
  sends the new action at revision 2 in confirmed mode.
- **F2/F4:** Plugin access explains the activity preferences whenever its surfaces grant is
  absent, independently of declared quick families; the obsolete one-tap wording is removed.
  The typed resolver's KDoc now describes quick versus confirmed wire mode.
- **F3:** native apps and Camera in Applications use the same fresh-press gate as plugin app
  launches. The regression covers both rows before draw, at the draw timestamp, after draw
  with an older contact (including a selection change), and with a fresh successful tap.
- **F5:** the offline-command regression restores the link before the offline update and
  verifies command focus survives. Its rejection now proves the offline guard independently
  of link loss. Stored policy, phone validation, the wire and composer Send are unchanged.

The focused `LauncherInteractiveTest`, `SessionPagesTest` and `SessionReducerTest` run passed:

```text
BUILD SUCCESSFUL in 48s
87 actionable tasks: 5 executed, 82 up-to-date
```

Three independent mutations reverted only the relevant guard in `SessionReducer.kt`, keeping
the regressions. Each selected test failed by assertion, with one test, one failure, zero
errors and zero skips. The temporary runner restored the original source bytes in `finally`
after each mutation and verified byte equality. Exact commands:

```powershell
.\gradlew.bat :glasses-hub:testDebugUnitTest --console=plain --tests '*.SessionPagesTest.without the launcher bundle a delayed row tap cannot invoke a replacement revision'
.\gradlew.bat :glasses-hub:testDebugUnitTest --console=plain --tests '*.LauncherInteractiveTest.native and Camera launches require a press begun after the selected row was drawn'
.\gradlew.bat :glasses-hub:testDebugUnitTest --console=plain --tests '*.LauncherInteractiveTest.a confirmed command is inert offline and reports NotAvailable when the link is lost'
```

Observed output excerpts, in the same order:

```text
1 test completed, 1 failed
BUILD FAILED in 10s
87 actionable tasks: 4 executed, 83 up-to-date

1 test completed, 1 failed
BUILD FAILED in 8s
87 actionable tasks: 4 executed, 83 up-to-date

1 test completed, 1 failed
BUILD FAILED in 7s
87 actionable tasks: 4 executed, 83 up-to-date
```

After restoring the guards, the full glasses command passed with 1,283 tests, zero failures,
errors or skips, counted from the JUnit XML. Debug assembly passed in the same invocation:

```powershell
.\gradlew.bat :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug --console=plain
```

Real output tail (`build/noconfirm-round2-glasses.log`):

```text
BUILD SUCCESSFUL in 1m 45s
116 actionable tasks: 7 executed, 109 up-to-date
```

The full phone command passed with 1,029 tests, zero failures, errors or skips, counted from
the JUnit XML. Debug assembly passed in the same invocation:

```powershell
.\gradlew.bat :phone-hub:testDebugUnitTest :phone-hub:assembleDebug --console=plain
```

Real output tail (`build/noconfirm-round2-phone.log`):

```text
BUILD SUCCESSFUL in 1m 4s
134 actionable tasks: 7 executed, 127 up-to-date
```

`git diff --check` passed; every changed file is within the contract's source/documentation
globs. Searches found no obsolete dialog strings or removed panel references in
`glasses-hub/src/main`. No build configuration, SDK, wire, phone validation, composer Send,
push, merge, tag, ADB or device action changed or ran.

There are no open implementation questions. The orchestrator must verify delayed ring/touchpad
contacts across a page update with an older phone, native/Camera row changes in Applications,
offline focused commands with the link up, and command taps on frequently updating Activities
entries. The review's F6 wording in out-of-scope READMEs and the policy-store KDoc remains for
integration; quick mode still depends on approvals retained from earlier builds.
