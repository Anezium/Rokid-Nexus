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
) {
    private var pending: PhonePluginPrincipal? = null
    private var active: PhonePluginPrincipal? = null

    fun open(principal: PhonePluginPrincipal): Boolean {
        active?.takeIf { it.grantKey() != principal.grantKey() }?.let { closePrincipal(it, "switch") }
        pending?.takeIf { it.grantKey() != principal.grantKey() }?.let(runtime::unbind)
        pending = principal
        if (!runtime.bind(principal)) {
            pending = null
            return false
        }
        val timeoutKey = timeoutKey(principal)
        scheduler.cancel(timeoutKey)
        scheduler.schedule(timeoutKey, REGISTRATION_TIMEOUT_MS) {
            if (pending?.grantKey() == principal.grantKey()) {
                pending = null
                runtime.hideOwnedSurfaces(principal.descriptor.id)
                runtime.unbind(principal)
                logger("external plugin registration timed out plugin=${principal.descriptor.id}")
            }
        }
        if (runtime.isRegistered(principal)) onRegistered(principal.grantKey())
        return true
    }

    fun onRegistered(key: PluginGrantKey) {
        val principal = pending?.takeIf { it.grantKey() == key } ?: return
        scheduler.cancel(timeoutKey(principal))
        pending = null
        active = principal
        if (!deliver(principal, BusPaths.PLUGIN_OPEN, "open")) {
            closePrincipal(principal, "open_failed")
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
            scheduler.cancel(timeoutKey(principal))
            runtime.unbind(principal)
        }
        pending = null
    }

    fun onRevoked(key: PluginGrantKey) {
        pending?.takeIf { it.grantKey() == key }?.let { principal ->
            scheduler.cancel(timeoutKey(principal))
            runtime.hideOwnedSurfaces(principal.descriptor.id)
            runtime.unbind(principal)
            pending = null
        }
        active?.takeIf { it.grantKey() == key }?.let { closePrincipal(it, "revoked") }
    }

    fun onBinderDied(key: PluginGrantKey) {
        pending?.takeIf { it.grantKey() == key }?.let { principal ->
            scheduler.cancel(timeoutKey(principal))
            pending = null
            runtime.hideOwnedSurfaces(principal.descriptor.id)
            runtime.unbind(principal)
        }
        active?.takeIf { it.grantKey() == key }?.let { principal ->
            active = null
            runtime.hideOwnedSurfaces(principal.descriptor.id)
            runtime.unbind(principal)
        }
    }

    fun onPackageUnavailable(packageName: String) {
        pending?.takeIf { it.packageName == packageName }?.let { principal ->
            scheduler.cancel(timeoutKey(principal))
            pending = null
            runtime.hideOwnedSurfaces(principal.descriptor.id)
            runtime.unbind(principal)
        }
        active?.takeIf { it.packageName == packageName }?.let { principal ->
            closePrincipal(principal, "package_unavailable")
        }
    }

    private fun closePrincipal(principal: PhonePluginPrincipal, reason: String) {
        deliver(principal, BusPaths.PLUGIN_CLOSE, reason)
        runtime.hideOwnedSurfaces(principal.descriptor.id)
        runtime.unbind(principal)
        if (active?.grantKey() == principal.grantKey()) active = null
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

    private fun timeoutKey(principal: PhonePluginPrincipal): String =
        "registration:${principal.packageName}:${principal.descriptor.id}"

    companion object {
        const val REGISTRATION_TIMEOUT_MS = 5_000L
    }
}
