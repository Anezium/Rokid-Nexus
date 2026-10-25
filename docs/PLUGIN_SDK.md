# Nexus Android plugin SDK

> [PLUGINS.md](PLUGINS.md) is the full guide to building a plugin (module
> structure, the headless-manifest rules, and the NexusUi design kit). This
> document is the SDK reference: artifact coordinates, the service contract,
> and the approval flow.

For the complete self-contained plugin contract — endpoints, limits,
lifecycle, and publishing — see [`plugins/AGENTS.md`](../plugins/AGENTS.md).

The SDK artifact is `com.github.Anezium.Rokid-Nexus:bus-client`, released
through JitPack from `sdk-v*` tags on this repository (see the "Rokid Nexus
SDK" GitHub releases for the current version). The `shared` artifact is
resolved transitively.

## 1. Add the dependency

```kotlin
repositories { maven("https://jitpack.io") }

dependencies {
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:sdk-v0.23.0")
}
```

For local development against a checkout, publish a snapshot instead:
`.\gradlew.bat :shared:publishToMavenLocal :bus-client:publishToMavenLocal
'-PversionName=0.1.0-SNAPSHOT'` and consume it from `mavenLocal()`.

The launcher-bundle APIs documented below are available in this source checkout;
`sdk-v0.23.0` does not include them. External plugins using interactive activity
destinations, typed quick actions, or the conversation/media/route templates must
consume a snapshot until an SDK release includes those APIs.

Use `compileSdk = 36`. The bus-client AAR supports `minSdk >= 26`; the
repository's canonical Sample and Transit plugin templates use `minSdk = 30`
(Android 11), matching the phone hub — don't require a newer API level without
a reason, or your plugin won't install on Android 11 phones the hub supports.
The repository builds with JDK 17.

## 2. Declare the plugin service

Declare exactly one exported service for the Nexus plugin action. Installation
does not approve it.

```xml
<service android:name=".HelloPluginService" android:exported="true">
    <intent-filter>
        <action android:name="com.anezium.rokidbus.action.PLUGIN" />
    </intent-filter>
    <meta-data android:name="com.anezium.rokidbus.plugin.ID" android:value="hello" />
    <meta-data android:name="com.anezium.rokidbus.plugin.DISPLAY_NAME" android:value="Hello Nexus" />
    <meta-data android:name="com.anezium.rokidbus.plugin.API_VERSION" android:value="3" />
    <meta-data android:name="com.anezium.rokidbus.plugin.CAPABILITIES" android:value="surfaces" />
    <meta-data android:name="com.anezium.rokidbus.plugin.RECEIVE_PREFIXES" android:value="/plugin/hello,/system/plugin" />
    <meta-data android:name="com.anezium.rokidbus.plugin.SETTINGS_ACTIVITY" android:value=".HelloActivity" />
    <meta-data android:name="com.anezium.rokidbus.plugin.LAUNCHABLE" android:value="true" />
</service>
```

Plugin IDs use `[a-z][a-z0-9._-]{2,63}`. Requested capabilities are `surfaces`,
`ink_surface`, `http_proxy`, `microphone`, `stt`, `tts`, `camera`, `mediasync`,
`assistant`, and `wireless_debugging`. The skills grants, `skills_provider` and
`skills_client`, are requested through their own metadata keys instead (§3.6).
`ink_surface` is distinct from `surfaces`:
it grants the phone-side compiler and native glasses renderer for interactive
Ink pages, and adding it to an existing descriptor requires re-approval. Camera paths
are protected by the approved signer-bound grant. `microphone` is grantable from
the phone UI for any plugin that requests it (see §3.1); the plugin needs no
Android `RECORD_AUDIO` permission — glasses-microphone PCM reaches the plugin
over the hub, not through the phone's own recorder. `stt` is a separate grant for
hub-produced transcript text and does not require the plugin to request raw
`microphone` access. `tts` is its opposite number, speech out rather than in
(§3.3); it is likewise independent of `microphone`, since the plugin supplies
text and never touches audio.

Descriptor capabilities authorize Nexus hub resources and routes. They do not
replace Android permissions for a phone-local platform API. A headless plugin
may use such an API under permissions declared in its own manifest and granted
through its own settings UI; Assistant's direct Calendar Provider access under
`READ_CALENDAR` and `WRITE_CALENDAR` is one example. That access adds no Nexus
capability, receive prefix, typed SDK surface, or wire route. The plugin owns
the consent, denial, and provider-error UX.

## 3. Implement the service

```kotlin
class HelloPluginService : NexusPluginService() {
    private var surface: NexusSurfaceSession? = null

    override fun onNexusOpen() {
        surface = nexusSurfaceSession("main")
        surface?.showCard(
            NexusCard(
                title = "Hello Nexus",
                lines = listOf("> First", "  Second"),
                footer = "swipe · tap · back",
            ),
        )
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            surface?.hide()
        }
    }

    override fun onNexusClose() {
        surface?.hide()
        surface = null
    }
}
```

The hub can cold-start this service after the app process was stopped. Do not use
an Activity initializer or static factory. `onNexusOpen`, `onNexusClose`, input,
link-state, and registration callbacks are serialized on the application main
thread. Override `onNexusOpen(openType)` instead of `onNexusOpen()` when it
matters *why* the plugin was opened: `PluginOpenTypes.OPEN` is a deliberate
pick (the glasses launcher, or *Open* on the phone), `PluginOpenTypes.AI_ASSIST`
is the assist button handing over, and `PluginOpenTypes.RESUME` returns a plugin
from lease-bounded background audio; treat anything else like `OPEN`. The
default forwards to the plain `onNexusOpen()`. A granted background detach calls
`onNexusBackground()` rather than `onNexusClose()`; the latter remains the final
close callback. Duplicate lifecycle IDs are ignored. The glasses path already deduplicates
paired directional aliases; plugins should act once on each delivered input.

Approved, registered plugins automatically receive informational glasses signals;
no descriptor capability or extra grant is required. Test
`LinkStateBits.GLASSES_WORN` in `onNexusLinkState`, override
`onNexusGlassesAiButton(active)` for the AI-assist button (`true` on start,
`false` on stop), and handle `BusPaths.GLASSES_DEVICE_INFO` in `onNexusMessage`.
The version-1 device payload contains `deviceName`, `batteryLevel`, `sound`,
`brightness`, `systemVersion`, `isCharging`, and `wearingStatus`, in addition to
`type`, `id`, and `pluginId` envelope fields — the hardware serial number is
never included. These callbacks are observational and do not alter Hi Rokid's
assistant behavior.

Beyond the typed surface API, the service exposes `hubTarget` to select which
hub the plugin binds (phone by default), and two raw hooks for traffic on the
declared receive prefixes: `onNexusMessage` (JSON envelopes) and
`onNexusBinaryMessage` (binary frames with their metadata). Hub state rides the
additive capabilities contracts in `shared`: the phone announces `features`
plus the camera consumer display name (`PhoneHubCapabilitiesContract`), the
glasses announce renderer features, image/pin/notice/activity/Ink surface versions,
image limits, their app version, and onboarding completion
(`GlassesHubCapabilitiesContract`); unknown fields stay ignorable in both
directions.

Surface IDs are local to the plugin. The SDK validates fields and payload size;
the hub injects verified ownership and global sequencing. High-level code cannot
set a trusted owner, global sequence, or arbitrary system path.

### Choosing a HUD kind

Choose the object by what the wearer is doing, not by how large you want it to
look:

- **Ongoing process** the wearer follows → **activity**.
- **Discrete event** needing attention or a response → **notice**.
- **Engaged interaction** the wearer is driving → **surface**.
- **Trivial static fact** that just needs to stay put → **pin**.

An activity is not a frequently updated pin. If there is a state machine behind
the value — a route, delivery, ride, workout, or timer — use an activity. Your
plugin describes that state and the platform decides whether it is currently a
chip, panel, flare, pulse, or hidden. Plugins cannot select a presentation or
supply activity layouts, images, animations, colors, or timings.

### Surface list rows

A card used to be a title and some lines. It can also be a **list**: rows with a
second line, a weight saying how much each matters, and a selection the HUD
draws itself. Pass `richLines` instead of `lines`.

```kotlin
enum class NexusRowTone { ALERT, NORMAL, DIM, BODY }

data class NexusCardLine(
    val text: String,
    val badge: String? = null,
    val trail: List<String> = emptyList(),
    val sub: String? = null,                    // second line, smaller and dimmer
    val tone: NexusRowTone = NexusRowTone.NORMAL,
    val selected: Boolean = false,              // the HUD draws the rail
)

data class NexusCard(
    val title: String,
    val lines: List<String>,
    val subtitle: String? = null,               // one dim line under the title
    val footer: String? = null,
    val contentKey: String? = null,
    val richLines: List<NexusCardLine>? = null,
    val handlesBack: Boolean = false,
)
```

`text` and `sub` each hold 240 characters; `richLines` and `lines` are mutually
exclusive, and up to 64 rows are accepted.

| Tone | Means | Use it for |
|---|---|---|
| `ALERT` | Needs the wearer now | A question waiting on them, something failing |
| `NORMAL` | An ordinary entry | The default |
| `DIM` | Present, not competing | Finished, stale, no longer actionable |
| `BODY` | Prose that must wrap | A message or log excerpt; `badge` becomes a label column beside it |

**Do not draw your own selection.** A `>` typed into `text` costs two of the
twenty-six monospace columns a row reads comfortably in, and puts the selection
somewhere different from every other list on the device. Set `selected` and let
the platform draw the rail.

**Say what a row is worth, never how it should look.** `tone` describes
importance; the platform maps it to size, weight and colour. A plugin that
finds itself reaching for a colour is working around the tier.

[surface-list-rows.html](surface-list-rows.html) draws all of it — plain lines,
a list with sub lines and a rail, the four tones, body rows with their label
column. Open it in a browser.

### Editable fields

A card can carry one bounded, focusable text field in place of its read-only
body — the one way a plugin gets typed input back from the wearer. It takes
ordinary Android text input: a keyboard bonded to the glasses, or the phone's
own Keyboard & remote screen, which types into the glasses' Nexus IME. On a
phone hub from 1.4.13, opening the field brings that screen forward with its
keyboard up — by itself when the user has let Nexus display over other apps,
otherwise through a notification to tap. Fields the wearer merely lands on in
other apps never do this.

```kotlin
data class EditableSurfaceField(
    val label: String? = null,
    val placeholder: String? = null,
    val initialText: String? = null,
    val submitLabel: String? = null,
    val inNotice: Boolean = false,
)

data class NexusCard(
    // ...
    val editable: EditableSurfaceField? = null,
)

interface NexusPluginCallbacks {
    fun onSurfaceTextCommitted(surfaceId: String, text: String, cancelled: Boolean) = Unit
}
```

Send it like any other card, with `editable` set instead of `lines`:

```kotlin
surface?.showCard(
    NexusCard(
        title = "Reply",
        lines = emptyList(),
        editable = EditableSurfaceField(placeholder = "Type your reply…", submitLabel = "Send"),
    ),
)
```

`label`, `placeholder`, and `submitLabel` are each capped at a small number of
characters (64, 64, and 24) and `initialText` at 512 UTF-16 code units;
oversized values are rejected locally the same way an oversized `NexusCard`
title is. The card renders a real focusable `EditText` rather than the
non-interactive body a plain card gets, so normal Android input — a bonded
hardware keyboard, or the phone keyboard through the glasses IME — reaches it
directly; nothing plugin-side subscribes to keystrokes as they happen.

Set `inNotice = true` (SDK 0.20.0, glasses hub 1.4.13) to have the field
typed into your own notice band instead, like an Android inline reply: the band draws the text and caret live
under its message and drops its action row while the field is open. It only
applies while your band is the one on screen, so open the field from a band
action (Relay's Type chip does) and keep the band alive while it is open. On a
hub that predates it, or with no band of yours up, the field shows as a card as
usual, so the flag never costs a fallback path. From glasses hub 1.5.0 the
screen behind stays in view while the band carries the field; earlier hubs
draw it black.

A card with a title and nothing else behaves the same way under your band on
glasses hub 1.5.0: it draws nothing while a band of yours is up, so you can
keep your session open under the band without covering the screen, and it
comes back as a card about 1.5 s after the band goes unless you hide it first.

The wearer's answer comes back exactly once, on `onSurfaceTextCommitted`, when
they submit (Enter, on a bonded keyboard or on the phone keyboard; `label` and
`submitLabel` are accepted and reserved, the current renderer does not draw
them) or cancel (Back):
`cancelled` is `true` for the latter, in which case `text` is always empty and
should be ignored. A submitted `text` is silently clamped to 512 UTF-16 code
units if it runs longer — the same limit `initialText` and `label` obey — so a
field meant to collect more than that should say so in its `label` or
`placeholder`; there is no in-band way to signal "your reply was cut short"
after the fact. `NexusPluginService` forwards the callback to the overridable
`onNexusSurfaceTextCommitted(surfaceId, text, cancelled)` hook, following the
same pattern as `onNexusActivityAction`.

There is no separate grant for this: it rides `surfaces` and the foreground-slot
ownership rules (`SURFACE_BUSY`, replacement, BACK, link-loss) a plain card
already has. The glasses hub must be new enough, though: check
`client.supportsEditableSurface` before showing a field — the `EDITABLE_SURFACE`
bit, negotiated like `supportsInkSurface`, true only while the data link is up —
and do something else when it is false, because an older hub shows the card and
never commits it. Relay falls back to dictation.

`showCard` returning `SENT` means the hub accepted the Binder call, not that the
field is on screen: another plugin may hold the foreground slot, and the hub
then answers `SURFACE_BUSY` a moment later. Set `session.onRejected = { code ->
… }` before the call to hear about it, and undo whatever state you entered on
`SENT`; hubs before 1.4.6 never delivered that rejection at all.

In particular, a new
`showCard`/`updateCard` on the same session — including one that arrives while
the field is still open, such as a different reply overtaking a card mid-type
— replaces it before any commit for the old field lands; do not assume a
commit callback necessarily still refers to whatever your own state currently
considers "the current" card. Bind commits to whatever identity your plugin
opened the field for, and reject or ignore ones that no longer match.

### Reader surfaces

Use a reader for a long, continuous document such as an agent conversation.
Unlike a card, it has no row layout or three-line prose clamp: send semantic
segments and let the glasses renderer wrap and scroll them.

```kotlin
enum class NexusReaderSegmentKind {
    HEADER,
    PROSE,
    ASIDE,
}

enum class NexusReaderAnchor {
    BOTTOM,
    TOP,
}

data class NexusReaderSegment(
    val kind: NexusReaderSegmentKind,
    val text: String,
    val emphasis: Boolean = false,
)

data class NexusReader(
    val title: String,
    val subtitle: String? = null,
    val footer: String? = null,
    val contentKey: String? = null,
    val segments: List<NexusReaderSegment>,
    val anchor: NexusReaderAnchor = NexusReaderAnchor.BOTTOM,
)

fun NexusSurfaceSession.showReader(reader: NexusReader): NexusSdkResult
```

`HEADER` introduces a speaking turn. Put the speaker token before the first
`·`; setting `emphasis` makes that token bright for the wearer's own turns.
`PROSE` is full-width body text with no line clamp, and an empty prose segment
creates a paragraph break. `ASIDE` is one muted event line; include your own
prefix such as `⋯ ` when wanted.

```kotlin
surface?.showReader(
    NexusReader(
        title = "Conversation",
        subtitle = "3 turns",
        footer = "tap · back",
        contentKey = "conversation-42",
        segments = listOf(
            NexusReaderSegment(
                NexusReaderSegmentKind.HEADER,
                "YOU · now",
                emphasis = true,
            ),
            NexusReaderSegment(
                NexusReaderSegmentKind.PROSE,
                "Summarize the renderer contract.",
            ),
            NexusReaderSegment(
                NexusReaderSegmentKind.HEADER,
                "CX · now",
            ),
            NexusReaderSegment(
                NexusReaderSegmentKind.PROSE,
                "The glasses own wrapping, scroll position, and paging input.",
            ),
            NexusReaderSegment(
                NexusReaderSegmentKind.ASIDE,
                "⋯ read BUSSPEC.md",
            ),
        ),
    ),
)
```

The model validates locally: `title` is required, non-blank, and at most 120
characters; `subtitle` and `footer` are at most 240; `contentKey` is at most
128; there are 1 through 240 segments; each segment text is at most 4,096
characters; and all segment text together is at most 40,000 characters. Null
shell fields and false `emphasis` are omitted from the wire payload.

`showReader` uses the existing `surfaces` grant and the same result mapping as
`showCard`; there is no reader-specific capability or grant. Call it again on
the same session to replace the complete document.

Reader input is renderer-owned, and the allow list is narrower than a card's.
Directional keys and media next/previous scroll the document on the glasses and
never reach `onNexusInput`; only ENTER, DPAD_CENTER, and BACK are forwarded to
the surface callback. So a reader that models a selection index is writing dead
code: there is no per-segment focus, and the keys that would move one never
arrive.

`anchor` says where reading begins, and the distinction it draws is
stream-shaped versus document-shaped content. `BOTTOM`, the default, suits a
chat, a log, an agent transcript: the surface opens at the end, an update stays
pinned there when the wearer was already near the end, and otherwise restores
the previous offset. `TOP` suits an article, a recipe, a README, a saved note:
the surface opens at the start and never follows the tail, so an update always
restores where the wearer had scrolled to. Sending `TOP` needs glasses hub
1.4.3 or newer; an older hub ignores it and opens at the bottom as before.

### Live activities

Activities reuse the existing `surfaces` grant and plugin API version 3. They
live on `NexusPluginClient`, not `NexusSurfaceSession`, because the real-world
process continues when its engaged surface closes:

```kotlin
sealed interface NexusActivityProgress {
    data class Percent(val value: Int) : NexusActivityProgress
    data object Indeterminate : NexusActivityProgress
}

data class NexusActivityAction(
    val id: String,
    val glyph: String,
    val label: String,
    val typed: NexusActionType? = null,        // launcher bundle, media only
)

data class NexusActivityTrack(
    val count: Int,
    val at: Int,
    val target: Int,
    val label: String? = null,
)

data class NexusActivity(
    val glyph: String,
    val primary: String,
    val secondary: String? = null,
    val progress: NexusActivityProgress? = null,
    val eta: String? = null,
    val detail: List<String> = emptyList(),
    val actions: List<NexusActivityAction> = emptyList(),
    val maxDurationMs: Long? = null,
    val wakeDisplay: Boolean = false,
    val badge: String? = null,
    val track: NexusActivityTrack? = null,
    val measure: String? = null,
    // Launcher bundle; see "Interactive activities" below.
    val destination: NexusPageDestination? = null,
    val activation: NexusActivityActivation? = null,
    val defaultActionId: String? = null,
    val persistence: NexusActivityPersistence = NexusActivityPersistence.SESSION,
)

val supportsActivitySurface: Boolean
val supportsActivityExtras: Boolean
fun supportsLauncherInteractive(): Boolean
val registrationGeneration: Int
fun startActivity(activity: NexusActivity): NexusSdkResult
fun updateActivity(
    activity: NexusActivity,
    significant: Boolean = false,
    urgent: Boolean = false,
): NexusSdkResult
fun endActivity(): NexusSdkResult
fun sendActivityCommandResult(command: NexusActivityCommand, result: NexusActivityCommandResult): NexusSdkResult
fun publishActivityGeometry(revision: Long, geometry: RouteGeometry): NexusSdkResult
fun publishActivityHeading(deg: Double, accuracyDeg: Double? = null): NexusSdkResult

interface NexusPluginCallbacks {
    fun onActivityAction(id: String) = Unit
    fun onActivityCommand(command: NexusActivityCommand): NexusActivityCommandResult? {
        onActivityAction(command.actionId)
        return null
    }
    fun onActivityClosed(reason: String) = Unit
}
```

`NexusPluginService` forwards those callbacks to the overridable
`onNexusActivityAction(id)`, `onNexusActivityCommand(command)` and
`onNexusActivityClosed(reason)` hooks used in the examples below.

Check the live `supportsActivitySurface` value immediately before starting or
updating. For a registered plugin with the `surfaces` grant, all three methods
return `CAPABILITY_NOT_AVAILABLE` without sending when the glasses did not
announce activity v1, so the same plugin APK remains safe with an old hub.

The typed models enforce the wire caps in `init`: `primary` is required and at
most 12 trimmed characters; `secondary` is optional and at most 28; `eta` is
optional and at most 8; `detail` has at most two 32-character entries;
percentage progress is `0..100`; and there are at most three actions.
`maxDurationMs`, when present on start, is clamped by the hub to one minute
through 12 hours. Without it the activity lasts until explicitly ended,
replaced, or its owner disconnects. There is no TTL and no keep-alive loop.

Because an activity belongs to the registration that started it, a plugin
that keeps one running across a hub reconnect must start it again on the new
registration. `onRegistrationState(APPROVED)` alone cannot tell: it is
reported twice for every registration (at once, then with the capability
metadata). Compare `registrationGeneration` (SDK 0.21.0) with the value
current when the activity started; a different number is a new registration
that holds nothing yet.

Activity and action glyphs are strings, not enums, because the glyph vocabulary
is additive. Use a platform glyph for each action; the main activity glyph may
also be one your plugin registered through the custom-glyph API. The wire
validates glyph-name shape, and an unknown well-formed name renders as `dot`
instead of failing on an older hub. Each action's `id`, `glyph`, and `label`
must be nonblank. Activity v1 intentionally sets no separate numeric length cap
on action IDs or labels beyond that requirement and the three-action limit.

`updateActivity` sends the complete mutable state: nullable optional fields are
explicitly cleared when null and both lists are sent even when empty.
`maxDurationMs` is start-only and is omitted from updates, so an update cannot
restart or change the safety deadline. `significant` is a transient hint and is
sent only when true. `wakeDisplay` is also start-only: when true, a later
`significant` update may wake a dark display. Ordinary updates never wake it.

A Maps-shaped route can publish the next maneuver as one object. Its one
command states the desired result, never a toggle: the action offered is the
one that changes the current state, so a repeated or late command cannot flip
it back.

```kotlin
class MapsLikePluginService : NexusPluginService() {
    private var routeActivityStarted = false
    private var muted = false

    override fun onNexusOpen() = Unit
    override fun onNexusClose() = Unit // The route activity continues.
    override fun onNexusInput(event: NexusInputEvent) = Unit

    fun startRoute() {
        val result = nexusClient?.startActivity(
            routeActivity(
                glyph = "turn-left",
                distance = "300 m",
                street = "Rue de la Paix",
                percent = 42,
                maxDurationMs = 4 * 60 * 60 * 1000L,
            ),
        )
        routeActivityStarted = result == NexusSdkResult.SENT
    }

    fun updateRoute(
        glyph: String,
        distance: String,
        street: String,
        percent: Int,
        maneuverChanged: Boolean,
    ) {
        if (!routeActivityStarted) return
        nexusClient?.updateActivity(
            routeActivity(glyph, distance, street, percent),
            significant = maneuverChanged,
        )
    }

    private fun routeActivity(
        glyph: String,
        distance: String,
        street: String,
        percent: Int,
        maxDurationMs: Long? = null,
    ) = NexusActivity(
        glyph = glyph,
        primary = distance,
        secondary = street,
        progress = NexusActivityProgress.Percent(percent),
        eta = "12:41",
        detail = listOf("then right on Av. de l'Opera"),
        actions = listOf(
            if (muted) {
                NexusActivityAction(id = "unmute", glyph = "play", label = "Unmute")
            } else {
                NexusActivityAction(id = "mute", glyph = "pause", label = "Mute")
            },
        ),
        maxDurationMs = maxDurationMs,
    )

    override fun onNexusActivityAction(id: String) {
        when (id) {
            "mute" -> muted = true
            "unmute" -> muted = false
        }
    }

    override fun onNexusActivityClosed(reason: String) {
        routeActivityStarted = false
    }

    fun finishRoute() {
        nexusClient?.endActivity()
        routeActivityStarted = false
    }
}
```

Ordinary updates pulse. Set `significant = true` only for a real transition
such as a maneuver change or arrival. The hub decides whether that becomes a
flare and permits at most one flare per activity every 10 seconds; a throttled
flare becomes a pulse and is never queued. Do not use `significant` for distance
countdown ticks, nor for media moving to a new track: on a negotiated launcher
the collection is ordered by each activity's latest significant update, so a
significant track change would lift what plays above a running route.

#### Activity extras

`badge`, `measure`, `track`, and `urgent` are extras (SDK 0.21.0, both hubs
1.5.0): optional, drawn by the platform, and understood only when both hubs
support them. `supportsActivityExtras` reports
that. Without it the SDK still sends the activity, the older hub drops the
extras, and the wearer sees the v1 form, so nothing needs a second code path.

- `badge` — at most 5 characters, such as a line number ("38", "M4",
  "RER B"). The expanded panel and the flare draw it as an outlined plate where
  the glyph would be. The chip keeps `glyph`.
- `measure` — at most 8 characters, a second quantity that belongs with
  `primary`, such as a walk's distance next to its minutes ("250 m"). The
  expanded panel reads "3 min - 250 m"; the chip keeps "3 min" with "250 m"
  stacked under it beside the glyph, and `secondary` on its line below.
- `track` — `NexusActivityTrack(count, at, target, label)`: 2 to 12 ordered
  positions such as the stops of a ride or the stages of a delivery, where the
  process is now, and where the wearer is headed, with an optional label of at
  most 20 characters naming the target. It replaces the progress bar in the
  panel. Keep sending `progress` too; older glasses draw that.
- `urgent` — pass `updateActivity(activity, significant = true, urgent = true)`
  for a time-critical transition such as "get off at the next stop". It is
  refused without `significant`. The flare gets a bright outline that beats
  once it has arrived, and it has its
  own budget of one per activity per minute, so an ordinary flare a few seconds
  earlier cannot swallow it. Past that budget it is an ordinary significant
  update. It never wakes a display that `wakeDisplay` would not.

The platform never advances a track or counts anything down between your
updates: what the wearer reads is what you last sent, so send the values the
source you follow reports.

```kotlin
nexusClient?.updateActivity(
    NexusActivity(
        glyph = "bus",
        badge = "38",
        primary = "Get off",
        secondary = "Next stop: Luxembourg",
        progress = NexusActivityProgress.Percent(80),
        track = NexusActivityTrack(count = 5, at = 3, target = 4, label = "Luxembourg"),
    ),
    significant = true,
    urgent = true,
)
```

The expanded panel fits `primary` rather than cutting it: it shrinks first, and
if the value still does not fit beside the ETA, the ETA moves to the secondary
row. The 12-character cap is unchanged.

By default, an idle expanded panel collapses to its chip after about 10 seconds.
The wearer can keep the primary activity expanded from Nexus phone Settings.
That is a platform preference; no plugin API can read, set, or override it.

The platform keeps at most two activity islands and one pin in stable corners.
Exactly one activity is primary: the most recently significant one, or the
oldest started one when none is significant. Only the primary activity shows
the expanded panel. Without the launcher bundle the hubs hold two activities: a
third start replaces the least-recently-updated non-primary activity, with the
oldest start as the deterministic fallback when no non-primary candidate
exists. With it, the collection holds eight, one per plugin, and still two
islands; see "Interactive activities" below.

Activity islands take no input: they are passive, and native apps and surfaces
keep every key. A tap on an island neither opens your plugin nor runs an action.
Your actions are reached only inside the wearer's Nexus session, between hubs
that negotiated the launcher bundle, where they arrive on
`onNexusActivityCommand` (and, through its default, `onNexusActivityAction`).
Glasses hubs before 1.7.0 still let the idle-layer primary activity take
forward/backward and center tap. BACK is never claimed.
`onNexusActivityClosed(reason)` reports `owner`, `replaced`, `disconnect`, or
`max-duration`.

The phone hub owns canonical activity state and resends it after a glasses
reconnect, after first clearing possible ghosts. You should still call
`endActivity()` when the underlying process ends. Do not end it merely because
an engaged surface received `onNexusClose`.

An activity never keeps the display on. Set `wakeDisplay = true` at start only
when a later significant transition must not be missed; the flag is remembered
for that activity, and is omitted from every update payload. The glasses hub
allows at most one actual wake per five seconds globally across activities,
notices, surfaces, and plugins. A significant update arriving while the display
is already interactive spends no budget. Activity v1 does not include plan
014's glance layer.

#### Guidance planner

A plugin that guides a route (Navigation from a navigation app's
notifications, Transit from its own itinerary) should not decide on its own
which step flares and which beats. `NexusGuidancePlanner` (SDK 0.22.0) turns successive
`NexusGuidanceStep`s into activity traffic identically for every guide: the
first step starts the activity, a new `stepKey` (the next maneuver or leg) or
arrival is significant, the moment a step becomes `imminent` is urgent once
per step, other visible changes are quiet updates, and a step that changes
nothing the wearer sees sends nothing.

```kotlin
private val planner = NexusGuidancePlanner()

fun onGuidance(step: NexusGuidanceStep) {
    val client = nexusClient ?: return
    when (val plan = planner.plan(step)) {
        is NexusGuidancePlan.Start ->
            client.startActivity(plan.step.toActivity(maxDurationMs = routeMs, wakeDisplay = true))
        is NexusGuidancePlan.Update -> client.updateActivity(
            plan.step.toActivity(),
            significant = plan.significant,
            urgent = plan.urgent && client.supportsActivityExtras,
        )
        NexusGuidancePlan.Unchanged -> Unit
    }
}

override fun onNexusActivityClosed(reason: String) = planner.reset()
```

Call `reset()` whenever the activity is gone (ended, closed, or lost with a
registration), so the next step starts it again. `NexusGuidanceGlyphs` names
the shared maneuver, vehicle, walk, and arrival glyphs, and
`forTransitMode(mode)` maps GTFS-style mode names onto the vehicle glyphs.
The Sample plugin's demo route runs through the planner.
The planner produces plain activity steps; an interactive activity
(launcher bundle) adds its destination, commands, and activation to the
`NexusActivity` the step returns.

**Ongoing work.** An activity ends when its owner disconnects. A plugin that
keeps guiding after its surface or skill call ends, because the wearer asked
it to, holds its one session foreground service with
`holdNexusOngoingWork(additionalTypes)` (SDK 0.22.0), which also starts the service so it
outlives the hub's binding, and calls `releaseNexusOngoingWork()` the moment
the process ends. This is a narrow exception to dormancy for a process the
wearer explicitly started and follows as an activity; never hold it to poll or
wait. Transit's journey guidance is the precedent.

#### Interactive activities (launcher bundle)

When both hubs negotiated the launcher bundle, `supportsLauncherInteractive()`
is true and an activity becomes an entry in the wearer's activity collection:
something to consult and control, not only to watch. Without it the SDK drops
every field below before sending and the activity is an ordinary one, so the
same code runs against old hubs.

- `destination = NexusPageDestination(pageId, params)` — one of your own pages
  (`<yourPluginId>:<suffix>`; another namespace is `INVALID_PAYLOAD`), with params
  of at most 2,048 bytes. A tap on the card opens it directly inside the session,
  with those params on `NexusPageRequest.params`: it is a page request, never an
  `onNexusOpen`, and nothing underneath changes. You need the pages metadata.
- `activation` — `DESTINATION` opens the destination on the first tap;
  `COMMANDS` focuses your commands on the first tap and never runs one then.
  Left null: the destination when there is one, else the commands when there
  are any, else the hub's own summary of your activity. An explicit mode without
  its destination or a command is refused by the model.
- `defaultActionId` — the command focused first; the first action by default.
- `persistence = ONGOING` — only while the user turned on your own "Show in
  activities" setting. When your plugin disconnects, Nexus keeps the entry as an
  inert offline snapshot ("last known HH:MM") with its destination still
  openable: you hold no service for it, and it revives with your next
  `startActivity` from a new registration. Turning the setting off means
  `endActivity()`. See plugins/AGENTS.md for the background rules.
- `NexusActivityAction.typed` — `MediaPrevious`, `MediaPlay`, `MediaPause` or
  `MediaNext`, and nothing else on an activity. A typed action of a family the
  user approved for your plugin runs on one tap (see "Actions" under Pages).
- Using any of these fields also requires every action id to be a page-style id
  (printable ASCII without spaces, at most 128) and ids to be unique.

The user can also disable your activity on the phone (the hub then ends it and
refuses your starts with an `ACTIVITY_DISABLED` error on `/error`) or pin it. In focus the wearer steps
along your commands and a hub `Open app` slot that opens your full app through
the labelled launch step; that slot is the hub's, not one of your three.

A command arrives on `onNexusActivityCommand(command)` with `actionId`, a fresh
`invocationId`, the revision `seq` the wearer saw, and the `typed` you declared
on that action. Return the outcome: `done`, `rejected` or `stale`, with an
optional message of at most 64 characters. Return null when you cannot tell yet;
the glasses then show the command as unconfirmed, and you may still report
within eight seconds with `sendActivityCommandResult(command, result)`, once.
Never invent `done` for something you did not check, never retry a command, and
treat a revision you no longer show as `stale`. The default implementation calls
`onNexusActivityAction(id)` and returns null, so an unchanged plugin keeps
working and never reports an invented success.

```kotlin
class PlayerPluginService : NexusPluginService() {
    private fun playerActivity(state: PlayerState) = NexusActivity(
        glyph = "music",
        primary = state.title.take(12),
        secondary = state.artist?.take(28),
        actions = listOf(
            NexusActivityAction("prev", "previous", "Previous", NexusActionType.MediaPrevious),
            // The desired state for the revision shown, never a toggle.
            if (state.playing) {
                NexusActivityAction("pause", "pause", "Pause", NexusActionType.MediaPause)
            } else {
                NexusActivityAction("play", "play", "Play", NexusActionType.MediaPlay)
            },
            NexusActivityAction("next", "next", "Next", NexusActionType.MediaNext),
        ),
        destination = NexusPageDestination("player:now"),
        activation = NexusActivityActivation.COMMANDS,
        defaultActionId = if (state.playing) "pause" else "play",
        persistence = NexusActivityPersistence.ONGOING,
    )

    override fun onNexusActivityCommand(command: NexusActivityCommand): NexusActivityCommandResult? {
        val player = currentSession() ?: return NexusActivityCommandResult.rejected("No player")
        when (command.typed) {
            // A desired state that already holds was decided on an older revision.
            NexusActionType.MediaPlay -> if (player.playing) return NexusActivityCommandResult.stale() else player.play()
            NexusActionType.MediaPause -> if (!player.playing) return NexusActivityCommandResult.stale() else player.pause()
            NexusActionType.MediaNext -> player.next()
            NexusActionType.MediaPrevious -> player.previous()
            else -> return NexusActivityCommandResult.rejected()
        }
        return NexusActivityCommandResult.done()
    }
    // ...
}
```

A navigation plugin can draw its route with the activity:
`publishActivityGeometry(revision, geometry)` sends a `RouteGeometry`
(`com.anezium.rokidbus.shared`; at most 24 paths, 1,024 points, 4 labels, 4
stops, 16 KiB, coordinates 0..1,000 in a north-up frame spanning `spanMeters`)
and `publishActivityHeading(deg, accuracyDeg)` the compass heading. If your
`street` or `major` paths come from a map source that must be credited, set
`attribution` (1 to 40 printable characters, for OpenStreetMap
`"© OpenStreetMap"`); the glasses draw it in the corner of every map. Gate
provider streets on `supportsRenderedMaps`, which guarantees both hubs preserve
and render that credit; unsupported pairs retain route-only geometry. Latest wins;
neither is ever queued. Geometry needs the SPP data plane and returns
`CAPABILITY_NOT_AVAILABLE` over the control link alone, so your text guidance
must stand without it. The hub keeps at most five headings a second and treats
one older than three seconds as absent. Clip your paths with Liang-Barsky to a
window covering the rotated viewport, never by clamping vertices, and mark a
stale or missing fix in `position.quality` rather than leaving an old position
looking live.

`supportsRenderedMaps` requires the separate `RENDERED_MAP` v1 bit through both
hubs, the launcher bundle, the `surfaces` grant and live SPP. It is false with
either older hub, even if `supportsLauncherInteractive()` is true.
After session loss/replacement it stays false until the current peer announces
support again; retained launcher support does not carry this negotiation over.
`renderedMapViews: Set<NexusRenderedMapView>` contains only `FOLLOW` and/or
`OVERVIEW` under unexpired hub feedback, and is empty otherwise. The SDK keeps
at most ambient Follow plus one selected page view. `onRenderedMapsChanged()`
(`onNexusRenderedMapsChanged()` on the service) reports feedback changes; expiry
is checked on read. Re-read immediately before each request and schedule one
cancellation deadline from `renderedMapLeaseRemainingMs` (null without demand)
so pending work ends when the earliest slot expires, even between journey ticks.
This timer belongs only to an existing journey and grants no new lifetime. Page visibility or
successful geometry publication alone never authorize view-driven networking.
Use `renderedMapFrames: Set<NexusRenderedMap>` for network authorization. Each
frame has `view`, the acknowledged `revision`, and `pageId` (null for activity
geometry). Keep a bounded owner-local correlation from your published revisions
to their footprints and request only the acknowledged footprints, rechecking at
the request boundary. Unknown revisions authorize nothing. Moving the current
tracker while an older Follow frame is acknowledged does not authorize its new
tiles, even when publishing the new geometry is throttled or refused. Feedback
contains no caller-provided tile keys or another owner's authority. Renewals
should reconcile demand and expiry without publishing another page revision.
`clearRenderedMapDemand()` discards this plugin's feedback when replacing its
journey. The SDK also clears applicable slots on registration reset, SPP loss,
page hide/close and activity end/close. No receive prefix or new grant is needed.
Plugins cannot send `/map/rendered` or claim their own visibility. See
`BUSSPEC.md` "Rendered map feedback v1" for the bounded hub-only authority.

### Notice bands

Notices reuse the existing `surfaces` grant and API version 3, and live on
`NexusPluginClient` for the same reason pins do: a plugin can wake, say one
thing, and go dormant again without ever opening a surface.

**A notice is a message or a question, not a menu.** It arrives, says its piece,
and leaves on its own deadline. With no explicit `ttlMs`, Nexus computes
`2000 ms + 45 ms` per normalized text character and clamps that to 4–45
seconds; an explicit value is clamped to 2–45 seconds. The absolute lifetime is
90 seconds. Anything the wearer follows over minutes is an activity; anything
they browse or drive is a surface.

A body may contain 1024 characters. When one event has distinct parts, use
`lines` instead: at most 16 strings sharing the same 1024-character budget,
including one separator per line. Body and lines are mutually exclusive.
Each line is trimmed, embedded newlines collapse to spaces, and empty lines are
dropped. Nexus owns the break between entries, then wraps any entry that is too
wide with no marker or continuation indent.

Nexus measures either representation on the glasses and replaces one
eight-line page with the next; your plugin never calculates page breaks.
Forward and backward change pages, the footer gains a platform `2/4` indicator,
and nothing scrolls. The first page turn replaces both countdowns with a
30-second inactivity timeout restarted on every page gesture.

[notice-band-states.html](notice-band-states.html) shows the band's six states
as the wearer sees them — plain, interactive, with actions, answered, paged,
and with an image — with an interactive demo of the one-answer rule. Open it in
a browser.

```kotlin
data class NexusNoticeAction(
    val id: String,
    val glyph: String,
    val label: String,
)

data class NexusNoticeImage(
    val contentKey: String,
    val mimeType: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
)

data class NexusNotice(
    val title: String? = null,
    val body: String? = null,
    val footer: String? = null,
    val interactive: Boolean = false,
    val actions: List<NexusNoticeAction> = emptyList(),
    val ttlMs: Long? = null,
    val image: NexusNoticeImage? = null,
    val wakeDisplay: Boolean = false,
    val lines: List<String> = emptyList(),
    val backdrop: Boolean = false,
)

data class NexusNoticeUpdate(
    val title: String? = null,
    val body: String? = null,
    val footer: String? = null,
    val interactive: Boolean? = null,
    val actions: List<NexusNoticeAction> = emptyList(),
    val ttlMs: Long? = null,
    val lines: List<String> = emptyList(),
    val rearm: Boolean? = null,
)

val supportsNoticeSurface: Boolean
fun showNotice(notice: NexusNotice): NexusSdkResult
fun showNotice(notice: NexusNotice, imageBytes: ByteArray): NexusSdkResult
fun updateNotice(update: NexusNoticeUpdate): NexusSdkResult
fun hideNotice(): NexusSdkResult

interface NexusPluginCallbacks {
    fun onNoticeInput(event: NexusInputEvent) = Unit
    fun onNoticeAction(id: String) = Unit
    fun onNoticeClosed(reason: NexusNoticeCloseReason) = Unit
}
```

`NexusPluginService` forwards those to the overridable `onNexusNoticeInput`,
`onNexusNoticeAction(id)`, and `onNexusNoticeClosed(reason)` hooks.

Give a band up to three actions and the platform draws a row of glyph chips
under the footer: forward and backward step along it, confirm fires the selected
one, and you hear the id through `onNexusNoticeAction`. A fourth action is
refused, not dropped.

**A band pages unless its row needs the directions.** With two or more actions
the directions belong to the row, and such a band draws a single page. With one
action or none there is nothing to step along, so they turn pages while the tap
still answers — which is what lets a long message worth exactly one reply be
both readable and answerable. No gesture ever carries two meanings, which is
the rule this serves. So if you are sending a conversation *and* a way to
answer it, send **one** action: Back already dismisses from anywhere, and a
second chip usually buys nothing while costing the wearer everything past line
eight.

With no actions, `interactive = true` claims one confirming gesture and calls
`onNexusNoticeInput`; the two callbacks never both fire. Setting `interactive`
alongside actions is redundant: offering answers is already asking for one.

**Both callbacks fire at most once per question.** A notice takes exactly one
answer: the first confirm fires, the row leaves the band, and after that the
band claims nothing and sends nothing — no second action, no second input, and
no falling back from one to the other. Write both handlers as if they run once,
because they do. Two fast temple taps used to reach a plugin as two calls, which
for a messaging plugin meant two messages sent.

> **Behaviour change from 1.0.46.** A notice with `interactive = true` used to
> call `onNexusNoticeInput` on every confirm for as long as the band was up. It
> now calls it on the first confirm only. If your plugin relied on repeated
> taps, ask again explicitly with an `updateNotice` that carries `interactive`
> or a new row.

That is also why the SDK gives you no way to clear the row: answering removes it
for you. To ask again, send a new question — an `updateNotice` carrying
`actions` or `interactive`, or a fresh `showNotice`.

For a cosmetic action-label update, such as a countdown, use
`NexusNoticeUpdate(actions = labels, rearm = false)`. The ordered action ids
and the interactive flag must stay the same. This preserves both the question
and its answered state, so a tap in flight remains valid across a countdown
tick. Other updates retain their existing rearm behavior.
This option is available in SDK 0.19.0 with both Nexus hubs 1.4.11 or newer.
It requires `noticeInteractionVersion: 1`; an older phone hub
returns `CAPABILITY_NOT_AVAILABLE` from the typed SDK call instead of silently
rearming the question.

Notice protocol v5 binds each event to its notice instance and question.
Phone and glasses hubs must speak the same notice version; mixed versions
report `CAPABILITY_NOT_AVAILABLE`. The SDK tracks an internal question token
and drops callbacks queued for an earlier question before calling your hooks.
No callback signature changes are required. Rebuild plugins with this SDK to
gain the plugin-side protection; an older bundled SDK cannot filter callbacks
already queued in its process. With a phone hub that does not advertise
`noticeInteractionVersion: 1` at registration, legacy callback behavior remains.

**`onNoticeClosed` is scoped to the current local question, not guaranteed once
for every show.** Calling `hideNotice()` and then `showNotice()` before the old
close is delivered suppresses that old `onNoticeClosed(OWNER)`. A new show,
rearming update, or fresh registration retires the previous callback context;
this prevents an old close from shutting down a replacement. Release local
resources belonging to the superseded context when replacing it, rather than
waiting for its close callback. A hide with no intervening replacement still
accepts its matching owner-close callback. A same-owner re-show does not emit a
separate replacement close. These filtering rules apply when the hub advertises
interaction support; older hubs retain the legacy behavior described above.
An SPP link-state change by itself does not retire the question or block its
callbacks, because the notice may still be live over CXR.

If a glasses-side send fails, the band shows *Delivery not confirmed* and
does not offer a second answer automatically: a partial write may already have
delivered the first. This is transport failure feedback, not a delivery receipt.

```kotlin
nexusClient?.showNotice(
    NexusNotice(
        title = "Marie",                       // optional, max 32 trimmed chars
        body = "On my way, ten minutes out.",  // optional, max 1024
        footer = "scroll to choose",           // optional, max 40
        actions = listOf(
            NexusNoticeAction(id = "reply", glyph = "phone", label = "Reply"),
            NexusNoticeAction(id = "later", glyph = "timer", label = "Later"),
        ),
    ),
)

override fun onNexusNoticeAction(id: String) {
    when (id) {
        "reply" -> openReply()
        "later" -> snooze()
    }
}
```

One relayed conversation remains one notice while preserving its message
boundaries:

```kotlin
nexusClient?.showNotice(
    NexusNotice(
        title = "Mika",
        lines = listOf(
            "Can you check the build when you have a minute?",
            "I added a second message to exercise thread extraction.",
            "Reply from the glasses when you are ready.",
        ),
    ),
)
```

To attach a JPEG or PNG, pass its declared metadata in the notice and its
encoded bytes to the binary overload. The frame is limited to 64 KiB, each edge
to 512 px, and total decoded area to 512 x 512; aim near 480 x 160. Nexus checks
the signature, dimensions, and SHA-256 before forwarding it. The image is full
band width under the title, capped at 150 physical pixels, and appears on page
one only. Its first body window is three lines; later pages use eight.

```kotlin
val photo = notificationPictureBytes()
nexusClient?.showNotice(
    NexusNotice(
        title = "Marie",
        body = notificationBody,
        image = NexusNoticeImage(
            contentKey = "message-${notificationId}",
            mimeType = "image/jpeg",
            pixelWidth = 480,
            pixelHeight = 160,
        ),
    ),
    photo,
)
```

Text and image appear in the same frame after background decode; there is no
text-only waiting state. `showNotice(notice)` refuses a notice whose `image` is
set, and the binary overload refuses one without image metadata. Updates remain
text-only and preserve the current image. Use a fresh `showNotice` to replace
or remove it. The binary overload also requires the live
`supportsImageSurface` capability; keep the text-only form as the fallback.

Action glyphs are strings for the same reason activity glyphs are: the
vocabulary is additive, the wire validates name shape rather than membership,
and an unknown well-formed name renders as `dot` on an older hub. Each action's
`id`, `glyph`, and `label` must be nonblank; there is no numeric cap on an id or
label beyond that and the three-action limit.

Answering turns the band into a display you still own, which is the shape most
of these want. A voice-reply plugin asks, hears the pick once, and then narrates
what it is doing on the same band:

```kotlin
override fun onNexusNoticeAction(id: String) {
    if (id != "reply") return
    // The row is already gone from the band; from here it is a display.
    // Empty string clears the footer — the "scroll to choose" hint is spent.
    nexusClient?.updateNotice(NexusNoticeUpdate(body = "Listening…", footer = ""))
    startDictation(
        onPartial = { text -> nexusClient?.updateNotice(NexusNoticeUpdate(body = text)) },
        onSent = {
            nexusClient?.updateNotice(NexusNoticeUpdate(body = "Reply sent"))
            nexusClient?.hideNotice()
        },
    )
}
```

`updateNotice` has patch semantics, and they hold all the way to the glasses:
**null keeps a field, an empty string clears it**, and a field you leave out is
one the wearer keeps seeing. The hub relays your patch rather than its own copy
of the band, so `footer = ""` really does take the footer off the band and
`interactive = false` really does stop it asking. Actions are the exception:
passing a non-empty list replaces the whole row, while an empty list leaves the
current row alone rather than clearing it. The wearer's selection follows its
action id across a replacement, so reordering your answers does not move their
finger onto a different one. Lines follow the same empty-list SDK convention:
a non-empty list replaces a body or the current lines, while an empty list is
absent from the wire and leaves the text alone. Sending `body` instead switches
a lines notice back to the body representation; setting both in one update is
rejected.

Two of these fields also *reopen* an answered band: `actions` and `interactive`.
Setting either is how you ask again — a new row, or `interactive = true` on a
band that has no row. An update that carries neither, like every call in the
example above, drives an answered band as a display without reopening it, which
is almost always what you want after the wearer has replied.

**BACK always dismisses the band**, platform-side, and you never hear about it.
That is deliberate and it does not change when a notice carries actions: a
plugin must not be able to hold the wearer inside a banner. Ring scroll and
every other key keep reaching whatever is underneath, except forward and
backward while an unanswered row or multiple text pages are actually up. A
plain one-page notice claims no direction at all.

Check the live `supportsNoticeSurface` value immediately before use. Unlike
pins it accounts for the link: a notice is a moment, so the hub never holds one
for glasses it cannot reach and tells you instead.

Set `wakeDisplay = true` on `NexusNotice` only for a new event the wearer must
not miss. It is serialized only when true and is honored only by `showNotice`;
`updateNotice` has no wake field, and a raw `/notice/update` that supplies one
is rejected as `INVALID_NOTICE`. A dark display may be woken for at most three
seconds, never held on. The same global five-second budget is shared with every
plugin, notice, activity, and surface. If the display is already interactive,
no lock is needed and the budget remains available for the next dark-screen
event.

Set `backdrop = true` only when the notice should hide everything else on the
glasses display behind an opaque black scrim. It defaults to false, is
serialized only when true, and is show-only; updates preserve the value chosen
by the original show, while a replacement notice chooses its own value.

#### Destinations and withdrawal (launcher bundle)

With `supportsLauncherInteractive()`, a notice can lead somewhere and be taken
back. All three fields are show-only and are dropped before sending without the
bundle:

- `destination = NexusPageDestination(pageId, params)` — one of your own pages;
  the glasses' notification history opens it directly.
- `eventKey` — your own opaque id for the event (printable ASCII without spaces,
  at most 128 characters).
- `conversation = true` — the event is a message in the thread at
  `destination`; it needs both fields, and that thread's `conversation` page uses
  the same value as its `threadKey`.

When the event leaves the phone (its notification was removed), call
`withdrawNotice(eventKey)`: the glasses mark it removed and stop offering a
reply for that thread. It is not a read or delivery receipt, and it is refused
with `CAPABILITY_NOT_AVAILABLE` without the bundle.

When the user tells your plugin to stop keeping what it showed (they turned a
source off, switched the plugin off, or revoked an access it depends on), call
`forgetNotices(onAccepted)`: the glasses forget every notification your plugin
showed, history entries and every copy of their text. Decide it from the user's
setting change, not from what your plugin still remembers having shown. `SENT`
only means the request left your process: it may sit in the client's reconnect
queue, which dies with the client. `onAccepted` runs once the phone hub holds the
request on disk; from there it reaches the glasses across a link loss or a
restart of either hub. Keep the request owed on your side until then and send it
again on your next registration; repeating it is harmless. Hubs before Nexus
1.9.0 keep no notification history, refuse the path and never accept it.

```kotlin
nexusClient?.showNotice(
    NexusNotice(
        title = "Mika",
        body = "On se voit à 8h ?",
        destination = NexusPageDestination("relay:thread:9f2c"),
        eventKey = "9f2c",
        conversation = true,
    ),
)
// Later, when Android removes the notification:
nexusClient?.withdrawNotice("9f2c")
```

### Real image surfaces

Image surfaces use the existing `surfaces` grant; do not add a descriptor
capability and do not change API version 3. They are available only while the
glasses renderer has announced image v1 and the SPP binary link is live. Check
`nexusClient?.supportsImageSurface` immediately before sending and keep a card
fallback: it is a live value and can become false when SPP drops. `showImage`
and `updateImage` return `CAPABILITY_NOT_AVAILABLE` without sending when either
condition is absent.

Preprocess on the phone. Correct orientation, downscale so both decoded edges
are at most 512 px (and total pixels at most `512 * 512`), then encode as JPEG or
PNG. For photographs, start around JPEG quality 70--80 and adjust to a 20--40 KiB
target. The hard compressed cap is 65,536 bytes. PNG is most useful for simple
graphics; neither format may exceed the decoded bounds. The SDK verifies the
format signature, actual encoded dimensions, SHA-256, metadata, and size before
calling the binary transport. Do not base64 the image.

```kotlin
val bytes = resources.openRawResource(R.raw.image_surface_sample).use { it.readBytes() }
val image = NexusImage(
    contentKey = "tweet-123-photo-1", // stable identity, max 128 chars
    mimeType = ImageSurfaceContract.MIME_JPEG,
    pixelWidth = 480,
    pixelHeight = 480,
    title = "Photo",
    caption = "Optional caption",
    footer = "back",
    handlesBack = true,
)

val result = if (nexusClient?.supportsImageSurface == true) {
    surface?.showImage(image, bytes)
} else {
    surface?.showCard(NexusCard("Photo", listOf("Image preview unavailable")))
}
```

Use `updateImage(image, bytes)` to replace the current image. Every image update
is a complete binary frame and the phone hub enforces 150 ms between image
frames for the same surface. A faster frame is rejected with `/error` code
`IMAGE_RATE_LIMITED`; the SDK preflight returns
`NexusSdkResult.IMAGE_RATE_LIMITED` immediately. Plugins should not build
animation loops around v1.

### Persistent pins

Pins reuse the existing `surfaces` grant and API version 3. They occupy one
global last-writer-wins slot, are independent from `NexusSurfaceSession`, and
remain visible across normal surface and launcher changes until hidden,
replaced, expired, or your plugin's grant goes away. For that reason
`showPin`/`hidePin` live on `NexusPluginClient`, not a surface session.

**A pin does not need a surface, and does not need you to stay connected.**
This is the shape it was built for: a ride-hailing plugin spots the "driver
arriving" notification on the phone, wakes, connects, sends `showPin`, and goes
dormant again. The pin stays on the glasses. On every update it wakes and sends
`showPin` again — there is no `/pin/update`, a `show` always carries the full
state and replaces the previous one. When the ride ends it wakes once more and
sends `hidePin`. Do not hold the bus connection open for the life of a pin;
that violates the background policy in [PLUGINS.md](PLUGINS.md) and burns a
process on three lines of text.

Because of that, every pin has a deadline. Send no `ttlMs` and the hub gives
you **30 minutes** — pins are for facts worth a corner for the length of an
errand, and an unbounded default would strand one on the glasses whenever a
plugin is killed before it can `hidePin`. Set `ttlMs` explicitly when you know
your own horizon (a countdown, an ETA, a shift), anywhere from one second to
24 hours. Sending a fresh `showPin` restarts the clock, so a plugin that keeps
updating never hits its deadline.

Check the live `supportsPinSurface` value immediately before use. Both methods
return `CAPABILITY_NOT_AVAILABLE` without sending unless the glasses announced
pin v1. Old glasses therefore continue to work without a plugin API bump.

**You do not need the glasses to be awake.** `supportsPinSurface` says these
glasses can show a pin, not that one would appear this second. Push yours when
your event happens; if the glasses are off or out of range the hub holds it and
delivers it when they come back. So `CAPABILITY_NOT_AVAILABLE` means one thing —
this pair cannot show pins at all — and retrying will not change it.

```kotlin
val result = nexusClient?.showPin(
    NexusPin(
        title = "AB-123-CD",                 // optional, max 24 trimmed chars
        lines = listOf("Grey Toyota Prius"), // 0..2, max 28 trimmed chars each
        position = NexusPinPosition.TOP_RIGHT,
        ttlMs = 30 * 60 * 1000L,             // optional; clamped to 1 s..24 h
    ),
)

// Later; only the current owner can clear the slot.
nexusClient?.hidePin()
```

A pin has two size tiers. `NexusPinSize.SMALL` is the default and keeps the caps
above; `NexusPinSize.MEDIUM` allows a 28-character title and three lines of 32
characters, and renders slightly larger and up to 60% of the screen width
instead of 45%. Pick the smallest tier that fits: a pin competes with whatever
the wearer is actually looking at.

Lines can also carry emphasis. Pass `richLines` instead of `lines` (the two are
mutually exclusive, exactly like `NexusCard.richLines`): `NexusPinEmphasis.BRIGHT`
promotes a line to the phosphor title tone and `DIM` states the muted body tone
explicitly. The title is always bright.

```kotlin
nexusClient?.showPin(
    NexusPin(
        title = "Bus 42 · Central",
        size = NexusPinSize.MEDIUM,
        richLines = listOf(
            NexusPinLine("arrives in 4 min", NexusPinEmphasis.BRIGHT),
            NexusPinLine("then 11 min · 26 min"),
            NexusPinLine("platform 2", NexusPinEmphasis.DIM),
        ),
    ),
)
```

At least one title or line must be non-empty after trimming. Typed-model cap
violations throw `IllegalArgumentException`, and the caps checked are the ones
for the tier you passed. The hub rejects malformed raw traffic with
`INVALID_PIN` and accepts at most one `/pin/show` per plugin every 500 ms
(`PIN_RATE_LIMITED`). The glasses overlay is text-only and has no input. The
sample plugin cycles small pin, medium pin, hidden from its existing tap action
for on-device validation.

### Ink surfaces

Ink is the authored-layout sibling of a card. The plugin submits a small `.ink`
single-file component; the phone compiles it on a dedicated thread into a
bounded, revisioned document, and the glasses render that document with native
Views. A later `update` carries only set-data-style changes and becomes a
revision-checked render patch. Ink shares the one foreground surface slot with
ordinary `/surface/*` content, so `SURFACE_BUSY`, replacement, BACK, link loss,
and self-close follow the same ownership rules.

Request the separate `ink_surface` capability. Do not add `/ink/event` to
`RECEIVE_PREFIXES`: it is an owner-scoped direct callback path delivered to the
registered binder. Check `supportsInkSurface` immediately before `show` or
`update`; it reports Ink v1 from the glasses plus the live SPP data link. The
session separately checks that this plugin is approved for `ink_surface`.
`hide` remains available during teardown even after the renderer bit disappears.

```kotlin
class StatusPluginService : NexusPluginService() {
    private var ink: NexusInkSurfaceSession? = null
    private var card: NexusSurfaceSession? = null
    private var usingInk = false

    override fun onNexusOpen() {
        ink = nexusInkSurfaceSession("status")
        card = nexusSurfaceSession("status")
        usingInk = nexusClient?.supportsInkSurface == true &&
            ink?.show(
                page = STATUS_PAGE,
                data = JSONObject()
                    .put("title", "Link")
                    .put("value", "72"),
                handlesBack = false,
            ) == NexusSdkResult.SENT
        if (!usingInk) showCardFallback()
    }

    override fun onNexusInkReady(surfaceId: String) = Unit

    override fun onNexusInkAction(
        surfaceId: String,
        actionId: String,
        dataset: JSONObject,
    ) {
        if (surfaceId == "status" && actionId == "refresh") {
            ink?.update(JSONObject().put("value", "73"))
        }
    }

    override fun onNexusInkClosed(surfaceId: String, reason: NexusInkCloseReason) {
        if (surfaceId == "status") usingInk = false
    }

    override fun onNexusInkError(surfaceId: String, problems: List<NexusInkProblem>) {
        if (surfaceId == "status") {
            usingInk = false
            showCardFallback()
        }
    }

    override fun onNexusClose() {
        if (usingInk) ink?.hide() else card?.hide()
        ink = null
        card = null
        usingInk = false
    }

    override fun onNexusInput(event: NexusInputEvent) = Unit

    private fun showCardFallback() {
        card?.showCard(NexusCard("Link", listOf("72")))
    }

    private companion object {
        val STATUS_PAGE = """
            <script type="application/json" def>{"data":{}}</script>
            <page>
              <view class="page">
                <text class="title">{{ title }}</text>
                <text>{{ value }}</text>
                <view class="action" bindtap="refresh" data-source="status">
                  <text>Refresh</text>
                </view>
              </view>
            </page>
            <style>
              .page { display: flex; flex-direction: column; gap: 16rpx; padding: 24rpx; }
              .title { font-size: 40rpx; font-weight: 700; }
              .action { border-width: 1rpx; padding: 12rpx; }
            </style>
        """.trimIndent()
    }
}
```

The accepted single-file blocks are one JSON `<script def>` with an optional
`data` object, one `<page>`, and an optional `<style>`. `<script setup>` and all
other executable scripts are rejected. The v1 template subset includes
interpolation, bounded expressions, `wx:if`/`wx:elif`/`wx:else`, `wx:for` and
its item/index/key attributes, plus `bindtap`/`catchtap`. The tap handler name is
the `actionId`; `data-*` attributes become the callback dataset. Update keys use
set-data paths such as `metrics[0].value` or `rows[2].label`.

Supported components are `view`, `text`, `scroll-view`, `chart` (line, area,
pie, radar, and the sample-derived bar extension), `progress`, inline-JSON
`lottie-view`, and declarative `nx-canvas`. `image` is accepted but currently
renders a placeholder reference: the public session has no asset-transfer
argument, so use `NexusSurfaceSession.showImage` for real pixels. Styles are a
strict class-selector/WXSS subset with flexbox, box and text properties,
opacity, transforms, transitions, `rpx`, percentages, and design-token custom
properties. Unsupported tags, attributes, selectors, styles, scripts, sources,
or expressions produce typed `NexusInkProblem` callbacks; they are never
silently interpreted as arbitrary HTML/CSS.

Ink v1 is inert by construction: no JavaScript, WebView, URL loading, page-side
network, or arbitrary code execution. Lottie content must be inline JSON. The
hard public budgets are:

| Area | Limit |
|---|---|
| Authored page | 32 KiB UTF-8 |
| Initial merged data / update patch | 16 KiB UTF-8 |
| Compiled document / render patch | 64 KiB each |
| Render tree | 256 nodes, depth 32 |
| Patch | 1,024 changes |
| Chart | 4 series, 256 points per series |
| Canvas | 512 commands, 30 fps maximum |
| Inline Lottie JSON | 32 KiB |

`show`, `update`, and `hide` return the normal `NexusSdkResult` values. Invalid
local size input returns `INVALID_PAYLOAD` and immediately calls
`onNexusInkError`; compiler/renderer problems arrive the same way. Close reasons
are `USER`, `PLUGIN`, `REPLACED`, `LINK_LOST`, and `RENDERER_ERROR`. The canonical
working page and data-patch flow are in
[`plugins/sample`](../plugins/sample/src/main/java/com/anezium/rokidbus/plugin/sample/HelloPluginService.kt).

### 3.1 Microphone (audio lease)

Request the `microphone` capability and add `/audio` to the plugin's receive
prefixes:

```xml
<meta-data android:name="com.anezium.rokidbus.plugin.CAPABILITIES"
    android:value="surfaces,microphone" />
<meta-data android:name="com.anezium.rokidbus.plugin.RECEIVE_PREFIXES"
    android:value="/plugin/yourid,/system/plugin,/audio" />
```

Once the owner grants `microphone`, acquire a lease through
`nexusAudioSession(callbacks)` and drive it with `start()` / `stop()`. The hub
holds a single glasses-microphone lease at a time and streams the raw PCM to the
current holder; the SDK routes the reply, frames, and revocation to your
callbacks — you never handle the raw `/audio/*` envelopes yourself.

```kotlin
class DictationService : NexusPluginService() {
    private var audio: NexusAudioSession? = null

    fun beginListening() {
        val session = nexusAudioSession(object : NexusAudioCallbacks {
            override fun onAudioStarted(format: NexusAudioFormat) {
                // format is 16000 Hz, 1 channel, "pcm16le"
            }

            override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) {
                // Variable buffers, typically ~10 frames/s at ~3.2 KiB each.
                // Feed your STT, recorder, VAD, etc. `pcm` is owned by the caller;
                // copy it if you keep it past this call.
            }

            override fun onAudioStopped(reason: NexusAudioStopReason) {
                // RELEASED, REVOKED (link lost), or a DENIED_* / ERROR terminal.
                audio = null
            }
        }) ?: return
        audio = session
        when (session.start()) {
            NexusSdkResult.SENT -> Unit                       // lease requested
            NexusSdkResult.CAPABILITY_NOT_GRANTED -> Unit     // owner hasn't granted mic
            NexusSdkResult.NOT_REGISTERED -> Unit             // hub not connected yet
            else -> Unit
        }
    }

    fun stopListening() {
        audio?.stop()   // fires onAudioStopped(RELEASED); safe if already stopped
    }
}
```

Format is fixed at **16 kHz, mono, signed 16-bit little-endian PCM**
(`NexusAudioFormat`). `onAudioStopped` fires exactly once per active session —
on your own `stop()`, on a hub revoke (e.g. the glasses link drops), or on a
denied acquire (`DENIED_BUSY` when another plugin holds the lease,
`DENIED_NO_LINK`, `DENIED_START_FAILED`). The session also tears down (with
`onAudioStopped`) if the plugin loses approval or the service is destroyed, so
you do not need to release on `onNexusClose` yourself.

#### Let the display sleep while listening

Requires SDK **0.18.0** and Nexus hubs **1.4.10** or newer. Older hubs treat
`detach` as an ordinary hide and may close the plugin; update both hubs before
using this lifecycle. The plugin API version remains **3**.

After `onAudioStarted`, a plugin that owns the active microphone lease may hide
its last ordinary surface with `surface.detach()` (equivalent to
`surface.hide(detach = true)`). This opt-in is per hide. The hub ignores it
unless that exact plugin currently owns the ACTIVE lease; a pending acquire, a
missing lease, or ordinary `hide()` keeps the existing full-close behavior.

```kotlin
private var lastMicPinAtMs = 0L

private fun refreshMicPin() {
    lastMicPinAtMs = android.os.SystemClock.elapsedRealtime()
    nexusClient?.showPin(
        NexusPin(title = "MIC ON", lines = listOf("Stop on phone"), ttlMs = 5_000L),
    )
}

override fun onAudioStarted(format: NexusAudioFormat) {
    refreshMicPin()
    surface?.detach()
}

override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) {
    if (android.os.SystemClock.elapsedRealtime() - lastMicPinAtMs >= 2_000L) {
        refreshMicPin()
    }
    // Process the PCM frame here.
}

override fun onNexusBackground() {
    surface = null
    // Keep the NexusAudioSession: the SDK deliberately retains only audio here.
}

override fun onNexusOpen(openType: String) {
    if (openType == PluginOpenTypes.RESUME) showListeningControls() else showHome()
}

override fun onAudioStopped(reason: NexusAudioStopReason) {
    audio = null
    nexusClient?.hidePin()
}
```

The background transition sets the client to closed-for-input, releases speech,
TTS, and snapshot sessions, calls `onNexusBackground()`, and retains only the
audio session. `NexusPluginService` keeps its foreground service while that
audio session is active. Reopening delivers `onNexusOpen("resume")`; ending the
lease while backgrounded causes a final `onNexusClose()` and service unbind.
The phone hub also displays **Listening in the background** with a **Stop**
action that revokes the lease.

Use a pin as the glasses-side listening indicator because it survives the
surface transition, but remember its display semantics: a pin **never wakes or
keeps the display on** and has a TTL (30 minutes when omitted). The phone row is
the persistent revocation control. For a live microphone indicator, use a short
TTL and renew it only while audio arrives, as above. Pins otherwise survive a
plugin disconnect, and a crashed process cannot execute its cleanup callbacks.
Always hide the pin when audio stops normally. The sample plugin demonstrates the complete acquire → pin → detach →
stream → resume/stop flow.

Two hardware facts to design around:

- **The glasses must be worn.** The on-glasses microphone DSP beamforms toward
  the wearer's mouth and gates otherwise, so a lease acquired while the glasses
  sit unworn yields near-silence. Gate your UX on
  `LinkStateBits.GLASSES_WORN` from `onNexusLinkState` if silence would confuse
  the user.
- **The level is conservative.** Captured speech peaks well below full scale;
  if you play the audio back or show a meter, apply gain (roughly 5×) or
  normalize.

### 3.2 Speech to text

Request `stt` and receive `/stt`; do not request `microphone` unless the plugin
also needs raw PCM:

```xml
<meta-data android:name="com.anezium.rokidbus.plugin.CAPABILITIES"
    android:value="surfaces,stt" />
<meta-data android:name="com.anezium.rokidbus.plugin.RECEIVE_PREFIXES"
    android:value="/plugin/yourid,/system/plugin,/stt" />
```

After install, the user must grant **Speech to text** in **Rokid Nexus →
Settings → Plugin access**. Installation never grants it. Adding `stt` to an
already installed descriptor changes the requested capability set and returns
the plugin to Pending until the user re-approves it.

Create one typed session with `nexusSpeechSession(callbacks)`. This complete
minimal service starts a French utterance when opened:

```kotlin
class SpeechPluginService : NexusPluginService() {
    private var speech: NexusSpeechSession? = null

    override fun onNexusOpen() {
        val session = nexusSpeechSession(object : NexusSpeechCallbacks {
            override fun onSpeechStarted(realtime: Boolean) {
                // realtime=true means partial hypotheses may follow.
            }

            override fun onSpeechState(state: NexusSpeechState) {
                // LISTENING, RECOGNIZING, or PROCESSING
            }

            override fun onSpeechPartial(text: String) {
                // Update lightweight UI only. Never log transcript text.
            }

            override fun onSpeechFinal(text: String) {
                // Use the completed transcript. Never log transcript text.
            }

            override fun onSpeechStopped(
                reason: NexusSpeechStopReason,
                error: NexusSpeechError?,
            ) {
                speech = null
                // error has kind plus optional provider/detail; no transcript.
            }
        }) ?: return
        speech = session
        when (session.start(language = "fr")) {
            NexusSdkResult.SENT -> Unit
            NexusSdkResult.CAPABILITY_NOT_GRANTED -> speech = null
            NexusSdkResult.NOT_REGISTERED -> speech = null
            else -> speech = null
        }
    }

    override fun onNexusInput(event: NexusInputEvent) = Unit

    override fun onNexusClose() {
        speech?.stop()
        speech = null
    }
}
```

`language` is optional and accepts a hub `TranscriptionLanguage` ID such as
`auto`, `en`, `fr`, `de`, `es`, `it`, `pt`, `ja`, `ko`, `yue`, `zh-hant`, or
`zh-hans`. An absent or unknown ID uses the hub's configured language for that
session. Only utterance mode exists in v1.

All speech callbacks are serialized on the plugin application's main thread,
just like lifecycle and audio callbacks. Offload network calls, database work,
large parsing, or other heavy processing immediately. Transcript strings are
immutable, but retaining or persisting them is a plugin privacy decision; do
not put partials, finals, prompts, or user speech in logcat, analytics, crash
breadcrumbs, or bug reports.

The hub has one global speech session shared with its Speech settings dictation
test. It also consumes the same one-holder glasses audio lease used by
`NexusAudioSession`. A settings test, another STT plugin, or a raw microphone
lease can therefore produce `DENIED_BUSY`. The lease is handed back as soon as
the speaker stops rather than when the transcript lands, so the microphone frees
up during `PROCESSING` and a link drop while the result is in flight does not
lose it. Realtime engines set
`realtime=true` and may emit monotonic partials before one final. Buffered
engines set `realtime=false` and normally emit no partial callbacks.

Start denials map as follows:

| Hub reason | `NexusSpeechStopReason` |
|---|---|
| `BUSY` | `DENIED_BUSY` |
| `NO_LINK` | `DENIED_NO_LINK` |
| `NOT_READY` | `DENIED_NOT_READY` |
| `START_FAILED` | `DENIED_START_FAILED` |
| `INVALID_REQUEST` or unknown | `DENIED_INVALID` |

Session endings map as follows:

| Hub reason | `NexusSpeechStopReason` |
|---|---|
| `completed` | `COMPLETED` |
| `cancelled` | `CANCELLED` |
| `no_speech` | `NO_SPEECH` |
| `error` or unknown | `ERROR` |
| `link_lost` | `LINK_LOST` |
| `revoked` | `REVOKED` |

`NexusSpeechError` exposes the slice-1 `SttErrorKind` name and optional
provider/detail. `stop()` is idempotent: while active it sends one stop request
and immediately finishes locally with `CANCELLED`; late replies/events remain
consumed by the sticky typed route. Approval loss or direct client close
terminates with `ERROR`, while the service's normal close/destruction calls
stop first and terminates with `CANCELLED`.

### 3.3 Text to speech

Request `tts` and, if you use raw receive prefixes, name only the two plugin
events `/tts/started` and `/tts/done`:

```xml
<meta-data android:name="com.anezium.rokidbus.plugin.CAPABILITIES"
    android:value="surfaces,tts" />
<meta-data android:name="com.anezium.rokidbus.plugin.RECEIVE_PREFIXES"
    android:value="/plugin/yourid,/system/plugin,/tts/started,/tts/done" />
```

After install, the user must grant **Text to speech** in **Rokid Nexus →
Settings → Plugin access**. Adding `tts` to an existing descriptor changes the
requested capability set and returns the plugin to Pending until the user
re-approves it.

Create one typed session with `nexusTtsSession(callbacks)` and speak a line:

```kotlin
class TtsPluginService : NexusPluginService() {
    private var tts: NexusTtsSession? = null

    override fun onNexusOpen() {
        tts = nexusTtsSession(object : NexusTtsCallbacks {
            override fun onTtsStarted(utteranceId: String) {
                // The glasses began speaking this utterance.
            }

            override fun onTtsDone(
                utteranceId: String,
                reason: NexusTtsDoneReason,
            ) {
                // COMPLETED, STOPPED, PREEMPTED, CANCELLED, or UNAVAILABLE.
            }
        })

        when (tts?.speak("Hello from my Nexus plugin.")) {
            NexusSdkResult.SENT -> Unit
            NexusSdkResult.CAPABILITY_NOT_AVAILABLE -> Unit
            NexusSdkResult.INVALID_PAYLOAD -> Unit
            NexusSdkResult.TTS_RATE_LIMITED -> Unit
            else -> Unit
        }
    }

    override fun onNexusInput(event: NexusInputEvent) = Unit

    override fun onNexusClose() {
        tts?.close()
        tts = null
    }
}
```

Every callback carries the `utteranceId` of the speech it is about. Pass your
own to `speak(text, utteranceId)` — a message key, a row id, whatever you
already use — and answers arrive already matched to what asked for them;
omit it and the SDK invents one, which is enough when a plugin only ever says
one thing at a time. Text is normalized by replacing newline runs with spaces
and trimming, and must remain nonblank and no longer than 1024 characters
afterwards — the same budget a notice body gets, so whatever you can show you
can also read out. Treat it as a ceiling rather than a target: a
maximum-length utterance talks at the wearer for about a minute.

There is one voice. Calling `speak` again preempts the current utterance,
whose callback receives `PREEMPTED`. `stop()` addresses only the session's
current utterance and completes it with `STOPPED`; `close()` also stops
current speech and finishes any tracked callback exactly once. Natural
completion is `COMPLETED`. `UNAVAILABLE` means the utterance could not be
spoken: the phone's speech engine failed, or the sound had no safe place to
play — speech only ever goes to the glasses or to earbuds, never to the
phone's own speaker, and an utterance that finds neither within a few seconds
is dropped rather than played out loud into the room. `CANCELLED` means the
platform stopped the utterance because something needed the glasses
microphone. It will not resume when the microphone is released; call `speak`
again if you still want it said.

Speak and stop share a five-commands-per-second budget, enforced both in the
SDK and in the hub. `INVALID_PAYLOAD` means the text or id did not survive
validation before anything was sent, and `TTS_RATE_LIMITED` means the budget
is spent. `CAPABILITY_NOT_AVAILABLE` no longer occurs on current hubs —
speech does not depend on the glasses being reachable — but hubs older than
1.2.3 still answer it when no glasses renderer is available, so keep handling
it. A missing grant is a different answer, `CAPABILITY_REQUIRED_TTS`, so a
capability you forgot to request never looks like hardware that failed.

Speech uses the phone's own voice, at the voice and speed the wearer picked
in the hub's Settings → Voice screen. Plugins cannot read or change them, and
neither can a single utterance: they are the wearer's choice for everything
that speaks, not a per-plugin one.

### 3.4 Wireless debugging

`wireless_debugging` is an explicit high-risk grant. It allows a plugin to ask
the glasses hub to enable Android's real ADB-over-Wi-Fi transport on the current
LAN, create a two-minute pairing code, cancel that pairing window, query status,
or disable the transport. It never grants arbitrary shell access to the plugin
and it does not drive Settings or Accessibility. The contract requires phone and
glasses hubs 1.4.1 or newer; older hubs do not recognize the capability or route.
`ENABLE` and `START_PAIRING` restore the Wi-Fi radio when it is off and wait
for an already-saved network before enabling ADB.
They do not choose or configure a network. `DISABLE` turns off wireless ADB only
and deliberately leaves normal Wi-Fi connected.

Send `WirelessAdbContract.request(action)` to
`BusPaths.WIRELESS_ADB_REQUEST`. The hub answers on
`BusPaths.WIRELESS_ADB_REPLY` with the same envelope id. This is an owner-scoped
direct reply, so it must not be declared in `RECEIVE_PREFIXES`. The phone hub
rebuilds the canonical request and stamps `pluginId` from the authenticated
registration before forwarding it; plugin-provided identity and unknown fields
are ignored. A successful `START_PAIRING`
reply contains the IPv4 host, pairing port, six-digit code, connect port, and
expiry needed to form `adb pair` and `adb connect` commands. Treat the code as
a short-lived secret: do not persist or log it. If the UI offers a copy action,
make that user-initiated and explain that it places the command on the Android
clipboard until the clipboard is cleared; the pairing code still expires after
two minutes. Mark the clip sensitive and protect every code-bearing window with
`FLAG_SECURE`. A hub must not clear its active session until the pairing service
is confirmed stopped or the transport is disabled; failed expiry cleanup remains
tracked and is retried. Branch on `errorCode`, not the display-oriented `message`;
the stable code list is specified in
[BUSSPEC.md](../BUSSPEC.md#wireless-adb-control-v1).

### 3.5 Assist button takeover

The `assistant` grant lets a plugin replace Rokid's assistant when the assist
button is pressed: the hub dismisses the native scene and opens the plugin with
`openType = PluginOpenTypes.AI_ASSIST`, then reports the button edges through
`onNexusGlassesAiButton`. Whether that happens *right now* is a separate switch
on the phone, on by default, that the wearer can pause from the phone or that
the plugin can move for them — without touching its grant. A plugin can step
back and step in again; only the wearer, on the phone, decides whether it may
replace the assistant at all.

Call `requestNexusAssistantTakeover()` to learn the position and
`setNexusAssistantTakeover(enabled)` to move it; both return `SENT`,
`NOT_REGISTERED`, or `CAPABILITY_NOT_GRANTED`. The hub answers on
`onNexusAssistantTakeover(enabled)` with where the switch ended up, so a `set`
doubles as a read, and rejections land on `onNexusAssistantTakeoverError(code)`
— `PLUGIN_NAMESPACE_DENIED` means a phone hub older than 1.4.8, which does not
know the route. This is an owner-scoped direct reply; do not declare it in
`RECEIVE_PREFIXES`. Paused, the button stays with Rokid's assistant and the
plugin remains one launcher pick away, so a plugin that offers this switch
should also do something useful when opened with `PluginOpenTypes.OPEN` —
Assistant listens at once.

### 3.6 Skills

Skills (SDK 0.22.0, phone hub 1.7.0) let an approved plugin publish typed
operations that an approved caller, in practice Assistant, invokes through the phone hub. The provider
keeps its domain logic, Android permissions, and data; the hub discovers the
operations, asks the wearer which ones each caller may use, routes every call
with both identities stamped from authenticated registrations, and bounds it.
The wire contract is [Skills v1 in BUSSPEC](../BUSSPEC.md#skills-v1). Skills
need a phone hub that announces them at registration: check
`nexusClient?.supportsSkills` and handle `CAPABILITY_NOT_AVAILABLE`, which is
what every skills call returns on an older hub.

**Declare a catalog.** Put a JSON catalog in `res/raw` and point a dedicated
metadata key at it. Do not add `skills_provider` to `.CAPABILITIES`: hubs that
predate skills reject unknown capability names and would refuse the whole
plugin, while they ignore this key.

```xml
<meta-data
    android:name="com.anezium.rokidbus.plugin.SKILLS"
    android:resource="@raw/nexus_skills" />
```

```json
{
  "version": 1,
  "operations": [{
    "id": "count_words",
    "version": 1,
    "label": "Count words",
    "description": "Counts the words and characters in a short text.",
    "examples": ["How many words are in this sentence?"],
    "effect": "read",
    "cancellable": false,
    "deduplicates": true,
    "requires": [],
    "prerequisites": [],
    "data": ["text"],
    "input": {"type": "object", "properties": {"text": {"type": "string", "minLength": 1, "maxLength": 500}}, "required": ["text"]},
    "output": {"type": "object", "properties": {"words": {"type": "integer", "minimum": 0, "maximum": 500}}, "required": ["words"]}
  }]
}
```

The hub reads the catalog from your APK without starting the plugin. A catalog
holds at most 32 operations in 64 KiB, each description at most 2 KiB. `effect`
is `read` or `action`; `requires` may list `surfaces` when the operation
drives an activity; `prerequisites` names your own Android access (`network`,
`location`, `notification_access`); `data` names what a result may return from
a fixed vocabulary (`place_names`, `schedules`, `itinerary`, `media_metadata`,
`playback_state`, `status`, `text`), which is what the approval screen shows.
Schemas use a bounded JSON Schema subset: one `type` per node, objects that
never accept unknown properties, `maxLength` on every string, `maxItems` on
every array, `minimum` and `maximum` on every number, and no `$ref`,
combinators, patterns, formats, or nulls. Anything outside the subset makes the
catalog invalid; the plugin itself stays valid and Plugin access shows why.

**Entity references.** Mark a string with `"nexusRef": "<type>"` to return an
identifier of your own, such as a stop id. The hub replaces it with an opaque,
expiring handle before the caller sees it, and swaps the handle back when a
caller passes it into one of your operations. A caller can only return a
reference it was given, never mint one; the hub checks session, provider,
package revision, catalog, and type. Still validate the identifier you receive:
the hub guarantees it came from you, not that it is still current.

**Answer invocations.** Override `onNexusSkillInvoked` and answer each
`NexusSkillInvocation` exactly once, from any thread, within `remainingMs`
(15 seconds from acceptance, cold start included). The hub has validated the
arguments against your input schema.

```kotlin
override fun onNexusSkillInvoked(invocation: NexusSkillInvocation) {
    when (invocation.operationId) {
        "count_words" -> {
            val text = invocation.arguments.getString("text")
            invocation.complete(JSONObject().put("words", text.trim().split(Regex("\\s+")).size))
        }
        else -> invocation.fail(SkillErrorCodes.UNSUPPORTED_OPERATION)
    }
}
```

| Answer | Use it when |
|---|---|
| `complete(data)` | The read finished or the postcondition was observed. An empty read is a valid completion. |
| `accepted(data?)` | You dispatched the action but could not observe it complete. |
| `needsInput(reason, prompt?, choices)` | Nothing happened and the wearer must choose; at most eight `SkillProviderChoice`s, each with a label and one of your references. |
| `fail(code, dispatch)` | A known failure: a `SkillErrorCodes` value or your own domain code, and whether the effect was dispatched (`NONE`, `DISPATCHED`, `UNKNOWN`). |
| `unknown()` | You cannot tell whether the effect happened. |

A result must fit in 16 KiB and match your declared output; the SDK refuses an
answer that does not, locally and before the hub does, and leaves the
invocation open for a correct one. `onNexusSkillCancelled` marks the
invocation cancelled: stop what remains and answer with what is known. Keep
network timeouts inside `remainingMs`.

An invocation does not open your plugin. If the hub started you only for the
call, it refuses your surface, Ink, notice, pin, microphone, speech, TTS, and
camera traffic until the call ends, and an activity unless the running
operation lists `surfaces` in `requires`. The launcher bundle's activity
results, geometry, and heading follow the same activity rule, a notice
withdrawal is notice traffic, and a page answer is refused unless the wearer
asked the glasses for one of your pages in the meantime. The hub keeps you bound while the
call runs and releases you afterwards; do not keep running on your own unless
the operation started an ongoing process the wearer asked for (see
`holdNexusOngoingWork` below).

**Consent.** Declaring a catalog requests the separate `skills_provider`
grant, which returns an installed plugin to Pending until the wearer approves
it again. The wearer then allows each operation for each skills client, on
your Plugin access screen; every operation starts off, and a change to an
operation's contract, a new operation, or a new signing key needs a fresh
approval. Your description is shown to the model as data about the operation:
it never grants, approves, or routes anything.

**Callers.** A caller declares
`<meta-data android:name="com.anezium.rokidbus.plugin.SKILLS_CLIENT" android:value="true" />`,
which requests `skills_client`. `requestSkillCatalog()` answers on
`onNexusSkillCatalog(entries, errorCode)` with the operations approved for it,
each under a model-facing `alias` the hub generates from the provider and
operation identities. `invokeSkill(SkillInvokeRequest(session, requestKey,
alias, arguments))` answers exactly once on `onNexusSkillResult`: a route
rejection arrives there too, as a failed result. `requestKey` identifies the
request within your session: the hub reuses the outcome of an exact duplicate
and refuses the same key with different arguments. `cancelSkill` and
`closeSkillSession` end work early. One invocation runs per session and at
most four across callers; excess work fails with `busy` rather than queueing.

The Sample plugin's `count_words` is the complete provider template, and
Transit's catalog is a larger example with references, choices, and an
operation that drives an activity.

## Pages

A page is consultation content the glasses hub draws inside the Nexus session,
over whatever the wearer was doing: a native app, the Rokid home, or another
plugin's immersion. It is not an `onNexusOpen` call and never replaces the
screen underneath. The wearer opens the session with a triple tap, picks your
plugin's row or card, reads, and goes BACK to exactly where they were. The wire
contract is BUSSPEC's "Session and page protocol v1", extended by its
"Launcher bundle v1".

The session is the glasses' default launcher when the phone's last announcement
negotiated the launcher bundle; the user can still choose the classic launcher on
the phone or the glasses. Both hubs must negotiate page support. An old phone or
glasses hub, or a plugin without the metadata below, keeps today's behaviour:
your plugin appears in Applications as an explicitly labelled full-app
immersion.

### Opting in

Add one metadata entry to the plugin service, next to the existing descriptor
keys. It is not a capability: the wearer approves nothing new, and an existing
grant is kept.

```xml
<meta-data
    android:name="rokidbus.plugin.pages"
    android:value="1" />
```

Any other value means no pages; it never makes the plugin invalid. Template
pages need the existing `surfaces` grant.

A plugin whose typed actions should be able to run on one tap also declares the
families it offers. This lets the user approve them on the phone; it approves
nothing by itself and is not a capability either:

```xml
<meta-data
    android:name="rokidbus.plugin.quickActions"
    android:value="checklist,media" />
```

### Callbacks

`NexusPluginService` gains four protected hooks, called on the main thread:

```kotlin
protected open fun onNexusPageRequest(request: NexusPageRequest): NexusPage
protected open fun onNexusPageAction(action: NexusPageAction): NexusPageResult
protected open fun onNexusPageVisibility(visibility: NexusPageVisibility)
protected open fun onNexusPageClosed(closed: NexusPageClosed)
```

A plugin that drives its own `NexusPluginClient` overrides the same callbacks
on `NexusPluginCallbacks`: `onPageRequest`, `onPageAction`, `onPageVisibility`
and `onPageClosed`. The defaults answer unavailable and reject actions, so an
unchanged plugin compiles and behaves as before.

Pages arrive whether or not your plugin is open. They never call `onNexusOpen`
or `onNexusClose`, never start the session foreground service, and never release
your audio, speech, TTS or surface sessions: an immersion of your plugin can be
running underneath while the wearer reads one of its pages. `onNexusPageClosed`
is not `onNexusClose`. The hub keeps your service bound while a page needs it;
do not poll, and do not start a foreground service for pages.

Return the answer from `onNexusPageRequest`; that return value is the response.
Answer quickly and from memory: the wearer's eight-second budget runs from their
selection and includes a cold start of your service (at most five seconds), so
do no blocking I/O on this path. An exception is answered as unavailable without
its message.

```kotlin
override fun onNexusPageRequest(request: NexusPageRequest): NexusPage =
    when (request.pageId) {
        "myplugin:main" -> NexusPage.Content(
            revision = revision,
            title = "Today",
            body = PageBody.SelectableList(
                items = items.map { PageRow(it.key, it.title, sub = it.detail,
                    target = PageTarget.Page("myplugin:item", """{"key":"${it.key}"}""")) },
                emptyText = "Nothing yet",
            ),
        )
        else -> NexusPage.Unavailable
    }
```

`NexusPageRequest` carries `requestId`, `sessionGeneration`, `frameIndex`,
`pageId`, `reason` (`OPEN`, `REFRESH`, `RETRY`), a copy of the row's `params`,
and `minimumRevision`: the revision the glasses already retain for that frame.
Answer at that revision or above, even after a cold start; a lower one is
refused. An answer at exactly that revision tells the glasses nothing changed,
and they keep the body they already show. So once your service was recreated,
or whenever you rebuild a page without knowing it equals what you last sent,
answer above it. Your pages live in your namespace, `<pluginId>:<suffix>`, and the
glasses open `<pluginId>:main` first. The SDK supplies the correlation and
envelope ids; you never write your plugin id or another owner into a page.

### Templates

`NexusPage.Content(revision, title, body, actions, live)` names one of the
typed bodies from `com.anezium.rokidbus.shared`: the four below everywhere, and
three more with the launcher bundle. The hub owns layout, selection and one-axis
navigation.

| Body | Content |
|---|---|
| `PageBody.Summary` | optional `primary`, `secondary`, `text`; `facts` (`PageFact(id, label, value)`); `items` |
| `PageBody.SelectableList` | `items`; optional `emptyText` |
| `PageBody.Document` | `segments` (`PageSegment(id, kind, text)`, kind `HEADER`, `PROSE` or `ASIDE`); `items`; optional `footer` |
| `PageBody.Commands` | `items`; optional `text` |

A row is `PageRow(id, label, sub, enabled, target)`. Its target opens another of
your pages, `PageTarget.Page(pageId, paramsJson)`, or invokes one of the page's
declared actions, `PageTarget.Action(actionId)`; a row without a target is
readable but inert. Ids are unique within their list. A document keeps its text
order, paragraphs, explicit line breaks and Unicode, and the wearer pages
through it with swipes; its `footer` is paginated after the text, in the caption
style. Summary or commands text that does not fit the HUD with the page's first
row, or a fact too long for its lines, is read the same way: swipes page
through it, a tap reaches the rows, and BACK returns to the text. This applies
whenever it becomes too long, on opening or after a live revision, a refresh or
a larger HUD inset, unless the wearer holds a row past the first or a row a
newer revision removed: that row then stays selected and BACK reaches the text.
Once too long, it is read this way until the page is opened or retried again.
Text that has always fitted is shown as is.

When a page becomes unavailable, for a timeout or a lost link, the glasses keep
its last content readable, dimmed under the failure, with Retry and Back. None
of its rows or actions can be chosen until a Retry is answered.

Once `supportsLauncherInteractive()` is true, three more bodies are drawn;
before that the SDK answers a page using one as `INVALID_PAGE`, so keep a base
template as the fallback. `ink` stays reserved, and there is no Ink page body.

| Body | Content |
|---|---|
| `PageBody.Conversation` | `threadKey` (your notices' `eventKey` for the thread); `messages` (`ConversationMessage(id, role, text, sender, status)`, at most 64, oldest first); optional `caption` (the one capture time, 64); optional `composer` |
| `PageBody.Media` | `title` and `state` (`PLAYING`, `PAUSED`, `STOPPED`, `NONE`, `UNAVAILABLE`); optional `artist`, `album`, `source`, `status`; `controls` (up to 3 action ids in swipe order) and `defaultControl` |
| `PageBody.Route` | `position` (`RoutePositionQuality`); optional `maneuver` (`RouteManeuver(glyph, distance, street, eta)`), `facts` (6), `follow` and `overview` `RouteGeometry`, `ribbon` (`RouteRibbon(stops, line, between, note)`), `fixAgeS` |

- A message's `role` is `RECEIVED`, `OURS` or `UNKNOWN`, and it alone decides the
  side; `status` is `HANDED_OFF` or `UNCONFIRMED`. There are no per-message
  times and no read or delivery marks.
- `ConversationComposer(recipient, sendActionId, maxChars, replyable, ...)`
  offers the hub composer (Dictate, Type, Send). `sendActionId` must name one of
  the page's actions typed `NexusActionType.ComposeSend`, and no row may target
  that action. `maxChars` is 1..1,000. With `replyable = false` the hub keeps the
  text but withholds Send, showing your optional `unavailableText` and
  `unavailableSub`.
- Media `controls` must name page actions typed `MediaPrevious`, `MediaPlay`,
  `MediaPause` or `MediaNext`; they are the hub's command strip, not rows.
- Route geometry follows the activity geometry rules above; the whole page
  still fits 65,536 bytes.

A row may set `checked = true/false` to draw a checkbox; it is meant for a row
whose target is an action typed `SetChecked`.

Limits: 64 rows, 16 facts, 240 segments; labels and `primary` 120 characters;
`sub`, `secondary`, `footer`, `emptyText` and fact values 240; `text` and each
segment 4,096; row params 2,048 serialized UTF-8 bytes. The title is at most 48
characters and the whole response at most 65,536 UTF-8 bytes. Shorten your own
summaries before sending if you need to; the hub never truncates a page. A
content page the SDK finds invalid is answered as `INVALID_PAGE` instead.

### Actions

`NexusPageActionSpec(id, label, kind, confirm, typed)` declares an action: label
at most 24 characters, kind `PLUGIN`, `IMMERSION` or `HUB`. The glasses hub
confirms a plugin action with the wearer, Cancel preselected, whatever
`confirm` says, before `onNexusPageAction` is called with a fresh `invocationId`
and the revision the wearer saw. There is one exception, between hubs that
negotiated the launcher bundle: an action whose `typed` is `SetChecked(checked)`
(family `checklist`) or a media type (family `media`), when your plugin
declared that family in `rokidbus.plugin.quickActions` and the user approved it
for your plugin on the phone, runs on one tap. The phone and the glasses check
the owner, the revision and the type; nobody can check what your callback then
does, and the user's approval is trust in your plugin, so keep a quick action
exactly what its type says. Sends, deletes, anything with an external effect,
untyped actions, immersions and full-app launches are always confirmed, and no
field of yours can make an action quick. Types state the desired state, never a
toggle: put the task and the state in the action id (`check:milk:1`) and bind it
to the revision you sent.

`NexusPageAction.mode` says how the action was reached: `CONFIRMED`, `QUICK`, or
`COMPOSE` for the composer's Send, which also carries the wearer's `text`.
`typed` is the type you declared, as you sent it. The SDK delivers an action
only for a page it answered, at the revision it last sent and for an action that
revision declared, as encoded when it was sent, in a mode that fits it (quick
only for a quick-eligible type, compose only for the composer's send action and
within its `maxChars`), while that page is visible under an unexpired lease;
anything else never reaches your code. A refresh request suspends that until
your answer to it has gone out, and an Unavailable, invalid or below-floor
answer, or one that could not be sent, leaves nothing of the earlier revision to
act on. Return `NexusPageResult.done(message, replacement)`,
`.rejected(message)` or `.stale(message)`; a message is at most 64 characters
and a replacement is the same page at a higher revision. Each also takes an
optional `code` (printable ASCII, at most 32); the hub acts on
`NexusPageResult.CODE_AUTHORITY_LOST` only, for a composer whose reply authority
is gone: it keeps the text and stops offering Send. A lost result shows as
unconfirmed and is never resent. `IMMERSION` only opens your own full app, after
the hub's labelled launch step; `HUB` gives you no hub control.

```kotlin
override fun onNexusPageAction(action: NexusPageAction): NexusPageResult {
    val desired = action.typed as? NexusActionType.SetChecked ?: return NexusPageResult.rejected()
    val taskId = action.actionId.removePrefix("check:").substringBeforeLast(':')
    val task = tasks[taskId] ?: return NexusPageResult.stale("Task removed")
    if (action.revision != revision) return NexusPageResult.stale()
    task.done = desired.checked
    return NexusPageResult.done(replacement = listPage(++revision))
}
```

### Visibility and live updates

`NexusPageVisibility` reports `visible` and, when visible, `leaseDurationMs`:
how long the lease still runs, counted on your device from the callback's
arrival. The glasses' own clock is meaningless on the phone. A page answered with
`live = true` may send newer revisions while it is visible under that lease:

```kotlin
nexusClient?.publishPage(request, NexusPage.Content(revision + 1, title, body, live = true))
```

`NexusPluginClient.publishPage(request, page)` returns `SENT` only for the
frame's current request, after its live answer was sent, while visible, before
the lease runs out, and at a revision above every one already sent; otherwise it
refuses without sending. After a cover, a refresh request takes over the frame:
nothing can be published for it until your answer to that refresh has gone out,
then publish against that newest request. Page answers, revisions and results go
over the live Binder or not at all: they are never queued for a reconnect, and
one that could not be sent grants no publishing or action right. Publish on real
changes, at most about two revisions a second; never poll. `NexusPluginClient.supportsPageSession` says
whether pages can be served at all: approved with `surfaces`, both hubs on page
v1 for your plugin, and a usable link (CXR control or the SPP data plane). After
the link drops it stays false until the hub sends its next page version.
`supportsLauncherInteractive()` adds the launcher bundle on top: the three new
bodies, typed actions, quick actions and the composer. Without it, typed actions
and checked rows are dropped from what you send, and a page with a
`ComposeSend` action is answered as `INVALID_PAGE`, since a hub without the
composer would otherwise offer your send as an ordinary confirmed action.

`NexusPageClosed` arrives with `back`, `session_closed`, `timeout`,
`link_lost`, `replaced` or `frame_limit`; drop that page's state. `link_lost`
also comes for one page alone when SPP drops while CXR stays up and the page the
glasses hold, or the one being sent to them, is too big for a CXR message, and
when the hub could not send a page of yours to the glasses; your other pages go
on, and a page that merely waited to be sent while SPP dropped and returned is
sent and kept. Link loss,
revocation, a replaced registration or a closed client void every page and
publisher at once, and nothing is replayed on reconnect.

### Examples

Navigation and Relay are the first two providers. Both are in this repository;
wearers get them with each plugin's next release, on hubs that announce page v1.

Navigation answers `nav:main` with a `summary` of the guidance it already shows
in its activity: the manoeuvre, the distance or remaining stops, the street, the
ETA when the source app supplies one, and the source and time. Missing values
are left out rather than invented. A Commands row opens `nav:commands`, a
`commands` page whose only row opens the full app.

Relay answers `relay:main` with a `selectableList` of the inbox's threads and
each thread with a `document` of its messages, speaker headers when more than
one person speaks, and the time the snapshot was captured; there is no
per-message time. A thread too long for one answer keeps its newest whole
messages and says that earlier ones are not shown. Its pages are read-only: no
reply, read-aloud or mark-as-read, and the inbox's redaction and hidden-preview
settings apply.

## 4. Approve and debug

After installing the APK, open **Rokid Nexus → Settings → Plugin access**. Review
the requested capabilities and approve only those needed. Pending, denied,
disabled, invalid, and missing-capability plugins are not launchable.

**Your grants are true by the time you are told you are approved.** This is
worth knowing if your plugin acts the instant it is approved — waking, pushing a
pin or a notice, and going dormant again, which is the shape this SDK
recommends. `onNexusRegistrationState(APPROVED)` used to arrive ahead of the
grant list, so a plugin that pushed immediately asked about an empty set and
was refused a capability the wearer had granted. The client now reads its own
grants from the hub as approval lands, so `hasCapability` is answerable on the
first callback. Against an older hub the call is unavailable and the grants
follow a few milliseconds later as they always did — so if you push on
approval, treat `CAPABILITY_NOT_GRANTED` there as "not yet" and let the retry
happen, rather than as a refusal.

For local software validation:

```powershell
.\gradlew.bat :plugin-sample:testDebugUnitTest :plugin-sample:assembleDebug
```

**Settings → Advanced → Developer mode** is a global toggle. It unlocks the
Bus inspector, a live journal of plugin traffic and rejections, and shows DEV
badges; package, signer, API, and route details are available with developer
details. Logs and bug reports must redact device identifiers, signing digests,
credentials, locations, user text, and full payloads.

Normal use should not require ADB. The present repository still needs owner-run
device validation for APK install/update, glasses accessibility onboarding,
force-stop wake, input, revoke, and CXR-L/SPP continuity. Those are deployment
and hardware gates, not SDK initialization requirements.

Debug builds include a phone-hub-owned end-to-end image probe. With both hubs
installed, the glasses accessibility service armed, and SPP connected, run:

```powershell
adb -s $phone shell am broadcast -n com.anezium.rokidbus.phone/.PhoneProbeBroadcastReceiver -a com.anezium.rokidbus.phone.PROBE --es probe image-surface
```

This loads the bundled 480x480 JPEG in the phone hub and sends it through the
normal SPP frame, glasses validation/decode, and HUD renderer. The receiver is
present only in debug builds.

Compatibility details and reserved lifecycle payloads live in
[BUSSPEC.md](../BUSSPEC.md). [`plugins/sample`](../plugins/sample) is the
canonical headless template: package `com.anezium.rokidbus.plugin.sample`,
`minSdk 30`, a headless manifest, and a NexusUi/BusTheme settings screen with
the required uninstall row.

This project is licensed under the [Apache License 2.0](../LICENSE).
