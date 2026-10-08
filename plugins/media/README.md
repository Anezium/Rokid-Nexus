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

Media titles and artwork are user data. They may be rendered on the requested HUD but
must not be included in production logs.

## Assistant operations

On hubs advertising Skills v1, separately approved `get_now_playing` and `pause`
operations work without opening the media HUD. Notification access is required.
Reads return metadata for a unique playing session or bounded player choices.
Pause can run with its control operation alone and returns no title or artist.
It sends explicit pause to the exact session, observes its playback callback, and
reports accepted if dispatch was confirmed but the paused state was not observed.
Several possible players require a choice; a destroyed reference is never
redirected. References are bounded, expire after ten idle minutes, and become
invalid when the provider process restarts. Skill calls fetch no artwork and
leave the HUD monitor's listener lifecycle independent.
