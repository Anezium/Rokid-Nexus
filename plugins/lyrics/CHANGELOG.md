# Changelog — Lyrics

## 1.0.4

- Settings gain "Position on glasses" (Middle or Higher) and "Text size"
  (Small, Normal or Large) for synced lyrics. Higher starts the active line
  near the top with the next line right under it. The defaults keep the
  previous layout, and a change re-lays out lyrics already on the glasses.
  Needs a glasses hub that understands the timed-lines layout; older hubs keep
  the centered layout.

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
