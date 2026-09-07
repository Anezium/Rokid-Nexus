# Changelog — Lyrics

## Unreleased

- Show two contextual synced lyric lines during playback, with Glance as the
  default. Yield to apps, launcher, notices, camera, and Assistant; resume after
  interruptions. Keep ROM widgets and normal display sleep behavior.
- Add explicit Karaoke and "Hide lyrics for this track" settings. Hide on stop
  or missing lyrics, freeze briefly on pause, and recover safely after reconnect.
- Let the approved Nexus media trigger start Lyrics independently of the foreground
  plugin. Both Nexus and Lyrics need notification access for automatic playback.

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
