package com.anezium.rokidbus.client.plugin

import android.content.Context
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.CapabilityParseResult
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONObject
import java.util.ArrayDeque

class NexusPluginClient internal constructor(
    private val pluginId: String,
    private val callbacks: NexusPluginCallbacks,
    private val transport: NexusPluginTransport,
) : NexusPluginTransport.Listener, AutoCloseable {
    private val seenEventIds = ArrayDeque<String>()
    private val seenEventIdSet = linkedSetOf<String>()
    private var registrationState = PluginRegistrationResult.REGISTRATION_FAILED
    private var opened = false
    private var closed = false
    private var approvedCapabilities: Set<PluginCapability> = emptySet()

    val isApproved: Boolean
        get() = registrationState == PluginRegistrationResult.APPROVED

    fun hasCapability(capability: PluginCapability): Boolean =
        isApproved && capability in approvedCapabilities

    fun connect() {
        check(!closed) { "NexusPluginClient is closed" }
        transport.connect(this)
    }

    fun send(path: String, id: String, payload: JSONObject): Boolean {
        if (closed || !isApproved) return false
        return transport.send(path, id, payload)
    }

    override fun onRegistrationState(result: Int) {
        if (closed) return
        registrationState = result
        if (result != PluginRegistrationResult.APPROVED) approvedCapabilities = emptySet()
        callbacks.onRegistrationState(result)
        if (result != PluginRegistrationResult.APPROVED && opened) {
            opened = false
            callbacks.onClose()
        }
    }

    override fun onLinkState(state: Int) {
        if (!closed) callbacks.onLinkState(state)
    }

    override fun onMessage(path: String, id: String, payload: JSONObject) {
        if (closed || payload.optString("pluginId") != pluginId || !rememberEvent(id)) return
        when (path) {
            BusPaths.PLUGIN_OPEN -> if (!opened && isApproved) {
                opened = true
                callbacks.onOpen()
            }
            BusPaths.PLUGIN_CLOSE -> if (opened) {
                opened = false
                callbacks.onClose()
            }
            BusPaths.PLUGIN_INPUT -> if (opened && isApproved) {
                callbacks.onInput(
                    NexusInputEvent(
                        surfaceId = payload.optString("localSurfaceId", payload.optString("surfaceId")),
                        keyCode = payload.optInt("keyCode"),
                        action = payload.optInt("action"),
                    ),
                )
            }
            BusPaths.PLUGIN_REGISTRATION -> {
                // A fresh registration means the hub has no open session with us (it just
                // (re)accepted this client), so a stale `opened` from a previous hub life
                // must not swallow the next PLUGIN_OPEN.
                if (opened) {
                    opened = false
                    callbacks.onClose()
                }
                val result = payload.optInt("result", PluginRegistrationResult.REGISTRATION_FAILED)
                val parsed = PluginCapability.parseList(payload.optString("capabilities"))
                approvedCapabilities = if (parsed is CapabilityParseResult.Valid) {
                    parsed.capabilities
                } else {
                    emptySet()
                }
                onRegistrationState(result)
            }
            else -> if (isApproved) callbacks.onMessage(path, id, payload)
        }
    }

    override fun onError(message: String) = Unit

    override fun close() {
        if (closed) return
        closed = true
        if (opened) {
            opened = false
            callbacks.onClose()
        }
        transport.close()
        seenEventIds.clear()
        seenEventIdSet.clear()
    }

    private fun rememberEvent(id: String): Boolean {
        if (id.isBlank() || !seenEventIdSet.add(id)) return false
        seenEventIds += id
        while (seenEventIds.size > MAX_SEEN_EVENTS) {
            seenEventIdSet.remove(seenEventIds.removeFirst())
        }
        return true
    }

    companion object {
        private const val MAX_SEEN_EVENTS = 128

        fun create(
            context: Context,
            pluginId: String,
            callbacks: NexusPluginCallbacks,
            hubTarget: HubTarget = HubTarget.PHONE,
        ): NexusPluginClient = NexusPluginClient(
            pluginId = pluginId,
            callbacks = callbacks,
            transport = AndroidNexusPluginTransport(context, pluginId, hubTarget),
        )
    }
}
