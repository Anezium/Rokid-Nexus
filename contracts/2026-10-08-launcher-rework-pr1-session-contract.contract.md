---
task: launcher-rework-pr1-session-contract
date: 2026-10-08
status: active
branch: dev/launcher-rework
worktree: E:\Tools\Rokid\RokidNexus-launcher-rework
scope_globs:
  - "BUSSPEC.md"
  - "docs/PLUGIN_SDK.md"
  - "shared/src/main/java/com/anezium/rokidbus/shared/PageSurfaceContract.kt"
  - "shared/src/test/java/com/anezium/rokidbus/shared/PageSurfaceContractTest.kt"
  - "shared/src/main/java/com/anezium/rokidbus/shared/BusConstants.kt"
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/session/**"
  - "glasses-hub/src/test/java/com/anezium/rokidbus/glasses/session/**"
forbidden_globs:
  - "local.properties"
  - "settings.gradle.kts"
  - "**/build.gradle.kts"
  - "gradle/**"
  - "bus-client/**"
  - "phone-hub/**"
  - "plugins/**"
  - "ink-engine/**"
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/RokidBusAccessibilityService.kt"
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/ActivityController.kt"
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/NoticeController.kt"
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/NoticeKeyDispatcher.kt"
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/LauncherOverlayRenderer.kt"
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceController.kt"
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/SurfaceActivity.kt"
  - "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/TouchpadGestureDetectors.kt"
  - "glasses-hub/src/main/AndroidManifest.xml"
  - "shared/src/main/java/com/anezium/rokidbus/shared/GlassesHubCapabilitiesContract.kt"
  - "shared/src/main/java/com/anezium/rokidbus/shared/PhoneHubCapabilitiesContract.kt"
  - "shared/src/main/java/com/anezium/rokidbus/shared/plugin/**"
  - "plans/**"
  - "docs/mockups/**"
test_commands:
  - "./gradlew :shared:testDebugUnitTest"
  - "./gradlew :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug"
max_failures: 2
---

# Goal

PR1 of `plans/027-glasses-home-stack.md` ("Interaction contract + pages v1"). When this
is done, the repository contains (1) the wire and behaviour specification of the Nexus
session, its anchored stack root, its frames and the hub-pulled page protocol, written
into `BUSSPEC.md` and `docs/PLUGIN_SDK.md` and explicitly marked as not yet active;
(2) a pure, dependency-free contract layer: `PageSurfaceContract` in `:shared`
(payload validation and limits) and a `SessionReducer` package in `:glasses-hub`
(state, events, effects) whose unit tests prove every transition the plan calls out;
(3) nothing wired into the running hubs. Both test suites are green. No runtime
behaviour of either hub or of any plugin changes.

# Non-goals

- Do not wire the reducer into `RokidBusAccessibilityService`, `ActivityController`,
  `LauncherOverlayRenderer`, `SurfaceController` or any window. No class outside the
  new `session` package may reference it. That is PR2/PR3.
- Do not import anything from the alvarosw fork (`E:\Tools\Rokid\_forks\alvarosw`).
  That is PR2.
- Do not announce `pageSessionVersion` or any new field on `/system/hub/capabilities`,
  and do not add a plugin capability. Define the names as constants only.
- Do not add the SDK Kotlin API (`onNexusPageRequest` etc.) to `:bus-client`; PR1 only
  documents it as a preview.
- No templates rendering, no Ink page profile, no Navigation widget v2, no notification
  history, no composer, no per-app ambient modes, no foreground resolver.
- Do not touch the alvarosw fork, `local.properties`, Gradle files, or any file outside
  `scope_globs`.

# Constraints

- MUST write all code, comments, docs and the commit message in English, in the style
  of the surrounding code (see `ActivitySurfaceContract.kt` and
  `ActivityStateMachine` in `ActivityController.kt` for the house style: `object`
  contracts with `const val` limits, sealed validation results, JUnit4 tests with
  backtick names).
- MUST keep `shared/.../PageSurfaceContract.kt` free of Android imports beyond
  `org.json` (the module is an Android library, but the contract must stay testable
  on the JVM like `ActivitySurfaceContract`).
- MUST keep `glasses-hub/.../session/**` free of any `android.*` import and of any
  reference to existing hub singletons. Time is an injected `nowMs: Long`; nothing
  schedules, nothing posts to a Handler. Effects are returned as values.
- MUST place the new BUSSPEC section after `## Activity protocol v1` (its last
  subsection `### Lifecycle, reconnect, and errors` ends before `## Camera contract`,
  line ≈ 1577) and title it `## Session and page protocol v1 (specified, not active)`.
- MUST place the SDK text as a new section `## Pages (preview, not yet shipped)` in
  `docs/PLUGIN_SDK.md`, after the activity section, stating in its first paragraph that
  no hub version implements it yet.
- MUST keep every existing test green. MUST NOT modify or delete an existing test.
- MUST NOT change `MAX_ACTIVE_ACTIVITIES` or any constant of an existing contract.
- MUST NOT add a new value to `PluginCapability` (an unknown capability value
  invalidates a descriptor on old phones; see `shared/.../plugin/PluginDescriptor.kt:81-86`).
  Provider opt-in is the additive metadata key named below.
- MUST NOT add routes to `BusPaths` other than the six `/page/*` paths listed below.
- MUST commit on `dev/launcher-rework` in the worktree, in small commits (at least:
  docs, shared contract + tests, reducer + tests), author Anezium, no AI attribution,
  no `Co-Authored-By`. MUST NOT push. MUST NOT switch branches, rebase, or touch the
  main checkout at `E:\Tools\Rokid\RokidNexus`.
- MUST paste the real tail of each test command's output in the final report. If a
  build fails for an environment reason (SDK, Gradle distribution, network), stop and
  report; do not repair the environment.

## Wire contract to specify and implement

Paths (add to `BusPaths`): `/page/request`, `/page/response`, `/page/action`,
`/page/result`, `/page/visibility`, `/page/closed`. Direction: request, action,
visibility and closed go hub → provider plugin; response and result go plugin → hub.
The phone hub stamps the authenticated plugin id on provider frames, as it does for
activities; the glasses hub never trusts a plugin id carried in a payload.

Constants (`PageSurfaceContract`):

| Name | Value |
|---|---|
| `VERSION` | 1 |
| `GATE_MS` | 800 |
| `LOADING_HINT_MS` | 2 000 |
| `PAGE_TIMEOUT_MS` | 8 000 (measured from the initiating selection) |
| `COLD_REGISTRATION_BUDGET_MS` | 5 000 (included in the 8 000) |
| `LEASE_MS` | 120 000 |
| `LEASE_RENEW_MS` | 30 000 |
| `MAX_FRAMES` | 6, root included |
| `MAX_ROOT_STOPS` | 6 |
| `MAX_ID_CHARS` | 128 (page ids, action ids, invocation ids, request ids) |
| `MAX_ACTIONS` | 64 per page |
| `MAX_PARAMS_BYTES` | 2 048 (UTF-8 of the serialized `params` object) |
| `MAX_DATASET_BYTES` | 2 048 (UTF-8 of the serialized `data` object) |
| `MAX_PAGE_BYTES` | 65 536 (UTF-8 of the whole serialized response payload) |
| `MAX_SNAPSHOT_TOTAL_BYTES` | 524 288 (sum of retained frame snapshots) |
| `META_PLUGIN_PAGES` | `"rokidbus.plugin.pages"` (additive metadata, value `"1"`) |
| `CAPABILITY_FIELD_PAGE_SESSION_VERSION` | `"pageSessionVersion"` (name only, not announced) |
| `TEMPLATE_*` | `summary`, `selectableList`, `document`, `commands`, `conversation`, `media`, `route`, `ink` |
| errors | `INVALID_PAGE_REQUEST`, `INVALID_PAGE`, `PAGE_TOO_LARGE`, `PAGE_TIMEOUT`, `PAGE_UNAVAILABLE`, `FRAME_LIMIT`, `STALE_GENERATION`, `UNCONFIRMED_ACTION` |

Payload shapes (document in BUSSPEC, validate in the contract):

- `/page/request`: `requestId`, `sessionGeneration` (long), `frameIndex` (0 = root),
  `pageId`, `params` (object, optional), `reason` ∈ `open | refresh | retry`.
- `/page/response`: `requestId`, `sessionGeneration`, `frameIndex`, `pageId`,
  `revision` (long, monotonic per page), `template`, `title` (≤ 48 chars), `body`
  (object, template-specific, opaque to PR1 beyond size), `actions` (array ≤ 64 of
  `{id, label ≤ 24, kind ∈ hub | plugin | immersion, confirm: boolean}`),
  `live` (boolean: provider will send revisions while the lease holds), or
  `error` ∈ the error list instead of a page.
- `/page/action`: `invocationId`, `sessionGeneration`, `frameIndex`, `pageId`,
  `revision` (the revision the wearer saw), `actionId`.
- `/page/result`: `invocationId`, `status` ∈ `done | rejected | stale`, optional
  `message` (≤ 64 chars), optional replacement page as in response.
- `/page/visibility`: `pageId`, `visible` (boolean), `leaseUntilMs` (hub clock, absent
  when not visible). Sent on cover, uncover and every renewal.
- `/page/closed`: `pageId`, `reason` ∈ `back | session_closed | timeout | link_lost |
  replaced | frame_limit`.

Validation rules: every id matches `PluginDescriptor.isValidId`-like safety
(printable ASCII, no whitespace, ≤ 128); a response whose `sessionGeneration` or
`requestId` does not match the pending request is `STALE_GENERATION`; a response above
`MAX_PAGE_BYTES` is `PAGE_TOO_LARGE`; `actions` above 64 or a label above 24 chars is
`INVALID_PAGE`; a `template` outside the list is `INVALID_PAGE`; `ink` template requires
the `ink_surface` grant (document only; the check itself lands with the renderer).

## Reducer to implement (`glasses-hub/.../session/SessionReducer.kt`)

States (sealed): `Closed`, `Opening(sinceMs)`, `Root(stops, selected)`,
`InPage(frames: List<Frame>, root)`, with `Frame(pageId, status ∈ Loading(sinceMs,
requestId) | Shown(snapshot, revision, leaseUntilMs) | Unavailable(lastSnapshot?,
reason), selected)`. A `Snapshot` is the validated response payload plus its byte size.

Events (sealed): `TripleTap(nowMs)`, `Contact(nowMs)`, `Enter(nowMs)`,
`Back(nowMs)`, `Step(delta ∈ ±1, nowMs)`, `Tick(nowMs)`, `NoticeArmed`,
`NoticeCleared`, `NoticeArrived(preview)`, `ActivityEnded(stopId)`,
`ActivityStarted(stop)`, `PageResponse(payload, nowMs)`, `PageResult(payload, nowMs)`,
`LinkLost`, `LinkRestored`, `EditableFocused(boolean)`.

Effects (sealed, returned, never executed): `ShowGate`, `ShowRoot`, `ShowFrame`,
`RequestPage(request)`, `SendAction(action)`, `SendVisibility(pageId, visible,
leaseUntilMs?)`, `SendClosed(pageId, reason)`, `RestoreUnderneath`, `None`.

Rules (each is a test):

1. `TripleTap` in `Closed` → `Opening`, effect `ShowGate`. `TripleTap` while
   `NoticeArmed` is in force → no transition, no effect (the detector must not fire;
   the reducer refuses as defence in depth). `TripleTap` while `EditableFocused(true)`
   → ignored.
2. In `Opening`, every `Contact`, `Enter`, `Back`, `Step` is absorbed with no effect;
   a `Tick` at `sinceMs + GATE_MS` or later → `Root`, effect `ShowRoot`. A `Contact`
   begun inside the gate whose classification (`Enter`/`Back`/`Step`) arrives after
   the deadline is still absorbed: the reducer tracks pending contacts started during
   the gate.
3. `Root`: `Step` moves the selection without wrap (at either end it stays); `Enter`
   on a stop → `InPage` with one `Loading` frame, effect `RequestPage(frameIndex=1,
   reason=open)`; `Back` → `Closed`, effect `RestoreUnderneath`; `TripleTap` → stays
   at root. Stop order and the stop list are frozen for the whole session:
   `ActivityStarted` while open is queued and only applied in `Closed`;
   `ActivityEnded` marks the stop `ended` in place without reordering; `NoticeArrived`
   while open updates the preview stop's text and a counter, never the order or the
   selection.
4. The root never exceeds `MAX_ROOT_STOPS`; the builder orders: pinned activity,
   second activity, latest notification preview, `Notifications`, `Applications`, and
   inserts `Activities · n` before `Notifications` when more than two activities are
   live.
5. `InPage`: `Back` pops one frame, effect `SendClosed(pageId, back)` for the popped
   frame and `SendVisibility(visible=true, lease)` for the uncovered one; `Back` on
   the last frame → `Root` (same selection as before entering). `Enter` on an item
   that opens a page pushes a frame; pushing beyond `MAX_FRAMES` is refused with no
   request sent and the frame marked `FRAME_LIMIT` reason in its last effect.
6. Only the top frame may have a pending request; a second `Enter` while the top frame
   is `Loading` is ignored (single action per gesture, no queue).
7. `PageResponse` whose `requestId` or `sessionGeneration` does not match the pending
   request is dropped (`STALE_GENERATION`), including a response that arrives after
   `Back` already popped the frame or after the session closed. A late response never
   reopens anything.
8. `Tick` at `sinceMs + LOADING_HINT_MS` marks the frame as "still loading" (a flag in
   `Loading`); `Tick` at `sinceMs + PAGE_TIMEOUT_MS` → `Unavailable(reason=PAGE_TIMEOUT)`
   keeping the previous snapshot if the request was a refresh; `Enter` on
   `Unavailable` with the Retry item selected → `Loading` again with a new request
   (`reason=retry`).
9. Covering a frame (pushing on top of it) sends `SendVisibility(visible=false)` and
   drops its lease; uncovering restarts validation (`RequestPage(reason=refresh)`)
   while keeping the snapshot displayed. Renewal: a `Tick` within `LEASE_RENEW_MS` of
   the lease expiry on a visible `Shown` frame emits `SendVisibility(visible=true,
   newLease)`.
10. Live revisions (`PageResponse` with a higher `revision`, same `pageId`, no
    pending request) replace the snapshot only on the visible frame; on a covered
    frame they are dropped. A revision lower than or equal to the current one is dropped.
11. Action: `Enter` on an action item emits `SendAction` with a fresh `invocationId`
    and the shown `revision`; while the invocation is pending the frame ignores a
    second `Enter`; `PageResult(done)` clears it; `PageResult(stale|rejected)` clears
    it and keeps the page; no result before `PAGE_TIMEOUT_MS` → the action is marked
    `UNCONFIRMED_ACTION`, never retried by the reducer.
12. `LinkLost` in `InPage`: every frame becomes `Unavailable(lastSnapshot,
    reason=link_lost)`, all leases and pending invocations are invalidated, `Back`
    still pops locally with no effect sent, the session stays open; `LinkRestored`
    does not replay any request or action by itself (the next `Enter` on Retry does).
13. Retained snapshot bytes are summed over all frames; pushing a frame whose snapshot
    would exceed `MAX_SNAPSHOT_TOTAL_BYTES` evicts the oldest covered snapshot
    (keeping its `pageId` for refresh) before accepting.
14. `Closed` ignores everything except `TripleTap`, `ActivityStarted/Ended`,
    `NoticeArrived/Armed/Cleared` and `EditableFocused` (state bookkeeping only; the
    editable flag is recorded in every state so that rule 1 sees it, and it is cleared
    by `EditableFocused(false)` in every state). `LinkLost/LinkRestored` are tracked
    only while a session is open; the host re-sends the link state when a session
    opens (PR2).

Decisions taken for the executor's questions: snapshot bytes are the UTF-8 size of the
serialized payload (rule 13 is tested with an injected smaller budget); what a selected
item does comes from an injected item resolver (default: a `plugin` action without
`confirm` sends an action, `confirm`, `hub` and `immersion` items are inert until PR2);
the first display of a frame also emits `SendVisibility(visible=true, lease)` (one
sentence added to BUSSPEC); live revisions follow the stricter BUSSPEC text (`live:
true`, lease held, same original request id), which satisfies rule 10.

# Acceptance tests

`$BASE` is the worktree HEAD recorded by the executor with `git rev-parse HEAD`
before its first edit (the owner's plan, mockup and contract commits lie below it and
are excluded from the scope, test-file and commit checks).

| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Shared contract tests | `./gradlew :shared:testDebugUnitTest` | BUILD SUCCESSFUL, `PageSurfaceContractTest` ≥ 12 tests covering: valid request, each error code, byte limits computed on UTF-8, template list, action label limit, id safety |
| 2 | Reducer tests | `./gradlew :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug` | BUILD SUCCESSFUL, `SessionReducerTest` with at least one test per rule 1–14 (≥ 20 tests), named after the rule |
| 3 | No wiring | `git grep -n "session\.Session" -- glasses-hub/src/main | grep -v "/session/"` | no output |
| 4 | No Android in the reducer | `git grep -n "^import android" -- glasses-hub/src/main/java/com/anezium/rokidbus/glasses/session` | no output |
| 5 | Scope | `git diff --name-only $BASE..HEAD` | every path matches `scope_globs`, none matches `forbidden_globs` |
| 6 | Docs placed | `grep -n "## Session and page protocol v1 (specified, not active)" BUSSPEC.md` and `grep -n "## Pages (preview, not yet shipped)" docs/PLUGIN_SDK.md` | one hit each, BUSSPEC hit after the `## Activity protocol v1` section and before `## Camera contract` |
| 7 | Existing suites intact | same as 1 and 2 | no previously existing test removed or renamed (`git diff --stat $BASE..HEAD -- '*Test.kt'` shows only the two new files) |
| 8 | Commits | `git log --format='%an %s' $BASE..HEAD` | author Anezium on every commit, ≥ 3 commits, no AI attribution |

# Plan sketch

1. Read `plans/027-glasses-home-stack.md` fully (sections 3, 4, 5 are the authority),
   then `BUSSPEC.md` 848–1577 (notice and activity protocols) and
   `ActivitySurfaceContract.kt` + its test for the house style.
2. Write the BUSSPEC section: session model (base / session / frames), the gate, the
   gesture owner table from plan §4 (including the armed-notice exclusion and the
   editable exception), the six paths, payloads, limits, errors, lease, link loss,
   negotiation (`pageSessionVersion` in both hub announcements as a future additive
   field; provider opt-in via `rokidbus.plugin.pages`). Commit.
3. Write the SDK preview section: provider callbacks `onNexusPageRequest(request) →
   NexusPage`, `onNexusPageAction(action) → NexusPageResult`, `onNexusPageVisibility`,
   `onNexusPageClosed`, what a template page is, what is forbidden (no `/launcher/open`
   from a page, no surface stacking), sizes. Commit with step 2 if small.
4. Implement `PageSurfaceContract` + tests. Run test command 1. Commit.
5. Implement the `session` package: `SessionState.kt`, `SessionEvent.kt`,
   `SessionEffect.kt`, `SessionReducer.kt`, `RootStops.kt` (builder of rule 4), and
   `SessionReducerTest.kt`. Run test command 2. Commit.
6. Run acceptance checks 3–8, write the report with the real command tails.

# Context the executor cannot re-derive

- Shared checkout rule: the owner runs several sessions on one clone. Work ONLY in the
  worktree `E:\Tools\Rokid\RokidNexus-launcher-rework` (branch `dev/launcher-rework`,
  starting commit = `$BASE`, see Acceptance tests). Never `git checkout` another branch there. Commit at
  every coherent step; an uncommitted file can vanish.
- `local.properties` in the worktree is a verbatim copy of the main checkout's; it is
  untracked and must not be edited or regenerated. The hubs link against the vendor CXR
  library, so build `:glasses-hub` WITHOUT `-PskipCxrGlobal=true`. Gradle 9.5.1 and
  Java 17 are installed; a first build in a fresh worktree takes several minutes.
- Keep command output small: redirect Gradle output to a file under the worktree's
  `build/` and tail it (the T3 relay stalls on multi-megabyte stdout).
- Key chain today (`RokidBusAccessibilityService.onKeyEvent`, lines 153–223): R08 ring
  path first, then notice UP handling, then `TripleTapDetector`, then notice → launcher
  overlay → surface → activity. `TripleTapDetector` (`TouchpadGestureDetectors.kt`)
  counts contacts (KEYCODE_NOTIFICATION) within a 600 ms window and suppresses ENTER and
  BACK for 800 ms after a trigger. The firmware classifies each contact as ENTER about
  300 ms after it; this is why the triple tap cannot be recognised while a notice is
  armed (plan §3 decision 1): taps 1–2 would already have answered the notice.
- Notice claims today (`NoticeController.kt:59–96`, `NoticeKeyDispatcher.kt`): an
  interactive or action-bearing notice claims confirm; more than one action or a paged
  band claims the directions; BACK dismisses any visible notice; a backdrop notice claims
  every unhandled classification; a camera overlay suppresses notice input; an editable
  surface takes confirm/directions back. The reducer models "notice armed" as an
  opaque boolean fed by the host; it must not reimplement these rules.
- Activity input theft today (`ActivityController.kt:501–506, 526–540, 746–758`):
  `claimsInput()` ignores the foreground native app, `handleKeyEvent` swallows ENTER and
  opens the owner plugin through `GlassesHub.openLauncherEntry`. Plan decision 2 removes
  activity input everywhere; PR1 only documents it, PR2/3 delete it.
- Both activity stores evict at two (`ActivitySurfaceContract.MAX_ACTIVE_ACTIVITIES`,
  `PhoneActivityState.kt:88–94`, `ActivityController.kt:116–123`). The root builder
  (rule 4) must therefore accept any number of live activities as input but the plan's
  "collection of 8" is negotiated later; do not change the constant.
- Hub capability announcements are additive JSON on `/system/hub/capabilities`
  (`BUSSPEC.md:1901–1919`); unknown fields are ignored in both directions. That is why
  `pageSessionVersion` can be added later without breaking old peers, and why PR1 only
  reserves the name.
- `PluginDescriptor.parse` rejects a descriptor whose capability list contains an
  unknown value (`shared/.../plugin/PluginDescriptor.kt:81–86`,
  `PluginCapability.kt:21–29`); unknown metadata KEYS are ignored. Hence provider
  opt-in through metadata, never through a capability.
- Byte limits are measured on the UTF-8 encoding of the serialized JSON
  (`toString().toByteArray(Charsets.UTF_8).size`), the same way `ink-engine` measures
  `MAX_PAGE_BYTES`.
- Test style: JUnit4 (`org.junit.Test`, `org.junit.Assert.*`), backtick test names,
  `org.json` available on the JVM test classpath in `:shared` and `:glasses-hub`.
- HUD facts for the docs: 480×640 portrait, 240 dpi, green monochrome additive optics.
  Touchpad: tap, one-axis swipe, double tap = BACK, triple tap = Nexus; long press and
  two-finger gestures belong to the firmware. Do not restate these as open questions.

# Escalation triggers (mechanical — never self-assessed by the executor)

- Any test_command fails after `max_failures` (2) attempts.
- The diff touches a file outside `scope_globs` or matching `forbidden_globs`.
- A build fails for an environment reason (SDK path, Gradle distribution download,
  cache not writable, network refused).
- A rule in "Reducer to implement" cannot be satisfied without contradicting another
  rule or the plan; report the conflict with the two rule numbers instead of picking one.
- The worktree is not on `dev/launcher-rework` or `git status` shows files you did not
  create.

# Autonomy

May decide alone: file split inside the `session` package, names of private helpers,
exact wording of the docs as long as every element above is present, the order of
tests, the JSON field order, the way byte sizes are computed as long as it is UTF-8 of
the serialized payload.

Must stop and report: anything in the escalation list; any wish to change an existing
constant, test, route or public type; any need to touch `:bus-client` or the phone hub;
any doubt about whether a behaviour belongs to PR1 or to PR2 (default: it belongs to
PR2, leave it out and say so).
