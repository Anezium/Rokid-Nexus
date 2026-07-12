package com.anezium.rokidbus.client.plugin

import android.content.Context
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
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
    @Volatile private var currentLinkState = 0
    @Volatile private var hubCapabilities = 0

    val isApproved: Boolean
        get() = registrationState == PluginRegistrationResult.APPROVED

    fun hasCapability(capability: PluginCapability): Boolean =
        isApproved && capability in approvedCapabilities

    val supportsImageSurface: Boolean
        get() = currentLinkState and LinkStateBits.SPP_DATA_UP != 0 &&
            hubCapabilities and BusCapabilityBits.IMAGE_SURFACE != 0

    fun connect() {
        check(!closed) { "NexusPluginClient is closed" }
        transport.connect(this)
    }

    fun send(path: String, id: String, payload: JSONObject): Boolean {
        if (closed || !isApproved) return false
        return transport.send(path, id, payload)
    }

    internal fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray): Boolean {
        if (closed || !isApproved) return false
        val sent = transport.sendBinary(path, id, payload, data)
        if (!sent) {
            currentLinkState = currentLinkState and LinkStateBits.SPP_DATA_UP.inv()
            hubCapabilities = transport.capabilities()
        }
        return sent
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
        if (closed) return
        currentLinkState = state
        hubCapabilities = transport.capabilities()
        callbacks.onLinkState(state)
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
        currentLinkState = 0
        hubCapabilities = 0
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
