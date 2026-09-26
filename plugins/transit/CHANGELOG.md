# Changelog — Transit

## Unreleased

- Skills for an approved assistant: favorite stops, stop search, and
  departures with their observation time and realtime quality, including "the
  one after that" by trip identity rather than row position.
- **Journey guidance.** "Take me home" plans a public transport journey to a
  saved home and guides it step by step in Transit's own activity on the
  glasses: walk, board, ride with the stops left, get off at the next stop,
  transfer, arrive. A missed departure replans once. The journey survives a
  restart, and opening Transit during it shows every leg.
- Home setting, chosen from the geocoder. Only its name ever reaches an
  assistant; its location and yours stay in Transit.
- Requests identify Transit with its version and a contact.

## 1.0.4

- Add realtime ETAs for Hong Kong KMB, Citybus, and green-minibus stops, with automatic fallback to Transitous schedules.

## 1.0.3

- Android 11 support: the plugin now installs on Android 11 (API 30) phones.

## 1.0.2

- The app icon in Android settings and installer dialogs is now the plugin's own glyph on the Nexus dark background (adaptive icon), instead of a washed-out or generic mark.

## 1.0.1

- Near Me now takes a single fresh location fix (fused, GPS, then network)
  instead of falling back to stale last-known positions, and releases the
  location foreground service right after the fix.
- Location setup is a guided two-step flow in the plugin settings: grant
  precise location, then allow background access ("Allow all the time") so
  Near Me works while the phone stays in your pocket.
- Clearer HUD messages when a location permission is still missing.
- Reworked Near Me and favourites refresh loops with lifecycle test
  coverage.

## 1.0.0 — unreleased

- First release as a headless plugin APK (previously an in-hub built-in).
- Near Me live departures with a self-managed location foreground service.
- Favourite stops board; one-shot migration of favourites from the old
  built-in on first approval.
- Kit settings screen with permission handling, stop search, favourites,
  and uninstall.
