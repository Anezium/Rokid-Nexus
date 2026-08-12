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
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:sdk-v0.15.0")
}
```

For local development against a checkout, publish a snapshot instead:
`.\gradlew.bat :shared:publishToMavenLocal :bus-client:publishToMavenLocal
'-PversionName=0.1.0-SNAPSHOT'` and consume it from `mavenLocal()`.

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
`assistant`, and `wireless_debugging`. `ink_surface` is distinct from `surfaces`:
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
thread. Duplicate lifecycle IDs are ignored. The glasses path already deduplicates
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

`anchor` says where reading begins, and the distinction it draws is
stream-shaped versus document-shaped content. `BOTTOM`, the default, suits a
chat, a log, an agent transcript: the surface opens at the end, an update stays
pinned there when the wearer was already near the end, and otherwise restores
the previous offset. `TOP` suits an article, a recipe, a README, a saved note:
the surface opens at the start and never follows the tail, so an update always
restores where the wearer had scrolled to. Sending `TOP` needs glasses hub
1.4.3 or newer; an older hub ignores it and opens at the bottom as before.

Reader navigation is renderer-owned. Directional keys and media next/previous
scroll locally and never reach `onNexusInput`; confirmation (ENTER or
DPAD_CENTER) and BACK keep the ordinary surface callback behavior.

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
)

val supportsActivitySurface: Boolean
fun startActivity(activity: NexusActivity): NexusSdkResult
fun updateActivity(
    activity: NexusActivity,
    significant: Boolean = false,
): NexusSdkResult
fun endActivity(): NexusSdkResult

interface NexusPluginCallbacks {
    fun onActivityAction(id: String) = Unit
    fun onActivityClosed(reason: String) = Unit
}
```

`NexusPluginService` forwards those callbacks to the overridable
`onNexusActivityAction(id)` and `onNexusActivityClosed(reason)` hooks used in
the example below.

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

A Maps-shaped route can publish the next maneuver as one object:

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
            NexusActivityAction(id = "mute", glyph = "pause", label = "Mute"),
        ),
        maxDurationMs = maxDurationMs,
    )

    override fun onNexusActivityAction(id: String) {
        if (id == "mute") muted = !muted
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
countdown ticks.

By default, an idle expanded panel collapses to its chip after about 10 seconds.
The wearer can keep the primary activity expanded from Nexus phone Settings.
That is a platform preference; no plugin API can read, set, or override it.

The platform can keep two activities and one pin in stable corners. Exactly one
activity is primary: the most recently significant one, or the oldest started
one when none is significant. A third start replaces the
least-recently-updated non-primary activity, with the oldest start as the
deterministic fallback when no non-primary candidate exists. Only the primary
activity can show the expanded panel
or claim its action row.

With no actions, a center tap on the idle layer opens the plugin through its
normal `onNexusOpen` path. With one to three actions, forward/backward selects
one and center tap invokes `onNexusActivityAction(id)`. Activity input is inert
while a surface, notice, launcher, or camera overlay owns the context; BACK is
never claimed. `onNexusActivityClosed(reason)` reports `owner`, `replaced`,
`disconnect`, or `max-duration`.

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

data class NexusNoticeTextInput(
    val id: String,
    val hint: String,
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
    val textInput: NexusNoticeTextInput? = null,
)

data class NexusNoticeUpdate(
    val title: String? = null,
    val body: String? = null,
    val footer: String? = null,
    val interactive: Boolean? = null,
    val actions: List<NexusNoticeAction> = emptyList(),
    val ttlMs: Long? = null,
    val lines: List<String> = emptyList(),
    val textInput: NexusNoticeTextInput? = null,
)

val supportsNoticeSurface: Boolean
val supportsNoticeTextInput: Boolean
fun showNotice(notice: NexusNotice): NexusSdkResult
fun showNotice(notice: NexusNotice, imageBytes: ByteArray): NexusSdkResult
fun updateNotice(update: NexusNoticeUpdate): NexusSdkResult
fun hideNotice(): NexusSdkResult

interface NexusPluginCallbacks {
    fun onNoticeInput(event: NexusInputEvent) = Unit
    fun onNoticeAction(id: String) = Unit
    fun onNoticeTextSubmitted(id: String, text: String) = Unit
    fun onNoticeClosed(reason: NexusNoticeCloseReason) = Unit
}
```

`NexusPluginService` forwards those to the overridable `onNexusNoticeInput`,
`onNexusNoticeAction(id)`, `onNexusNoticeTextSubmitted(id, text)`, and
`onNexusNoticeClosed(reason)` hooks.

When `supportsNoticeTextInput` is true, a fresh notice may carry one
`NexusNoticeTextInput`. Nexus renders the single-line field below the band,
focuses its glasses-owned editor, and opens the existing phone keyboard screen.
Pressing Enter submits at most once through `onNexusNoticeTextSubmitted`; the
phone hub validates the current owner and field id before delivery. Text is
limited to 1024 UTF-16 characters and 3072 UTF-8 bytes and must not be logged or
persisted by transport code. A text field is mutually exclusive with
`interactive` and `actions`, so switch from a choice row to typing with a fresh
`showNotice`, not an ambiguous mixed notice. Plugins never receive or send the
trusted `/core/remote-input/*` routes.

```kotlin
nexusClient?.showNotice(
    NexusNotice(
        title = "Assistant",
        body = "Type on your phone, then press Enter.",
        textInput = NexusNoticeTextInput(
            id = "question",
            hint = "Ask Assistant",
        ),
        ttlMs = 45_000,
    ),
)

override fun onNexusNoticeTextSubmitted(id: String, text: String) {
    if (id == "question") submitQuestion(text)
}
```

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
Check `supportsNoticeTextInput` separately before adding an editor; older
glasses may still support ordinary notices without the phone-backed field.

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
