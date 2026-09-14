package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONObject

enum class AssistantTakeoverAction(val wireValue: String) {
    STATUS("status"),
    SET("set"),
    ;

    companion object {
        fun fromWireValue(value: String): AssistantTakeoverAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** A parsed, valid request: `enabled` is present exactly when [action] is [AssistantTakeoverAction.SET]. */
data class AssistantTakeoverRequest(
    val action: AssistantTakeoverAction,
    val enabled: Boolean? = null,
)

/**
 * Whether the assist button hands over to the approved assistant plugin.
 *
 * The `assistant` capability grant says a plugin *may* replace Rokid's assistant; this switch
 * says whether it does *right now*. They are kept apart on purpose: a plugin can ask to step
 * back, and ask to step in again, without ever touching its own grant — which is a decision
 * the wearer makes on the phone, with the plugin's identity in front of them.
 */
object AssistantTakeoverContract {
    const val VERSION = 1
    const val ERROR_INVALID_REQUEST = "INVALID_REQUEST"

    fun statusRequest(): JSONObject = request(AssistantTakeoverAction.STATUS)

    fun setRequest(enabled: Boolean): JSONObject =
        request(AssistantTakeoverAction.SET).put("enabled", enabled)

    /** Null on an unknown version or action, or a `set` that does not say which way. */
    fun parseRequest(payload: JSONObject): AssistantTakeoverRequest? {
        if (payload.optInt("version", -1) != VERSION) return null
        val action = AssistantTakeoverAction.fromWireValue(payload.optString("action")) ?: return null
        return when (action) {
            AssistantTakeoverAction.STATUS -> AssistantTakeoverRequest(action)
            AssistantTakeoverAction.SET -> {
                if (payload.opt("enabled") !is Boolean) return null
                AssistantTakeoverRequest(action, payload.getBoolean("enabled"))
            }
        }
    }

    fun reply(pluginId: String, enabled: Boolean): JSONObject {
        require(PluginDescriptor.isValidId(pluginId)) { "Invalid plugin id" }
        return JSONObject()
            .put("version", VERSION)
            .put("pluginId", pluginId)
            .put("enabled", enabled)
    }

    /** The switch position a reply reports, or null when the payload is not a v1 reply. */
    fun replyEnabled(payload: JSONObject): Boolean? {
        if (payload.optInt("version", -1) != VERSION) return null
        return payload.opt("enabled") as? Boolean
    }

    private fun request(action: AssistantTakeoverAction): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("action", action.wireValue)
}
