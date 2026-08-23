# Changelog — Lyrics

## Unreleased

- Ambient home widget on the glasses: two synced lines over the launcher,
  shown when music with lyrics is playing. Off / Glance / Karaoke (default)
  in settings. The phone hub opens the plugin on playback when Nexus has
  notification access; Karaoke holds the glasses display while the track
  plays.

## 1.0.3

- Refresh the lightweight playback anchor at each timed-lyrics line boundary
  so transport latency cannot leave the glasses highlighting one line behind.

## 1.0.2

- Android 11 support: the plugin now installs on Android 11 (API 30) phones.

## 1.0.1

- The app icon in Android settings and installer dialogs is now the plugin's own glyph on the Nexus dark background (adaptive icon), instead of a washed-out or generic mark.

## 1.0.0 — unreleased

- First release as a headless plugin APK (previously an in-hub built-in).
- Time-synced lyrics on the HUD via the SDK timed-lines surface.
- Providers: Spotify synced lyrics (in-app sign-in), Musixmatch (optional),
  LrcLib, Netease.
- Auto-open on playback changes, toggleable in settings.
- Own notification listener ("Nexus Lyrics") and kit settings screen with
  notification-access onboarding and uninstall.
