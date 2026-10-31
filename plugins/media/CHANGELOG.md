# Changelog — Media Deck

## 1.0.4

- Integrated Nexus player with cover artwork, progress, full-text scrolling and
  quick activity-card controls. One Back leaves its focused controls.
- Keep in activities retains an idle Resume music entry. Nexus owns when its
  floating band appears; the settings link opens the shared display chooser.

The activity and its page need Nexus hubs 2.0.0; with older hubs Media Deck behaves like 1.0.3.

- A "Which player?" choice made through Assistant reaches only that player for that call
  and leaves the surface, the activity and the page as they were.
- **Show in activities.** A new setting, available once notification access is on, keeps
  what plays among the glasses' activities even with Media Deck closed: title, artist,
  Previous, Play or Pause, and Next. A tap on the card focuses Play/Pause; nothing plays on
  that first tap. The activity follows only real changes (the player, the track, playing or
  paused), never the position, and nothing polls. While idle it keeps a Resume music entry. Turning the setting off or
  revoking notification access ends it; the setting itself is kept.
- A new track or another player updates the activity quietly: it no longer lifts what plays
  above a running route or another activity in the glasses' stack.
- The activity carries the whole title, up to 40 characters, for the glasses' card and island,
  and is marked with Media Deck's own disc instead of a play or pause mark, which sat next to the
  glasses' own media indicator and could contradict it. "Paused" or "Stopped" opens the artist's
  line instead, so a narrow line cuts the artist rather than the state.
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

## 1.0.3

- Expose approved now-playing and playback operations to Assistant through
  Nexus Skills. Requires Nexus hubs 1.7.0, notification access, and operation
  approvals; a call does not open the plugin's HUD.
- Keep media references tied to the observed session and reject stale targets.
- Device tests covered now-playing and pause with an Android test player.
  Compatibility with real music players remains unverified.

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
