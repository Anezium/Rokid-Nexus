# Navigation

Navigation follows the route Google Maps or Citymapper is already guiding on
the phone and keeps it on the glasses as one activity: the next maneuver or leg,
the distance or the stops left, the street or the stop, and the arrival time.
A new step flares; the moment a turn is a few metres away, or the stop to get
off at is next, is urgent. When the app stops guiding, the activity ends.

Navigation reads notifications and nothing else. It needs Notification Access,
and it ignores every notification that is not Google Maps' `navigation`
guidance or Citymapper's GO `trip-progress`, before reading it. Guidance lives
in process memory only; nothing is stored.

## What it reads

- **Google Maps** posts turn-by-turn as a `ProgressStyle` notification:
  `"80 m · Prendre à droite sur Av. X"` as the title and the arrival time as the
  sub-text. The maneuver itself is a bitmap, so the arrow comes from the
  instruction's words (English and French); one it cannot place gets the
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
one switch per app, Google Maps and Citymapper. Switching one off ends that
app's live route on the glasses at once and ignores its guidance until it is
switched back on, so nobody has to uninstall the plugin to keep one app off
the HUD. Switched back on, a route already running is picked up straight away.
The screen also asks for Notification Access and shows the route it sees.

## Requirements

- Both Rokid Nexus hubs 1.5.0 or newer. Badge, measure, track and the
  urgent beat are activity extras; a hub that does not announce them shows the
  same route without them.
- Google Maps or Citymapper set to English or French, the two wordings the
  plugin reads.
- Notification Access for Navigation (Settings button in the plugin screen).

## Build

```
./gradlew :plugin-nav:testDebugUnitTest :plugin-nav:assembleDebug -PskipCxrGlobal=true
```
