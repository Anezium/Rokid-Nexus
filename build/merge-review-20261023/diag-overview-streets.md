# Whole-route streets: revision starvation

Base: `dev/launcher-rework` at `56707e7e`. Investigation: 2026-10-27.
Only the supplied private-safe captures were inspected; no devices were used.
Paths below are relative to the repository unless marked as capture paths.

## Observed cause

1. `PhonePageBroker.kt:717-752` commits `shownRevision` after a successful **send**,
   not after a glasses draw. `onRenderedMap` (`:263,284`) requires that forwarded
   revision at both gate and delivery. This intentionally rejects a positive
   report superseded while it travelled back. There is no second revision counter
   on the glasses: `session/SessionScreens.kt:789` copies the page revision into
   `RenderedMapContext`.
2. The glasses do not keep renewing an obsolete painted revision after accepting
   newer content. `page/RouteMapPainter.kt:388-396,419-431` binds the input and its
   authority, invalidates, paints, and only then calls `drawn()`.
   `page/RenderedMapLease.kt:43-51,80-88` marks every same-presentation revision
   pending and sends it after the next draw. Both the compact page and full map
   use this lease (`page/SessionScreenView.kt:141-147`).
3. Navigation's pacing has a bootstrap hole. `NavRenderedFrames.kt:49-52` holds a
   replacement only if an SDK acknowledgment **already exists**. Without a lease,
   every changed GPS snapshot is allowed to supersede the preceding publication.
   `NavPluginService.kt:42-47` applies this to pages and `NavRuntime.kt:48-55` to
   ambient geometry. `NavPages.kt:180-208` deduplicates unchanged content and
   rate-limits changes to 500 ms, but a moving fix changes the content every second.
   A delayed first report can therefore never establish the lease that would
   enable pacing. Expiry opens the same hole again.
4. The phone capture confirms this positive starvation: `whole-route-phone.log`
   lines 57,80,102 reject Follow revisions 281/282, 285/288 and 288/293;
   lines 124,152,177,203 reject Overview 290/298, 295/303, 299/308 and 303/314.
   `NAV_PUBLICATION ... NO_LEASE` at lines 97,127,149,173,198 continues admitting
   new pages. `NAV_CORRELATION ... NO_LEASE` yields no cover and no tile demand.
   This is a producer/feedback race, not an Overview geometry/paint defect.
5. The claimed Follow pass at 287/291 (phone lines 89-90), and the later 290/297
   pass (117-118), both have **visible=false**. Those are token revocations, which
   deliberately survive intervening revisions (`PhonePageBroker.kt:262-265`);
   neither authorizes streets. The capture contains no positive PHONE_PAGE pass
   for either selected view. Follow can still draw streets obtained under an
   independent ambient Follow lease (positive ambient passes at phone lines
   23,39,75). Overview needs its own distinct cover. This also explains the
   startup symptom: the first lease races 1 Hz updates until a report happens to
   beat the next send; expiry can restart the starvation. The capture begins
   during an existing journey, so it does not measure the reported 30-60 s startup
   interval or establish a particular tile-download latency.

## Timing and feedback-loop checks

`shared/.../MapFeedbackDiagnostics.kt:60-69,79` aggregates each gate/verdict/reason/
kind/view for five seconds. The sparse GLASSES_DRAW/GLASSES_SEND lines do not mean
draws/reports happen only every 5.5 seconds: intervening `SPP TX /map/rendered`
lines and increasing `count`/`suppressed` prove otherwise. Both device clocks
are independent; no cross-device wall-clock subtraction is used here.

Phone `BusHubService.kt:4281-4292` handles inbound SPP messages inline on its reader.
The supplied phone trace shows additional processing delay even after RX: page
report RX at 22:14:51.510, capability check at 51.806, revision gate at 52.143;
page revision 282 was already sent at 51.594. Later feedback falls progressively
further behind current page revisions. The capture cannot attribute that delay
to a specific blocking call or distinguish all transport/CPU causes.

There is no unconditional report -> page -> report loop. `NavJourneyService.kt:250-263`
republishes only when the effective basemap changes; stable renewal only flushes
an already dirty map. `NavPluginService.kt:161-164` catches up pages after that
reconciliation; `NavPages.kt:187-190` suppresses identical content/cover. Loaded
tiles, lease expiry and restoration can cause real additional revisions, but GPS
updates alone reproduce the missing-first-ack race. The earlier cover-retention
and retry fixes (`a21ee9d4`, `1bf6e031`) do not cover this bootstrap phase; their
delayed-feedback journey test seeds immediate ambient/page acknowledgments first.
`1a569654` supplies the gates which distinguish this failure from cover eviction.

## Fix boundary and safety

Fix Navigation's publication pacing, including its initial acknowledgment and
post-expiry recovery. Hold the last sent publication for a bounded quiet window
when no acknowledgment exists, coalesce incoming fixes, and retry at the actual
deadline rather than busy-posting at zero without an SDK lease. A first valid
acknowledgment releases the latest snapshot immediately. Missing feedback must
still permit eventual updates for hidden/Text/Commands presentations; waiting is
never authorization to fetch tiles.

Keep the phone's two exact-revision checks, registration/request/frame checks and
page lease unchanged. Accepting old revisions there would authorize obsolete
content. Changing the glasses to report a newer revision before painting it would
invent draw authority. No wire fields or SDK types need to change.

## Verification

The implementation is plugin-side only:

- `NavRenderedFrames.kt:17-24,49-69` records the last successful publication time
  per surface. A missing acknowledgment now holds replacements until that
  publication's five-second deadline, rather than bypassing pacing. The existing
  live-lease wait still applies when an older acknowledgment exists. Neither
  branch creates a lease or changes the publication-cover correlation.
- `NavSettings.kt:93-94,177-180` uses Android elapsed realtime and applies the gate
  only with negotiated rendered-map support. `NavPluginService.kt:43,93` and
  `NavRuntime.kt:49-53` share the computed nonzero retry delay, including when
  there is no SDK lease to supply a deadline. Missing feedback remains bounded;
  dirty pages/maps retain only the latest snapshot.
- The phone's `shownRevision` gates and glasses production code are unchanged.
  `BUSSPEC.md` and `PLUGIN_SDK.md` need no change: wire fields, accepted authority,
  SDK types and network authorization are unchanged. The plugin changelog and
  launcher completion report describe the new publication pacing.

New Navigation regressions in `NavJourneyHubTest.kt:221-297` start **without** seed
leases: Follow and Overview each receive 35 seconds of 1 Hz alternating synthetic
fixes, with feedback delayed 2.5 seconds from the very first page. They assert
no early requests, first-publication retention, continuous selected-view leases
after bootstrap, street-bearing pages, continuing publications and independent
page authority. Ambient startup has a separate 1.5-second delayed-feedback case.
Another case retries a missing first page report without another journey event.
`NavMapPublisherTest.kt:205-228` checks the exact quiet-window deadline, immediate
release on acknowledgment, missing-feedback retries and expiry recovery without
creating tile authority.

Hub regressions were added for the parent to execute:

- `PhonePageBrokerLauncherTest.kt:171-195`: after three newer 1 Hz revisions, an
  old report still cannot be delivered; replacing the route with a non-map page
  rejects both old and current-revision positive reports.
- `RenderedMapLeaseTest.kt:120-167`: compact and full-screen maps, both Follow and
  Overview, report a new revision only after drawing it. Binding alone sends no
  report; removal prevents later draw/heartbeat renewal.

Mutation check: restored the original no-lease bypass by adding `drawn == null`
to the gate's no-delay branch. The focused journey/publisher run failed **6 of
23 tests**, including both first Follow/Overview tests (revision 1 was replaced
by revision 2 before its first feedback), ambient startup, the event-free page
deadline and the bounded deadline unit test. The fixed source was restored
byte-for-byte in a `finally` block. See `overview-mutation.log` and
`overview-mutation-failures.txt`. Real tail:

```text
23 tests completed, 6 failed
> Task :plugin-nav:testDebugUnitTest FAILED
BUILD FAILED in 8s
64 actionable tasks: 1 executed, 63 up-to-date
```

Final verification forced all three test tasks to execute (the extra shared
debug task forces execution beneath its `test` aggregate):

```powershell
.\gradlew.bat :plugin-nav:testDebugUnitTest --rerun :shared:testDebugUnitTest --rerun :shared:test :bus-client:testDebugUnitTest --rerun -PskipCxrGlobal=true --console=plain
```

XML totals: Navigation **254**, shared **540**, bus client **212**; **1,006 tests,
zero failures/errors/skips**. The run compiled the changed Navigation production
and test sources. Real output excerpt and tail from `overview-verified-tests.log`:

```text
> Task :shared:testDebugUnitTest
> Task :shared:test
> Task :bus-client:testDebugUnitTest
> Task :plugin-nav:testDebugUnitTest

BUILD SUCCESSFUL in 18s
74 actionable tasks: 6 executed, 1 from cache, 67 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.1/userguide/configuration_cache_enabling.html
```

The first fresh full run exposed a bus-client concurrency-test failure in
`NexusPageTest` (`threads never blocked: [TERMINATED, TERMINATED]`). The focused
unmodified bus-client rerun and the final forced full suite both passed. No
bus-client source/test changes were made for it. Two Navigation assertions were
updated for the intentional bounded wait after lease loss; restored page streets
still propagate on feedback before another fix, and an island coming back
acknowledges its own pending publication before its geometry catches up.

Not run: phone/glasses test or assemble suites. `../CxrGlobal` is absent from
this worktree; the parent owns those checks. No settings or SDK/cache workarounds,
`local.properties` changes, private SDK/Gradle homes, device access, pushes, tags
or merges were performed. `git diff --check` passed.

Remaining device question: confirm that suppressing bootstrap publication churn
drains the observed return-path backlog on the release builds. The capture does
not locate its specific slow handler, measure cold-start duration, or prove the
fixed on-device behavior. The five-second quiet window is bounded pacing, not a
promise of continuous leases across return-path delays exceeding their lifetime.
