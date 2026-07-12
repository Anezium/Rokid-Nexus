package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONObject
import java.util.UUID

interface ExternalPluginRuntime {
    fun bind(principal: PhonePluginPrincipal): Boolean
    fun isRegistered(principal: PhonePluginPrincipal): Boolean
    fun deliver(principal: PhonePluginPrincipal, path: String, id: String, payload: JSONObject): Boolean
    fun hideOwnedSurfaces(pluginId: String)
    fun unbind(principal: PhonePluginPrincipal)
}

interface ExternalPluginScheduler {
    fun schedule(key: String, delayMs: Long, action: () -> Unit)
    fun cancel(key: String)
}

class ExternalPluginController(
    private val runtime: ExternalPluginRuntime,
    private val scheduler: ExternalPluginScheduler,
    private val logger: (String) -> Unit = {},
    private val onRegisteredPrincipal: (PhonePluginPrincipal) -> Unit = {},
    private val onForegroundChanged: () -> Unit = {},
) {
    private var pending: PhonePluginPrincipal? = null
    private var active: PhonePluginPrincipal? = null
        set(value) {
            val changed = field?.grantKey() != value?.grantKey()
            field = value
            if (changed) onForegroundChanged()
        }
    private var openGeneration = 0L
    private var automaticRebindAttempted = false

    fun open(principal: PhonePluginPrincipal): Boolean {
        active?.takeIf { it.grantKey() != principal.grantKey() }?.let { closePrincipal(it, "switch") }
        pending?.takeIf { it.grantKey() != principal.grantKey() }?.let { previous ->
            cancelWatchdogs(previous)
            runtime.unbind(previous)
        }
        cancelWatchdogs(principal)
        openGeneration += 1
        automaticRebindAttempted = false
        return beginColdOpen(principal, openGeneration)
    }

    private fun beginColdOpen(principal: PhonePluginPrincipal, generation: Long): Boolean {
        pending = principal
        if (!runtime.bind(principal)) {
            pending = null
            return false
        }
        val timeoutKey = registrationTimeoutKey(principal)
        scheduler.cancel(timeoutKey)
        scheduler.schedule(timeoutKey, REGISTRATION_TIMEOUT_MS) {
            if (generation == openGeneration && pending?.grantKey() == principal.grantKey()) {
                pending = null
                if (automaticRebindAttempted) {
                    giveUpOpen(principal, generation)
                } else {
                    runtime.hideOwnedSurfaces(principal.descriptor.id)
                    runtime.unbind(principal)
                    logger("external plugin registration timed out plugin=${principal.descriptor.id}")
                }
            }
        }
        if (runtime.isRegistered(principal)) onRegistered(principal)
        return true
    }

    fun onRegistered(principal: PhonePluginPrincipal) {
        onPluginActivity(principal.descriptor.id)
        val pendingPrincipal = pending?.takeIf { it.grantKey() == principal.grantKey() }
        if (pendingPrincipal == null) {
            onRegisteredPrincipal(principal)
            return
        }
        scheduler.cancel(registrationTimeoutKey(pendingPrincipal))
        pending = null
        active = pendingPrincipal
        if (!deliverOpen(pendingPrincipal, "open", openGeneration)) {
            if (automaticRebindAttempted) {
                giveUpOpen(pendingPrincipal, openGeneration)
            } else {
                closePrincipal(pendingPrincipal, "open_failed")
            }
        } else {
            onRegisteredPrincipal(pendingPrincipal)
        }
    }

    fun input(
        ownerPluginId: String,
        localSurfaceId: String,
        keyCode: Int,
        action: Int,
    ): Boolean {
        val principal = active?.takeIf { it.descriptor.id == ownerPluginId } ?: return false
        return deliver(
            principal,
            BusPaths.PLUGIN_INPUT,
            "input",
            JSONObject()
                .put("localSurfaceId", localSurfaceId)
                .put("keyCode", keyCode)
                .put("action", action),
        )
    }

    fun closeActive(reason: String = "close") {
        active?.let { closePrincipal(it, reason) }
        pending?.let { principal ->
            cancelWatchdogs(principal)
            runtime.unbind(principal)
        }
        pending = null
    }

    fun activeId(): String? = active?.descriptor?.id

    fun activeDisplayName(): String? = active?.descriptor?.displayName

    /**
     * A plugin that shows a surface while the HUD is idle becomes the foreground
     * plugin. This remains a containment path for third-party plugins that violate
     * the hub-initiated lifecycle: PLUGIN_OPEN lets inputs through the SDK gate and
     * keeps the open/close lifecycle balanced.
     */
    fun adopt(principal: PhonePluginPrincipal): Boolean {
        if (active?.grantKey() == principal.grantKey()) return true
        if (active != null) return false
        if (!runtime.isRegistered(principal)) return false
        cancelWatchdogs(principal)
        openGeneration += 1
        automaticRebindAttempted = false
        active = principal
        if (!deliverOpen(principal, "adopted", openGeneration)) {
            closePrincipal(principal, "adopt_failed")
            return false
        }
        logger("external plugin adopted as foreground plugin=${principal.descriptor.id}")
        return true
    }

    /** Any valid surface traffic, plus registration, acknowledges the latest PLUGIN_OPEN. */
    fun onPluginActivity(pluginId: String) {
        active?.takeIf { it.descriptor.id == pluginId }?.let { principal ->
            scheduler.cancel(openAckTimeoutKey(principal))
        }
    }

    /**
     * A plugin that hides its own last surface (BACK on the HUD) is closed, not paused:
     * without the PLUGIN_CLOSE the SDK-side `opened` flag stays true and every later
     * launcher open is silently dropped as a duplicate.
     */
    fun onPluginSelfHid(pluginId: String) {
        val principal = active?.takeIf { it.descriptor.id == pluginId } ?: return
        cancelWatchdogs(principal)
        active = null
        deliver(principal, BusPaths.PLUGIN_CLOSE, "self_hidden")
        runtime.unbind(principal)
        logger("external plugin self-closed plugin=$pluginId")
    }

    fun onRevoked(key: PluginGrantKey) {
        pending?.takeIf { it.grantKey() == key }?.let { principal ->
            cancelWatchdogs(principal)
            runtime.hideOwnedSurfaces(principal.descriptor.id)
            runtime.unbind(principal)
            pending = null
        }
        active?.takeIf { it.grantKey() == key }?.let { closePrincipal(it, "revoked") }
    }

    fun onBinderDied(key: PluginGrantKey) {
        pending?.takeIf { it.grantKey() == key }?.let { principal ->
            cancelWatchdogs(principal)
            pending = null
            runtime.hideOwnedSurfaces(principal.descriptor.id)
            runtime.unbind(principal)
        }
        active?.takeIf { it.grantKey() == key }?.let { principal ->
            cancelWatchdogs(principal)
            active = null
            runtime.hideOwnedSurfaces(principal.descriptor.id)
            runtime.unbind(principal)
        }
    }

    fun onPackageUnavailable(packageName: String) {
        pending?.takeIf { it.packageName == packageName }?.let { principal ->
            cancelWatchdogs(principal)
            pending = null
            runtime.hideOwnedSurfaces(principal.descriptor.id)
            runtime.unbind(principal)
        }
        active?.takeIf { it.packageName == packageName }?.let { principal ->
            closePrincipal(principal, "package_unavailable")
        }
    }

    private fun closePrincipal(principal: PhonePluginPrincipal, reason: String) {
        cancelWatchdogs(principal)
        deliver(principal, BusPaths.PLUGIN_CLOSE, reason)
        runtime.hideOwnedSurfaces(principal.descriptor.id)
        runtime.unbind(principal)
        if (active?.grantKey() == principal.grantKey()) active = null
        if (pending?.grantKey() == principal.grantKey()) pending = null
    }

    private fun deliverOpen(
        principal: PhonePluginPrincipal,
        type: String,
        generation: Long,
    ): Boolean {
        if (!deliver(principal, BusPaths.PLUGIN_OPEN, type)) return false
        val timeoutKey = openAckTimeoutKey(principal)
        scheduler.cancel(timeoutKey)
        scheduler.schedule(timeoutKey, OPEN_ACK_TIMEOUT_MS) {
            if (generation == openGeneration && active?.grantKey() == principal.grantKey()) {
                onOpenAckTimedOut(principal, generation)
            }
        }
        return true
    }

    private fun onOpenAckTimedOut(principal: PhonePluginPrincipal, generation: Long) {
        if (automaticRebindAttempted) {
            giveUpOpen(principal, generation)
            return
        }
        automaticRebindAttempted = true
        logger("external plugin open unacknowledged plugin=${principal.descriptor.id}; rebinding")
        active = null
        runtime.unbind(principal)
        if (!beginColdOpen(principal, generation)) giveUpOpen(principal, generation)
    }

    private fun giveUpOpen(principal: PhonePluginPrincipal, generation: Long) {
        if (generation != openGeneration) return
        cancelWatchdogs(principal)
        pending = null
        active = null
        runtime.hideOwnedSurfaces(principal.descriptor.id)
        runtime.unbind(principal)
        logger("external plugin open failed plugin=${principal.descriptor.id}")
    }

    private fun deliver(
        principal: PhonePluginPrincipal,
        path: String,
        type: String,
        extra: JSONObject = JSONObject(),
    ): Boolean {
        val id = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("version", 1)
            .put("type", type)
            .put("id", id)
            .put("pluginId", principal.descriptor.id)
        extra.keys().forEach { key -> payload.put(key, extra.get(key)) }
        return runtime.deliver(
            principal,
            path,
            id,
            payload,
        )
    }

    private fun cancelWatchdogs(principal: PhonePluginPrincipal) {
        scheduler.cancel(registrationTimeoutKey(principal))
        scheduler.cancel(openAckTimeoutKey(principal))
    }

    private fun registrationTimeoutKey(principal: PhonePluginPrincipal): String =
        "registration:${principal.packageName}:${principal.descriptor.id}"

    private fun openAckTimeoutKey(principal: PhonePluginPrincipal): String =
        "open-ack:${principal.packageName}:${principal.descriptor.id}"

    companion object {
        const val REGISTRATION_TIMEOUT_MS = 5_000L
        const val OPEN_ACK_TIMEOUT_MS = 4_000L
    }
}
