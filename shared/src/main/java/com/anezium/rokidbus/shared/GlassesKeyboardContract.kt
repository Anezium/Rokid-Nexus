package com.anezium.rokidbus.shared

import org.json.JSONObject

/**
 * Lets the phone read, and on the owner's tap change, which input method the glasses use. The
 * phone's Keyboard & remote screen only reaches a glasses field through Nexus's own keyboard, and
 * the Rokid companion app can select its own keyboard again at any time; onboarding selects
 * Nexus's only when nothing else is, so it never takes the choice back from the owner by itself.
 */
object GlassesKeyboardContract {
    const val VERSION = 1

    const val ACTION_STATUS = "status"
    const val ACTION_USE_NEXUS = "use_nexus"

    /** The glasses hub lacks WRITE_SECURE_SETTINGS; the owner's Repair re-grants it. */
    const val ERROR_PERMISSION_MISSING = "permission_missing"
    const val ERROR_FAILED = "failed"

    fun requestToJson(action: String): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("action", action)

    /** Null for an action this build does not know, which the glasses must refuse. */
    fun actionFromRequest(payload: JSONObject?): String? {
        val json = payload ?: return null
        if (json.optInt("version", 0) < 1) return null
        return json.optString("action").takeIf { it == ACTION_STATUS || it == ACTION_USE_NEXUS }
    }

    fun replyToJson(reply: GlassesKeyboardReply): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("nexusSelected", reply.nexusSelected)
        .put("canSwitch", reply.canSwitch)
        .apply {
            reply.currentPackage?.let { put("currentPackage", it) }
            reply.error?.let { put("error", it) }
        }

    /** Null for a reply this build cannot parse, so version skew reads as "no answer". */
    fun fromReply(payload: JSONObject?): GlassesKeyboardReply? {
        val json = payload ?: return null
        if (json.optInt("version", 0) < 1) return null
        val selected = json.opt("nexusSelected") as? Boolean ?: return null
        return GlassesKeyboardReply(
            nexusSelected = selected,
            canSwitch = json.optBoolean("canSwitch", false),
            currentPackage = json.optString("currentPackage").takeIf(String::isNotBlank),
            error = json.optString("error").takeIf(String::isNotBlank),
        )
    }
}

/**
 * [currentPackage] names the selected keyboard's app so the phone can say whose it is; null when
 * none is selected. [error] is set only when [GlassesKeyboardContract.ACTION_USE_NEXUS] failed.
 */
data class GlassesKeyboardReply(
    val nexusSelected: Boolean,
    val canSwitch: Boolean,
    val currentPackage: String? = null,
    val error: String? = null,
)
