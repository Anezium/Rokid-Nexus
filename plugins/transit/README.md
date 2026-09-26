# Transit

Transit puts live public transport on the glasses: nearby stops with
live departures ("Near Me"), and a favourites board for the stops you actually
use.

## How it works

- `TransitRuntime` owns the domain logic (repository, location provider,
  favourites store) behind a small host interface; `TransitPluginService` is
  the `NexusPluginService` adapter that renders it through SDK cards with
  rich lines.
- Near Me runs a location foreground service (`foregroundServiceType=
  "location"`) that the plugin starts and stops itself, permission-guarded.
- `TransitSettingsActivity` (NexusUi kit) manages the location permission,
  stop search, favourites, and uninstall.
- On first approval the hub offers a one-shot migration of favourites saved
  by the old built-in Transit (`TransitLegacyMigrationReceiver`).

## Skills

Transit publishes skill operations (`res/raw/nexus_skills.json`) that an
approved assistant can call through the phone hub while Transit stays
dormant. The wearer allows each operation for each assistant on Transit's
Plugin access screen; all start off.

- `list_favorites` pages the saved stops, twenty at a time, with a cursor
  that a changed list invalidates instead of skipping rows. The list's order
  is not a preference: with several candidates the assistant asks.
- `search_stops` returns at most eight stops and says when it cut the list.
- `get_departures` returns at most twelve departures at a stop with the time
  the board was observed and, per row, whether the time is a live prediction,
  the timetable, or unknown. Line and direction selectors distinguish "no such
  line" from "which direction?". A follow-up passes the departure just
  mentioned as `after` and continues in its own line and direction, matched by
  the feed's trip id or a unique line, direction, and scheduled time, never by
  row position. A board is reused for thirty seconds, then refreshed; a failed
  refresh is reported, never served stale. Walking time to the stop is not
  known.
- `start_journey`, `journey_status`, and `stop_journey` run journey guidance
  (below).

Stops, departures, and journeys reach the assistant as opaque references the
hub issues; the stop identifiers stay with Transit and the hub. Each call
fetches once within its deadline and starts no refresh loop or location.

## Journey guidance

"Take me home" plans a public transport journey from the current position to
the saved home (or to a stop from an earlier answer) and guides it on the
glasses in Transit's own activity, without opening any other app. Planning
uses the routing endpoint of the service Transit already queries
(`TransitJourneyPlanner`, a seam another provider could replace), keeping each
leg's absolute times and realtime flag. The first itinerary is guided; the
answer counts the alternatives.

The activity follows the itinerary and the position through the SDK's shared
guidance planner, the same one Navigation uses: a walk shows the minutes and
distance left, boarding counts down to the departure, a ride shows the stops
left as a track and makes getting off urgent at the stop before, a transfer is
a new step, and arrival ends the activity. When a departure passes without the
wearer aboard, Transit replans once and announces the new plan; it never
keeps counting down a vehicle that has left. The next boarding is refreshed at
most once a minute.

The journey holds Transit's single foreground service, with the location
type, and position updates only while it runs, and releases both at arrival or
stop. It is saved, so a restarted Transit resumes it at the same step; an
expired one is discarded. Opening Transit during a journey, from the launcher
or by tapping the idle activity, shows every leg with its times and the
current one marked; back leaves the activity running, and guidance ends only
from the explicit *Stop guidance* action or `stop_journey`.

**Home** is set in Transit's settings from the geocoder's results. Only its
label and the itinerary ever reach an assistant or its AI provider; the home's
coordinates and the live position never leave Transit.

The routing service permits non-commercial use, asks every client to identify
itself (Transit sends `RokidNexus-Transit/<version> (+https://github.com/Anezium)`),
and asks to be contacted before routing use. That agreement is a release gate
for journey guidance.

## Requirements

- Location permission for Near Me (favourite boards work without it), and
  "all the time" location for journey guidance.
- Skills and journey guidance need a phone hub that announces skills v1;
  journey guidance also needs the `surfaces` grant and glasses that support
  activities. With an older hub Transit works as before.
