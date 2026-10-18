# Navigation map and transit device check — 2026-10-18

The owner's requested transit test ran against the currently installed compact
glasses and Navigation releases, source pin `9e26f358`. No source/build change was
made by the parent during this test. A newly observed rendering defect remains
under correction; this is not final acceptance of external transit detail pages.

## Route-page street map

Started another bounded real walking journey to the owner's public destination,
Gare de Goussainville. Opened the Navigation activity from the Nexus root and
waited six seconds on its route page. The actual glasses screenshot
`build/smoke/shots/nav-current-route-page-streets-verified.png` shows OSM streets,
the route and position, scale and attribution inside that page.

The previous gallery screenshot was taken before the street response. The gallery
now uses the new loaded-map capture. The earlier attempted recapture with a
misleading `basemap-loaded` filename actually opened the existing Relay chat while
Android's notification-permission dialog still blocked journey startup. It is not
used as Navigation evidence. Permission completion and the Navigation root were
subsequently checked before the successful capture. No source basemap failure was
established by this test.

## Real transit source and observations

Navigation's integrated planner currently requests walking routes. To test its
existing external transit adapter, opened Google Maps in transit mode toward the
same station. Selected a bus 1611 itinerary with an initial walk to its stop and
started Maps' glanceable guidance from its visible Start control. No ticket,
saved journey, account, clock, mock location or provider setting was changed.

Initially Maps posted an empty ongoing status notification. Navigation correctly
showed no activity for it. Once Maps was backgrounded, its notification supplied
walking instructions and Navigation appeared in the actual Nexus root. Observed
on the glasses:

- Root: Navigation with three minutes toward the stop and the source's arrival
  time, with the existing local Relay preview below it.
- Ambient: walking glyph, three minutes, 250 metres and the stop name.
- Detail: correct source/facts, but an incorrect lost-GPS glyph, `~3 min` and
  `GPS lost` warning. This differs from the correct ambient instruction.

The Google Maps notification supplies instructions, not the complete route
geometry or a measured position. The external route page is therefore text-only
by design. No transport map, boarding, vehicle ride, stop-by-stop progress or
alighting was physically validated. The itinerary screenshot establishes that
this is a transit journey's first walking leg, not a separate walking-only route.
Source times are reported as supplied by Maps; they are not travel advice.

## New defect and delegated correction

`NavRoutePage.notification()` sets `PageBody.Route.position=NONE`, no maps, and
the actual external maneuver/facts. `RoutePresentation.degraded()` treats every
non-LIVE body position as degraded, so the detail renderer converts this absence
of supplied position into a false GPS-loss claim. It must distinguish unavailable
external geometry from an actual degraded mapped journey without inventing LIVE
position or weakening genuine lost-GPS behavior.

The same Fable 5.1 High subscription implementation owner received a bounded
source/UI correction and regression task. Parent authors no app UI/source. The
new fix, actual affected tests/builds and a new independent review remain pending.
Prior compact R8 approval and 2,340 passing cases describe the previous pin only.

## Cleanup and evidence

Stopped the internal journey through Stop journey, and Maps through its visible
Fin control. Scoped inspection found zero remaining Maps notification records;
the Navigation journey service is absent. Maps' original permissions were already
granted and were unchanged. Temporary Navigation precise/coarse/notification
grants were restored to the saved false baseline with pre-existing metadata kept.
App guidance and Google Maps source switches were already on; neither changed.
No display preference, microphone, draft or Send action was used. Final phone is
Main with the hub, glasses native home; no devices are reserved by this task.

Local ignored evidence includes `build/glasses-ux-compact/transit-qa-state.json`,
`build/smoke/shots/transit-maps-*.png`, and the self-contained browser gallery
`build/smoke/nexus-transit-captures-20261018.html`. It separates current captures,
historical Navigation screenshots and source simulations. Current transit detail
is explicitly marked as an unresolved defect. Gallery previews at 728 and 360
reported no console errors, with content heights 1,489 and 1,301.


## Fable correction received

Fable delivered clean commit `17c36035eca033492714420805827250e127379b`,
merged into canonical `a484fb4616b7449e8944e3b8aef5edfaf999d141`. The
production change is limited to `RoutePresentation.degraded`: explicit stale
positions remain degraded; NONE without any supplied map preserves text guidance;
NONE on a mapped route still degrades. No publisher is marked LIVE or given
invented geometry. The regression preserves the source walking glyph/exact
value/facts without a false GPS warning, then verifies mapped NONE still shows
lost-position treatment in the same session.

Affected full glasses/Navigation tests and glasses debug/signed-release build
are running at the configured canonical checkout. New independent Sol High
review uses an immutable clean detached snapshot at the author commit. Prior
2340-case evidence is not a result for this new change. No corrected candidate
is installed yet; source review, observed checks and physical recapture remain
pending.


## Actual checks for the candidate fix

The parent's command completed at canonical `a484fb46` with exit 0:

```text
BUILD SUCCESSFUL in 2m 44s
259 actionable tasks: 16 executed, 243 up-to-date
```

Full glasses suite: 1,178 cases, zero failures/errors/skips, newly executed.
Navigation: 211 cases, zero failures/errors/skips, up-to-date output reused.
The 15-case RouteNavigationTest suite includes the new regression. The phone
suite was not part of this invocation; its earlier 952 cases are prior evidence.
Glasses debug and release assemblies succeeded. The frozen signed candidate is
`build/glasses-ux-compact/apks/transit-gps-glasses-17c36035.apk`, SHA-256
`ee083e1e0076e4fffb26b1841b0a7d9db1639818aa34bebab7ea71d13d315bbc`.
The existing release signer was verified.

Actual log: `build/glasses-ux-compact/transit-false-gps-r11-tests-build.log`;
XML summary: `transit-r11-test-results.json`. Independent source review is still
running and has received these observed results. Candidate not yet installed;
physical corrected-page recapture and final approval remain pending.


## Approved fix installed; recapture blocked before ingestion

Fresh independent Sol review approved `17c36035` after reading the full prior
context, affected source/callers and observed test/build evidence. Verified the
frozen candidate hash and established release certificate again, then installed
that glasses APK in place with `-r`: Success. Navigation's APK was unchanged; its
installed SHA-256 still matches the accepted `555122c8...` release.

A new real Maps bus 1611 guidance session posted valid ongoing navigation
instructions, but Navigation no longer ingested them. Its Android notification
access remained allowed and its source switches on, while NMS's 19 live listener
records contained no Navigation listener. An ActivityManager service record alone
was misleading: its binder was null, requested/received/hasBound were all false
and no hosting process was shown in that binding block. The precise OS cause is
not established. This occurs before parser/publication/rendering, not in the
reviewed glasses GPS presentation decision.

Bounded recovery attempts used ordinary UI: opened the glasses hub; stopped and
started the phone hub; rescanned the Maps source while restoring its enabled
state; and cycled only Navigation's existing notification access, restoring it
to allowed. Global DND mode and notification-policy access list were explicitly
compared before/after and unchanged. No USB/ADB reset, uninstall, app-data clear,
clock/location simulation or account/signing change occurred. None of these
attempts restored a live listener, so they are not repeated as a watcher.

Astra's separate read-only diagnosis confirmed the ingestion boundary and the
supported in-process `NotificationListenerService.requestRebind` API under the
existing grant. The current Navigation source has no hook for it. Fable is now
implementing a bounded foreground/settings-entry recovery, without granting
access or retrying in the background. New source/tests/review and actual device
verification will be required for that separate change.

The attempted `transit-fixed*-detail` and similarly named PNGs from this pass
actually show the Notifications collection because no Navigation activity was
present. They are excluded from acceptance and the published gallery. No fixed
external detail-page appearance is claimed.

Maps was explicitly ended through Fin again; phone returned to Main and glasses
to native home. No internal journey or microphone/draft/Send action ran in this
pass. Navigation's runtime location/notification permissions remain at their
false baseline; Maps' original grants were unchanged. Devices are released while
the author works. New source acceptance does not erase this device-QA limitation.
