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
