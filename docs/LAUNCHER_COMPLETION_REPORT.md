# Launcher completion (plan 028) — implementation report

Date: 2026-10-12, amended 2026-10-17 after GPT-6 Astra's independent review (section 2). Branch `dev/launcher-complete` (local; integration checkout `dev/launcher-rework`
fast-forwarded to the same commit). Base `2d08f0a3` (approved plan 028, r17 mockup). Not pushed,
released or tagged. The later [2026-10-18 device smoke](LAUNCHER_DEVICE_SMOKE_20261018.md)
records signed in-place installs, three hardware-discovered fixes, targeted independent
reviews, and the precise subset of hardware acceptance now observed.

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
| Hub-owned explicit Open app outside the 3-action plugin budget, labelled launch kept | `G/session/LauncherInteractive.kt` (`withOpenAppRows`, `OPEN_APP_KEY`), `G/session/SessionReducer.kt` (`OpenApp`, launch step) | `LauncherInteractiveTest`, `RouteNavigationTest` | Done |
| Page bodies for conversation, media, route (not stubs) | `S/PageTemplate.kt` (Conversation/Media/Route, decode/encode, limits), `G/session/TypedPageItems.kt`, `G/session/SessionScreens.kt`, `G/page/ConversationView.kt`, `G/page/LauncherBlocks.kt`, `G/page/RouteMapPainter.kt` | `PageInteractiveContractTest`, `RouteGeometryContractTest`, `ComposerSessionTest`, `RouteNavigationTest`, `LauncherRenderingTest` | Done |
| Collection: 8 entries over the bundle vs 2 ambient/v1 slots; deterministic membership, overflow and frozen root order | `G/ActivityController.kt` (`ActivityState.setCapacity`, eviction order, `onLauncherNegotiated`), `G/session/RootStops.kt` | `ActivityCollectionTest`, `ActivityStateMachineTest`, host test (five entries kept through outage, trimmed to two by an older phone) | Done |
| Enabled/pinned preferences separate from live binding and action authority; inert offline snapshot; disable/uninstall/revoke clears; fresh register/link revalidates | `P/PhoneActivityPreferences.kt`, `P/PhoneActivitySnapshotStore.kt`, `P/PhoneActivityState.kt`, `P/PhoneActivityRouter.kt`; hub stamps `state`/`userPinned`/`knownAtWallMs` | `PhoneActivityLauncherTest`, `PhoneLauncherStoresTest` | Done |
| Typed quick actions under hub-owned per-plugin approval; current pages always confirm; old/unknown plugins confirmed; compose.send never quick | `S/PageActionType.kt` (`PageActionType`, `QuickActionPolicyContract`), `P/PluginQuickActionPolicyStore.kt`, `P/PluginPermissionsActivity.kt`, `/core/policy/quick-actions`; glasses `QuickPolicyState`, `LauncherQuickGate` | `PageActionTypeTest`, `PhoneLauncherStoresTest`, `LauncherInteractiveTest` | Done |
| Owner/grant/registration/visible revision/lease/fresh invocation/contact checks kept | `P/PhonePageBroker.kt`, `P/PhoneActivityState.kt` (pending invocation/deadline), glasses reducer | `PhonePageBrokerLauncherTest`, `SessionReducerTest`, `ComposerSessionTest` | Done |
| No public `/core/native-apps`, `/core/remote-input`, `/core/navigation`, `/core/pointer` capability | `S/plugin/PathRules.kt`, `S/plugin/PluginCapability.kt` | `LauncherPathAndDescriptorTest` | Done |

### B. Working todo example and Media Deck

| Feature | Implementation | Tests | State |
|---|---|---|---|
| Sample todo: opt-in Show in activities, stable task ids, explicit desired checked state, current revision, result state, no blind toggle or fake success; public SDK only | `plugins/sample/.../TodoActivity.kt`, `TodoControl.kt`, `TodoPages.kt`, `TodoSection.kt`, `TodoService.kt`, `TodoStore.kt` | `TodoActivityTest`, `TodoPagesTest`, `TodoStoreTest` | Done |
| Media Deck activity monitoring separate from the full surface; metadata / no session / access revoked / session changed / link loss; no stale command or auto retry | `plugins/media/.../MediaDeckActivityControl.kt`, `MediaDeckActivityModel.kt`, `MediaDeckActivityPublisher.kt`, `MediaDeckPages.kt`, `session/MediaDeckMonitorHub.kt`, `session/MediaDeckState.kt`, `session/MediaDeckCommand.kt` | `MediaDeckActivityPublisherTest`, `MediaDeckMonitorHubTest`, `MediaDeckStateMachineTest`, `MediaDeckPagesTest`, `MediaDeckRuntimeTest` | Done |
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
