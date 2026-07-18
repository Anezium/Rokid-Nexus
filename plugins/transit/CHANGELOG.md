# Changelog — Transit

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
