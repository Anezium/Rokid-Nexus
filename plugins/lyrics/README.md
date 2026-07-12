# Lyrics

Lyrics shows live, time-synced lyrics on the glasses for whatever is playing
on the phone. Track detection comes from the plugin's own notification
listener; lyrics come from Spotify's own synced lyrics (when signed in),
Musixmatch (optional), LrcLib, and Netease, tried in that order by the
provider chain.

## How it works

- `MediaNotificationListenerService` follows the active media session and
  keeps the plugin service alive through an internal binding, so the lyrics
  surface can auto-open when playback changes (toggleable in settings).
- `LyricsRuntime` drives the HUD through the SDK's timed-lines surface:
  the full line set is sent once per track, then only small playback anchors
  keep the glasses in sync.
- `LyricsPluginService` is the thin `NexusPluginService` adapter;
  `LyricsSettingsActivity` (NexusUi kit) handles notification access, the
  Spotify sign-in (`SpotifyLoginActivity`, cookie stored encrypted on the
  phone), Musixmatch credentials, and uninstall.

## Requirements

- Notification access for "Nexus Lyrics" (prompted from the settings screen).
- Optional: a Spotify account for Spotify's own synced lyrics.
