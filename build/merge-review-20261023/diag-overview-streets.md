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

Implementation, regression and mutation results will be appended after execution.
