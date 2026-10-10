package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONArray
import org.json.JSONObject

/**
 * A family of typed actions the user may approve to run on one tap, per plugin. The approval is
 * trust in that plugin: the hubs check owner, revision and type, never what the callback does.
 */
enum class QuickActionFamily(val wireValue: String) {
    CHECKLIST("checklist"),
    MEDIA("media"),
    ;

    companion object {
        fun fromWireValue(value: String): QuickActionFamily? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * The hub's vocabulary of typed actions. A plugin attaches one to a page or activity action so the
 * hub knows what it means: an explicit desired state, never a toggle. Only [quickEligible] types
 * may ever skip the confirmation, and only once the user approved their [family] for that plugin.
 */
sealed interface PageActionType {
    val wireType: String

    /** The quick-action family, or null for a type that is never quick. */
    val family: QuickActionFamily?

    val quickEligible: Boolean get() = family != null

    val isMedia: Boolean get() = family == QuickActionFamily.MEDIA

    /** Set one checklist entry to [checked]; the desired state, bound to the revision shown. */
    data class SetChecked(val checked: Boolean) : PageActionType {
        override val wireType: String get() = PageActionTypeContract.TYPE_SET_CHECKED
        override val family: QuickActionFamily get() = QuickActionFamily.CHECKLIST
    }

    data object MediaPrevious : PageActionType {
        override val wireType: String get() = PageActionTypeContract.TYPE_MEDIA_PREVIOUS
        override val family: QuickActionFamily get() = QuickActionFamily.MEDIA
    }

    data object MediaPlay : PageActionType {
        override val wireType: String get() = PageActionTypeContract.TYPE_MEDIA_PLAY
        override val family: QuickActionFamily get() = QuickActionFamily.MEDIA
    }

    data object MediaPause : PageActionType {
        override val wireType: String get() = PageActionTypeContract.TYPE_MEDIA_PAUSE
        override val family: QuickActionFamily get() = QuickActionFamily.MEDIA
    }

    data object MediaNext : PageActionType {
        override val wireType: String get() = PageActionTypeContract.TYPE_MEDIA_NEXT
        override val family: QuickActionFamily get() = QuickActionFamily.MEDIA
    }

    /** Hand the composer's text to the plugin. Never quick: only the composer's own Send reaches it. */
    data object ComposeSend : PageActionType {
        override val wireType: String get() = PageActionTypeContract.TYPE_COMPOSE_SEND
        override val family: QuickActionFamily? get() = null
    }
}

/** How an action's `typed` field read. */
sealed interface PageActionTypeRead {
    data object Absent : PageActionTypeRead
    data class Known(val type: PageActionType) : PageActionTypeRead

    /** A well-formed `{type}` object this build does not know; the action stays an ordinary one. */
    data class Unknown(val wireType: String) : PageActionTypeRead
    data class Invalid(val reason: String) : PageActionTypeRead
}

/** Wire codec for [PageActionType]: `{"type": "...", ...}`. */
object PageActionTypeContract {
    const val FIELD_TYPED = "typed"
    const val TYPE_SET_CHECKED = "setChecked"
    const val TYPE_MEDIA_PREVIOUS = "media.previous"
    const val TYPE_MEDIA_PLAY = "media.play"
    const val TYPE_MEDIA_PAUSE = "media.pause"
    const val TYPE_MEDIA_NEXT = "media.next"
    const val TYPE_COMPOSE_SEND = "compose.send"

    /** Longest type name accepted even as unknown; anything longer is malformed. */
    const val MAX_TYPE_CHARS = 32

    fun encode(type: PageActionType): JSONObject = JSONObject().put("type", type.wireType).apply {
        if (type is PageActionType.SetChecked) put("checked", type.checked)
    }

    /** Reads the `typed` field of [action]; absent and JSON null are both [PageActionTypeRead.Absent]. */
    fun readField(action: JSONObject): PageActionTypeRead =
        if (!action.has(FIELD_TYPED)) PageActionTypeRead.Absent else read(action.opt(FIELD_TYPED))

    fun read(value: Any?): PageActionTypeRead {
        if (value == null || value == JSONObject.NULL) return PageActionTypeRead.Absent
        val typed = value as? JSONObject ?: return PageActionTypeRead.Invalid("typed must be an object")
        val type = typed.opt("type") as? String ?: return PageActionTypeRead.Invalid("typed.type must be a string")
        if (!PageSurfaceContract.isValidId(type) || type.length > MAX_TYPE_CHARS) {
            return PageActionTypeRead.Invalid("typed.type is malformed")
        }
        return when (type) {
            TYPE_SET_CHECKED -> when (val checked = typed.opt("checked")) {
                is Boolean -> PageActionTypeRead.Known(PageActionType.SetChecked(checked))
                else -> PageActionTypeRead.Invalid("setChecked needs a boolean checked")
            }
            TYPE_MEDIA_PREVIOUS -> PageActionTypeRead.Known(PageActionType.MediaPrevious)
            TYPE_MEDIA_PLAY -> PageActionTypeRead.Known(PageActionType.MediaPlay)
            TYPE_MEDIA_PAUSE -> PageActionTypeRead.Known(PageActionType.MediaPause)
            TYPE_MEDIA_NEXT -> PageActionTypeRead.Known(PageActionType.MediaNext)
            TYPE_COMPOSE_SEND -> PageActionTypeRead.Known(PageActionType.ComposeSend)
            else -> PageActionTypeRead.Unknown(type)
        }
    }

    /** A known type, or null for anything absent, unknown or malformed. */
    fun parseOrNull(value: Any?): PageActionType? = (read(value) as? PageActionTypeRead.Known)?.type
}

/** The user's quick-action approvals, as the phone sends them to the glasses. */
data class QuickActionPolicy(
    val revision: Long,
    /** Approved families by plugin id; a plugin absent from the map has none. */
    val approvals: Map<String, Set<QuickActionFamily>>,
) {
    fun approves(pluginId: String, family: QuickActionFamily): Boolean = approvals[pluginId]?.contains(family) == true

    companion object {
        val NONE = QuickActionPolicy(revision = 0, approvals = emptyMap())
    }
}

/**
 * Quick actions are a user decision per plugin, never a capability: a plugin declares the families
 * it offers in its descriptor metadata, the user approves them on the phone, and the phone stamps
 * `hubQuick` on qualifying actions and sends this policy to the glasses. Unknown families in either
 * place are ignored, so a newer plugin or phone never invalidates an older hub.
 */
object QuickActionPolicyContract {
    /** Descriptor metadata: comma-separated [QuickActionFamily] wire values. Not a capability. */
    const val META_PLUGIN_QUICK_ACTIONS = "rokidbus.plugin.quickActions"

    /** Phone-stamped on a page or activity action that may run without a confirmation. */
    const val FIELD_HUB_QUICK = "hubQuick"

    /** More plugins than any phone holds; past it the policy is malformed rather than truncated. */
    const val MAX_PLUGINS = 128

    /** The families a descriptor declares; unknown, blank and repeated entries are ignored. */
    fun parseDeclaredFamilies(value: String?): Set<QuickActionFamily> = value.orEmpty()
        .split(',', ';', '\n')
        .mapNotNull { QuickActionFamily.fromWireValue(it.trim().lowercase()) }
        .toCollection(linkedSetOf())

    /**
     * Whether an action carrying [type] may be stamped quick for a plugin whose user approved
     * [approved]. The descriptor declaration is checked by the caller when it builds [approved].
     */
    fun isQuick(type: PageActionType?, approved: Set<QuickActionFamily>): Boolean {
        val family = type?.family ?: return false
        return family in approved
    }

    fun toPayload(policy: QuickActionPolicy): JSONObject = JSONObject()
        .put("revision", policy.revision)
        .put(
            "approvals",
            JSONObject().apply {
                policy.approvals.toSortedMap().forEach { (pluginId, families) ->
                    if (families.isNotEmpty()) {
                        put(pluginId, JSONArray().apply { families.sortedBy { it.wireValue }.forEach { put(it.wireValue) } })
                    }
                }
            },
        )

    /** The policy, or null when it is malformed; a plugin with no known family is simply absent. */
    fun parse(payload: JSONObject): QuickActionPolicy? {
        val revision = nonnegativeLong(payload.opt("revision")) ?: return null
        val approvals = payload.opt("approvals") as? JSONObject ?: return null
        if (approvals.length() > MAX_PLUGINS) return null
        val parsed = linkedMapOf<String, Set<QuickActionFamily>>()
        for (pluginId in approvals.keys()) {
            if (!PluginDescriptor.isValidId(pluginId)) return null
            val array = approvals.opt(pluginId) as? JSONArray ?: return null
            val families = linkedSetOf<QuickActionFamily>()
            for (index in 0 until array.length()) {
                val wire = array.opt(index) as? String ?: return null
                QuickActionFamily.fromWireValue(wire)?.let(families::add)
            }
            if (families.isNotEmpty()) parsed[pluginId] = families
        }
        return QuickActionPolicy(revision, parsed)
    }

    private fun nonnegativeLong(value: Any?): Long? = when (value) {
        is Int -> value.toLong().takeIf { it >= 0 }
        is Long -> value.takeIf { it >= 0 }
        else -> null
    }
}
