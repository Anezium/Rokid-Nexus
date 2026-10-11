# Navigation

Navigation follows the route Google Maps, Citymapper, OsmAnd, Organic Maps, maps.me or Yandex Maps
is already guiding on the phone and keeps it on the glasses as one activity: the next maneuver or leg,
the distance or the stops left, the street or the stop, and the arrival time.
A new step flares; the moment a turn is a few metres away, or the stop to get
off at is next, is urgent. When the app stops guiding, the activity ends.

Navigation reads notifications and nothing else for those apps. It needs Notification Access,
and ignores unknown packages and disabled sources before reading their text.
Each parser also checks its navigation channel/category and ongoing state.
Guidance lives in process memory only; nothing is stored.

It can also guide a walking route of its own, which the wearer starts on the
phone (see [Walking routes](#walking-routes)).

## What it reads

- **maps.me** (`com.mapswithme.maps.pro`): background driving distance and
  next street on `ActiveNavigationChannel`, captured in English and French
  with 17.12.72038-googleRelease. Disable its picture-in-picture mode and
  leave the app for the notification to appear. Its bitmap-only maneuver
  stays neutral; no ETA, close-turn warning or arrival is inferred. The APK
  notification code also enables cycling, but excludes walking and transit;
  cycling has not been exercised on the emulator. On a Galaxy S23 (Android
  16) maps.me never updates it: in picture-in-picture it posts no
  notification, and without it Android refuses its navigation service the
  location ("Foreground service started from background can not have
  location access"), so the first distance stays frozen. Organic Maps does
  not have this problem.

- **Yandex Maps** (`ru.yandex.yandexmaps`): driving distance and street, plus
  ETA from its expanded notification view. Captured in English and Russian
  on the API 36.1 emulator with version 30.9.1. Its maneuver is bitmap-only,
  so the glyph stays neutral and a short distance does not trigger an urgent
  turn. The generic "Navigator is running" notification is ignored; that was
  all the walking trip and the initial leg of a metro route exposed. Cycling,
  transit ride/transfer steps and arrival are not verified.
  French is not among its [documented app languages](https://yandex.com/support/m-maps/en/configure-app).

- **OsmAnd / OsmAnd+** (`net.osmand`, `net.osmand.plus`) posts a distance and
  maneuver title plus a BigTextStyle route description and trip summary.
  Navigation reads the next distance and the summary's ETA, not the trip's
  distance or the following leg's distance. English, French and Korean
  maneuver words come from upstream `route_*` resources. Unworded arrows,
  including exit-only roundabout instructions, stay neutral. Zero metres
  does not mean arrival; route removal ends the activity. Driving captures
  from OsmAnd+ 5.4.4 confirm English/French straight and right-turn steps,
  including a 300 m to 15 m countdown. After a language change, an old-language
  description is omitted until its maneuver prefix matches the title; confirmed
  distance, maneuver and ETA remain visible. Korean remains source-derived. Contract derived
  from [NavigationNotification](https://github.com/osmandapp/OsmAnd/blob/7c4433506fe831e32ebb791522ac8ecd5262ca09/OsmAnd/src/net/osmand/plus/notifications/NavigationNotification.java).

- **Organic Maps** (`app.organicmaps`, GitHub `app.organicmaps.web`) posts
  distance as title and street as text on the `NAVIGATION` channel. The
  maneuver is bitmap-only: Navigation keeps the neutral route glyph and
  does not infer a turn warning or arrival from a short/zero distance.
  There is no ETA; at arrival the app removes its notification. English
  and French metric fields are covered. English walking (distance only) and French driving (distance and street)
  were captured on the API 36.1 emulator with Organic Maps 2026.08.27-18-web. Contract derived from upstream
  [NavigationService](https://github.com/organicmaps/organicmaps/blob/98099c37a3c353a240a9d41959492e67990a2083/android/libs/routing/src/main/java/app/organicmaps/routing/NavigationService.java).

- **Google Maps** posts turn-by-turn as a `ProgressStyle` notification:
  `"80 m · Prendre à droite sur Av. X"` as the title and the arrival time as the
  sub-text. The maneuver itself is a bitmap, so the arrow comes from the
  instruction's words (English, French and Korean); one it cannot place gets the
  plugin's neutral `route` mark instead of a guessed arrow. Its progress is a
  share of the whole trip, which barely moves, so it is not shown.
- **Google Maps on public transport** posts an empty notification first and
  fills it a few seconds later: `"Marchez 3 min (250 m)"` or `"Prenez la ligne
  X"` as the title, the stop or direction and the departure time as the text.
  A walk shows its minutes with the distance beside them (`measure`) and the
  stop below; a line to board shows as its badge with the departure time.
- **Citymapper** draws GO entirely in its own layout, with nothing in the
  standard fields. Navigation inflates that layout and reads the title,
  subtitle, prediction and arrival views by name. Walk, wait, departure and
  ride steps are recognised; the line waited for becomes the ride's badge, and
  the stops Citymapper counts down become a track.

Anything that does not read as guidance is dropped: no value is estimated or
invented between two notifications.

## Walking routes

On the plugin screen, search a place (Transitous geocoding), pick it and tap
Start walking. Precise location is asked for at that tap, never before. The
first accurate fix and the destination go to Transitous once, for one walking
itinerary; transit legs are never turned into walking guidance, and a failed
plan ends the journey instead of retrying. Then a foreground service of type
`location`, with its own notification carrying Stop, reads location only
while the journey runs.

Progress only comes from recent, accurate fixes near the route and only moves
forward. When the signal is lost the last trusted position is kept with its
age (stale) instead of being moved; before any trusted fix there is no
position at all. The compass heading is only the bearing of a fresh fix taken
while walking, never carried over to an older one.

The journey ends on arrival, on Stop (its notification, the phone screen, or
End on the glasses' route page), as soon as location permission is removed,
when no route was planned within two minutes, after ten minutes without GPS
and after twelve hours. It is never started at boot or restarted by the
system, and nothing about it is stored. While it runs it owns the route:
other apps' guidance is ignored, and picked up again from what they post when
it ends. Route data: Transitous and OpenStreetMap contributors.

## Pages

With the glasses' Nexus session and hubs that both support pages, the
Navigation card in the session opens `nav:main`: the same guidance as a page,
with the turn or leg, its distance or stops, the street, the arrival time when
the app gives one, the source and when it was received. It follows the route
while it is on screen, at most twice a second, and says so plainly when there
is no route or no Notification Access. Its Commands row opens `nav:commands`,
which only offers the full app, behind the hub's labelled launch step. Pages are
read-only and leave the card and the activity as they are.

When both hubs negotiated the launcher bundle, the activity opens `nav:main`
directly and it is a route page instead. A walking route carries the next
turn, its distance, street and time left, facts, and two maps the hub draws
(follow, heading-up; whole route, north-up) from the planned route and the
street names the planner returned, with the position live, stale with its
age, or none. The map (at most once a second) and the heading (at most five a
second) follow the activity over the data plane only; with only the control
link up they are dropped, never queued, and the text guidance is unchanged.
The page's End the journey action stops it after the hub's confirmation.
Guidance read from another app has no geometry behind it, so its route page
is text only, with no position and no street drawn. A Citymapper ride shows
a stop ribbon from the stops it counted: only the stop to get off at is
named, and a vehicle placed between two stops is flagged as an estimate.

## Settings

The plugin screen on the phone (Rokid Nexus, Navigation) has a main switch and
one switch per app. OsmAnd editions share a switch, as do Organic Maps editions. Switching one off ends that
app's live route on the glasses at once and ignores its guidance until it is
switched back on, so nobody has to uninstall the plugin to keep one app off
the HUD. Switched back on, a route already running is picked up straight away.
The screen also asks for Notification Access and shows the route it sees.
Above the switches it plans and starts walking routes, and shows the running
one with its Stop. The app switches never affect a walking route.

## Requirements

- Both Rokid Nexus hubs 1.5.0 or newer. Badge, measure, track and the
  urgent beat are activity extras; a hub that does not announce them shows the
  same route without them.
- Google Maps or Citymapper set to English, French or Korean, the wordings the
  plugin reads. Korean is read from the apps' own Korean strings and has not
  yet been checked on a real trip in Korea.
- Notification Access for Navigation (Settings button in the plugin screen),
  to follow other apps. Walking routes need precise location instead, asked
  for when one is started, and the internet for the one Transitous request.

See [validation evidence and device checklist](VALIDATION.md) for observed
formats, mode limits and the remaining phone/glasses checks.

## Build

```
./gradlew :plugin-nav:testDebugUnitTest :plugin-nav:assembleDebug -PskipCxrGlobal=true
```
