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

## Keep in activities (launcher bundle)

The **Floating activity** settings link opens Nexus's separate display choices:
automatic, always visible, home only, briefly on track changes, or Activities
only. Temporary bands use the user's 3/5/10-second duration and retain the
player's card after disappearing. Media Deck publishes a stable opaque track
event key; pause, position, artwork and metadata refinement do not renew it.
This uses the public launcher v4 SDK, without media-specific policy in Nexus.

An active activity previews Previous, Play/Pause and Next directly on its
selected Nexus card. Tap to focus its controls; the final **Open player** slot
opens the integrated cover player. One Back from that player returns to the
containing Nexus menu, even while a transport button is focused. The passive
floating band has no playback buttons.

- An explicit setting in Media Deck's phone screen, off by default and available only once
  notification access is on. It is kept through a revoke, a reboot or a process death, and can
  always be turned off.
- While it is on, Media Deck keeps watching the active media sessions through its notification
  listener, even with the full surface closed, and publishes a now-playing activity: the title
  (12 characters, ellipsized, and whole up to 40 as the launcher headline) and the artist (or
  the app's name, followed by "Paused" or "Stopped" when nothing plays), marked with Media
  Deck's JPEG cover on bundle v3 (its `disc` glyph as fallback), Previous, Play or Pause, and
  Next as typed `media.*` commands, `ONGOING` persistence and the
  `media:main` page as its destination, opened directly on tap. It is updated only when the session, the item or
  playing/paused changes, never for the position, and nothing polls.
- With nothing playing, the ongoing entry stays as a destination to the player page; its
  service binding is released after publication. No notification access or unreadable sessions
  end the activity and let the service go.
  Turning the setting off ends the activity and stops watching. A registration that is not
  approved, the activity turned off in Nexus, or the hub replacing it stop it too while the
  setting stays on; the next registration (opening Media Deck) tries once more.
- Every command, from the activity, the page or the full surface, is checked against the
  session it was shown for: its identity (a hash of its package and session token) and its
  state revision. It is sent to that session only, never to "whichever session is active", as
  an explicit play or pause, and never retried. The answer is `done` (handed to that session;
  what the player then does is observed), `stale` (another session took over, or the state
  moved) or `rejected` (no session, no access, the player refused).
- Activity command ids carry the state revision they were published at (`pause@12`), so a
  command is tied to what the wearer saw even while a newer update is still on its way; the
  hub keeps the focus on the same slot when an id changes. The page's middle control instead
  keeps one id, `playpause`, whose typed value is the explicit Play or Pause at the shown
  revision.
- The idle page offers **Resume last player**. This separate explicit action asks Android's
  last media receiver to play, only if no active session exists when pressed. It never redirects
  a command that was shown for a particular session. Android may have no resumable receiver;
  the result reports a request, and the page explains to open a player on the phone if silent.
- **Keep in activities → Remove** removes the entry and stops monitoring. Hello Nexus uses the
  same SDK `NexusActivityPreference` for its checklist; turning it off preserves the list's items.
  Any plugin can use this default-off preference and `NexusUi.activitySettingRow`; Nexus keeps
  the offline entry without holding its service. See the public SDK guide for the lifecycle.

## Pages

`media:main` (`rokidbus.plugin.pages = 1`) shows what plays with its three controls. With the
launcher bundle it is a `media` body whose controls are the typed actions, so a user who
approved the `media` quick-action family (`rokidbus.plugin.quickActions = media`, descriptor
metadata, not a capability) runs them on one tap. Bundle v2 adds locally advancing progress and a horizontal transport strip.
Bundle v3 uses the same detailed JPEG encoder as the original media surface, up to
256 pixels per edge and 32 KiB compressed for inline page transport; no dithering.
The card and compact pin use that same cover. Phone seeks and speed changes
refresh the anchor without periodic traffic. The floating pin stays compact, including while
retaining a cover offline, and track changes do not expand it. Older
launchers get the base summary template with the controls and Open the full app as rows.
The page watches the sessions only while it is shown.

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

A skill call has its own read of the sessions and its own opaque references. Answering
"Which player?" authorizes only that reference for that one call: it never changes the
player the surface, the activity or the page follow, never turns on Show in activities,
never starts the session watcher, and never makes a command the activity offered reach
another session. While only a skill call keeps Media Deck bound, the phone refuses its
activity traffic; Media Deck then forgets what it sent, without retrying, and publishes the
activity whole on the next change once the call is over.
