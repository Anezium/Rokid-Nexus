package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONObject
import java.util.UUID

/** Owns an ambient binding independently of the wearer's foreground plugin. */
class MediaTriggerCoordinator(
    private val clock: () -> Long,
    private val runtime: ExternalPluginRuntime,
    private val scheduler: ExternalPluginScheduler,
    private val resolveRegisteredPlugin: () -> PhonePluginPrincipal?,
    private val foregroundPluginId: () -> String? = { null },
    private val graceMs: Long = 60_000L,
) {
    private var target: PhonePluginPrincipal? = null
    private var pending = false
    private var playing = false
    private var linkAvailable = false
    private var stoppedAt = 0L
    private var retryAfter = 0L
    @Volatile var isHoldingOpen: Boolean = false
        private set

    @Synchronized
    fun onPlaybackChanged(playing: Boolean) {
        if (this.playing && !playing) stoppedAt = clock()
        this.playing = playing
        reconcile()
    }

    @Synchronized
    fun onLinkChanged(available: Boolean) {
        linkAvailable = available
        if (!available) close("link_lost") else reconcile()
    }

    @Synchronized
    fun onListenerLost() {
        playing = false
        close("notification_access_lost")
    }

    @Synchronized
    fun onRegistered(principal: PhonePluginPrincipal) {
        if (!pending || target?.grantKey() != principal.grantKey()) return
        pending = false
        scheduler.cancel(REGISTRATION_KEY)
        if (foregroundPluginId() == principal.descriptor.id || deliverOpen(principal)) {
            isHoldingOpen = true
        } else {
            close("open_failed")
            retryAfter = clock() + REOPEN_COOLDOWN_MS
        }
    }

    @Synchronized
    fun onPluginClosed(pluginId: String, reason: String) {
        val principal = target?.takeIf { it.descriptor.id == pluginId } ?: return
        if (reason in setOf("revoked", "package_unavailable", "binder_died", "open_failed")) {
            close(reason)
            retryAfter = clock() + REOPEN_COOLDOWN_MS
            return
        }
        // PLUGIN_CLOSE ended the SDK session, but the ambient binding still belongs to us.
        // Wait until foreground teardown completes before opening the headless session again.
        isHoldingOpen = false
        scheduler.schedule(REOPEN_KEY, 0L) {
            synchronized(this) {
                if (target?.grantKey() == principal.grantKey()) reconcile()
            }
        }
    }

    @Synchronized
    fun onRevoked(key: PluginGrantKey) {
        if (target?.grantKey() == key) close("revoked")
    }

    @Synchronized
    fun onPackageUnavailable(packageName: String) {
        if (target?.packageName == packageName) close("package_unavailable")
    }

    @Synchronized
    fun refreshDisplayPolicy(pluginId: String) {
        if (target?.descriptor?.id == pluginId) close("display_policy_changed")
        scheduler.schedule(REOPEN_KEY, 0L) { reconcile() }
    }

    @Synchronized
    fun tickGrace() = reconcile()

    @Synchronized
    fun close(reason: String = "hub_stopped") {
        scheduler.cancel(REGISTRATION_KEY)
        scheduler.cancel(REOPEN_KEY)
        val principal = target
        target = null
        pending = false
        isHoldingOpen = false
        if (principal != null) {
            if (foregroundPluginId() != principal.descriptor.id) {
                deliver(principal, BusPaths.PLUGIN_CLOSE, reason)
            }
            runtime.hideOwnedSurfaces(principal.descriptor.id)
            runtime.unbind(principal)
        }
    }

    @Synchronized
    private fun reconcile() {
        val approved = resolveRegisteredPlugin()
        if (target != null && approved?.grantKey() != target?.grantKey()) close("revoked")
        if (!linkAvailable || approved == null) return
        if (!playing) {
            if (clock() - stoppedAt >= graceMs) close(PLUGIN_MEDIA_TRIGGER_CLOSE_REASON)
            return
        }
        if (pending || isHoldingOpen || clock() < retryAfter) return
        target = approved
        pending = true
        if (!runtime.bind(approved)) {
            close("bind_failed")
            retryAfter = clock() + REOPEN_COOLDOWN_MS
            return
        }
        scheduler.schedule(REGISTRATION_KEY, ExternalPluginController.REGISTRATION_TIMEOUT_MS) {
            synchronized(this) {
                if (pending && target?.grantKey() == approved.grantKey()) {
                    close("registration_timeout")
                    retryAfter = clock() + REOPEN_COOLDOWN_MS
                }
            }
        }
        if (runtime.isRegistered(approved)) onRegistered(approved)
    }

    private fun deliverOpen(principal: PhonePluginPrincipal): Boolean =
        deliver(principal, BusPaths.PLUGIN_OPEN, BusPaths.PLUGIN_OPEN_TYPE_MEDIA_TRIGGER)

    private fun deliver(principal: PhonePluginPrincipal, path: String, type: String): Boolean {
        val id = UUID.randomUUID().toString()
        return runtime.deliver(principal, path, id, JSONObject()
            .put("version", 1).put("id", id).put("type", type)
            .put("pluginId", principal.descriptor.id))
    }

    companion object {
        const val PLUGIN_MEDIA_TRIGGER_OPEN_TYPE = BusPaths.PLUGIN_OPEN_TYPE_MEDIA_TRIGGER
        const val PLUGIN_MEDIA_TRIGGER_CLOSE_REASON = "media_idle"
        const val REOPEN_COOLDOWN_MS = 10_000L
        private const val REGISTRATION_KEY = "ambient-media-registration"
        private const val REOPEN_KEY = "ambient-media-reopen"
    }
}
