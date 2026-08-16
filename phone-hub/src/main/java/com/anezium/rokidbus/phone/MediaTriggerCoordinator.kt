package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths

/**
 * Hub side of the ambient media trigger: bridges a [MediaPlaybackTrigger] to the plugin
 * lifecycle through [externalPluginController]. Pure Kotlin so the open/close contract can
 * be driven by tests without Android.
 *
 * Playback edges arrive from the media-trigger service via [onPlaybackChanged]; the trigger
 * decides open/close; this coordinator resolves which plugin to open (a registered media-trigger
 * plugin, currently the lyrics plugin) and routes to the controller.
 *
 * A close is issued only through the trigger's grace path, which already defers while the plugin
 * owns a visible surface. This layer never calls `externalPluginController.closeActive`
 * independently of a trigger decision.
 */
class MediaTriggerCoordinator(
    clock: () -> Long,
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

    /** Feed a play/pause/stop edge from the media-trigger service. */
    fun onPlaybackChanged(playing: Boolean) {
        trigger.onPlaybackChanged(playing)
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
        externalPluginController.closeActive(PLUGIN_MEDIA_TRIGGER_CLOSE_REASON)
        isHoldingOpen = false
        logger("media trigger: closing plugin after idle grace")
    }

    companion object {
        /** Alias for the shared wire token, kept for callers that pair open/close type. */
        const val PLUGIN_MEDIA_TRIGGER_OPEN_TYPE = BusPaths.PLUGIN_OPEN_TYPE_MEDIA_TRIGGER
        const val PLUGIN_MEDIA_TRIGGER_CLOSE_REASON = "media_idle"
    }
}