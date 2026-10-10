Reviewed on 2026-10-08 by Claude Fable 5.1 through Cursor. The first Claude-provider attempt failed with an API usage limit and produced no review.

# Review: `qa/skills-workspace` (baseline `07001474`)

Scope reviewed: all tracked diffs plus the four untracked Media files. Root `AGENTS.md` and `plugins/AGENTS.md` read. No edits, builds, tests, or ADB were performed; everything below is from reading code and the shared contract (`SkillsContract.kt`, `SkillSchema.kt`, `SkillLimits.kt`, `SkillsCoordinator.kt`, `NexusSkills.kt`).

## Findings

### High

**H1 — Media choice label can be rejected by the SDK preflight, leaving the call to time out silently.**
`E:/Tools/Rokid/RokidNexus-skills-workspace-qa/plugins/media/src/main/java/com/anezium/rokidbus/plugin/media/MediaSkills.kt:47` builds `SkillProviderChoice(it.player.take(80), …)`, and `E:/Tools/Rokid/RokidNexus-skills-workspace-qa/plugins/media/src/main/java/com/anezium/rokidbus/media/session/AndroidMediaSkillAccess.kt:136` caps the app label at 80. The contract caps choice labels at `SkillLimits.MAX_CHOICE_LABEL_CHARS = 60` and requires them non-blank (`SkillsContract.kt:491-492`). `NexusPluginClient.sendSkillResult` (`bus-client/.../NexusPluginClient.kt:352-356`) runs `parseProviderResult` on the outgoing payload and returns `INVALID_PAYLOAD` without sending and without removing the live invocation. `E:/Tools/Rokid/RokidNexus-skills-workspace-qa/plugins/media/src/main/java/com/anezium/rokidbus/plugin/media/MediaDeckPluginService.kt:87-93` discards the `NexusSdkResult`, so nothing is ever answered and the hub waits out the 15 s deadline.
Repro: two playing sessions, one from an app whose label is >60 chars (or an app returning an empty `getApplicationLabel`) → `pause` → no answer → `deadline_exceeded` after 15 s.
Fix: cap labels with `SkillLimits.MAX_CHOICE_LABEL_CHARS` and fall back to the package name on blank (`AndroidMediaSkillAccess.player()`); in the service, check the result like Transit does (`TransitPluginService.kt:257-262`) and send `fail(UNAVAILABLE)` on `INVALID_PAYLOAD`.

### Medium

**M1 — Pre-dispatch exceptions on `pause` are reported as `unknown`, which is dishonest.**
`MediaDeckPluginService.kt:80-86` maps every exception to `Unknown` for `pause`. But `AndroidMediaSkillAccess.pause()` already catches everything around dispatch (lines 106-109, 119-120), so an exception can only come from `hasAccess()`/`sessions()` — before anything was sent. Concrete trigger: `check(controllers.size <= MAX_REFERENCES)` at `AndroidMediaSkillAccess.kt:40` throws `IllegalStateException` (and contradicts the `take(MAX_REFERENCES)` on line 44), or `getActiveSessions` throws `SecurityException` when listener access is revoked between `hasAccess()` and the read. The hub turns `unknown` into `SkillError(UNAVAILABLE, dispatch=UNKNOWN)` and the Assistant tells the wearer it cannot confirm whether playback paused, when nothing was dispatched.
Fix: drop the `check` (or return a `Failed`), and in the service map exceptions to `Failed(SETUP_REQUIRED)` / `Failed(UNAVAILABLE)` with `dispatch = NONE` for both operations; reserve `Unknown` for `MediaPauseOutcome.UNKNOWN`.

**M2 — `ACTION_PAUSE` gating is stricter than the HUD and may refuse players that pause fine.** (Hypothesis about real players; the asymmetry itself is a fact.)
`AndroidMediaSkillAccess.kt:91` returns `UNSUPPORTED` unless `ACTION_PAUSE` is advertised. The HUD's `MediaSessionMonitor.togglePlayback()` (`MediaSessionMonitor.kt:90-98`) sends `pause()` without checking actions. Apps that advertise only `ACTION_PLAY_PAUSE` would be pausable from the HUD but "unsupported" from the skill. `TransportControls.pause()` is an explicit pause regardless of advertised actions, so there is no toggle risk.
Fix: accept `ACTION_PAUSE or ACTION_PLAY_PAUSE` as the capability check, still send only `pause()`.

**M3 — Confirmation wait runs to the full invocation deadline when `STATE_PAUSED` is never observed.**
`AndroidMediaSkillAccess.kt:66-70` only recognises `STATE_PAUSED`; `AndroidMediaSkillAccess.kt:114-118` keeps waiting until ~250 ms before the deadline. A player that answers pause with `STATE_STOPPED`/`STATE_NONE`, or one that never posts a state, holds the Assistant turn ~14.7 s before returning `accepted`. Honest, but a bad wait for an action op.
Fix: after `dispatch == SENT`, bound the confirmation window (e.g. 2–3 s) and return `ACCEPTED`; optionally treat `STATE_STOPPED`/`STATE_NONE` after SENT as paused-equivalent or at least as an early exit.

**M4 — Inactive-journey `status` for a non-active ref clears the journey focus even though another journey is active.**
`E:/Tools/Rokid/RokidNexus-skills-workspace-qa/plugins/transit/src/main/java/com/anezium/rokidbus/plugin/transit/TransitJourneySkills.kt:82-84`: when `asked != active.id`, the result carries `focus(null)`, and `AssistantSkillMemory.record` (`AssistantSkills.kt:269-273`) overwrites the `transit|transit_journey_focus` slot with `active:false`. The model then believes no journey is running. Recoverable (the new policy line tells it to call status without a ref), but the memory is wrong until it does. `stop()` on line 120 already uses `focus(controller.active())`.
Fix: use `.put("focus", focus(active))` on line 83 so the focus always reflects the real active journey; keep top-level `active:false` for the asked ref.

### Low

**L1** `MediaSkills.kt:45` returns `BUSY` when more than 8 candidates exist; `busy` means hub load, not ambiguity. Prefer `UNAVAILABLE` or offer the first `SkillLimits.MAX_CHOICES`. Also replace the local `MAX_CHOICES` (`MediaSkills.kt:82`) with `SkillLimits.MAX_CHOICES`.

**L2** `MediaSkills.kt:44`: with no playing session but ≥2 paused ones, `get_now_playing` returns a Choice, so "what is playing?" asks "which player?" instead of answering that nothing is playing. Consider `no_session`/first paused for the read op; keep the choice only for `pause`.

**L3** `E:/Tools/Rokid/RokidNexus-skills-workspace-qa/plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/WorkspaceRetriever.kt:91`: `ranked.firstOrNull { qualifies(...) }` picks the best passage for a clause, but `add` may reject it (2-per-file cap or duplicate text) and the clause then gets nothing although the next-ranked eligible passage would fit. Use `ranked.firstOrNull { qualifies(it.first, group) && addable(it.first) }`. Related hypothesis: splitting on `and`/`et` turns a single-topic query such as "research and development budget" into `{research}` + `{development, budget}`, so any passage with just "research" in the body becomes eligible; plan 026 acknowledges this. If it shows up in practice, require ≥2 substantive terms per clause before treating the query as compound.

**L4** Budget sharing (`WorkspaceRetriever.kt:104-109`) applies to every query, while the plan text puts it under compound questions. Negligible in production because chunks are ≤ `MAX_CHUNK_CHARS = 800` and the equal split is ≈ 810 with three candidates; worth one sentence in the plan so the behaviour is documented.

**L5** `TransitSkills.kt:299-301` compiles three regexes on every `normalizeLabel` call; hoist them to the companion. `AndroidMediaSkillAccess.kt:66,72,107` allocate `setOf(...)` per callback; trivial.

**L6** `plugins/transit/src/main/res/raw/nexus_skills.json` new blocks (`groups`, three `focus` objects) use expanded multi-line style while the surrounding file is compact one-liners.

**L7** Operational: both catalogs' digests change, so Transit needs re-approval on devices and Media Deck is a new Skills approval. The device QA report should expect the Pending state.

**L8** `MediaDeckPluginService.kt:76`: `skillWork.execute` after `onDestroy`'s `shutdownNow()` would throw `RejectedExecutionException` on the main thread. Unlikely ordering; guard with `isShutdown` if you want it closed.

## Checked and found sound

- Dispatch gate (`AndroidMediaSkillAccess.kt:59-131`): `READY→SENDING→SENT` CAS with `READY→ABORTED` in `finally` means a cancelled/expired waiter can never let a late `start` send; `SENDING` at return → `UNKNOWN`, `SENT` → `ACCEPTED`, nothing sent → `DEADLINE`. Callbacks and `start` both run on main, so no pre-dispatch PAUSED can be mis-attributed. `onSessionDestroyed` before dispatch → `STALE`, after → `UNKNOWN`. Correct.
- Target exactness: refs resolve by `MediaSession.Token` equality; a destroyed/unknown ref → `STALE_REFERENCE`, never retargeted. `pause` reads no metadata; only app label and state appear in choices and output.
- Ref bounds: ≤32 process-local refs, pruned when the token is gone or idle >`REFERENCE_IDLE_MS`; `references` is only touched on the single skill thread. Hub dedupes handles per `(type,value)` (`SkillsCoordinator.kt:724-733`), so `journey` and `focus.journey` share one handle.
- Output schemas: `get_now_playing` `no_session` case, `pause` required `focus.session/player`, Transit `groups` (≤12, optional `line`), and three `transit_journey_focus` objects all match what the code emits; `nexusRef` strings have no `maxLength`, as the parser requires. Depth/node counts stay within limits. Tests validate every Completed payload against the catalog.
- Transit filter normalisation: NFKD + mark strip + apostrophe variants + dash/space collapse; `followAnchor`/`identifies` still compare feed-derived strings exactly, which is right. The unit test proves normalisation; it does not prove the real-device failure cause, as you noted.
- Workspace single-clause behaviour is unchanged (new `qualifies` ≡ old rule when there is one group), and the compound test is meaningful because the fixture uses one 2 800-char chunk per document, so without sharing the second topic would get no body.
- No new permissions, routes, or hub changes; the Media skill path never touches surfaces or the HUD monitor.

## Test gaps to add before or alongside the run

- Label >60 chars and blank label → choice label is clamped/fallback and the result is sent (covers H1).
- Nine playing sessions → whatever L1 decides, not `busy`.
- Service exception mapping for `pause` (covers M1) — currently only testable by extracting the mapping from `MediaDeckPluginService`.
- `status(journey = stale)` while another journey is active → focus reflects the active one (covers M4).

## Readiness

Ready for the test run. Everything compiles on inspection (`data object` is already used in the repo; signatures match the SDK), and no finding blocks executing the authored suites. H1, M1, and M4 are small, local fixes and I would apply them before the device pass since each changes an observable answer; M2 and M3 can be judged on device. I did not run any build or test.

## Implementation responses

- H1: use shared choice limits, nonblank fallback and distinct bounded labels; handle SDK INVALID_PAYLOAD without waiting for the hub timeout. A malformed read/choice fails with no dispatch, while malformed completed-pause delivery retains uncertainty about the effect.
- M1: catch all access/selection exceptions inside the non-mutating preparation stage and return known failures. The service's unexpected post-preparation action failure remains unknown to avoid claiming no effect after a possible dispatch. Add regression tests for revoked-access and excessive-session exceptions before pause.
- M2: accept advertised ACTION_PAUSE or ACTION_PLAY_PAUSE, while sending explicit pause only.
- M3: cap confirmation at 2.5 seconds after confirmed dispatch; STATE_STOPPED returns accepted rather than completed. The invocation deadline remains the hard bound. No paused-equivalence is inferred.
- M4: preserve the real active journey focus when a different journey was queried. Add a regression case.
- L1/L2: use shared choice count, fail unavailable rather than busy for excess candidates, and return no_session for unreferenced reads with no playing session. Exact referenced paused sessions remain readable.
- L3/L4: choose an addable passage for each clause and apply fair budget sharing only to compound queries.
- L5/L6: hoist normalization regexes, avoid per-callback sets, and retain the compact catalog style.
- L7/L8: expect catalog reapproval in device checks; refuse calls once the skill executor is shut down.

The conjunctive-query lexical broadening remains a documented heuristic. Live Transit filter diagnosis still requires on-device observation; the normalization unit test alone does not establish the original failure's cause.

## Follow-up disposition

The already completed follow-up review (`skills-workspace-fable51-review-20261008-r3-followup`, Claude Fable 5.1 through Cursor) approved the corrections. Every High and Medium finding was resolved. The reviewer ran no builds, tests, or ADB commands.

Remaining non-blocking remarks were the conservative invalid-payload fallback's shape, the deliberate no-session result for an unreferenced read with only paused sessions, and a redundant controller-list truncation. This approval applies to the code inspected at that time, before the on-device lifecycle correction below.

The user subsequently prohibited further Fable/Cursor use without an explicit request. No further delegated review was launched.

## Issue found during USB testing

The first Media Deck read returned the fixture track, but the following pause returned `stale_reference`, including a retry that read and paused within the same question. The provider registered anew for each invocation, and the hub releases its headless binding after the result. `AndroidMediaSkillAccess` had stored its token references in a Service-owned instance, so normal Service destruction discarded them.

The final implementation shares a bounded, synchronized `MediaSkillReferences<MediaSession.Token>` in process memory across recreated Services. It persists nothing, prunes expired or inactive tokens before selection, and never maps an old token to a replacement. Process death still intentionally invalidates these references. Four regression cases cover reuse with reordered controller lists, replacement, expiry, and excess-session rejection. This fix was reviewed locally and tested directly; it was not sent to Cursor.

The corrected device path read a track, then accepted an ordinary follow-up pause. The fixture logged one explicit pause and changed from playing (`3`) to paused (`2`). An ambiguous two-session pause required a choice before dispatch; the selected token alone paused. Ignored pause produced the truthful unconfirmed-command answer, and a destroyed reference produced a known failure with no retargeting. A fixture advertising only `ACTION_PLAY_PAUSE` also received explicit pause successfully.

The non-blocking invalid-payload fallback remark was also resolved locally: every pause outcome that might follow dispatch now falls back to `unknown`, including accepted/unknown; only known pre-dispatch failure/choice outcomes may fail unavailable. The no-session product choice remains deliberate, and the redundant truncation was removed with the reference-store correction. The final Media test/debug/release command passed with 20 tests and no failures; no further model review was requested or run.
