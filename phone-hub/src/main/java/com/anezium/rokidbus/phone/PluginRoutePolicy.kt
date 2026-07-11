package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.plugin.PathRules
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONObject

sealed interface PluginRouteCaller {
    data object Internal : PluginRouteCaller
    data object DebugLegacy : PluginRouteCaller
    data object Unregistered : PluginRouteCaller
    data object Ambiguous : PluginRouteCaller
    data object Pending : PluginRouteCaller
    data object Revoked : PluginRouteCaller
    data class Plugin(
        val pluginId: String,
        val grantedCapabilities: Set<PluginCapability>,
    ) : PluginRouteCaller
}

sealed interface PluginRouteDecision {
    data object Allowed : PluginRouteDecision
    data class Denied(val code: String) : PluginRouteDecision
}

object PluginRoutePolicy {
    private val localSurfaceIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun authorize(caller: PluginRouteCaller, path: String): PluginRouteDecision {
        if (caller == PluginRouteCaller.Internal || caller == PluginRouteCaller.DebugLegacy) {
            return PluginRouteDecision.Allowed
        }
        when (caller) {
            PluginRouteCaller.Unregistered -> return PluginRouteDecision.Denied("UNREGISTERED_CALLER")
            PluginRouteCaller.Ambiguous -> return PluginRouteDecision.Denied("AMBIGUOUS_CALLER")
            PluginRouteCaller.Pending -> return PluginRouteDecision.Denied("PENDING_APPROVAL")
            PluginRouteCaller.Revoked -> return PluginRouteDecision.Denied("REVOKED")
            else -> Unit
        }
        val plugin = caller as PluginRouteCaller.Plugin
        val normalized = PathRules.normalizeAbsolute(path)
            ?: return PluginRouteDecision.Denied("INVALID_PATH")
        val required = PathRules.requiredCapability(normalized)
        if (required != null) {
            return if (required in plugin.grantedCapabilities) {
                PluginRouteDecision.Allowed
            } else {
                PluginRouteDecision.Denied("CAPABILITY_REQUIRED_${required.wireValue.uppercase()}")
            }
        }
        if (PathRules.isReserved(normalized)) {
            return PluginRouteDecision.Denied("SYSTEM_ROUTE_DENIED")
        }
        return if (PathRules.isPluginPrivate(normalized, plugin.pluginId)) {
            PluginRouteDecision.Allowed
        } else {
            PluginRouteDecision.Denied("PLUGIN_NAMESPACE_DENIED")
        }
    }

    fun injectSurfaceOwner(pluginId: String, payload: JSONObject): JSONObject? {
        val localSurfaceId = payload.optString("surfaceId")
        if (!localSurfaceIdPattern.matches(localSurfaceId)) return null
        return JSONObject(payload.toString())
            .put("surfaceId", "$pluginId:$localSurfaceId")
            .put("localSurfaceId", localSurfaceId)
            .put("ownerPluginId", pluginId)
    }
}
