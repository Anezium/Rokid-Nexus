# Navigation

Navigation follows the route Google Maps, Citymapper, OsmAnd, Organic Maps or Yandex Maps
is already guiding on the phone and keeps it on the glasses as one activity: the next maneuver or leg,
the distance or the stops left, the street or the stop, and the arrival time.
A new step flares; the moment a turn is a few metres away, or the stop to get
off at is next, is urgent. When the app stops guiding, the activity ends.

Navigation reads notifications and nothing else. It needs Notification Access,
and ignores unknown packages and disabled sources before reading their text.
Each parser also checks its navigation channel/category and ongoing state.
Guidance lives in process memory only; nothing is stored.

## What it reads

- **Yandex Maps** (`ru.yandex.yandexmaps`): driving distance and street, plus
  ETA from its expanded notification view. Captured in English and Russian
  on the API 36.1 emulator with version 30.9.1. Its maneuver is bitmap-only,
  so the glyph stays neutral and a short distance does not trigger an urgent
  turn. The generic "Navigator is running" notification is ignored; that was
  all the walking trip exposed. Cycling/transit and arrival are not verified.
  French is not an offered app language.

- **OsmAnd / OsmAnd+** (`net.osmand`, `net.osmand.plus`) posts a distance and
  maneuver title plus a BigTextStyle route description and trip summary.
  Navigation reads the next distance and the summary's ETA, not the trip's
  distance or the following leg's distance. English, French and Korean
  maneuver words come from upstream `route_*` resources. Unworded arrows,
  including exit-only roundabout instructions, stay neutral. Zero metres
  does not mean arrival; route removal ends the activity. Driving captures
  from OsmAnd+ 5.4.4 confirm English/French straight and right-turn steps,
  including a 300 m to 15 m countdown. Korean remains source-derived. Contract derived
  from [NavigationNotification](https://github.com/osmandapp/OsmAnd/blob/7c4433506fe831e32ebb791522ac8ecd5262ca09/OsmAnd/src/net/osmand/plus/notifications/NavigationNotification.java).

- **Organic Maps** (`app.organicmaps`, GitHub `app.organicmaps.web`) posts
  distance as title and street as text on the `NAVIGATION` channel. The
  maneuver is bitmap-only: Navigation keeps the neutral route glyph and
  does not infer a turn warning or arrival from a short/zero distance.
  There is no ETA; at arrival the app removes its notification. English
  and French metric fields are covered. The distance-only walking notification was also captured on the API 36.1
  emulator with Organic Maps 2026.08.27-18-web. Contract derived from upstream
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

## Settings

The plugin screen on the phone (Rokid Nexus, Navigation) has a main switch and
one switch per app. OsmAnd editions share a switch, as do Organic Maps editions. Switching one off ends that
app's live route on the glasses at once and ignores its guidance until it is
switched back on, so nobody has to uninstall the plugin to keep one app off
the HUD. Switched back on, a route already running is picked up straight away.
The screen also asks for Notification Access and shows the route it sees.

## Requirements

- Both Rokid Nexus hubs 1.5.0 or newer. Badge, measure, track and the
  urgent beat are activity extras; a hub that does not announce them shows the
  same route without them.
- Google Maps or Citymapper set to English, French or Korean, the wordings the
  plugin reads. Korean is read from the apps' own Korean strings and has not
  yet been checked on a real trip in Korea.
- Notification Access for Navigation (Settings button in the plugin screen).

## Build

```
./gradlew :plugin-nav:testDebugUnitTest :plugin-nav:assembleDebug -PskipCxrGlobal=true
```
