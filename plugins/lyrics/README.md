# Lyrics

Lyrics shows live, time-synced lyrics on the glasses for whatever is playing
on the phone. Track detection uses the notification-listener grant to read
active media sessions; lyrics come from Spotify's own synced lyrics (when
signed in), Musixmatch (optional), Netease, and LrcLib, tried in that order by
the provider chain.

## How it works

- `MediaNotificationListenerService` is an empty `NotificationListenerService`
  that exists only to hold the notification-access grant; the actual media
  session monitoring starts in `onNexusOpen` and stops on close — the plugin
  stays dormant while its surface is closed.
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
