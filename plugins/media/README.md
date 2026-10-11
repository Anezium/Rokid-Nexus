# Media Deck

Media Deck is the Rokid Nexus universal now-playing plugin. Playback remains owned by
the active Android media app; the plugin reads its `MediaSession`, sends a declarative
HUD surface, and forwards only play/pause/previous/next transport commands.

## Module boundary

- No CXR, Bluetooth, glasses SDK, phone-hub implementation, microphone, or network
  dependency.
- The plugin owns its notification-listener component and its `MediaDeckSettingsActivity`
  on the shared NexusUi kit.
- `MediaDeckPluginService` registers the headless APK through the external Nexus plugin
  SDK and adapts the isolated `MediaDeckRuntime` onto typed surface sessions.
- The glasses hub owns rendering through the versioned `media` surface documented in
  `../BUSSPEC.md`; the plugin never installs or launches glasses-side code.

## HUD contract

- Swipe back/forward: previous/next media item.
- Tap/center: play or pause.
- Back: close Media Deck and return to the underlying glasses app.
- Position advances locally from an anchor; the phone does not poll or stream
  progress updates.
- With the image renderer capability, artwork is aspect-fit to a maximum 256 px edge,
  JPEG-encoded under 64 KiB, and sent in the media envelope's SPP binary body.
- Without that capability, artwork keeps the original center-cropped,
  contrast-normalized, Floyd-Steinberg-dithered 96 x 96 `mono1` payload.

## Show in activities (launcher bundle)

- An explicit setting in Media Deck's phone screen, off by default and available only once
  notification access is on. It is kept through a revoke, a reboot or a process death, and can
  always be turned off.
- While it is on, Media Deck keeps watching the active media sessions through its notification
  listener, even with the full surface closed, and publishes a now-playing activity: the title
  (12 characters, ellipsized) and the artist (or the app's name), Previous, Play or Pause, and
  Next as typed `media.*` commands, Play/Pause focused first, `ONGOING` persistence and the
  `media:main` page as its destination. It is updated only when the session, the item or
  playing/paused changes, never for the position, and nothing polls.
- The plugin service is held bound only while there is something to publish. Nothing playing,
  no notification access or unreadable sessions end the activity and let the service go.
  Turning the setting off ends the activity and stops watching. A registration that is not
  approved, the activity turned off in Nexus, or the hub replacing it stop it too while the
  setting stays on; the next registration (opening Media Deck) tries once more.
- Every command, from the activity, the page or the full surface, is checked against the
  session it was shown for: its identity (a hash of its package and session token) and its
  state revision. It is sent to that session only, never to "whichever session is active", as
  an explicit play or pause, and never retried. The answer is `done` (handed to that session;
  what the player then does is observed), `stale` (another session took over, or the state
  moved) or `rejected` (no session, no access, the player refused).

## Pages

`media:main` (`rokidbus.plugin.pages = 1`) shows what plays with its three controls. With the
launcher bundle it is a `media` body whose controls are the typed actions, so a user who
approved the `media` quick-action family (`rokidbus.plugin.quickActions = media`, descriptor
metadata, not a capability) runs them on one tap; the hub adds its own Open app. Older
launchers get the base summary template with the controls and Open the full app as rows.
The page watches the sessions only while it is shown.

Media titles and artwork are user data. They may be rendered on the requested HUD but
must not be included in production logs.
