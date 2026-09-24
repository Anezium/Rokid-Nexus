# Changelog

## Unreleased

### Phone and glasses hubs

- **SPP connections now require mutual authentication.** The phone enrolls a
  pairing key through its authorized Hi Rokid CXR session; both hubs protect it
  with Android Keystore. Every SPP connection proves possession of that key,
  and every frame carries a directional MAC and replay counter. Unauthenticated
  clients cannot send commands or replace an active connection's output.
- **Upgrade both hubs for the SPP data plane.** Legacy SPP peers are rejected;
  the existing CXR control path remains available. Enrollment retries after a
  hub reset without relying on reverse CXR delivery. Plugin API and grants are
  unchanged.

## 1.4.11

### Upgrade together

Install **both Nexus hubs 1.4.11** before using notices, then update Relay to
**1.2.3**. Notice protocol v5 requires matching phone and glasses hubs; mixed
hub versions are not supported for notices. Plugins need to be rebuilt with
SDK **0.19.0** to gain the new callback protection. Plugin API version 3 and
existing grants are unchanged.

### Phone and glasses hubs

- **An SPP interruption no longer disables a live notice's SDK callbacks.**
  Losing or restoring one glasses transport leaves the current question intact;
  an explicit hide, replacement, or fresh registration still protects against
  stale replies. A newer owner hide also clears an older visible band when a
  replacement show was lost or failed image decoding.
- **HUD routing has dedicated owners.** The phone hub delegates notices, pins,
  activities, ordinary surfaces, and Ink to separate routers, with regression
  tests for owner delivery, reconnect state, and closure. Surface detach still
  preserves an active microphone lease through the existing audio lifecycle.
- **Notice replies stay with the question the wearer saw.** Both hubs now bind
  actions, gestures, and dismissals to the notice instance and question, so a
  delayed event cannot act on a replacement from the same plugin. The SDK also
  filters stale callbacks already queued for a plugin. Notice protocol v5
  requires matching phone and glasses hubs; rebuild plugins with the updated
  SDK to gain the callback protection.
- **A failed notice reply is visible.** The glasses show *Delivery not confirmed*
  when the transport rejects a send, retaining the one-answer guard instead of
  risking a duplicate effect after a partial write.
- **An interactive notice keeps key priority over focused Nexus windows.**
  Direction, confirm, and Back now reach the notice before the launcher or
  surface beneath it, including local activity and overlay windows. Confirming
  a notice can no longer activate an underlying control through those paths.
  The same dispatcher handles accessibility input and paired directional keys.
- **Relay countdowns keep the same question.** The SDK's optional `rearm = false`
  update refreshes action labels without invalidating a tap in flight.
- **Directional input can select Ink actions.** Tappable controls now have an
  outlined selection tracked by their node id. Confirm activates the selected
  control, and updates retain that selection when the node still exists. Paired
  directional key reports count as one move. A notice can take input above the
  page and return to the same selected Ink control when dismissed.

### Plugin SDK 0.19.0

- Add optional `NexusNoticeUpdate.rearm`. With `rearm = false`, a label-only
  action update preserves the current question, selection, and one-answer
  guard. This requires `noticeInteractionVersion: 1`; an older phone hub returns
  `CAPABILITY_NOT_AVAILABLE` rather than silently changing the question.
- Filter queued notice callbacks by the current local question. An SPP-only
  interruption does not retire that context, and reconnecting does not undo an
  explicit hide.
- **Callback contract change:** `onNoticeClosed` belongs to the current local
  notice context, not to every `showNotice` call. An immediate hide followed by
  show may suppress the previous `closed(owner)` callback. Plugins must not
  depend on exactly one close callback per show to clean up replaced state.

Validation and remaining transport-recovery follow-ups are recorded in the
[review and device retest](docs/reviews/hud-interaction-review-follow-up.md).

## 1.4.10

### Phone hub

- **Microphone plugins can let the glasses sleep without stopping.** A plugin
  that explicitly detaches its last surface while holding the active audio
  lease now stays bound in a visible, revocable background state. The launcher
  remains free, reopening resumes the plugin, and releasing, revoking, or
  stopping the lease always final-closes it. The phone plugin row shows
  *Listening in the background* with a **Stop** action. Requested in #37.
- **A compact microphone indicator that cannot stay stuck after a crash.**
  Sample 1.0.3 shows `MIC ON` / `Stop on phone` and renews a five-second pin
  only while audio frames arrive. Normal stops hide it immediately.

### Plugin SDK 0.18.0

- Add `NexusSurfaceSession.detach()` / `hide(detach = true)`,
  `PluginCloseTypes.BACKGROUND`, `PluginOpenTypes.RESUME`, and the default
  `onBackground()` / `onNexusBackground()` callbacks. A background close keeps
  only the active audio session and its foreground service; resume is delivered
  as a fresh open, while every final or unknown close retains fail-closed
  behavior.

## 1.4.9

- **Speech patience is now your choice.** Settings > Speech gains a Patience row
  that controls how long the glasses wait for you to start speaking, and how long
  a pause can last before your sentence is considered finished. The built-in
  Android recognizer no longer gives up after three seconds of silence either:
  it is only started once you begin speaking, so your patience is the only
  clock that counts.

## 1.4.8

- **Rokid's assistant on the button, Nexus in the launcher.** Under *Replace
  the glasses assistant* on a plugin's permissions screen there is now a second
  switch, *Assist button opens Nexus*. Turn it off and the assist button goes
  back to Rokid's assistant while the plugin stays in the glasses launcher, one
  pick away; it never touches the permission itself. Assistant can flip the same
  switch from the glasses. Asked for in #35.
- **Plugins learn why they were opened.** The SDK now passes the hub's reason
  to `onNexusOpen(openType)`: a launcher pick, the assist button, or a
  re-adoption after a restart. Existing plugins keep working unchanged.
- **A status row on every Nexus surface.** Phone charge, the date and the
  glasses' own charge now sit at the bottom of every plugin surface on the
  glasses, and a typed field starts clean when its card is reopened. Thanks to
  ruruw.

## 1.4.7

- **Four ways the glasses could get stuck are gone.** A full review of the
  glasses app found paths where one bad moment left it wedged until a restart:
  a phone that dropped off Wi-Fi mid-session could block every later camera
  reconnect; a *Repair now* sent while setup was still finishing could wait
  forever, and every repair after it answered "busy"; the boot-time start could
  overrun the window Android gives it; and one hiccup in the periodic capture
  check silently switched that check off for good. Each is now bounded or
  logged and carried on, and an expired pairing window that cannot be closed
  gives up after a few tries instead of retrying every five seconds forever.
  The camera, repair and boot paths were verified on hardware.
- **Every release is tested before it is built.** The unit tests for both hubs
  and every plugin now run on each push and pull request, and a release that
  fails them is not published.

## 1.4.6

- **A plugin can now ask you to type.** A card on the glasses can carry one
  text field. It works with a keyboard paired to the glasses, and with Nexus's
  own Keyboard & remote screen on the phone: what you type lands in the field
  as you type it, Enter submits. Relay uses it for typed replies and Assistant
  for typed notes. Thanks to ruruw, who built it and tested it on hardware.
- **The Nexus launcher no longer reappears behind a closed card.** After a
  plugin's full-screen card closed, Android sometimes brought back the Nexus
  launcher screen it had left paused underneath, instead of what you were
  looking at before. It now closes itself once it is only a leftover.
- **Settings can check the glasses' accessibility services.** A new *Check
  accessibility services* action under Maintenance asks the glasses which
  services besides Nexus's own are enabled: a foreign one sitting in front of
  Nexus's key handling has turned out to be the cause behind several "input
  stopped working" reports. It also says so when Nexus's own service is off.
- **Hub errors now reach plugins.** An error the hub sent back for a plugin's
  request never carried the plugin's id, so the plugin's SDK dropped it before
  the plugin saw it. A plugin is now told, for instance, when another plugin
  holds the foreground surface it asked for.

## 1.4.5

- **The update button now says why it can't.** Pressing Install or Reinstall on
  the glasses-app banner while the glasses were out of reach did nothing at
  all — no progress, no error, just a button that ignored you. The press now
  answers with the actual problem on the banner itself: the glasses aren't
  connected, reconnect them first. Thanks to cep b for the report.

## 1.4.4

- **Setup now asks for the one switch it cannot see.** The Hi Rokid app has an
  ADB debugging switch in its developer section. With it off, the glasses still
  pair and still report developer options as available, while every command
  setup needs is refused — so setup stopped halfway with nothing naming the
  cause, and it read as though the phone were unsupported. It never was.
  Onboarding now asks for that switch before automatic setup and opens Hi Rokid
  for you, manual setup says it before its checks rather than after they all
  pass, and the developer-options error points at Hi Rokid instead of at the
  step that had just failed. Thanks to MayonezSlap, Shon-z and JFernandes2612,
  who found it and posted the workaround before we knew what it was.

## 1.4.3

- **A document opens where reading begins.** A reader page always opened on its
  last paragraph. That is right for a conversation, where the newest line is
  the one you want, and wrong for an article: plugins showing a piece of prose
  dropped you at the closing note, and you scrolled up to find the start. A
  plugin can now say a page is a document rather than a stream. Those pages
  open at the top, and an update leaves you where you were reading instead of
  pulling you to the new end. Conversations behave exactly as before. Thanks to
  beyondlevi for the report and the diagnosis.

## 1.4.2

- **Your phone is now a trackpad for the glasses.** Drag on the pad and the
  glasses' own pointer moves with your finger; tap to click, hold to
  long-press. It drives the pointer the system already has rather than drawing
  one of ours, so it behaves the same everywhere — the Rokid launcher, a
  third-party app, anything on the lens. If the control link drops, Nexus falls
  back to its own cursor rather than leaving you stranded.
- **The remote has a real directional cross.** Up, down, left and right where a
  thumb expects them, select in the middle, back on its own line. The glasses
  move focus in the direction you pressed, and fall back to the next or
  previous item when a screen has nothing that way, so a press is never
  silently ignored. The pad and the cross sit side by side: use whichever fits
  the screen you are on.
- **The keyboard stops opening on its own.** Every time the glasses focused a
  text field, the phone raised the keyboard — so walking through a screen full
  of fields reopened it under your thumb at every step. It now opens when you
  tap the field.
- **Navigating wakes the display.** A direction press means someone is holding
  the phone and driving, so the glasses light the panel back up instead of
  moving through a screen you cannot see.

## 1.4.1

- **Assistant can act on the phone calendar.** It can add and list events after
  the wearer grants Android calendar access, and deletion fails closed unless
  exactly one event matches the requested title and start time. Recurring
  series require an explicit whole-series request.
- **Ink pages are a public HUD surface.** Plugins with the separate
  `ink_surface` grant can submit a strict, inert subset of Rokid's `.ink`
  format through the typed SDK, patch its data without resending the page, and
  receive ready, action, close, and typed validation-error callbacks. The phone
  compiles the page into bounded revisioned documents; the glasses render it
  with native Views, charts, inline Lottie, progress, and declarative canvas —
  no WebView, JavaScript, URL loading, or page-side network access. Assistant
  uses the same path for generated pages and template-driven results.
- **The phone can control native glasses apps.** The new Glasses apps screen
  lists and opens launchable APKs already installed on the glasses. Keyboard &
  remote adds previous/next/select/back navigation plus an ephemeral phone
  keyboard for the editor currently focused on the glasses. The hub-to-hub
  protocol is versioned, replay-safe, and unavailable to plugins; sensitive
  fields secure the phone window and existing editor contents are never copied
  back to the phone. Native APK installation is not part of this release.
- **Wireless ADB can be enabled and paired without driving Settings.** The
  new Wireless ADB plugin asks for an explicit privileged capability, trusts
  the current glasses Wi-Fi network through a fixed signed bridge command,
  and creates a two-minute pairing code for a computer on the same LAN. The
  code is never persisted or logged, its screen blocks capture, and expiry
  stays tracked until the pairing service is confirmed closed or the transport
  is disabled fail-closed.
- **Wireless ADB can recover from Wi-Fi being off.** Enable & pair now turns
  the glasses' Wi-Fi radio on and waits for a saved network before enabling
  ADB. Disabling wireless debugging remains deliberately scoped to ADB and
  leaves the normal Wi-Fi connection running.
- **The glasses remote has its buttons back, and both controls can be
  hidden.** Previous, next, select and back were laid out in a way that gave
  them no width at all, so the remote card was empty on every phone, and the
  keyboard card stretched far enough to push that card off the screen. Both
  are fixed, and Settings › Glasses now decides whether Keyboard & remote and
  Glasses apps appear on the home screen.
- **Manual pairing works on networks that block device discovery.** The glasses
  now tell the phone which port to connect to instead of both sides hoping
  mDNS finds the other — that lookup silently returns nothing on routers with
  multicast filtering or client isolation, which left pairing stuck with no
  usable port. Both the phone and the glasses need this release for the new
  path to apply.
- **Assistant answers stay visible through their whole display episode.** A
  single episode owner now spans the listening band, handover, and answer
  surface. Notice windows keep their normal screen-on flag, while the
  Assistant's scoped wake lock covers the band-to-card or band-to-Ink handover
  and is released when that episode ends.
- **Relay keeps delivering after days, even on phones that kill background
  apps.** Some Android systems (ColorOS in particular) quietly kill the
  process that listens for notifications, and Relay went silent until the app
  was opened again — typically a day or two after setup, with every battery
  setting already correct. The hub now holds a guardian binding on the
  listener plugin, so the system restarts it instead of leaving it dead.
- **Relay conversations are readable and live.** Inbox threads open as native
  full-screen reader documents, and a thread already on screen refreshes in
  place when its notification gains a message. Scroll position is preserved,
  while dictation, reply review, and sending remain uninterrupted.

## 1.2.11

- **The update banner says what the update is.** It used to offer a version
  number and nothing else, so anyone who already knows the app had no reason
  to suspect there was anything worth reading behind it. The first lines of
  the release notes now sit in the banner, cut off after three — tap the
  banner for the rest. Install still just installs.

## 1.2.10

- **The Store's Remove button removes again.** The new plugin detail screen
  asked Android to uninstall without holding the permission that request
  needs, and Android declined in silence — the tap did nothing. The app now
  declares it, and Remove opens the system uninstall dialog as intended.

## 1.2.9

- **The Store is twice as dense.** The list is now one slim row per plugin —
  icon, name, author, version or size, and a state mark — so about eleven
  plugins fit on a screen instead of four and a half. A pending update reads
  as an amber "Update" with an amber-edged row, and the header counts the
  catalogue: how many plugins, how many installed, how many updates.
- **Every plugin has a real page now.** Tap a row and everything the old card
  had no room for is there: a full-width install or update button, version,
  size, date and source at a glance, the plugin's What's new with a Read
  more fold, screenshots when the author published some — tap one to see it
  fullscreen and swipe through the rest — the long description, the
  capabilities it asks for, and an uninstall row. A History screen lists
  every release the plugin has ever shipped, with its notes.
- **The app has a changelog too.** Tapping the amber update banner — or
  Settings › About › What's new — opens the app's own release notes: the
  waiting version in full, every earlier release below it, and an install
  button at the bottom that follows the download live.
- Release notes and screenshots were always published by the plugin
  registry; the Store simply never showed them. No plugin needs an update
  to appear correctly.

## 1.2.8

- **A second message no longer arrives on a dark display.** When a short
  notification woke the glasses and expired, the display went back to sleep —
  and for the next few seconds any message that followed was accepted,
  displayed, and never seen, because the screen simply wasn't lit. A new
  message may now wake the display even right on the heels of the previous
  one. It stays polite about it: two wake-ups in a row at most without you
  touching anything, then the glasses wait for a quiet minute — so a runaway
  app still can't keep the screen burning, and the battery work from 1.2.6
  is untouched. Two timing holes went with it: a message landing at the
  exact moment the display was being put to sleep, and one landing during
  the quarter-second where the previous band was still fading out, both of
  which used to leave the new message invisible.

## 1.2.7

- **The built-in speech engine now works on phones whose default recognizer
  isn't Google's.** The Android engine feeds the glasses' microphone straight
  into your phone's speech service — but many phones (Vivo, Samsung…) ship
  with the maker's own service as the default, and those refuse audio that
  doesn't come from the phone's own mic. Nexus used to give up right there
  with a cryptic "client failed". It now walks through the other recognizers
  installed on the phone — Google's, then the on-device one — and uses the
  first that accepts the stream. And on the rare phone with no recognizer
  that can take external audio at all (ROMs without Google services), the
  message now says so plainly and points you to the cloud engines instead.

## 1.2.6

This release is about the battery (thanks for the detailed report!). Three
separate drains were found on the glasses, and all three are fixed.

- **The display goes back to sleep on its own.** The glasses' system never
  turns the display off by itself — and a display left awake costs about a
  quarter of the battery per hour, even when the optic looks black. Anything
  that woke the screen and didn't put it back — a notification, a glance at
  the launcher, a photo — quietly kept that meter running. Nexus now watches
  over it: three quiet minutes on battery — nothing on screen, nothing in
  use — and the display is put back to standby, exactly as if you had done
  the gesture yourself. It also no longer mistakes a full, unplugged battery
  for "still charging", which used to block the first standby after a full
  charge.

- **The command bridge stopped pacing.** The helper that lets the phone reach
  the glasses' system checked for work every single second, around the clock —
  measured at several percent of a CPU core doing nothing. It now sleeps until
  it is actually rung, with the same responsiveness when real work arrives.

- **Wi-Fi that Nexus turns on, Nexus now turns off — always.** Setup and the
  glasses camera sometimes left the radio running forever: a crash or restart
  at the wrong moment erased the note that said "we turned this on". That
  note now survives crashes, one caretaker decides when the radio is truly
  no longer needed, and a standing sweep retries anything a crash
  interrupted. Wi-Fi you enabled yourself is never touched.

## 1.2.5

- **Settings got a spring clean.** The main settings page had slowly collected
  every switch, preview and console in one long scroll. It now reads as short,
  honest sections — Connection, Glasses, Plugins, Maintenance, Advanced — and
  the display controls (screen position with its draggable preview, the phone
  battery chip, expanded activities) moved into their own *Display* screen
  under Glasses, with the row showing at a glance whether the position is
  automatic or manual.

- **The console earned its own screen — and a memory.** It used to sit at the
  bottom of the settings page and only showed what happened while you were
  looking at it. It now lives under Maintenance → *Console*, opens already
  filled with the recent activity that happened before you got there, keeps
  tailing live, and has a Share button so you can send the traces along with
  a bug report.

## 1.2.4

- **The display now follows your screen position.** The Hi Rokid app lets you
  raise or lower the glasses' virtual screen, and Rokid's own interface moves
  with it — but Nexus kept drawing everything at the top of the panel. If you
  keep your screen low, that put the notification window and every other Nexus
  surface out of comfortable view (thanks for the report!). The glasses now
  detect where your screen sits and move everything Nexus draws — notifications,
  assistant answers, plugin displays, the launcher — to meet it. There is
  nothing to set up: adjust the screen in Hi Rokid and Nexus follows along.

- **Prefer to place it yourself?** Settings → *Glasses display position* has a
  switch to turn the automatic following off, and a small mock of the glasses
  screen you can drag to pin the display exactly where you want it.

## 1.2.3

- **Spoken answers always use your phone's voice now.** The glasses' built-in
  voice, which previous releases fell back to whenever the phone's speech had
  no safe place to play, turned out to be the mystery behind several reports:
  it only really speaks English and Chinese, spells anything else out letter
  by letter, and reads numbers strangely enough that an English time was
  heard as "answering in Chinese". That fallback is gone. Every spoken answer
  is synthesized by the phone, with the voice and speed you picked, and plays
  through your glasses or earbuds. When the Bluetooth audio link has dozed
  off, the answer now waits a few seconds for it to wake instead of grabbing
  the nearest bad voice — and if no ear ever becomes available, it stays
  unspoken: the text is on the display either way, and silence beats speech
  you cannot understand. The phone's own speaker still never plays a word.

- **The Output setting is retired.** *Automatic* versus *Glasses only* was a
  choice between two workarounds for that fallback. With the glasses' voice
  out of the picture there is nothing left to choose: the Voice screen now
  simply shows the speed, the voices, and where the sound will go.

## 1.2.2

- **The typed pairing form works again.** On the Manual setup screen, entering
  the glasses' IP address, port and pairing code and pressing Pair used to do
  nothing at all — no error, no progress. The form was only wired up for a
  state the wizard never actually reached, so every attempt was silently
  refused. Typed pairing now starts from wherever the wizard is, and when a
  request genuinely cannot be sent, the screen says so instead of staying
  quiet. It also keeps using the connect port the glasses last reported, so
  pairing no longer depends on network discovery that many routers block.

## 1.2.1

- **Spoken answers stop coming out of the phone.** Since 1.1.6, plugin speech
  has been synthesized by the phone's own voice and carried to the glasses
  over the regular Bluetooth audio link. When that link isn't there — media
  audio disabled for the glasses, or the glasses simply not the phone's
  active audio output — the answer used to play out loud on the phone's
  speaker, with no way to redirect it. Now the hub checks where the sound
  would actually land before speaking: if the only destination is the phone's
  own speaker, the utterance goes to the glasses' built-in voice instead.
  Earbuds keep their priority — if you have some in, answers stay in them.

- **And you can pin speech to the glasses outright.** Settings → Voice gains
  an Output choice: *Automatic* (the behavior above) or *Glasses only*, which
  always uses the glasses' own voice — the one they share with Rokid's
  assistant — and never opens the Bluetooth audio stream. A wearer who picks
  it is never surprised by the phone speaking into the room: with the glasses
  unreachable, an answer fails quietly rather than out loud. It also spares
  you the glasses' music card, which likes to pop up when phone audio starts
  streaming their way.

## 1.2.0

- **A long text makes the notice grow.** The band across the top of the HUD
  used to hold eight lines behind a fixed ceiling, whatever it was asked to
  carry. Now it is sized by what it carries: a short notice stays the familiar
  glance, and a long one grows in place — up to nearly the whole display, with
  pages that deepen to fourteen lines when there is room for them. Reading
  works exactly as before: swipe to turn, a page counter in the corner, and
  the band staying up as long as you keep reading. Notices with answer buttons
  keep their compact shape; choosing does not need a bigger band.

- **The text budget finally matches the band.** A notice may now carry 8,192
  characters — eight times the old ceiling — so a detailed answer or a long
  message ends where it ends, not where the wire used to cut it. This is a
  protocol change (notice v4): the phone and glasses halves of Nexus check
  each other exactly, so update the app and let it bring the glasses along —
  a mismatched pair simply declines the band rather than truncating in
  silence.

- **Assistant answers stop being told to be short.** With the band able to
  hold a real explanation, the Assistant plugin (1.0.1) lets the model answer
  as fully as the question deserves, in real paragraphs. Update the plugin
  from the Store to get the longer answers.

## 1.1.9

- **The glasses repair their own helper after a restart.** The last release
  fixed the folder that had silenced the privileged helper; this one fixes the
  restart that kills it. Every reboot takes the helper down, and reviving it
  needs the glasses' Wi-Fi — which this firmware boots with off. So now, about
  twenty seconds after a restart, when the helper is missing, Settings opens
  briefly on the glasses, turns Wi-Fi on, and gets out of the way. The repair
  itself happens in the background, and once the helper is back the Wi-Fi is
  switched off again through it: the glasses end up exactly as they booted,
  minus the breakage. Measured on hardware, the whole thing takes about
  fifteen seconds — and if no known network is in range, the radio simply
  stays up and the repair completes on its own the first time one is.

- **It runs under your standing consent, once per boot at most.** Nexus →
  Settings → Glasses maintenance holds the switch — *Auto-repair at boot*, on
  by default, remembered by the glasses themselves so it works with the phone
  out of reach — and a *Repair now* button for everything else, which answers
  plainly: repaired, nothing to repair, or could not turn the Wi-Fi on.

- **Syncs stopped paying for a dead helper.** Deleting a capture through a
  helper that was gone used to spend six seconds per file discovering the same
  absence — a twenty-photo sync stalled for two minutes for nothing. The hub
  now knows the helper did not survive the reboot and says so once, instead of
  proving it file by file.

## 1.1.8

- **Deleting a capture from the glasses after it syncs works again, and stays
  working.** The hub and the privileged helper that does what an ordinary app
  cannot talk through a folder on the glasses. If the helper created that
  folder first, it owned it, and the hub could no longer put a single request
  in — so every command through that channel failed, silently and for good:
  removing a synced capture, and turning the glasses' Wi-Fi on without taking
  over your display. Photo Sync said the glasses had refused the delete, which
  was true and useless. The helper no longer creates the folder, clears one
  that is not the hub's, and the hub now checks that the channel it is about to
  use is one it can actually write to.

## 1.1.7

- **Videos from the glasses finally reach your phone.** Photo Sync has always
  promised that a long video resumes across sessions, and that machinery does
  work — but no video ever got far enough to use it. The capture catalog
  scanned only the folder the photos land in, on the belief that videos landed
  beside them. They do not: this firmware keeps them somewhere else entirely.
  So a recording was never listed, never transferred, and tapping *Sync now*
  answered that nothing was waiting while a 47 MB clip sat on the glasses. Both
  folders are watched now.

- **A synced photo keeps the day it was taken.** Captures reached the phone
  gallery with no date of their own, so they sorted by the moment they arrived:
  a day of photos synced at bedtime piled up at bedtime, in the wrong place in
  your timeline. The cause was inside the files. The glasses camera writes one
  EXIF date field and not the one Android actually reads, so the phone now
  fills in the missing field as each capture is published, using the capture
  time the filename already carries. Photos synced before this release keep the
  date they were given.

## 1.1.6

- **Speech comes from your phone now, and it can finally pronounce your
  language.** The last release had the glasses speak with their own engine.
  That engine turned out to know English and Chinese and nothing else: handed a
  French sentence it read the letters out one by one. It was never going to
  learn — the Rokid assistant sounds right in French because it does not use
  that engine either, it streams audio from the phone. So we do the same, with
  the phone's own voice. It reaches the glasses over the Bluetooth audio they
  already carry, which also means it follows your ears: put earbuds in and the
  answer goes there instead. If your phone has no usable voice, the glasses
  engine still takes over.

- **A voice you choose, at a speed you choose.** On the glasses those were
  device-wide settings shared with Rokid's own assistant, so we left them
  alone. On the phone they are ours to set for each sentence, so Settings has a
  Voice screen: pick the speed, pick among your phone's voices, and hear each
  one before you keep it. Voices that need a network say so, because choosing
  one sends what is spoken to be synthesised elsewhere.

- **The answer no longer arrives a second after you read it.** Bluetooth audio
  goes to sleep when nothing is playing, and waking it was being paid at the
  worst possible moment — right when you were waiting to hear the reply.
  Measured on real glasses, that cost 1.6 seconds. The link is now woken when
  the microphone opens, which is when we already know an answer is coming, so
  the wake-up hides behind your question: 6 milliseconds instead of 1600.

- **The assistant speaks its answers, and knows what you have told ChatGPT.**
  Ask it something and you hear the reply as well as see it, which is what a
  thing you talk to should do. It also pulls in the memories and custom
  instructions from the ChatGPT account you signed in with, instead of asking
  you to paste them in by hand, so it starts out already knowing what that
  account knows. Both can be turned off.

- **A notice keeps the input it asked for, and hands the display back when it
  leaves.** A banner that wanted your answer could have its taps taken by
  whatever was underneath, and dismissing one did not always return the glasses
  to standby.

## 1.1.5

- **Plugins can speak.** A plugin could put words on your display and take
  words from your mouth; it could not say anything. `tts` is the missing half:
  a new capability, granted per plugin and revocable like the others, that has
  the glasses read text aloud. The speech is produced on the glasses
  themselves, by the engine the Rokid assistant already uses — nothing is sent
  anywhere, nothing needs a network, and it works exactly as well with the
  phone in your pocket. Voice and speed stay where you already set them, in the
  Rokid assistant's own settings: they are your choice for everything that
  speaks on the device, so no plugin and not even the hub may change them.

- **Speech stops the moment the microphone opens.** Reading and dictating share
  one pair of ears. Left running together, the glasses record their own voice
  into whatever you are trying to say, and the transcript comes back with the
  notification read into it. Any request for the microphone now silences
  whatever is being spoken, and the plugin is told plainly that the platform
  stopped it. Cancelled speech never resumes on its own: by the time the
  microphone closes you have moved on, and a sentence finishing itself behind
  you is worse than one you did not hear.

## 1.1.4

- **Lists follow the selection past the viewport.** A card list rendered every
  row and let the screen clip the rest: with more conversations than the optics
  hold — eight notifications in the Relay inbox was enough — moving the
  selection past the last visible row kept working but showed nothing, on every
  plugin that sends list rows. The glasses now window the list around the
  selection: the selected row is always fully visible with a row of context
  after it when it fits, and muted `▴ 3` / `▾ 12` markers say how many rows
  hide on each side. A list that fits renders exactly as before. Thanks to
  Brilliant-Flight3682 for the report.

- **A notice can black out everything behind it — when you ask it to.** By
  default a notice still lands on top of whatever the wearer is doing, and the
  scene stays visible around it; that superposition is the point of a
  heads-up display. But a notification arriving over standby widgets reads as
  a collage, so the notice protocol gains `backdrop`: an opt-in, show-only
  flag that fades an opaque black scrim in with the band and hides every
  window behind it until the notice leaves. On the additive optics the scrim
  emits nothing — the rest of the display simply goes away. Relay surfaces it
  as "Black out behind notifications", off by default. Thanks to
  Brilliant-Flight3682 for describing the Even G2 experience this borrows
  from.

- **Glasses updates no longer fail on Android 11 phones.** The downloaded
  glasses APK was verified by asking the phone's PackageManager to parse it —
  and Android refuses to parse any archive whose minSdk it does not meet. The
  glasses hub ships with minSdk 31, so an Android 11 phone, the oldest Nexus
  supports, reported every glasses APK as "unreadable" and could never install
  or update the glasses app over the air, while the same APK installed fine
  over a dev cable. The phone now accepts an archive it cannot parse when the
  GitHub release digest has already verified it; phones new enough to parse
  still enforce the package name as before. Thanks to Sofathinker for the
  report and the settings log that pinned it.

## 1.1.3

- **Messages leave the glasses over SPP again.** 1.1.2 sent control traffic over
  CXR first, on the reasoning that it kept small messages out of the queue photo
  sync moves megabytes through. That reasoning still holds; the path no longer
  does. On Hi Rokid Global G1.11.11.0727 the glasses-to-phone CXR direction does
  not arrive: the glasses report `CXR-S TX ... result=0`, the phone's Rokid app
  logs the frame with its full payload and answers RESPONSE_SUCCEED, and the
  bound third-party client is never called. Opening a plugin from the glasses
  did nothing at all on an updated phone.

  Phone-to-glasses over the same link is unaffected, so the ordering is now
  deliberately asymmetric - the two directions no longer have the same
  reliability, and pretending otherwise cost a working feature. Thanks to
  @gtacoder-collab for the report that pinned it to the callback and for holding
  the line when we reverted it in 1.1.2.

- **The CXR receive path says when it drops something.** An unexpected key or an
  undecodable payload used to return in silence, which made "the callback was
  never invoked" and "the callback ran and threw the frame away" impossible to
  tell apart from a log. They have completely different causes; the absence of
  these lines is what proved which one this was.

## 1.1.2

- **Control messages leave the glasses over CXR again**, with SPP as the
  fallback rather than the first choice. 1.1.1 reversed that order to route
  around a CXR link that reported sends it had not delivered. It was the wrong
  trade: SPP is one RFCOMM channel with a single write lock, and it is the
  channel photo sync moves megabytes over, so a control message queued behind a
  chunk waits for that chunk to finish. CXR's separate path and small size
  ceiling are what keep control traffic out of that queue. The SPP fallback was
  already there and is unchanged; what it cannot cover is a send reported as
  successful that never arrived, which needs a real acknowledgement rather than
  a different running order.

## 1.1.1

A fix for something that only ever worked on one desk, found and fixed by
someone it did not work for.

- **The glasses are found by what they are, not by whose they are.** The phone
  hub matched one hard-coded Bluetooth address, written on the first day of the
  project and never generalised because the only tester owned that unit. Any
  other pair was invisible to it. The hub now identifies the glasses by the
  Nexus SPP service they advertise, with the `Glasses_*` name and the
  last-known address as fallbacks for when Android drops the remote metadata
  after a disconnect. Reported and fixed by Alexander Zhilin (#1, #2).
- **Photo Sync can move captures off any pair of glasses.** Outbound binary
  frames are the one path with no CXR fallback, so without a working SPP socket
  a capture could not leave the glasses at all. It could not have worked for
  anyone but us, and now it does.
- **Control messages from the glasses prefer the live SPP socket**, falling
  back to CXR, rather than the other way round.
- **The update banner no longer asks you to reinstall an app that is current.**
  Glasses going quiet used to erase the record of what they were running, so a
  stopped hub or a dropped link turned into "Reinstall latest glasses app" for
  an app that was already up to date. Both version numbers now survive the hub,
  and a hub that has not reported in yet no longer overrides what they prove.

## 1.1.0

The release Relay arrived in: messages on your glasses, answered out loud,
without touching the phone. Plus a first run that no longer needs a computer,
and the platform work both of those needed.

### Messages on your glasses

- A message you can reply to now reaches the glasses as it arrives, lights the
  display if it was dark, and is answered out loud. Each message keeps its
  sender beside it rather than being flattened into one paragraph, and a long
  conversation turns pages instead of stopping at eight lines.
- Speak your reply and it sends itself after a few seconds - visibly counted
  down on the chip you are looking at, cancellable for its whole length.
  Nothing is sent that you were not shown: if the glasses went dark or lost the
  link before the transcript appeared, the clock stops and waits for you.
- Miss one, or let it expire, and it is in **Messages** in the Nexus menu:
  every conversation waiting on you, newest first, read and answered the same
  way. It updates while you are looking at it.
- Everything with a reply box is relayed, from any app. Nothing else is, and
  nothing is stored: revoke notification access and what it captured goes with
  it.

### First run without a computer

- Setting up a new pair no longer needs a PC or a cable. The phone drives the
  glasses through pairing, turns their Wi-Fi on rather than sending you to
  another app, and hands the six-digit code to the phone instead of leaving it
  in your eyes to type blind.
- Setup asks for one switch at a time and gets out of the way. A single bad
  hand-off no longer closes the door on the whole flow, the manual route gives
  up quickly instead of retrying the slow one, and finishing hands you back to
  Nexus rather than to the ROM launcher.
- Wireless Debugging navigation reads the firmware's own localized labels, so
  it works in every language that firmware ships - no Nexus translation needed.
- Setup keeps a log you can read and send when something still goes wrong.
- The product speaks one language throughout. The half-French screens are gone.

### Glasses that stay in sync

- Captures taken while nothing was listening now arrive. A photo still being
  written left the run eligible while its own bytes were settling, and never
  came back for it; scanning and settling are separate decisions now.
- The hub comes back on its own after a reboot or an app update, instead of
  waiting to be opened by hand while captures pile up on the glasses.

### The HUD

- **Bands can carry a real message.** Up to sixteen structured lines, broken
  where the sender broke them, measured and paged on the glasses themselves.
- **Bands can light a dark display** for an event worth it - at most one wake
  every five seconds across every plugin, always a short pulse, never held on.
- **A band pages unless its answers need the swipes.** One answer or none, and
  the directions turn pages while a tap still replies.
- **Surfaces can be lists**: rows with a second line, a weight that says how
  much each matters, and a selection the glasses draw themselves. A row can
  also be prose under a fixed label, which is what makes a conversation read
  like one.
- A band is answered on the firmware's verdict about a touch, never on the
  contact that opens one - starting a swipe used to count as a tap.
- Five new marks in the shared glyph set: reply, send, retry, cancel, mic.

### For plugin authors — SDK 0.9.0

- `NexusRowTone`, plus `sub`, `tone` and `selected` on `NexusCardLine` and
  `subtitle` on `NexusCard`. Reference: `docs/surface-list-rows.html`.
- `lines` on `NexusNotice` and `NexusNoticeUpdate`; notice surface contract v3.
- `wakeDisplay` on `NexusNotice` and `NexusActivity`, off by default.
- `approvedCapabilities` on the bus: your grants are true by the time you are
  told you are approved, so a plugin that pushes the instant approval lands no
  longer reads an empty grant set.
- Everything above reuses the `surfaces` grant and plugin API version 3. No
  existing plugin needs a change.

### New plugins

- **Relay 1.0.0** - phone notifications on the glasses, answered by voice.
- **Photo Sync 1.0.1** - the auto-sync fixes above.

## 1.0.48

### Activities

- A new tier between a pin and a surface: an ongoing process — a ride
  approaching, a timer running, a transfer in flight — holds a stable corner
  of the HUD. It draws as a compact chip, and the primary activity can open
  into a panel with progress, details, and up to three platform-drawn
  actions the wearer steps through with scroll and fires with a tap.
- A significant update lets the panel flare to catch the eye — throttled by
  the platform, and never by holding the screen on: an activity still cannot
  wake or keep the display, exactly like every other tier.
- An idle panel folds back to its chip after about ten seconds. A new
  Settings switch keeps the primary activity expanded instead; it is the
  wearer's preference, and no plugin can read, set, or override it.

### Notices learn to ask a real question

- A notice can now carry up to three answers, drawn as glyph chips under
  the band — the same row an activity panel uses, drawn by the same view,
  so the wearer learns it once. Scroll steps along it, the selection wraps
  and follows its answer across updates, and a tap fires the one that is
  chosen.
- A notice takes exactly one answer, of either kind. The moment it is
  given, the row leaves the band, nothing fires again, and the phone
  refuses a duplicate — measured on hardware, two temple taps 188 ms apart
  used to mean two replies sent. This deliberately changes 1.0.46
  behaviour, where an interactive band replied on every tap.
- Clearing a field of a shown notice now actually reaches the glasses: the
  phone relays the patch a plugin sent instead of re-serialising its own
  state, so an emptied footer, a withdrawn question, or a cleared row
  arrives as exactly that.

### Plugin SDK 0.6.0

- The activity tier and notice actions land in the SDK: `NexusActivity`,
  `NexusNoticeAction`, `actions` on notices and their updates, the
  `onNexusActivityAction` and `onNexusNoticeAction` callbacks, and a
  nullable `interactive` on `NexusNoticeUpdate` so a band can be asked
  again.
- The guide gains a visual reference of the notice band's four states,
  with the one-answer rule as an interactive demo
  (`docs/notice-band-states.html`).

## 1.0.47

### Phone battery

- Your phone's charge now sits in the glasses' status row, beside the clock
  and the weather: a small phone glyph and the percentage, with a plus while
  it charges. The glasses always knew their own battery; now they admit the
  phone they depend on has one too.
- The chip behaves like the ROM's own indicators. It shows on the launcher
  and its screens, follows the status row when an app like the teleprompter
  moves it, tucks in beside the clock when the weather steps out, and never
  sits on top of anything.
- Not interested? Settings has a switch. Off means off — the chip leaves
  immediately and stays gone.

### Plugin marks

- A plugin's own icon now reaches the glasses. Until now a custom mark showed
  on the phone and fell back to a generic tile in the glasses launcher, which
  never had the plugin's APK to load it from; the mark itself now travels, as
  bare geometry the glasses draw in the HUD's one green.
- The design system holds: a plugin ships a shape, never a colour, a size, or
  a look. Tests now catch a mark that drifts from the rules instead of prose
  hoping it will not.

## 1.0.46

### Notices

- Plugins can interrupt you briefly with a band across the top of your view: a
  message that just arrived, a delivery at the door, one thing that happened
  and is worth a glance. It arrives, says its piece, and leaves on its own.
- You can answer one without opening anything. Tap the band and the plugin
  hears you, even though it has no screen open and never did — which is the
  point of the whole thing. Back always dismisses, and no plugin can take that
  key away from you.
- A band claims two gestures, not your glasses. Scroll still reaches whatever
  is underneath it, the launcher still opens, and every other control keeps
  working while a notice is up.
- Nothing can leave a band in your view: it clears on its own deadline, and no
  amount of updating it pushes past a minute.

### Motion

- The HUD moves now. Bands slide in and out instead of appearing, and the whole
  interface shares one set of timings rather than each screen inventing its
  own.
- Motion marks something happening, never decoration. Nothing on the glasses
  animates in a loop while you are wearing them and walking around.

## 1.0.45

### Pins

- Plugins can leave a small panel pinned in a corner of your view — a plate
  number, a gate, a door code. It stays there while you get on with things, and
  nothing has to hold a screen open to keep it in front of you.
- A pin outlives the plugin that put it there. A plugin woken by a notification
  can push one and go straight back to sleep, which is the whole point: the taxi
  that is eight minutes out should not need an app left open to tell you its
  plate.
- Pins pushed while the glasses are asleep on a table are no longer lost. They
  are kept and delivered the moment the glasses come back, instead of failing
  silently at exactly the moment a background plugin had something to say.
- A pin that names no deadline now clears itself after thirty minutes, so a
  plugin killed before it can tidy up cannot strand one in your view. Plugins
  that know their own horizon still set it, from a second to a day.
- Pins step aside while the camera is in use, and come back afterwards.

### Speech without an account

- Transcription now works out of the box on the phone's own speech engine: no
  key, no account, nothing to pay. It is the default until you pick something
  else, and whatever you pick still wins.
- The microphone is requested where you need it, on the dictation card, instead
  of sending you off to set something up elsewhere first.
- The Speech screen tells the truth about the engine you chose. It no longer
  offers to save an API key for an engine that takes none, and the language grid
  locks itself when the engine detects the language on its own.

## 1.0.44

### Glasses that keep themselves maintained

- Manual setup no longer fails after a successful pairing. It was looking for the
  glasses' connect port over mDNS, which plenty of routers never forward; the
  glasses now hand that port to the phone directly.
- Glasses whose pairing credential is no longer accepted repair themselves: the
  refused identity is dropped and a fresh pairing runs on its own, instead of
  every later maintenance pass failing silently for the life of the install.
- Setup no longer reports itself complete while the pairing is missing, which is
  what let a unit look healthy and still be unable to refresh its own watchdog.

### Fixes

- Text fields are no longer hidden behind the keyboard — the pairing form in
  particular, where you cannot check what you typed against a code that expires.
- The glasses display stays awake while you copy a pairing code, so the dialog
  no longer closes halfway through.

## 1.0.43

### Photos Sync

- Captures taken on the glasses now copy themselves into your phone gallery, in
  the same `Download/Hi Rokid/` album Hi Rokid imports into — over the Bluetooth
  connection the glasses already have, so neither device needs Wi-Fi. Install the
  new Photos Sync plugin from the Store to turn it on.
- Sync runs while charging by default; you can set it to sync as soon as you
  capture, or only when you tap Sync now. Interrupted transfers resume where they
  stopped, and every file is checksum-verified before it reaches the gallery.
- The transfer stays out of the way of everything else on the connection: it
  pauses for a camera session, yields whenever anything else is talking, and
  keeps only one chunk in flight.
- Deleting captures from the glasses after they are safely on the phone is
  available as an opt-in, and honestly reports when the glasses refuse.

### Glasses maintenance

- The command bridge now updates itself from the installed app, so a glasses unit
  whose ADB self-arm has gone stale still receives new privileged capabilities.
- The manual setup flow keeps the glasses display awake while you copy the
  pairing code, and the phone form no longer hides behind the keyboard.

## SDK 0.3.0

- New speech session API: plugins holding the new `stt` capability can start hub
  speech sessions and receive live state, partial and final transcript callbacks,
  without ever touching raw microphone audio or provider credentials. See the
  "Speech to text" section of the plugin SDK guide.

## 1.0.42

### Speech to text

- The hub now runs cloud speech engines against the glasses microphone: pick
  OpenAI, ElevenLabs or Azure, paste your provider key, and dictation works end
  to end from the new Speech settings screen — with live partial text on
  realtime engines and on-phone voice-activity endpointing.
- Twelve transcription languages with provider-tuned handling, including
  Cantonese and both Chinese scripts.
- ElevenLabs keys show the remaining credit balance with a usage gauge that
  refreshes after every dictation.
- Plugins can request the new "Speech to text" capability to receive transcripts
  through the SDK. The grant is separate from the raw microphone grant and is
  managed from the phone permissions screen; provider keys never leave the hub.
- Provider keys are stored encrypted with the phone's hardware keystore.

## SDK 0.2.1

- Restore JitPack distribution after the `sdk-v0.2.0` build failed while
  resolving an unused Kotlin Gradle plugin.
- Make SDK releases wait for the published JitPack POM before creating their
  GitHub release, so a green release can no longer advertise a missing
  artifact.

## 1.0.41

### R08 ring compatibility

- The Rokid R08 ring can now drive the Nexus HUD end to end alongside the R08 Access Bridge companion app: a triple tap opens the Nexus launcher, ring swipes move the selection, a single tap opens the selected plugin, and a double tap goes back.
- Ring control follows you into plugin surfaces. The hub translates ring gestures into the same key input plugins already receive from the temple touchpad, so every existing plugin works with the ring without an update.
- The glasses hub exposes an OPEN_LAUNCHER broadcast endpoint, and hands the ring back to R08 Access Bridge the moment no Nexus UI is on screen (with a bounded handoff while a plugin surface is opening). Requires R08 Access Bridge with the matching "Nexus launcher" ring action.

### Plugin microphone capability

- Plugins can request the glasses microphone and receive 16 kHz mono PCM through the new SDK audio session, with the grant managed from the phone permissions screen. No Android record permission is involved; audio comes from the glasses over the hub.

### Phone app

- The phone app now supports Android 11 and newer (previously Android 12+).

## 1.0.38

- The phone now offers a forced reinstall when the glasses package exists but its hub cannot report a version.
- Wi-Fi activation continues to use the full YodaOS Wi-Fi Settings page before the incompatible panel fallback.

## 1.0.37

- Wi-Fi activation now opens the full YodaOS Wi-Fi Settings page first, matching the proven R08 Access Bridge flow.
- The incompatible Android Wi-Fi panel remains only as a final fallback instead of bouncing users back to the launcher.

## 1.0.36

- Manual setup step 3 now enables glasses Wi-Fi through the privileged local command bridge before falling back to Settings accessibility.
- The flow still waits for a connected Wi-Fi network and the real Wireless debugging page before reporting success.

## 1.0.35

- Manual setup step 3 now turns on glasses Wi-Fi before opening Wireless debugging directly.
- The phone waits until the real Wireless debugging page is visible instead of treating a Settings launch request as success.

## 1.0.34

- Manual setup now confirms the Build-number taps only after Developer options are truly enabled on the glasses.
- Developer options and Wireless debugging no longer bounce back to the launcher when step 1 did not complete; the phone explains exactly which step to retry.

## 1.0.33

- Manual pairing now has three explicit controls: six rapid Build-number taps to enable Developer options, direct Developer options, and direct Wireless debugging positioning.
- The six-tap helper targets the displayed build identifier instead of relying on the Settings language and does not automate the rest of the Settings menus.

## 1.0.32

- Direct manual Settings buttons now clear any stale Settings sub-screen before opening, so **Open Developer options** reliably returns to the main developer screen and **Show Wireless debugging** reliably positions the Wireless Debugging row even when Settings was already open.

## 1.0.31

- Manual setup is now always available from onboarding while the glasses app is installed but setup is incomplete, even when the failing transport never delivers a diagnostic.
- The manual wizard no longer drives the glasses Settings menus automatically. It provides separate **Open Developer options** and **Show Wireless debugging** buttons; the latter opens the public Developer options screen already positioned with Wireless Debugging visible for the wearer to select.

## 1.0.30

- Automatic glasses setup now has a clear recovery path: after the initial secure-transport attempt and two internal retries fail, the phone surfaces a guided **Manual setup** action.
- The manual wizard opens the required Wireless Debugging pairing screen on compatible glasses, waits for their acknowledgement, and guides the user through the remaining values without storing the six-digit code.

## 1.0.29

- Failed automatic setup now offers a guided phone-side pairing fallback that opens the required glasses settings itself. The phone waits for a glasses acknowledgement and asks for an app update instead of showing a pairing form when the glasses build is too old.
- Developer mode on the phone now exposes the manual glasses setup wizard for support and testing.

## 1.0.28

- First-run glasses setup is much more resilient: if the secure channel drops mid-arm (including during the planned adbd restart), Nexus reconnects and resumes instead of failing with a support code.

## 1.0.27

- Setup failures on the glasses now show a short support code on the retry card, so a photo of the lens is enough to diagnose what went wrong.

## 1.0.26

- Glasses app updates now ask you to turn on phone Wi-Fi before starting instead of failing during delivery.
- First-run glasses setup now waits for the glasses to join a Wi-Fi network and explains how to recover when none is connected.

## 1.0.25

- The phone now surfaces the glasses' AI-assist button presses and wearing status to plugins, opening the door to features that react to them.

## 1.0.24

- The phone now checks for app and plugin updates on its own, even if you never open Rokid Nexus — you'll get a notification the moment one is available.

## 1.0.23

- Lens opens noticeably faster when the phone's Wi-Fi is off: the glasses no longer set up a Wi-Fi Direct group they were just going to discard, connecting straight to the phone's hotspot instead.

## 1.0.22

- Lens now works even when the phone's Wi-Fi is off: the phone hosts the camera link itself, brings the glasses onto it automatically, and no setting has to be toggled by hand.
- Faster Lens connection when the phone's Wi-Fi is off — the glasses join on the first try instead of retrying.
- Long lyric lines no longer lose their last words; the text shrinks to fit instead of clipping.
- Lens is steadier in longer sessions: it recovers after a plugin update mid-session, handles camera rotation more gracefully, keeps its adaptive text layout in more cases, and reconfigures its video decoder without a brief stutter.

## 1.0.7

- The "Set up your glasses" step can open the Nexus app on the lens directly, so the wearer never hunts through the glasses launcher.

## 1.0.6

- Fix a launch crash in 1.0.5: the install Wi-Fi check needed the ACCESS_WIFI_STATE permission.

## 1.0.5

- The glasses install step now checks that phone Wi-Fi is on first — the APK travels over a direct Wi-Fi link — and offers to turn it on instead of failing with an opaque error.
- On the glasses, enabling the accessibility service flows straight into the secure self-arm; no second tap needed.

## 1.0.4

- Split the glasses onboarding into an install-only card and a dedicated "Set up your glasses" card that owns the How it works guide; drop the Manual download link.
- The glasses report their self-arm setup state to the phone, so the setup step completes exactly when the launcher appears on the lens.

## 1.0.3

- Fix onboarding steps hiding their main button whenever a secondary action was shown — the automated "Install Nexus" glasses install and the notifications "Allow" were invisible.
- Drop the redundant Skip button from the notifications step; denying the system dialog already moves the setup along.

## 1.0.2

- Fix a first-launch crash: the hub no longer starts its foreground service before the Bluetooth permission is granted, and it starts automatically once the permission arrives.
- Ask for each permission from its own onboarding step — Bluetooth, notifications (skippable), and app installs — instead of prompting cold at launch.
- Only mark the first-plugin onboarding step done once a plugin is approved.

## 1.0.1

- Fix the self-arm watchdog script line endings so the secure bootstrap installs a working watchdog.
- Return to the Nexus HUD automatically after the accessibility service is enabled in Settings.
- Slim the glasses launcher, scroll it AR-clean, and show plugin icons.
- Add a phone-side "How it works" walkthrough of the on-glasses setup.
- Report the glasses app version to the phone and offer glasses updates from there.

## 1.0.0

- First public signed release of the Rokid Nexus phone and glasses apps.
- Provides the headless plugin platform, including the Store and developer mode.
- Supports camera and Lens capabilities plus feeds, transit, lyrics, and media plugins.
