package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONArray
import org.json.JSONObject

/** What SELECT on a row does: open a page, or invoke one of the envelope's declared actions. */
sealed interface PageTarget {
    /** [paramsJson] is the serialized `params` object handed to the page's provider. */
    data class Page(val pageId: String, val paramsJson: String? = null) : PageTarget
    data class Action(val actionId: String) : PageTarget
}

/**
 * A row. Without a [target] it is readable but inert; a disabled row is inert too. [checked] draws
 * a checkbox mark and is meant for a row whose target is an action typed `setChecked`.
 */
data class PageRow(
    val id: String,
    val label: String,
    val sub: String? = null,
    val enabled: Boolean = true,
    val target: PageTarget? = null,
    val checked: Boolean? = null,
)

data class PageFact(val id: String, val label: String, val value: String)

enum class PageSegmentKind(val wire: String) {
    HEADER("header"),
    PROSE("prose"),
    ASIDE("aside"),
}

data class PageSegment(val id: String, val kind: PageSegmentKind, val text: String)

/**
 * Where an activity card or a notice leads: one of its owner's own pages, with bounded params.
 * Opening it is navigation inside the session, never an immersion.
 */
data class PageDestination(val pageId: String, val paramsJson: String? = null)

/** Who wrote a conversation message. Sides come from this alone; [UNKNOWN] gets no side. */
enum class ConversationRole(val wire: String) {
    RECEIVED("received"),
    OURS("ours"),
    UNKNOWN("unknown"),
}

/** What is honestly known about one of our messages; never a delivery or read receipt. */
enum class ConversationMessageStatus(val wire: String) {
    HANDED_OFF("handed_off"),
    UNCONFIRMED("unconfirmed"),
}

data class ConversationMessage(
    val id: String,
    val role: ConversationRole,
    val text: String,
    val sender: String? = null,
    val status: ConversationMessageStatus? = null,
)

/**
 * The hub composer a conversation page offers. [sendActionId] names the page action typed
 * `compose.send`; [replyable] false keeps the text but withholds Send, with the optional
 * [unavailableText] and [unavailableSub] in the hub's own line.
 */
data class ConversationComposer(
    val recipient: String,
    val sendActionId: String,
    val maxChars: Int,
    val replyable: Boolean,
    val unavailableText: String? = null,
    val unavailableSub: String? = null,
)

enum class MediaPlaybackState(val wire: String) {
    PLAYING("playing"),
    PAUSED("paused"),
    STOPPED("stopped"),
    NONE("none"),
    UNAVAILABLE("unavailable"),
}

data class RouteManeuver(
    val glyph: String,
    val distance: String,
    val street: String? = null,
    val eta: String? = null,
)

enum class RouteStopState(val wire: String) {
    PASSED("passed"),
    UPCOMING("upcoming"),
    ALIGHT("alight"),
}

data class RouteRibbonStop(val name: String, val state: RouteStopState, val time: String? = null)

/** The vehicle is [fraction] of the way from stop [after] to the next one. */
data class RouteRibbonBetween(val after: Int, val fraction: Double)

data class RouteRibbon(
    val stops: List<RouteRibbonStop>,
    val line: String? = null,
    val between: RouteRibbonBetween? = null,
    val note: String? = null,
)

/** The template bodies; see [PageTemplateContract.supportedTemplates] for which a hub draws. */
sealed interface PageBody {
    val template: String
    val items: List<PageRow>

    data class Summary(
        val primary: String? = null,
        val secondary: String? = null,
        val text: String? = null,
        val facts: List<PageFact> = emptyList(),
        override val items: List<PageRow> = emptyList(),
    ) : PageBody {
        override val template: String get() = PageSurfaceContract.TEMPLATE_SUMMARY
    }

    data class SelectableList(
        override val items: List<PageRow>,
        val emptyText: String? = null,
    ) : PageBody {
        override val template: String get() = PageSurfaceContract.TEMPLATE_SELECTABLE_LIST
    }

    data class Document(
        val segments: List<PageSegment>,
        override val items: List<PageRow> = emptyList(),
        val footer: String? = null,
    ) : PageBody {
        override val template: String get() = PageSurfaceContract.TEMPLATE_DOCUMENT
    }

    data class Commands(
        override val items: List<PageRow>,
        val text: String? = null,
    ) : PageBody {
        override val template: String get() = PageSurfaceContract.TEMPLATE_COMMANDS
    }

    /**
     * One exact thread, oldest message first, with the capture time in [caption] (one per page).
     * Launcher bundle only; it carries no rows: the hub draws its own composer buttons.
     */
    data class Conversation(
        val threadKey: String,
        val messages: List<ConversationMessage>,
        val caption: String? = null,
        val composer: ConversationComposer? = null,
    ) : PageBody {
        override val template: String get() = PageSurfaceContract.TEMPLATE_CONVERSATION
        override val items: List<PageRow> get() = emptyList()
    }

    /**
     * Now playing. [controls] are page actions typed `media.*`, in swipe order; [defaultControl]
     * is focused first. Launcher bundle only; the controls are the hub's command strip, not rows.
     */
    data class Media(
        val title: String,
        val state: MediaPlaybackState,
        val artist: String? = null,
        val album: String? = null,
        val source: String? = null,
        val status: String? = null,
        val controls: List<String> = emptyList(),
        val defaultControl: String? = null,
        val artwork: LauncherArtwork? = null,
        val positionMs: Long? = null,
        val durationMs: Long? = null,
        val playbackSpeed: Float = 1f,
        val advancing: Boolean? = null,
    ) : PageBody {
        override val template: String get() = PageSurfaceContract.TEMPLATE_MEDIA
        override val items: List<PageRow> get() = emptyList()
    }

    /** Guidance with optional follow and overview geometry, or a transit ribbon. Launcher bundle only. */
    data class Route(
        val position: RoutePositionQuality,
        val maneuver: RouteManeuver? = null,
        val facts: List<PageFact> = emptyList(),
        val follow: RouteGeometry? = null,
        val overview: RouteGeometry? = null,
        val ribbon: RouteRibbon? = null,
        val fixAgeS: Int? = null,
    ) : PageBody {
        override val template: String get() = PageSurfaceContract.TEMPLATE_ROUTE
        override val items: List<PageRow> get() = emptyList()
    }
}

/**
 * Typed bodies of the page templates a hub can draw, and the page-id namespaces.
 *
 * The envelope validator in [PageSurfaceContract] stays the size and correlation authority and
 * keeps treating `body` as opaque; this decoder is the second check, run before a body is drawn
 * or a row is acted on. The base four templates are always decoded. `conversation`, `media` and
 * `route` are decoded only between hubs that negotiated the launcher bundle (`extended`); without
 * it they, and `ink` always, are valid envelopes this decoder reports as unsupported. Lengths count
 * UTF-16 code units, as the envelope's own title and label limits do.
 */
object PageTemplateContract {
    const val MAX_ITEMS = 64
    const val MAX_FACTS = 16
    const val MAX_SEGMENTS = 240
    const val MAX_LABEL_CHARS = 120
    const val MAX_PRIMARY_CHARS = 120
    /** `sub`, `secondary`, `footer`, a fact `value` and a list's `emptyText`. */
    const val MAX_DETAIL_CHARS = 240
    /** Summary and commands `text`, and each document segment. */
    const val MAX_TEXT_CHARS = 4_096

    const val FIELD_CHECKED = "checked"

    // Conversation.
    const val MAX_CAPTION_CHARS = 64
    const val MAX_MESSAGES = 64
    const val MAX_SENDER_CHARS = 48
    const val MAX_MESSAGE_TEXT_CHARS = 2_000
    const val MAX_RECIPIENT_CHARS = 48
    const val MAX_UNAVAILABLE_CHARS = 64

    // Media.
    const val MAX_MEDIA_TEXT_CHARS = 120
    const val MAX_SOURCE_CHARS = 48
    const val MAX_MEDIA_CONTROLS = 3

    // Route.
    const val MAX_GLYPH_CHARS = 32
    const val MAX_DISTANCE_CHARS = 16
    const val MAX_STREET_CHARS = 120
    const val MAX_ETA_CHARS = 32
    const val MAX_ROUTE_FACTS = 6
    const val MAX_LINE_CHARS = 16
    const val MAX_RIBBON_STOPS = 16
    const val MAX_STOP_NAME_CHARS = 32
    const val MAX_STOP_TIME_CHARS = 8
    const val MAX_NOTE_CHARS = 160

    /** The namespace of pages the glasses hub authors itself; no plugin may provide them. */
    const val LOCAL_NAMESPACE = "nexus"
    const val MAIN_PAGE = "main"

    /** The base templates every page-capable pair draws. */
    val supportedTemplates: Set<String> = setOf(
        PageSurfaceContract.TEMPLATE_SUMMARY,
        PageSurfaceContract.TEMPLATE_SELECTABLE_LIST,
        PageSurfaceContract.TEMPLATE_DOCUMENT,
        PageSurfaceContract.TEMPLATE_COMMANDS,
    )

    /** The base templates plus the launcher bundle's bodies. `ink` stays reserved either way. */
    val extendedTemplates: Set<String> = supportedTemplates + setOf(
        PageSurfaceContract.TEMPLATE_CONVERSATION,
        PageSurfaceContract.TEMPLATE_MEDIA,
        PageSurfaceContract.TEMPLATE_ROUTE,
    )

    /** What a pair draws: [extendedTemplates] once it negotiated the launcher bundle. */
    fun supportedTemplates(extended: Boolean): Set<String> = if (extended) extendedTemplates else supportedTemplates

    fun isSupported(template: String, extended: Boolean = false): Boolean = template in supportedTemplates(extended)

    /** A provider's conventional entry page. */
    fun mainPageId(pluginId: String): String = "$pluginId:$MAIN_PAGE"

    /** The owner before the first `:` of a valid page id with a nonempty suffix, or null. */
    fun namespaceOf(pageId: String): String? {
        if (!PageSurfaceContract.isValidId(pageId)) return null
        val separator = pageId.indexOf(':')
        if (separator <= 0 || separator == pageId.lastIndex) return null
        return pageId.substring(0, separator)
    }

    fun isLocalPageId(pageId: String): Boolean = namespaceOf(pageId) == LOCAL_NAMESPACE

    /** Whether [pluginId] may provide [pageId]: its own namespace, never the hub's. */
    fun isProviderPageId(pageId: String, pluginId: String): Boolean =
        pluginId != LOCAL_NAMESPACE && PluginDescriptor.isValidId(pluginId) && namespaceOf(pageId) == pluginId

    /**
     * A destination's wire object `{pageId, params?}`: a page id with a provider namespace (never
     * the hub's) and params within [PageSurfaceContract.MAX_PARAMS_BYTES]. Whose namespace it is
     * gets checked where the owner is authenticated, with [isDestinationOf].
     */
    fun readDestination(value: Any?): PageSurfaceValidationResult<PageDestination> {
        val destination = value as? JSONObject
            ?: return PageSurfaceValidationResult.Invalid(PageSurfaceContract.ERROR_INVALID_PAGE, "destination must be an object")
        val pageId = destination.opt("pageId") as? String
            ?: return PageSurfaceValidationResult.Invalid(PageSurfaceContract.ERROR_INVALID_PAGE, "destination pageId must be a string")
        val owner = namespaceOf(pageId)
        if (owner == null || owner == LOCAL_NAMESPACE || !PluginDescriptor.isValidId(owner)) {
            return PageSurfaceValidationResult.Invalid(PageSurfaceContract.ERROR_INVALID_PAGE, "destination must be a provider page")
        }
        val params = if (destination.has("params") && destination.opt("params") != JSONObject.NULL) {
            val raw = destination.opt("params") as? JSONObject
                ?: return PageSurfaceValidationResult.Invalid(PageSurfaceContract.ERROR_INVALID_PAGE, "destination params must be an object")
            PageSurfaceContract.boundedParams(raw)
                ?: return PageSurfaceValidationResult.Invalid(PageSurfaceContract.ERROR_INVALID_PAGE, "destination params are out of bounds")
        } else {
            null
        }
        return PageSurfaceValidationResult.Valid(PageDestination(pageId, params))
    }

    fun encodeDestination(destination: PageDestination): JSONObject = JSONObject().put("pageId", destination.pageId).apply {
        destination.paramsJson?.let { put("params", JSONObject(it)) }
    }

    /** Whether [destination] is one of [ownerPluginId]'s own pages. */
    fun isDestinationOf(destination: PageDestination, ownerPluginId: String): Boolean =
        isProviderPageId(destination.pageId, ownerPluginId)

    /**
     * Decodes [page]'s body. Navigation targets must stay inside [namespace], the authenticated
     * provider's; a hub-authored local page passes null and has its targets authorized by the
     * catalog instead. Action targets must name one of the envelope's actions, and the composer
     * and media controls must name actions of the right type.
     */
    fun decode(
        page: PageSurfaceResponse.Page,
        namespace: String?,
        extended: Boolean = false,
    ): PageSurfaceValidationResult<PageBody> =
        decode(page.template, page.bodyJson, page.actions, namespace, extended)

    /**
     * A decode that only knows the action ids. Bodies whose rules depend on action types (the
     * composer's send action, media controls) are invalid here; decode them with the actions.
     */
    fun decode(
        template: String,
        bodyJson: String,
        actionIds: Set<String>,
        namespace: String?,
        extended: Boolean = false,
    ): PageSurfaceValidationResult<PageBody> =
        decodeTyped(template, bodyJson, actionIds.associateWith<String, PageActionType?> { null }, namespace, extended)

    fun decode(
        template: String,
        bodyJson: String,
        actions: List<PageSurfaceAction>,
        namespace: String?,
        extended: Boolean = false,
    ): PageSurfaceValidationResult<PageBody> =
        decodeTyped(template, bodyJson, actions.associate { it.id to it.typed }, namespace, extended)

    private fun decodeTyped(
        template: String,
        bodyJson: String,
        actionTypes: Map<String, PageActionType?>,
        namespace: String?,
        extended: Boolean,
    ): PageSurfaceValidationResult<PageBody> = try {
        if (!isSupported(template, extended)) fail("template $template is not available on this hub")
        val body = JSONObject(bodyJson)
        val reader = Reader(actionTypes, namespace)
        PageSurfaceValidationResult.Valid(
            when (template) {
                PageSurfaceContract.TEMPLATE_SUMMARY -> PageBody.Summary(
                    primary = reader.optionalText(body, "primary", MAX_PRIMARY_CHARS),
                    secondary = reader.optionalText(body, "secondary", MAX_DETAIL_CHARS),
                    text = reader.optionalText(body, "text", MAX_TEXT_CHARS),
                    facts = reader.facts(body, MAX_FACTS),
                    items = reader.items(body, required = false),
                )
                PageSurfaceContract.TEMPLATE_SELECTABLE_LIST -> PageBody.SelectableList(
                    items = reader.items(body, required = true),
                    emptyText = reader.optionalText(body, "emptyText", MAX_DETAIL_CHARS),
                )
                PageSurfaceContract.TEMPLATE_DOCUMENT -> PageBody.Document(
                    segments = reader.segments(body),
                    items = reader.items(body, required = false),
                    footer = reader.optionalText(body, "footer", MAX_DETAIL_CHARS),
                )
                PageSurfaceContract.TEMPLATE_COMMANDS -> PageBody.Commands(
                    items = reader.items(body, required = true),
                    text = reader.optionalText(body, "text", MAX_TEXT_CHARS),
                )
                PageSurfaceContract.TEMPLATE_CONVERSATION -> reader.conversation(body)
                PageSurfaceContract.TEMPLATE_MEDIA -> reader.media(body)
                PageSurfaceContract.TEMPLATE_ROUTE -> reader.route(body)
                // isSupported already refused everything else; never read an unknown body as another.
                else -> fail("template $template has no decoder")
            },
        )
    } catch (invalid: InvalidBody) {
        PageSurfaceValidationResult.Invalid(PageSurfaceContract.ERROR_INVALID_PAGE, invalid.detail)
    } catch (_: Exception) {
        PageSurfaceValidationResult.Invalid(PageSurfaceContract.ERROR_INVALID_PAGE, "body could not be read")
    }

    /** The wire form of [body]; [decode] of it, with the same actions and namespace, gives [body] back. */
    fun encode(body: PageBody): JSONObject = JSONObject().apply {
        when (body) {
            is PageBody.Summary -> {
                body.primary?.let { put("primary", it) }
                body.secondary?.let { put("secondary", it) }
                body.text?.let { put("text", it) }
                if (body.facts.isNotEmpty()) put("facts", encodeFacts(body.facts))
                if (body.items.isNotEmpty()) put("items", encodeItems(body.items))
            }
            is PageBody.SelectableList -> {
                put("items", encodeItems(body.items))
                body.emptyText?.let { put("emptyText", it) }
            }
            is PageBody.Document -> {
                put(
                    "segments",
                    JSONArray().apply {
                        body.segments.forEach { put(JSONObject().put("id", it.id).put("kind", it.kind.wire).put("text", it.text)) }
                    },
                )
                if (body.items.isNotEmpty()) put("items", encodeItems(body.items))
                body.footer?.let { put("footer", it) }
            }
            is PageBody.Commands -> {
                put("items", encodeItems(body.items))
                body.text?.let { put("text", it) }
            }
            is PageBody.Conversation -> {
                put("threadKey", body.threadKey)
                body.caption?.let { put("caption", it) }
                put(
                    "messages",
                    JSONArray().apply {
                        body.messages.forEach { message ->
                            put(
                                JSONObject().put("id", message.id).put("role", message.role.wire).put("text", message.text).apply {
                                    message.sender?.let { put("sender", it) }
                                    message.status?.let { put("status", it.wire) }
                                },
                            )
                        }
                    },
                )
                body.composer?.let { composer ->
                    put(
                        "composer",
                        JSONObject()
                            .put("recipient", composer.recipient)
                            .put("sendActionId", composer.sendActionId)
                            .put("maxChars", composer.maxChars)
                            .put("replyable", composer.replyable)
                            .apply {
                                composer.unavailableText?.let { put("unavailableText", it) }
                                composer.unavailableSub?.let { put("unavailableSub", it) }
                            },
                    )
                }
            }
            is PageBody.Media -> {
                put("title", body.title)
                body.artist?.let { put("artist", it) }
                body.album?.let { put("album", it) }
                body.source?.let { put("source", it) }
                put("state", body.state.wire)
                body.status?.let { put("status", it) }
                put("controls", JSONArray().apply { body.controls.forEach(::put) })
                body.defaultControl?.let { put("defaultControl", it) }
                body.artwork?.let { put("artwork", it.toJson()) }
                body.positionMs?.let { put("positionMs", it) }
                body.durationMs?.let { put("durationMs", it) }
                if (body.playbackSpeed != 1f) put("playbackSpeed", body.playbackSpeed.toDouble())
                body.advancing?.let { put("advancing", it) }
            }
            is PageBody.Route -> {
                body.maneuver?.let { maneuver ->
                    put(
                        "maneuver",
                        JSONObject().put("glyph", maneuver.glyph).put("distance", maneuver.distance).apply {
                            maneuver.street?.let { put("street", it) }
                            maneuver.eta?.let { put("eta", it) }
                        },
                    )
                }
                if (body.facts.isNotEmpty()) put("facts", encodeFacts(body.facts))
                body.follow?.let { put("follow", RouteGeometryContract.encode(it)) }
                body.overview?.let { put("overview", RouteGeometryContract.encode(it)) }
                body.ribbon?.let { ribbon ->
                    put(
                        "ribbon",
                        JSONObject().apply {
                            ribbon.line?.let { put("line", it) }
                            put(
                                "stops",
                                JSONArray().apply {
                                    ribbon.stops.forEach { stop ->
                                        put(
                                            JSONObject().put("name", stop.name).put("state", stop.state.wire).apply {
                                                stop.time?.let { put("time", it) }
                                            },
                                        )
                                    }
                                },
                            )
                            ribbon.between?.let { put("between", JSONObject().put("after", it.after).put("fraction", it.fraction)) }
                            ribbon.note?.let { put("note", it) }
                        },
                    )
                }
                put("position", body.position.wireValue)
                body.fixAgeS?.let { put("fixAgeS", it) }
            }
        }
    }

    private fun encodeFacts(facts: List<PageFact>): JSONArray = JSONArray().apply {
        facts.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("value", it.value)) }
    }

    private fun encodeItems(items: List<PageRow>): JSONArray = JSONArray().apply {
        items.forEach { row ->
            put(
                JSONObject().put("id", row.id).put("label", row.label).apply {
                    row.sub?.let { put("sub", it) }
                    if (!row.enabled) put("enabled", false)
                    when (val target = row.target) {
                        is PageTarget.Page -> put(
                            "target",
                            JSONObject().put("pageId", target.pageId).apply {
                                target.paramsJson?.let { put("params", JSONObject(it)) }
                            },
                        )
                        is PageTarget.Action -> put("target", JSONObject().put("actionId", target.actionId))
                        null -> Unit
                    }
                    row.checked?.let { put(FIELD_CHECKED, it) }
                },
            )
        }
    }

    private class Reader(private val actionTypes: Map<String, PageActionType?>, private val namespace: String?) {
        fun items(body: JSONObject, required: Boolean): List<PageRow> {
            val array = array(body, "items", required) ?: return emptyList()
            check(array.length() <= MAX_ITEMS, "more than $MAX_ITEMS items")
            val rows = (0 until array.length()).map { index ->
                val entry = array.opt(index) as? JSONObject ?: fail("items[$index] must be an object")
                PageRow(
                    id = id(entry, "id"),
                    label = text(entry, "label", MAX_LABEL_CHARS),
                    sub = optionalText(entry, "sub", MAX_DETAIL_CHARS),
                    enabled = if (entry.has("enabled")) entry.opt("enabled") as? Boolean ?: fail("enabled must be a boolean") else true,
                    target = if (entry.has("target")) target(entry.opt("target")) else null,
                    checked = if (entry.has(FIELD_CHECKED)) entry.opt(FIELD_CHECKED) as? Boolean ?: fail("checked must be a boolean") else null,
                )
            }
            unique(rows.map(PageRow::id), "item")
            return rows
        }

        fun facts(body: JSONObject, limit: Int): List<PageFact> {
            val array = array(body, "facts", required = false) ?: return emptyList()
            check(array.length() <= limit, "more than $limit facts")
            val facts = (0 until array.length()).map { index ->
                val entry = array.opt(index) as? JSONObject ?: fail("facts[$index] must be an object")
                PageFact(id(entry, "id"), text(entry, "label", MAX_LABEL_CHARS), text(entry, "value", MAX_DETAIL_CHARS))
            }
            unique(facts.map(PageFact::id), "fact")
            return facts
        }

        fun segments(body: JSONObject): List<PageSegment> {
            val array = array(body, "segments", required = true) ?: return emptyList()
            check(array.length() <= MAX_SEGMENTS, "more than $MAX_SEGMENTS segments")
            val segments = (0 until array.length()).map { index ->
                val entry = array.opt(index) as? JSONObject ?: fail("segments[$index] must be an object")
                val kind = entry.opt("kind") as? String ?: fail("segment kind must be a string")
                PageSegment(
                    id(entry, "id"),
                    PageSegmentKind.entries.firstOrNull { it.wire == kind } ?: fail("segment kind $kind is unknown"),
                    text(entry, "text", MAX_TEXT_CHARS),
                )
            }
            unique(segments.map(PageSegment::id), "segment")
            return segments
        }

        fun conversation(body: JSONObject): PageBody.Conversation {
            val array = array(body, "messages", required = true) ?: fail("messages is required")
            check(array.length() <= MAX_MESSAGES, "more than $MAX_MESSAGES messages")
            val messages = (0 until array.length()).map { index ->
                val entry = array.opt(index) as? JSONObject ?: fail("messages[$index] must be an object")
                val role = entry.opt("role") as? String ?: fail("message role must be a string")
                val status = optionalText(entry, "status", MAX_ID_LIKE)?.let { wire ->
                    ConversationMessageStatus.entries.firstOrNull { it.wire == wire } ?: fail("message status $wire is unknown")
                }
                ConversationMessage(
                    id = id(entry, "id"),
                    role = ConversationRole.entries.firstOrNull { it.wire == role } ?: fail("message role $role is unknown"),
                    text = text(entry, "text", MAX_MESSAGE_TEXT_CHARS),
                    sender = optionalText(entry, "sender", MAX_SENDER_CHARS),
                    status = status,
                )
            }
            unique(messages.map(ConversationMessage::id), "message")
            val composer = optionalObject(body, "composer")?.let { entry ->
                val sendActionId = id(entry, "sendActionId")
                check(sendActionId in actionTypes, "composer send action $sendActionId is not one of the page's actions")
                check(actionTypes[sendActionId] is PageActionType.ComposeSend, "composer send action must be typed compose.send")
                val maxChars = integer(entry, "maxChars")
                check(maxChars in 1..PageSurfaceContract.MAX_COMPOSE_TEXT_CHARS, "composer maxChars must be 1..${PageSurfaceContract.MAX_COMPOSE_TEXT_CHARS}")
                ConversationComposer(
                    recipient = nonBlank(entry, "recipient", MAX_RECIPIENT_CHARS),
                    sendActionId = sendActionId,
                    maxChars = maxChars,
                    replyable = entry.opt("replyable") as? Boolean ?: fail("composer replyable must be a boolean"),
                    unavailableText = optionalText(entry, "unavailableText", MAX_UNAVAILABLE_CHARS),
                    unavailableSub = optionalText(entry, "unavailableSub", MAX_UNAVAILABLE_CHARS),
                )
            }
            return PageBody.Conversation(
                threadKey = id(body, "threadKey"),
                messages = messages,
                caption = optionalText(body, "caption", MAX_CAPTION_CHARS),
                composer = composer,
            )
        }

        private fun mediaTime(body: JSONObject, key: String): Long? {
            if (!body.has(key) || body.isNull(key)) return null
            val value = body.opt(key) as? Number ?: fail("$key must be an integer")
            val millis = value.toLong()
            check(value.toDouble() == millis.toDouble() && millis in 0..604_800_000L, "$key is out of bounds")
            return millis
        }

        fun media(body: JSONObject): PageBody.Media {
            val speed = if (body.has("playbackSpeed")) {
                val value = body.opt("playbackSpeed") as? Number ?: fail("playbackSpeed must be a number")
                value.toDouble().also { check(it.isFinite() && it in -16.0..16.0, "playbackSpeed is out of bounds") }.toFloat()
            } else 1f
            val advancing = if (body.has("advancing")) body.opt("advancing") as? Boolean ?: fail("advancing must be boolean") else null
            val state = body.opt("state") as? String ?: fail("state must be a string")
            val array = array(body, "controls", required = false)
            val controls = if (array == null) {
                emptyList()
            } else {
                check(array.length() <= MAX_MEDIA_CONTROLS, "more than $MAX_MEDIA_CONTROLS controls")
                (0 until array.length()).map { index ->
                    val control = array.opt(index) as? String ?: fail("controls[$index] must be a string")
                    check(PageSurfaceContract.isValidId(control), "controls[$index] must be a safe id")
                    check(control in actionTypes, "control $control is not one of the page's actions")
                    check(actionTypes[control]?.isMedia == true, "control $control must be typed media")
                    control
                }
            }
            unique(controls, "control")
            val defaultControl = if (body.has("defaultControl")) id(body, "defaultControl") else null
            check(defaultControl == null || defaultControl in controls, "defaultControl must be one of the controls")
            return PageBody.Media(
                title = nonBlank(body, "title", MAX_MEDIA_TEXT_CHARS),
                state = MediaPlaybackState.entries.firstOrNull { it.wire == state } ?: fail("media state $state is unknown"),
                artist = optionalText(body, "artist", MAX_MEDIA_TEXT_CHARS),
                album = optionalText(body, "album", MAX_MEDIA_TEXT_CHARS),
                source = optionalText(body, "source", MAX_SOURCE_CHARS),
                status = optionalText(body, "status", MAX_MEDIA_TEXT_CHARS),
                controls = controls,
                defaultControl = defaultControl,
                artwork = LauncherArtwork.parse(body.opt("artwork")),
                positionMs = mediaTime(body, "positionMs"),
                durationMs = mediaTime(body, "durationMs"),
                playbackSpeed = speed,
                advancing = advancing,
            )
        }

        fun route(body: JSONObject): PageBody.Route {
            val maneuver = optionalObject(body, "maneuver")?.let { entry ->
                val glyph = nonBlank(entry, "glyph", MAX_GLYPH_CHARS)
                check(glyph.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }, "maneuver glyph must be a glyph name")
                RouteManeuver(
                    glyph = glyph,
                    distance = text(entry, "distance", MAX_DISTANCE_CHARS),
                    street = optionalText(entry, "street", MAX_STREET_CHARS),
                    eta = optionalText(entry, "eta", MAX_ETA_CHARS),
                )
            }
            val ribbon = optionalObject(body, "ribbon")?.let { entry ->
                val array = array(entry, "stops", required = true) ?: fail("ribbon stops are required")
                check(array.length() <= MAX_RIBBON_STOPS, "more than $MAX_RIBBON_STOPS ribbon stops")
                val stops = (0 until array.length()).map { index ->
                    val stop = array.opt(index) as? JSONObject ?: fail("stops[$index] must be an object")
                    val state = stop.opt("state") as? String ?: fail("stop state must be a string")
                    RouteRibbonStop(
                        name = nonBlank(stop, "name", MAX_STOP_NAME_CHARS),
                        state = RouteStopState.entries.firstOrNull { it.wire == state } ?: fail("stop state $state is unknown"),
                        time = optionalText(stop, "time", MAX_STOP_TIME_CHARS),
                    )
                }
                val between = optionalObject(entry, "between")?.let { value ->
                    val after = integer(value, "after")
                    check(after in 0 until stops.size - 1, "between must sit between two stops")
                    val fraction = (value.opt("fraction") as? Number)?.toDouble() ?: fail("between fraction must be a number")
                    check(fraction.isFinite() && fraction in 0.0..1.0, "between fraction must be 0..1")
                    RouteRibbonBetween(after, fraction)
                }
                RouteRibbon(
                    stops = stops,
                    line = optionalText(entry, "line", MAX_LINE_CHARS),
                    between = between,
                    note = optionalText(entry, "note", MAX_NOTE_CHARS),
                )
            }
            val position = body.opt("position") as? String ?: fail("position must be a string")
            return PageBody.Route(
                position = RoutePositionQuality.fromWireValue(position) ?: fail("position $position is unknown"),
                maneuver = maneuver,
                facts = facts(body, MAX_ROUTE_FACTS),
                follow = optionalGeometry(body, "follow"),
                overview = optionalGeometry(body, "overview"),
                ribbon = ribbon,
                fixAgeS = if (body.has("fixAgeS")) integer(body, "fixAgeS").also { check(it >= 0, "fixAgeS must be nonnegative") } else null,
            )
        }

        private fun target(value: Any?): PageTarget {
            val target = value as? JSONObject ?: fail("target must be an object")
            val hasPage = target.has("pageId")
            val hasAction = target.has("actionId")
            check(hasPage != hasAction, "target needs exactly one of pageId and actionId")
            if (hasAction) {
                check(!target.has("params"), "an action target carries no params")
                val actionId = id(target, "actionId")
                check(actionId in actionTypes, "target action $actionId is not one of the page's actions")
                // A send is reached only through the composer's deliberate Send, never from a row.
                check(actionTypes[actionId] !is PageActionType.ComposeSend, "a row cannot target a compose.send action")
                return PageTarget.Action(actionId)
            }
            val pageId = id(target, "pageId")
            val owner = namespaceOf(pageId) ?: fail("target page $pageId has no namespace")
            check(namespace == null || owner == namespace, "target page $pageId leaves the provider's namespace")
            val params = if (target.has("params")) {
                val params = target.opt("params") as? JSONObject ?: fail("params must be an object")
                val serialized = params.toString()
                check(
                    serialized.toByteArray(Charsets.UTF_8).size <= PageSurfaceContract.MAX_PARAMS_BYTES,
                    "target params exceed ${PageSurfaceContract.MAX_PARAMS_BYTES} UTF-8 bytes",
                )
                serialized
            } else {
                null
            }
            return PageTarget.Page(pageId, params)
        }

        private fun optionalGeometry(body: JSONObject, key: String): RouteGeometry? {
            if (!body.has(key)) return null
            return when (val result = RouteGeometryContract.validate(body.opt(key))) {
                is RouteGeometryResult.Valid -> result.geometry
                is RouteGeometryResult.Invalid -> fail("$key: ${result.reason}")
            }
        }

        fun optionalText(payload: JSONObject, key: String, limit: Int): String? =
            if (payload.has(key)) text(payload, key, limit) else null

        private fun text(payload: JSONObject, key: String, limit: Int): String {
            val value = payload.opt(key) as? String ?: fail("$key must be a string")
            check(value.length <= limit, "$key exceeds $limit characters")
            return value
        }

        private fun nonBlank(payload: JSONObject, key: String, limit: Int): String {
            val value = text(payload, key, limit)
            check(value.isNotBlank(), "$key must contain text")
            return value
        }

        private fun integer(payload: JSONObject, key: String): Int {
            val number = payload.opt(key) as? Number ?: fail("$key must be an integer")
            val double = number.toDouble()
            check(double.isFinite() && double == Math.rint(double) && double in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble(), "$key must be an integer")
            return double.toInt()
        }

        private fun id(payload: JSONObject, key: String): String {
            val value = payload.opt(key) as? String ?: fail("$key must be a string")
            check(PageSurfaceContract.isValidId(value), "$key must be a safe id")
            return value
        }

        private fun array(payload: JSONObject, key: String, required: Boolean): JSONArray? {
            if (!payload.has(key)) return if (required) fail("$key is required") else null
            return payload.opt(key) as? JSONArray ?: fail("$key must be an array")
        }

        private fun optionalObject(payload: JSONObject, key: String): JSONObject? {
            if (!payload.has(key)) return null
            return payload.opt(key) as? JSONObject ?: fail("$key must be an object")
        }

        private fun unique(ids: List<String>, kind: String) {
            check(ids.toSet().size == ids.size, "$kind ids must be unique")
        }

        private fun check(condition: Boolean, reason: String) {
            if (!condition) fail(reason)
        }
    }

    /** Longest enum-like wire string a body field accepts. */
    private const val MAX_ID_LIKE = 32

    private class InvalidBody(val detail: String) : RuntimeException()

    private fun fail(reason: String): Nothing = throw InvalidBody(reason)
}
