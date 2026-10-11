# Changelog — Media Deck

## Unreleased

- **Assistant operations.** Approved now-playing and pause operations for Assistant
  through Nexus Skills. They need a phone hub that announces skills v1, notification
  access, and operation approvals; a call does not open the plugin's HUD. Media
  references stay tied to the observed session and stale targets are rejected. A
  "Which player?" choice reaches only that player for that call and leaves the
  surface, the activity and the page as they were.
- **Show in activities.** A new setting, available once notification access is on, keeps
  what plays among the glasses' activities even with Media Deck closed: title, artist,
  Previous, Play or Pause, and Next. A tap on the card focuses Play/Pause; nothing plays on
  that first tap. The activity follows only real changes (the player, the track, playing or
  paused), never the position, and nothing polls. Turning the setting off, nothing playing or
  revoking notification access ends it; the setting itself is kept.
- **Controls only reach the player you saw.** Every command, from the activity, its page or
  the full surface, is checked against that exact media session and its state: if another
  player took over or the state moved, it is refused as stale instead of reaching another app.
  Play and pause are sent as what you asked for, never as a toggle, and nothing is retried.
- A Media Deck page for the launcher: what plays with Previous, Play/Pause and Next, which
  you can allow to run on one tap in Nexus; older launchers get a summary with the same
  controls and a row to open the full app.
- The full surface shows "Nothing is playing" or the access message again whenever the
  session goes away, instead of keeping the last track; buffering now shows as playing with
  the clock held, as a tap would pause it.

## 1.0.2

- Android 11 support: the plugin now installs on Android 11 (API 30) phones.

## 1.0.1

- The app icon in Android settings and installer dialogs is now the plugin's own glyph on the Nexus dark background (adaptive icon), instead of a washed-out or generic mark.

## 1.0.0 — unreleased

- First release as a headless plugin APK (previously an in-hub built-in).
- Typed media surface with playback anchor and 96×96 one-bit artwork.
- Transport controls from the glasses: tap play/pause, swipe previous/next.
- Own notification listener ("Nexus Media Deck") and kit settings screen with
  notification-access onboarding and uninstall.
