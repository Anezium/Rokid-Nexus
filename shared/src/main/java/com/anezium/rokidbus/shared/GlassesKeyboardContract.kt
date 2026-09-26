package com.anezium.rokidbus.shared

import org.json.JSONObject

/**
 * Lets the phone read and change which input method the glasses use. The phone's Keyboard &
 * remote screen only reaches a glasses field through Nexus's own keyboard, and the Rokid companion
 * app can select its own keyboard again at any time. The owner's keep switch is persisted on the
 * glasses, because they must take the keyboard back at boot and whenever it changes, with or
 * without the phone around.
 */
object GlassesKeyboardContract {
    const val VERSION = 1

    const val ACTION_STATUS = "status"
    const val ACTION_USE_NEXUS = "use_nexus"
    const val ACTION_SET_KEEP = "set_keep"

    /** An owner who never touched the switch keeps Nexus's keyboard: absent config reads as on. */
    const val DEFAULT_KEEP_NEXUS = true

    /** The glasses hub lacks WRITE_SECURE_SETTINGS, which only the glasses setup grants. */
    const val ERROR_PERMISSION_MISSING = "permission_missing"
    const val ERROR_FAILED = "failed"

    fun requestToJson(request: GlassesKeyboardRequest): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("action", request.action)
        .apply { request.keep?.let { put("keep", it) } }

    /**
     * Null for an action this build does not know, or a keep change without its boolean, which
     * the glasses must refuse rather than guess.
     */
    fun fromRequest(payload: JSONObject?): GlassesKeyboardRequest? {
        val json = payload ?: return null
        if (json.optInt("version", 0) < 1) return null
        return when (val action = json.optString("action")) {
            ACTION_STATUS, ACTION_USE_NEXUS -> GlassesKeyboardRequest(action)
            ACTION_SET_KEEP -> (json.opt("keep") as? Boolean)?.let { GlassesKeyboardRequest(action, it) }
            else -> null
        }
    }

    fun replyToJson(reply: GlassesKeyboardReply): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("nexusSelected", reply.nexusSelected)
        .put("canSwitch", reply.canSwitch)
        .put("keepNexus", reply.keepNexus)
        .apply {
            reply.currentPackage?.let { put("currentPackage", it) }
            reply.error?.let { put("error", it) }
        }

    /** Null for a reply this build cannot parse, so version skew reads as "no answer". */
    fun fromReply(payload: JSONObject?): GlassesKeyboardReply? {
        val json = payload ?: return null
        if (json.optInt("version", 0) < 1) return null
        val selected = json.opt("nexusSelected") as? Boolean ?: return null
        val canSwitch = json.opt("canSwitch") as? Boolean ?: return null
        val keepNexus = json.opt("keepNexus") as? Boolean ?: return null
        return GlassesKeyboardReply(
            nexusSelected = selected,
            canSwitch = canSwitch,
            keepNexus = keepNexus,
            currentPackage = json.optString("currentPackage").takeIf(String::isNotBlank),
            error = json.optString("error").takeIf(String::isNotBlank),
        )
    }
}

/** [keep] is set only for [GlassesKeyboardContract.ACTION_SET_KEEP]. */
data class GlassesKeyboardRequest(val action: String, val keep: Boolean? = null)

/**
 * [currentPackage] names the selected keyboard's app so the phone can say whose it is; null when
 * none is selected. [error] is set only when switching to Nexus's keyboard failed.
 */
data class GlassesKeyboardReply(
    val nexusSelected: Boolean,
    val canSwitch: Boolean,
    val keepNexus: Boolean = GlassesKeyboardContract.DEFAULT_KEEP_NEXUS,
    val currentPackage: String? = null,
    val error: String? = null,
)
