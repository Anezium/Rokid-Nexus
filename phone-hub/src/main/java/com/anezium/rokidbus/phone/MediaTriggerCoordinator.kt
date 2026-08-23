package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths

/**
 * Hub side of the ambient media trigger: bridges a [MediaPlaybackTrigger] to the plugin
 * lifecycle through [externalPluginController]. Pure Kotlin so the open/close contract can
 * be driven by tests without Android.
 *
 * Playback edges arrive from the media-trigger service via [onPlaybackChanged]; the trigger
 * decides open/close; this coordinator resolves which plugin to open (a registered media-trigger
 * plugin, currently the lyrics plugin) and routes to the controller. Close observations can
 * re-establish that background open while playback continues, subject to a cooldown.
 *
 * A close is issued only through the trigger's grace path, which already defers while the plugin
 * owns a visible surface. This layer never calls `externalPluginController.closeActive`
 * independently of a trigger decision.
 */
class MediaTriggerCoordinator(
    private val clock: () -> Long,
    private val externalPluginController: ExternalPluginController,
    private val resolveRegisteredPlugin: () -> PhonePluginPrincipal?,
    private val pluginOwnsVisibleSurface: () -> Boolean = { false },
    private val logger: (String) -> Unit = {},
    private val graceMs: Long = MediaPlaybackTrigger.DEFAULT_GRACE_MS,
) {
    private val trigger = MediaPlaybackTrigger(
        now = clock,
        onOpen = { openRegisteredPlugin() },
        onClose = { closeTriggeredPlugin() },
        ownsVisibleSurface = pluginOwnsVisibleSurface,
        graceMs = graceMs,
    )

    /** True once a media trigger opened a plugin, until the grace close lands. */
    var isHoldingOpen: Boolean = false
        private set

    private var latestPlaybackIsPlaying = false
    private var lastReopenAtMs: Long? = null

    /** Feed a play/pause/stop edge from the media-trigger service. */
    fun onPlaybackChanged(playing: Boolean) {
        latestPlaybackIsPlaying = playing
        trigger.onPlaybackChanged(playing)
    }

    /** Re-establish the ambient owner after its foreground surface closes during playback. */
    fun onPluginClosed(pluginId: String, reason: String) {
        val principal = resolveRegisteredPlugin() ?: return
        if (principal.descriptor.id != pluginId) return

        isHoldingOpen = false
        if (!latestPlaybackIsPlaying) return
        if (
            reason == PLUGIN_MEDIA_TRIGGER_CLOSE_REASON ||
            reason == PLUGIN_SWITCH_CLOSE_REASON ||
            reason == BUILT_IN_PLUGIN_SWITCH_CLOSE_REASON
        ) {
            return
        }

        val now = clock()
        val previousReopenAt = lastReopenAtMs
        if (previousReopenAt != null && now - previousReopenAt < REOPEN_COOLDOWN_MS) {
            logger("media trigger: reopen suppressed by cooldown plugin=$pluginId")
            return
        }
        lastReopenAtMs = now
        isHoldingOpen = externalPluginController.open(
            principal,
            ExternalPluginOpenRequest(type = BusPaths.PLUGIN_OPEN_TYPE_MEDIA_TRIGGER),
        )
        if (isHoldingOpen) {
            logger("media trigger: reopening plugin=$pluginId after close reason=$reason")
        } else {
            logger("media trigger: reopen failed plugin=$pluginId after close reason=$reason")
        }
    }

    /** From the service's grace timer. */
    fun tickGrace() {
        trigger.tickGrace()
    }

    private fun openRegisteredPlugin() {
        val principal = resolveRegisteredPlugin() ?: run {
            logger("media trigger: no registered widget plugin, skipping open")
            return
        }
        isHoldingOpen = externalPluginController.open(
            principal,
            ExternalPluginOpenRequest(type = BusPaths.PLUGIN_OPEN_TYPE_MEDIA_TRIGGER),
        )
        logger("media trigger: opening plugin=${principal.descriptor.id}")
    }

    private fun closeTriggeredPlugin() {
        if (!isHoldingOpen) return
        val registered = resolveRegisteredPlugin()
        val stillOurs = registered != null &&
            externalPluginController.activeId() == registered.descriptor.id
        if (stillOurs) {
            externalPluginController.closeActive(PLUGIN_MEDIA_TRIGGER_CLOSE_REASON)
            logger("media trigger: closing plugin after idle grace")
        } else {
            logger("media trigger: dropping hold; active plugin is no longer the trigger target")
        }
        isHoldingOpen = false
    }

    companion object {
        /** Alias for the shared wire token, kept for callers that pair open/close type. */
        const val PLUGIN_MEDIA_TRIGGER_OPEN_TYPE = BusPaths.PLUGIN_OPEN_TYPE_MEDIA_TRIGGER
        const val PLUGIN_MEDIA_TRIGGER_CLOSE_REASON = "media_idle"
        private const val PLUGIN_SWITCH_CLOSE_REASON = "switch"
        // PhonePluginRegistry uses this wire reason for the same user-switch intent.
        private const val BUILT_IN_PLUGIN_SWITCH_CLOSE_REASON = "built_in_opened"

        /** Bounds recovery churn when a plugin crashes or immediately closes itself. */
        const val REOPEN_COOLDOWN_MS = 10_000L
    }
}
