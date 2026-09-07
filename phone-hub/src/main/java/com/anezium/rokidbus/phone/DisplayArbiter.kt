package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PathRules
import org.json.JSONObject

/** Wearer preferences constrain presentation independently of capability grants. */
enum class DisplayTier {
    PIN,
    NOTICE,
    ACTIVITY,
    SURFACE,
    AMBIENT,
    WAKE,
}

sealed interface DisplayDecision {
    data object Allow : DisplayDecision
    data class Deny(val code: String) : DisplayDecision
}

object DisplayArbiter {
    const val ERROR_SURFACE_BUSY = "SURFACE_BUSY"
    const val ERROR_DISPLAY_MUTED = "DISPLAY_MUTED"

    const val AMBIENT_WIDGET_SHOW = BusPaths.WIDGET_SHOW
    const val AMBIENT_WIDGET_UPDATE = BusPaths.WIDGET_UPDATE

    fun tierFor(path: String): DisplayTier? {
        val normalized = PathRules.normalizeAbsolute(path) ?: return null
        return when (normalized) {
            BusPaths.PIN_SHOW -> DisplayTier.PIN
            BusPaths.NOTICE_SHOW, BusPaths.NOTICE_UPDATE -> DisplayTier.NOTICE
            BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_UPDATE -> DisplayTier.ACTIVITY
            BusPaths.SURFACE_SHOW, BusPaths.SURFACE_UPDATE,
            BusPaths.INK_SHOW, BusPaths.INK_UPDATE,
            -> DisplayTier.SURFACE
            AMBIENT_WIDGET_SHOW, AMBIENT_WIDGET_UPDATE -> DisplayTier.AMBIENT
            else -> null
        }
    }

    fun decide(
        policy: PluginDisplayPolicy,
        tier: DisplayTier,
        holdsForeground: Boolean = false,
    ): DisplayDecision {
        val allowed = when (tier) {
            DisplayTier.PIN, DisplayTier.ACTIVITY, DisplayTier.AMBIENT ->
                policy == PluginDisplayPolicy.NORMAL || policy == PluginDisplayPolicy.DEMOTE
            DisplayTier.NOTICE -> policy != PluginDisplayPolicy.MUTE
            DisplayTier.SURFACE -> when (policy) {
                PluginDisplayPolicy.NORMAL -> true
                // User-open already made this plugin the foreground owner; that
                // path is not demote. Auto-show on an idle HUD still fails.
                PluginDisplayPolicy.DEMOTE -> holdsForeground
                PluginDisplayPolicy.NOTICES, PluginDisplayPolicy.MUTE -> false
            }
            DisplayTier.WAKE ->
                policy == PluginDisplayPolicy.NORMAL || policy == PluginDisplayPolicy.NOTICES
        }
        if (allowed) return DisplayDecision.Allow
        return DisplayDecision.Deny(denialCode(policy, tier))
    }

    fun allowsWake(policy: PluginDisplayPolicy): Boolean =
        decide(policy, DisplayTier.WAKE) is DisplayDecision.Allow

    fun applyNoticeWake(policy: PluginDisplayPolicy, payload: JSONObject): JSONObject {
        if (allowsWake(policy) || !payload.optBoolean("wakeDisplay", false)) {
            return payload
        }
        return JSONObject(payload.toString()).apply { remove("wakeDisplay") }
    }

    fun applyPresentationPolicy(policy: PluginDisplayPolicy, path: String, payload: JSONObject): JSONObject {
        val copy = JSONObject(payload.toString())
        if (tierFor(path) in setOf(DisplayTier.SURFACE, DisplayTier.NOTICE)) {
            copy.put("displayWakeAllowed", allowsWake(policy))
        }
        if (!allowsWake(policy)) {
            copy.remove("wakeDisplay")
            copy.remove("holdDisplay")
        }
        return copy
    }

    private fun denialCode(policy: PluginDisplayPolicy, tier: DisplayTier): String {
        val takingDisplay = tier == DisplayTier.SURFACE &&
            (policy == PluginDisplayPolicy.DEMOTE || policy == PluginDisplayPolicy.NOTICES)
        return if (takingDisplay) ERROR_SURFACE_BUSY else ERROR_DISPLAY_MUTED
    }
}
