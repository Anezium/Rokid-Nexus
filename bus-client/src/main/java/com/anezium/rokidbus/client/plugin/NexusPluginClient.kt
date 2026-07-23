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
    private val audioSessionLock = Any()
    private var registrationState = PluginRegistrationResult.REGISTRATION_FAILED
    private var opened = false
    private var closed = false
    private var approvedCapabilities: Set<PluginCapability> = emptySet()
    private var registeredAudioSession: NexusAudioSession? = null
    private var audioSessionApiUsed = false
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

    internal fun isApprovedForAudio(): Boolean = !closed && isApproved

    internal fun registerAudioSession(session: NexusAudioSession): Boolean =
        synchronized(audioSessionLock) {
            if (closed || registeredAudioSession?.let { it !== session } == true) {
                false
            } else {
                registeredAudioSession = session
                audioSessionApiUsed = true
                true
            }
        }

    internal fun unregisterAudioSession(session: NexusAudioSession) {
        synchronized(audioSessionLock) {
            if (registeredAudioSession === session) registeredAudioSession = null
        }
    }

    internal fun sendAudioAcquire(session: NexusAudioSession, id: String): Boolean {
        if (synchronized(audioSessionLock) { registeredAudioSession !== session }) return false
        return send(NEXUS_AUDIO_LEASE_ACQUIRE_PATH, id, JSONObject())
    }

    internal fun sendAudioRelease(
        session: NexusAudioSession,
        id: String,
        leaseId: String,
    ): Boolean {
        if (synchronized(audioSessionLock) { registeredAudioSession !== session }) return false
        return send(
            NEXUS_AUDIO_LEASE_RELEASE_PATH,
            id,
            JSONObject().put("leaseId", leaseId),
        )
    }

    internal fun releaseAudioSession() {
        currentAudioSession()?.terminate(
            reason = NexusAudioStopReason.RELEASED,
            releaseActiveLease = true,
        )
    }

    override fun onRegistrationState(result: Int) {
        if (closed) return
        registrationState = result
        if (result != PluginRegistrationResult.APPROVED) {
            approvedCapabilities = emptySet()
            terminateAudioSession(
                reason = NexusAudioStopReason.ERROR,
                releaseActiveLease = false,
            )
        }
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

    override fun onGlassesAiButton(active: Boolean) {
        if (closed) return
        callbacks.onGlassesAiButton(active)
    }

    override fun onMessage(path: String, id: String, payload: JSONObject) {
        if (closed || payload.optString("pluginId") != pluginId || !rememberEvent(id)) return
        if (routeAudioMessage(path, payload)) return
        when (path) {
            // A duplicate PLUGIN_OPEN (fresh event id) is the hub asking an already-open
            // plugin to re-present itself — e.g. the glasses fell back to the launcher
            // while the hub still considers the session open. onOpen implementations
            // reset and re-show, which also acknowledges the hub's open watchdog.
            BusPaths.PLUGIN_OPEN -> if (isApproved) {
                opened = true
                callbacks.onOpen()
            }
            BusPaths.PLUGIN_CLOSE -> if (opened) {
                opened = false
                releaseAudioSession()
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

    override fun onBinary(path: String, id: String, payload: JSONObject, data: ByteArray) {
        if (closed || !isApproved || payload.optString("pluginId") != pluginId || !rememberEvent(id)) return
        if (routeAudioBinary(path, payload, data)) return
        callbacks.onBinary(path, id, payload, data)
    }

    override fun onError(message: String) = Unit

    override fun close() {
        if (closed) return
        closed = true
        terminateAudioSession(
            reason = NexusAudioStopReason.ERROR,
            releaseActiveLease = false,
        )
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

    private fun routeAudioMessage(path: String, payload: JSONObject): Boolean {
        if (path != NEXUS_AUDIO_LEASE_ACQUIRE_REPLY_PATH &&
            path != NEXUS_AUDIO_LEASE_RELEASE_REPLY_PATH &&
            path != NEXUS_AUDIO_LEASE_REVOKED_PATH
        ) {
            return false
        }
        val (session, consume) = synchronized(audioSessionLock) {
            registeredAudioSession to audioSessionApiUsed
        }
        when (path) {
            NEXUS_AUDIO_LEASE_ACQUIRE_REPLY_PATH -> session?.onAcquireReply(payload)
            NEXUS_AUDIO_LEASE_RELEASE_REPLY_PATH -> session?.onReleaseReply(payload)
            NEXUS_AUDIO_LEASE_REVOKED_PATH -> session?.onRevoked(payload)
        }
        return consume
    }

    private fun routeAudioBinary(path: String, payload: JSONObject, data: ByteArray): Boolean {
        if (path != NEXUS_AUDIO_FRAMES_PATH) return false
        val (session, consume) = synchronized(audioSessionLock) {
            registeredAudioSession to audioSessionApiUsed
        }
        session?.onAudioFrame(payload, data)
        return consume
    }

    private fun currentAudioSession(): NexusAudioSession? =
        synchronized(audioSessionLock) { registeredAudioSession }

    private fun terminateAudioSession(
        reason: NexusAudioStopReason,
        releaseActiveLease: Boolean,
    ) {
        currentAudioSession()?.terminate(reason, releaseActiveLease)
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
