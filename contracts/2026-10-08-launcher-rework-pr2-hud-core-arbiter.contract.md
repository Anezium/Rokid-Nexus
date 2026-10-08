---
task: launcher-rework-pr2-hud-core-arbiter
date: 2026-10-08
status: active
branch: dev/launcher-rework
worktree: E:\Tools\Rokid\RokidNexus-launcher-rework
base: f37bcc7c (origin/main after PR #47)
scope_globs:
  - glasses-hub/src/main/java/com/anezium/rokidbus/glasses/session/**
  - glasses-hub/src/main/java/com/anezium/rokidbus/glasses/input/**
  - glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RokidBusAccessibilityService.kt
  - glasses-hub/src/main/java/com/anezium/rokidbus/glasses/ActivityController.kt
  - glasses-hub/src/main/java/com/anezium/rokidbus/glasses/NoticeController.kt
  - glasses-hub/src/main/java/com/anezium/rokidbus/glasses/NoticeKeyDispatcher.kt
  - glasses-hub/src/main/java/com/anezium/rokidbus/glasses/LauncherOverlayRenderer.kt
  - glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceActivity.kt
  - glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceController.kt
  - glasses-hub/src/main/java/com/anezium/rokidbus/glasses/GlassesHub.kt
  - glasses-hub/src/main/java/com/anezium/rokidbus/glasses/OpenLauncherReceiver.kt
  - glasses-hub/src/main/AndroidManifest.xml
  - glasses-hub/src/test/java/com/anezium/rokidbus/glasses/session/**
  - glasses-hub/src/test/java/com/anezium/rokidbus/glasses/input/**
  - glasses-hub/src/test/java/com/anezium/rokidbus/glasses/ActivityController*Test.kt
  - glasses-hub/src/test/java/com/anezium/rokidbus/glasses/Notice*Test.kt
  - NOTICE
  - glasses-hub/README.md
  - contracts/2026-10-08-launcher-rework-pr2-hud-core-arbiter.contract.md
forbidden_globs:
  - local.properties
  - "**/*.gradle.kts"
  - gradle/**
  - bus-client/**
  - phone-hub/**
  - plugins/**
  - ink-engine/**
  - shared/**
  - glasses-hub/src/main/java/com/anezium/rokidbus/glasses/TouchpadGestureDetectors.kt
  - glasses-hub/src/main/java/com/anezium/rokidbus/glasses/*Camera*.kt
  - glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RemoteInput*.kt
  - glasses-hub/src/main/java/com/anezium/rokidbus/glasses/NativeApps*.kt
  - BUSSPEC.md
  - docs/**
  - plans/**
  - contracts/2026-10-08-launcher-rework-pr1-session-contract.contract.md
test_commands:
  - ./gradlew :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug
  - ./gradlew :phone-hub:testDebugUnitTest
max_failures: 2
---

# Goal

PR2 of plan 027 (`plans/027-glasses-home-stack.md`, decisions 1, 2 and 9, delivery row
"2 — HUD core + provider binding"). Make the glasses hub route **every** key event through
one input arbiter, run the PR1 `SessionReducer` at runtime behind a backend switch whose
default keeps today's launcher, and adopt the runtime core of the alvarosw fork
(`E:\Tools\Rokid\_forks\alvarosw`, Apache-2.0, 27 commits by alvarosw) selectively, with
attribution. On device with the default switch position nothing visible changes except that
activity islands no longer take input.

Deliverables, each its own commit on `dev/launcher-rework`:

1. `input/` package: pure `InputArbiter` (source identification, press ownership, gate,
   GLOBAL recognition, ownership order) ported from the fork's `HudInput` and rewritten to
   the plan's order; `RawKeyEvent`, `DeviceClass`, `KeyEventAdapter`.
2. `session/` runtime: `SessionRunner` (ported from the fork's `HudRunner`), `SessionTimer`,
   `SessionEffectSink`; `SessionReducer` extended with the plugin open handoff (ported from
   the fork's `HudStateMachine`: open deadline, cancelled opens, `beneath`).
3. `SessionHost` (ported from the fork's `HudHost`): one overlay window attached only while
   the session is open, drawing a minimal gate, root and frame (text rows, no templates).
4. The wiring: `RokidBusAccessibilityService`, `SurfaceActivity`, `LauncherOverlayRenderer`
   and the ring path call the arbiter; the activity input branch is removed; the backend
   switch; notice presentation suppressed while the session is open; `NOTICE` credit.
5. Tests, ported and new (see Acceptance).

# Non-goals

- No page rendering of the typed templates, no root stop content from providers, no
  `Activities · n` overflow, no notification centre, no composer (PR3, PR4).
- No phone-hub change: no `/page/*` routing on the phone, no provider shipped, no
  descriptor parsing of `rokidbus.plugin.pages`. `RequestPage` effects are sent on the bus by
  the glasses sink and will time out to `Unavailable` until PR3; that is expected.
- No foreground resolver, no per-app ambient mode, no island/pin budget change (PR3).
- No phone preference for the backend switch (PR5); the switch is a glasses-local
  preference plus a protected broadcast for testing.
- No fork tiles, grid, weather, `SystemWidget*`, `Tile*`, `HomeLayer`, `GridHome`,
  `ListHome`, `HudMorph`, `HudMotionDriver`, `AmbientStack`, fork registry or signer switch.
- No change to the wire contract, to `PageSurfaceContract`, to `BusConstants`, to BUSSPEC
  or the SDK docs.
- No removal of `LauncherOverlayRenderer`, `LauncherMenuCatalog`,
  `LauncherReturnCoordinator`: the legacy backend is kept whole.

# Constraints

- MUST write all code, comments, docs and commit messages in English, in the style of the
  surrounding code. Pure packages (`input/`, `session/`) stay free of `android.*` imports
  and of hub singletons; time is injected; effects are values.
- MUST attribute ported code: each file whose logic comes from the fork starts with a
  comment `Adapted from the Rokid-Nexus fork by alvarosw (https://github.com/alvarosw/Rokid-Nexus),
  Apache-2.0.` and `NOTICE` gains a paragraph naming the fork, its author, its licence and
  the list of adapted files. Ported test files carry the same header. Port by rewriting to
  this contract, never by copying files that contain rules this contract replaces.
- MUST keep the default backend `LEGACY`. The preference key is
  `launcher.backend` in the glasses hub's existing preference store (the one the hub's
  settings already use; never a second preferences file), values `LEGACY` and
  `SESSION`; a broadcast `com.anezium.rokidbus.glasses.action.SET_LAUNCHER_BACKEND` with string
  extra `backend` changes it at runtime. The receiver is declared exactly like
  `OpenLauncherReceiver` (exported, no permission, action under
  `com.anezium.rokidbus.glasses.action.`): it only switches a local, reversible UI backend,
  the same exposure as `OPEN_LAUNCHER`, and it must stay reachable from
  `adb shell am broadcast` for the owner's smoke test.
- MUST make the backends mutually exclusive: at any moment at most one of
  `LauncherOverlayRenderer` and `SessionHost` has a window, and only the active backend
  receives GLOBAL. Switching while a backend is active closes it first: legacy hides;
  session dispatches `SessionEvent.Abort` (new, see rule A6), which closes with
  `SendClosed` and `RestoreUnderneath`, cancels the pending deadline, and drops pending
  contacts in the arbiter.
- MUST route every entry path through the arbiter (plan decision 1, "Implementation"):
  `RokidBusAccessibilityService.onKeyEvent` (touchpad and other devices),
  `handleRingKeyEvent` (R08), `handlePendingTempleTap`, `SurfaceActivity.dispatchKeyEvent`,
  and the window key dispatch in `LauncherOverlayRenderer` (its views must not consume keys
  the arbiter did not route to it). The ring passes the gate and session checks before its
  existing notice-owned policy.
- MUST remove the activity input branch: `ActivityController.handleKeyEvent`,
  `claimsInput`, `claimsRingKey`, `handleRingKey`, `handlePendingTempleTap`,
  `cancelRingInput`, `moveSelection`, `fireOrOpen` and the island selection state they
  drive. Islands stay rendered and animated as today; they take no key on any backend.
  `ActivityOverlayRenderer` keeps drawing; no chip is highlighted because the published
  snapshot carries `selectedActionIndex = -1` (the selection is simply no longer driven).
  The pure `ActivityStateMachine` action-selection API, `ActivityPresentationPolicy`'s
  `ActivityInputTarget`/`canResolveActivityTap` and their tests stay as unused, passing
  code (outside this PR's scope; PR3 removes them when actions move to the commands page).
  `MAX_ACTIVE_ACTIVITIES` and the activity wire contract are untouched. Tests inside the
  scope that only exercised the removed branch are deleted and listed by name in the
  report; every other existing test stays untouched and green.
- MUST NOT change `TripleTapDetector` (`TouchpadGestureDetectors.kt` is forbidden). The
  arbiter wraps it. Its 800 ms post-trigger suppression stays effective for the legacy
  backend. For the session backend: a `TRIGGER` that opens the session (session `Closed`)
  is followed by the reducer's gate, so post-trigger contacts and classifications are
  forwarded as events and PR1 rule 2 absorbs them (rule A3 below); a `TRIGGER` while the
  session is already open opens no gate (PR1 rules 3 and 7f), so for that case the arbiter
  honours the detector's own `CONSUME` decisions for the following 800 ms: ENTER/BACK are
  swallowed, not forwarded, while contacts are forwarded as `Contact` and are inert at the
  root. Two absorption mechanisms, each already tested, neither changed.
- MUST keep the editable exception of `onKeyEvent`: while
  `SurfaceController.hasFocusedEditableSurface()` is true there is no GLOBAL recognition,
  the raw key trace is skipped, and `EditableFocused(true)` is dispatched to the reducer
  (rule 14 of the PR1 contract).
- MUST keep the raw key trace log line (`key code=... t=...`) with its current format.
- MUST keep `KEYCODE_PROG_BLUE` passing to the system untouched, before anything else.
- MUST NOT change `NoticeController` claim rules. Only two additions are allowed: a read
  accessor the arbiter needs (if `NoticeKeyDispatcher` is not enough) and the session
  suppression (rule A8), implemented by the same mechanism the camera overlay already uses
  (`cameraOverlayActive` in `noticeVisibleForInput`), never by a new timer or a new state.
- MUST NOT send anything on the bus except what `SessionEffect` already names
  (`RequestPage`, `SendAction`, `SendVisibility`, `SendClosed`), `SendLauncherOpen`
  (which reuses `GlassesHub.openLauncherEntry` exactly as `LauncherOverlayRenderer` does)
  and `CloseSurface` (which reuses the existing `SurfaceController` close path as is,
  including its `/ink/closed` for an Ink surface; no `KEYCODE_BACK` is forwarded).
- MUST commit in small commits, author Anezium, no AI attribution, no `Co-Authored-By`.
  MUST NOT push. MUST NOT switch branches, rebase, or touch the main checkout at
  `E:\Tools\Rokid\RokidNexus`. MUST NOT modify `local.properties` or any Gradle file.
- MUST paste the real tail of each test command's output in the final report. If a build
  fails for an environment reason, stop and report; do not repair the environment.

## Arbiter rules (`glasses-hub/.../input/InputArbiter.kt`)

Input: a `RawKeyEvent(keyCode, action, repeatCount, eventTime, downTime, deviceId,
deviceClass)` built by `KeyEventAdapter` from the Android `KeyEvent` (`deviceClass` by
device name: contains "R08" → `R08`; every other device → `TOUCHPAD`, which is today's
behaviour (triple-tap recognition runs on every non-ring device and the editable exception
covers the bonded-keyboard case). `KEYBOARD_DPAD` and `OTHER` are declared but never
assigned in PR2; a comment says the touchpad reports as `ROKID,PSOC-TP-R` through
`Generic.kl` and that telling it from a keyboard needs a device trace first.) Context read at each event through an interface
(`backend`, `editableFocused`, `sessionOpen`, `noticeHandles(event)`, `legacyShown`,
`surfaceOwnsKeys`, `nativeInFront`). Output: `consumed: Boolean` plus a list of routed
intents (`ToSession(SessionEvent)`, `ToLegacyLauncher(key)`, `ToNotice(key)`,
`ToSurface(key)`, `ToRing(key)`, `PassThrough`). The arbiter never touches Android.

A1. **Reserved keys.** `KEYCODE_PROG_BLUE` → `PassThrough`, not consumed, before anything.

A2. **Press ownership.** A press is identified by `(deviceId, keyCode, downTime)`. The owner
    decided at DOWN receives every repeat and the UP of that press, even if ownership would
    be different by then (never retarget a dying gesture). An UP with no owned DOWN is
    consumed and routed nowhere (orphan UP, fork rule R3). Ported from `HudInput`.

A3. **Gate.** While the session is `Opening` (gate), touchpad contacts and their
    classifications are forwarded to the reducer as `Contact`/`Enter`/`Back`/`Step` events
    and consumed; the reducer absorbs them (PR1 rule 2). Ring keys during the gate are
    consumed and dropped. On the legacy backend the gate is `TripleTapDetector`'s own
    suppression and nothing here changes.

A4. **GLOBAL recognition.** Only `TOUCHPAD` events reach `TripleTapDetector`, only when
    `editableFocused` is false, and only when no visible notice claims the key
    (`noticeHandles(event)` false for this contact): while a notice can claim input, taps 1
    and 2 would answer it, so a triple tap is not recognised (plan decision 1). `TRIGGER`
    → `ToSession(TripleTap(eventTime))` when `backend == SESSION`, else
    `ToLegacyLauncher(open)`. A `TRIGGER` while the session is already open is still
    forwarded (PR1 test f: returns the anchored root) and the detector's 800 ms
    suppression of ENTER/BACK applies to the taps that complete it (see the
    `TripleTapDetector` constraint). The "armed notice" predicate is
    `NoticeController.ownsRingInput()` (interactive, action-bearing, paged or backdrop
    notice, which is the plan's gesture-table row); `NoticeKeyDispatcher` never sees a
    contact. The predicate must be false while the notice is suppressed (camera overlay or
    open session); wrap it if `ownsRingInput()` does not already account for that.

A5. **Open session owns navigation.** When `sessionOpen`, touchpad and ring keys are
    translated to `Contact`/`Enter`/`Back`/`Step` and routed to the session, consumed;
    nothing reaches the notice, the legacy launcher, a surface or the system. `OTHER` and
    `KEYBOARD_DPAD` devices: DPAD/ENTER/BACK are translated the same way; other keys are
    consumed and dropped.

A6. **Session closed, order:** notice dispatcher (`ToNotice`, consumed when it handles the
    DOWN or the UP, as today) → legacy launcher when shown (`ToLegacyLauncher`) → surface or
    editor (`ToSurface`, which keeps today's `SurfaceController.handleKeyEvent` semantics)
    → `PassThrough` to the native app or the system. No activity branch.

A7. **Ring.** `R08` events: during the gate or an open session, A3/A5 apply. Otherwise the
    existing ring policy runs unchanged (notice-owned ring, surfaces, ring focus
    publication), minus the removed activity claims.

A8. **Ambient suppression.** While the session is open the notice band is not presented and
    claims nothing, through the camera-overlay suppression mechanism. A notice arriving
    during the session is dispatched to the reducer as `NoticeArrived`; its deadline keeps
    running; it is neither answered nor dismissed while hidden. On close, `RestoreUnderneath`
    lifts the suppression; the notice is shown only if still within its window.

## Reducer additions (`session/SessionReducer.kt`), ported from the fork's open handoff

Numbered after the PR1 rules (1–14):

15. **Launching.** Selecting a `PageItem.Launch(pluginId)` (new item kind, produced only
    by an injected item resolver; `DefaultPageItems` stays unchanged and its PR1 test
    "rule 11 ... inert" stays green; nothing produces it at runtime until PR3) → state `Launching(pluginId, token, deadlineMs =
    nowMs + OPEN_TIMEOUT_MS)` with `OPEN_TIMEOUT_MS = 10_000` (the fork's F-4 bound, equal
    to the ring handoff), effects `SendLauncherOpen(pluginId, token)`, `ScheduleDeadline`.
    The root stays drawn underneath (`ShowRoot` is not re-emitted). Input during
    `Launching`: `Back` → the previous state with `CancelDeadline`, and the open is
    remembered in `cancelledOpen[pluginId] = deadlineMs`; `Enter`/`Step` → `None`.
16. **Surface arrives.** `SurfaceShown(surfaceId, ownerPluginId)` in `Launching` with a
    matching owner → `Closed` with effects `SendClosed`, `DetachHost`; the surface is in
    front, the underneath is not restored (the surface replaces it). In any other state a
    `SurfaceShown` whose owner has an entry in `cancelledOpen` not yet past its deadline →
    effect `CloseSurface(surfaceId, OPEN_CANCELLED)` (closed unseen, no `KEYCODE_BACK`
    forwarded) and the entry is removed; otherwise `SurfaceShown` is recorded as
    `ActivityStarted`-like context only (no state change).
17. **Open failure.** `OpenFailed(token)` or `Tick` at or past the deadline in `Launching`
    → the previous state, effect `ShowStatus(OpenFailed(pluginId, reason))` with reason
    `SEND_FAILED`, `REJECTED` or `TIMEOUT`; one entry per plugin in `cancelledOpen` so a
    second open does not forget the first (fork rule). `cancelledOpen` entries expire on
    `Tick` at their deadline.
18. **Beneath.** `SessionState` records what the session was opened over (`Underneath`:
    `Home`, `NativeApp`, `NexusSurface(surfaceId)`, `Unknown`) from the `TripleTap` event
    (new optional field, default `Unknown`); `RestoreUnderneath` carries it. A
    `NexusSurface` underneath never receives a close or a `PLUGIN_CLOSE` because the session
    opened or closed over it (plan PR3 exit; enforced here as a reducer test).
19. **Abort.** `SessionEvent.Abort` in any state except `Closed` → `Closed`, effects
    `SendClosed`, `RestoreUnderneath`, `CancelDeadline`; in `Closed` → `None`.
20. **Deadline token.** `SessionState.nextDeadlineMs()` is pure and returns the earliest
    pending deadline (gate, page request, invocation, lease, open, cancelled-open expiry)
    or null; `SessionRunner` schedules exactly one timer at that time after each event and
    dispatches `Tick(nowMs)` when it fires. Ticks are never scheduled while `Closed`.

Effects added: `SendLauncherOpen`, `CloseSurface`, `ShowStatus`, `ScheduleDeadline`,
`CancelDeadline`, `AttachHost`, `DetachHost` (the host attaches on the first drawing effect
of a session and detaches on `Closed`; emitted by the runner from the state change, in that
order around the transition's own effects, like the fork). `RestoreUnderneath` gains the
`Underneath` payload. No existing effect or event changes meaning.

## Runner (`session/SessionRunner.kt`), ported from the fork's `HudRunner`

Single-threaded on the main thread. Events raised while effects run are queued and handled
after the current event; the machine never sees two events interleaved. One timer
(`SessionTimer.schedule(atUptimeMs, task)` / `cancel()`), driven by
`SystemClock.uptimeMillis()`, never `elapsedRealtime` (the fork's R10: a deep sleep must not
expire a tap the wearer never left open). `SessionEffectSink.execute(effect)` then
`settled(state)` after each event; a throwing sink is reported through `onError` and does
not stop the drain. `state` is `@Volatile` for readers on other threads.

## Host (`session/SessionHost.kt`), ported from the fork's `HudHost`

One `TYPE_ACCESSIBILITY_OVERLAY` window, attached on `AttachHost`, detached on
`DetachHost`; `FLAG_KEEP_SCREEN_ON` only while attached (the 5 s vendor timeout memory:
never rewrite the setting). Opaque black background on the AR optics (not the fork's
translucent host). Draws: the gate (a one-line hint), the root (one text row per
`RootStop`, selected row marked, `Activities · n` row absent), a frame (title line plus
`Loading` / `Unavailable (<error>)` / `Shown (<revision>)`; no template rendering). The
host never reads a page's content beyond its title and state. If the window cannot be
added, dispatch `Abort` and log; never retry in a loop.

## Decisions taken for the executor

- The fork's `HudStateMachine` is not imported as a machine: `SessionReducer` is the one
  owner of the display and of input. Only its open handoff rules (15–17), `beneath` (18)
  and its runner/host/input scaffolding are adopted.
- The fork's `InputOwner.READER` (reader scroll semantics) is not ported: `ToSurface` keeps
  today's `SurfaceController` behaviour.
- `KEYCODE_NOTIFICATION` contact DOWN with `repeatCount == 0` is a `Contact`; ENTER UP is
  `Enter`; BACK UP is `Back`; DPAD UP/LEFT is `Step(-1)`, DOWN/RIGHT is `Step(+1)`; the
  swipe pair de-duplication of the fork (`DpadPairDedupe`, T3) is ported into the arbiter
  because the firmware sends duplicated swipe pairs (see the raw trace comment in
  `onKeyEvent`).
- When `backend == LEGACY`, the arbiter still owns ordering (A1, A2, A4 with
  `ToLegacyLauncher`, A6, A7); only the session branch is unreachable. This is what makes
  the activity removal and the editable exception identical on both backends.

# Acceptance tests

`$BASE` is the executor's starting HEAD, recorded with `git rev-parse HEAD` before the first
commit (expected `f37bcc7c`).

| # | Check | Command | Pass when |
|---|---|---|---|
| 1 | glasses-hub suite + build | `./gradlew :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug` | exit 0; report lists total tests and the deleted test names |
| 2 | phone-hub suite untouched | `./gradlew :phone-hub:testDebugUnitTest` | exit 0 |
| 3 | diff scope | `git diff --name-only $BASE..HEAD` | every path matches `scope_globs`, none matches `forbidden_globs` |
| 4 | shared untouched | `git diff --stat $BASE..HEAD -- shared BUSSPEC.md docs` | empty |
| 5 | arbiter tests | `InputArbiterTest` | named tests for A1–A8 incl.: gate trace TripleTap(1000), Contact(1700), Tick(1800), Enter(2500) absorbed; armed notice blocks recognition; editable blocks recognition; ring dropped during gate; native pass-through when nothing owns; backend switch exclusivity; orphan UP consumed; retargeting never happens across a press; TripleTap at the root followed by ENTER at +300 ms and BACK at +600 ms produces no `SendAction`, `ShowFrame` or close |
| 6 | reducer tests | `SessionReducerTest` | named tests for rules 15–20 incl.: cancelled open closes the late surface unseen; a second open keeps the first cancellation; `NexusSurface` underneath receives no close; `nextDeadlineMs` equals the earliest pending deadline |
| 7 | runner tests | `SessionRunnerTest` (ported) | re-entrant dispatch is queued, not interleaved; exactly one timer pending; a throwing sink does not stop the drain |
| 8 | activity removal | `grep -n "fun claimsInput\|fun claimsRingKey\|fun handleRingKey\|fun handleKeyEvent\|fun handlePendingTempleTap\|fireOrOpen" glasses-hub/src/main/java/com/anezium/rokidbus/glasses/ActivityController.kt; grep -rn "ActivityController\.\(claims\|handleKeyEvent\|handleRingKey\|handlePendingTempleTap\|cancelRingInput\)" glasses-hub/src/main` | both empty (`handleActivityEnvelope`, the wire handler, is kept and not matched) (`NoticeController`, `SurfaceController` and `LauncherOverlayRenderer` keep their own `claimsInput`/`handleRingKey`) |
| 12 | Review round 1 regressions | `./gradlew :glasses-hub:testDebugUnitTest` | the seven items under "Review round 1" each have a named test (items 1-6) or the NOTICE line (item 7), and pass |
| 9 | attribution | `grep -rln "alvarosw" glasses-hub/src NOTICE` | every ported file and `NOTICE` |
| 10 | default behaviour | code read | preference absent → `LEGACY`; `SessionHost` never attached on `LEGACY` |
| 11 | commits | `git log --format='%an %s%n%b' $BASE..HEAD` | author Anezium only; no `Co-Authored-By`, no AI name |

Device smoke test (owner, not the executor): `LEGACY` → triple tap opens today's launcher,
islands never react to a tap; `SESSION` → triple tap shows the gate then the root, BACK
closes and the app underneath is intact, `adb shell am broadcast` back to `LEGACY` with the
session open closes it.

# Executor stop 1 (Opus 5.5, 2026-10-08 18:10, no code written)

Three contradictions and four questions, settled above: acceptance 8 narrowed to
`ActivityController`; island selection state left undriven, not removed (option B);
re-trigger while open relies on the detector's suppression (option A, PR1 unchanged);
`PageItem.Launch(pluginId)` from the injected resolver only; every non-ring device is
`TOUCHPAD` in PR2; armed-notice predicate is `ownsRingInput()`; `CloseSurface` reuses the
existing close path including `/ink/closed`.

# Review round 1 (GPT-6.1 Sol, 2026-10-08, `design/nav-map-mockups/sol-review-pr2.md`)

Verdict "not merge-ready". Required before PR2 is closed (file:line as of `0790c4bc`):

1. **blocker** `InputArbiter.kt:229, :272`: on LEGACY, a notice that becomes armed after a
   triple tap must not defeat the detector's 800 ms ENTER/BACK suppression. Trace: contacts
   at 0/40, 200/240, 400/440 open the legacy launcher; an action-bearing notice arrives at
   500; ENTER DOWN at 700 must still be swallowed by the detector (it was before PR2), not
   routed to the notice. Separate "may recognise a new triple tap" (editable and armed-notice
   exclusions) from "an already-triggered suppression window is enforced" (always asked).
   Test with the real notice router: notice arriving after the trigger.
2. **blocker** `RokidBusAccessibilityService.kt:1392, :1426`, `SurfaceController.kt:978`
   (and the media / decoded-image branches at ~549/550 and ~600/601): a cancelled late
   surface is closed unseen by the synchronous `NexusSession` notification, then the
   original `showOrUpdate` resumes and calls `displaySurface` anyway, leaving a visible
   window with no active surface. Every presentation branch must stop when the notification
   synchronously cleared or replaced its active surface (cancellation vetoes presentation
   before publishing). Test at the publication/window level: `CloseSurface` can never be
   followed by a display of that surface.
3. **should-fix** `InputArbiter.kt:165`: a press is marked completed on its first UP; a
   duplicate UP (same device/key/downTime) or a post-UP repeat is consumed and emits no
   intent. Trace: page frame, BACK DOWN 3000 / UP 3030 pops to root; duplicate UP 3040 must
   not close the session.
4. **should-fix** `InputArbiter.kt:210`: the 5 s TTL applies only to completed records,
   never to a live press (a held SHIFT on a bonded keyboard with an editable card focused,
   then a letter at 7000 and SHIFT UP at 7100 with downTime 1000: both must pass as before).
5. **should-fix** `InputArbiter.kt:89, :153, :172`: a press owned by a session generation is
   delivered only to that generation; after Abort and a new session, the old UP is consumed
   silently. Trace: BACK DOWN 3000 at root; switch LEGACY 3020, SESSION 3040; contacts
   3100/3200/3300 open a new session, gate ends 4100; BACK UP 4200 (downTime 3000) must not
   close the new root.
6. **should-fix** `RokidBusAccessibilityService.kt:1374`: deduplicate notice arrivals by the
   notice instance identity (`NoticeInteractionIdentity`/instanceId), not by surfaceId: two
   successive Relay notices share `LOCAL_SURFACE_ID`. Test two same-plugin shows while the
   session suppresses the band: second preview and count of 2, root order and selection
   unchanged; cosmetic redraws and suppression toggles do not count as arrivals.
7. **should-fix** attribution: `SessionOverlayWindow` inside `RokidBusAccessibilityService.kt`
   is adapted from the fork's `HudHost`. Decision: do not put the fork header on the whole
   upstream service file; put the attribution comment on the `SessionOverlayWindow` class
   itself and list `RokidBusAccessibilityService.kt (SessionOverlayWindow only)` in `NOTICE`.

Then rerun acceptance 1-12 and report their real tails.

# Plan sketch

1. `input/` package + `InputArbiterTest` (pure, no wiring). Commit.
2. Reducer rules 15–20 + tests; `SessionRunner` + ported tests. Commit.
3. `SessionHost` + the bus sink (`RequestPage` → `/page/request`, etc.). Commit.
4. Wiring: service, `SurfaceActivity`, `LauncherOverlayRenderer`, ring path, activity
   branch removal, notice suppression, switch + receiver + manifest. Commit.
5. `NOTICE`, `glasses-hub/README.md` paragraph on the backend switch. Commit.
6. Acceptance 1–11, report.

# Context the executor cannot re-derive

- Today's chain: `RokidBusAccessibilityService.onKeyEvent` lines ≈153–223 (R08 → notice UP
  → `TripleTapDetector` → notice DOWN → `LauncherOverlayRenderer` → `SurfaceController` →
  `ActivityController`), `handleRingKeyEvent` ≈226, `handlePendingTempleTap` ≈331;
  `SurfaceActivity.dispatchKeyEvent` 47–50; `ActivityController.claimsInput` 501–506 (does
  not look at the foreground native app: the island can steal a tap inside Teleprompter
  today, which is why islands become passive everywhere).
- `TripleTapDetector`: 600 ms window, 800 ms post-trigger suppression of ENTER/BACK only;
  touchpad scancodes: contact 204 → `KEYCODE_NOTIFICATION`, tap 28 → ENTER ≈300 ms after
  contact, double tap 158 → BACK, swipes 103/105/106/108 → DPAD; one-finger hold 148 and
  two-finger 149 are the vendor assistants and never reach us as gestures.
- PR1 contract (`contracts/2026-10-08-launcher-rework-pr1-session-contract.contract.md`)
  rules 1–14 and its two review rounds are the reducer's authority; this contract only
  adds rules 15–20.
- Fork sources to port from: `glasses-hub/src/main/java/com/anezium/rokidbus/glasses/hud/`
  `HudInput.kt` (DeviceClass, RawKeyEvent, press ownership, orphan UP, DpadPairDedupe),
  `HudRunner.kt`, `HudHost.kt`, `HudKeyEventAdapter.kt`, `HudStateMachine.kt` (only
  `Opening`, `cancelledOpen`, `OpenFailed`, `beneath`), tests `HudInputTest`,
  `HudRunnerTest`, `HudHostTest`, `HudStateMachinePropertyTest` (port the generators for
  the open handoff only). The fork's main has moved 81 commits away from ours and it
  deleted `LauncherOverlayRenderer`, `RingSurfaceInputPolicy`, `SurfaceOverlayRenderer`:
  none of those deletions are imported.
- Glasses HUD: 480×640 portrait, green monochrome additive optics; pure black is
  transparent on the optics, so the host background is black on purpose.
- `NoticeController.noticeVisibleForInput(activeNotice, cameraOverlayActive)` at ≈84 is the
  suppression seam to reuse.

# Escalation triggers (mechanical — never self-assessed by the executor)

- Any test_command fails after `max_failures` (2) attempts.
- The diff touches a file outside `scope_globs` or matching `forbidden_globs`.
- A build fails for an environment reason (SDK path, Gradle distribution download, cache
  not writable, network refused).
- A rule here cannot be satisfied without contradicting a PR1 rule or the plan; report the
  conflict with the two rule numbers instead of picking one.
- An existing test outside the removed activity input branch would have to change.
- The worktree is not on `dev/launcher-rework` or `git status` shows files you did not
  create.

# Autonomy

May decide alone: file split inside `input/` and `session/`, private helper names, the exact
text rows of the minimal host, the order of tests, how the raw trace line is kept, which
fork test cases are worth porting beyond the ones named, the receiver's guard when
`OpenLauncherReceiver` has one to copy.

Must stop and report: anything in the escalation list; any wish to change
`TripleTapDetector`, `NoticeController` claim rules, `MAX_ACTIVE_ACTIVITIES`, a wire
constant, `PageSurfaceContract`, or a PR1 reducer rule; any need to touch the phone hub or
`:shared`; any doubt about whether a behaviour belongs to PR2 or PR3 (default: PR3, leave it
out and say so).
