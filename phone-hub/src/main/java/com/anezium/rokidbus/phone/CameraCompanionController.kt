package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONObject
import java.util.UUID

interface CameraCompanionRuntime {
    fun bind(principal: PhonePluginPrincipal): Boolean
    fun isRegistered(principal: PhonePluginPrincipal): Boolean
    fun deliver(principal: PhonePluginPrincipal, path: String, id: String, payload: JSONObject): Boolean
    fun deliverBinary(
        principal: PhonePluginPrincipal,
        path: String,
        id: String,
        payload: JSONObject,
        data: ByteArray,
    ): Boolean = false
    fun unbind(principal: PhonePluginPrincipal)
}

class CameraCompanionController(
    private val runtime: CameraCompanionRuntime,
    private val scheduler: ExternalPluginScheduler,
    private val resolveApprovedConsumer: () -> PhonePluginPrincipal?,
    private val logger: (String) -> Unit = {},
) {
    private data class Session(
        val sessionId: String,
        val principal: PhonePluginPrincipal,
        val pending: MutableList<BusEnvelope> = mutableListOf(),
        var openDelivered: Boolean = false,
    )

    private var active: Session? = null
    private val terminalSessionIds = linkedSetOf<String>()

    @Synchronized
    fun onRemoteEnvelope(envelope: BusEnvelope): Boolean = when (envelope.path) {
        BusPaths.CAMERA_SESSION_STATE -> {
            handleSessionState(envelope)
            true
        }
        BusPaths.CAMERA_LINK_OFFER -> {
            handleCameraEnvelope(envelope)
            true
        }
        BusPaths.CAMERA_FREEZE_IMAGE_CHUNK -> {
            handleCameraEnvelope(envelope)
            true
        }
        else -> false
    }

    @Synchronized
    fun onRegistered(principal: PhonePluginPrincipal) {
        val session = active?.takeIf { it.principal.grantKey() == principal.grantKey() } ?: return
        if (session.openDelivered) return
        scheduler.cancel(timeoutKey(session.sessionId))
        if (!deliverLifecycle(session, BusPaths.PLUGIN_OPEN, "camera_session_open")) {
            terminate(session, "open_failed")
            return
        }
        session.openDelivered = true
        val pending = session.pending.toList()
        session.pending.clear()
        pending.forEach { message ->
            if (!forward(session, message)) {
                terminate(session, "forward_failed")
                return
            }
        }
    }

    /** The plugin SDK drops any message whose payload lacks its own pluginId. */
    private fun forward(session: Session, envelope: BusEnvelope): Boolean {
        val payload = envelope.payload.put("pluginId", session.principal.descriptor.id)
        val binary = envelope.binary
        return if (binary == null) {
            runtime.deliver(session.principal, envelope.path, envelope.id, payload)
        } else {
            runtime.deliverBinary(session.principal, envelope.path, envelope.id, payload, binary)
        }
    }

    private fun forward(
        session: Session,
        path: String,
        id: String,
        payload: JSONObject,
    ): Boolean = forward(session, BusEnvelope(path = path, id = id, payload = payload))

    @Synchronized
    fun onLinkLost() {
        active?.let { terminate(it, "link_lost") }
    }

    @Synchronized
    fun onRevoked(key: PluginGrantKey) {
        active?.takeIf { it.principal.grantKey() == key }?.let { terminate(it, "revoked") }
    }

    @Synchronized
    fun onPackageUnavailable(packageName: String) {
        active?.takeIf { it.principal.packageName == packageName }?.let {
            terminate(it, "package_unavailable")
        }
    }

    @Synchronized
    fun onBinderDied(key: PluginGrantKey) {
        active?.takeIf { it.principal.grantKey() == key }?.let { terminate(it, "binder_died") }
    }

    @Synchronized
    fun close() {
        active?.let { terminate(it, "hub_destroyed") }
    }

    @Synchronized
    fun activeSessionId(): String? = active?.sessionId

    private fun handleSessionState(envelope: BusEnvelope) {
        val sessionId = envelope.payload.optString("sessionId")
        if (sessionId.isBlank()) return
        when (envelope.payload.optString("state")) {
            "opened" -> openSession(sessionId, envelope)
            "closed" -> closeSession(sessionId, envelope)
        }
    }

    private fun openSession(sessionId: String, envelope: BusEnvelope) {
        if (sessionId in terminalSessionIds || active?.sessionId == sessionId) return
        active?.let { terminate(it, "replaced") }
        val principal = resolveApprovedConsumer()
        if (principal == null) {
            rememberTerminal(sessionId)
            logger("camera session ignored session=$sessionId reason=no_approved_consumer")
            return
        }
        val session = Session(sessionId, principal).apply { pending += envelope.snapshot() }
        active = session
        if (!runtime.bind(principal)) {
            terminate(session, "bind_failed")
            return
        }
        scheduler.schedule(timeoutKey(sessionId), REGISTRATION_TIMEOUT_MS) {
            synchronized(this) {
                active?.takeIf { it.sessionId == sessionId && !it.openDelivered }?.let {
                    terminate(it, "registration_timeout")
                }
            }
        }
        if (runtime.isRegistered(principal)) onRegistered(principal)
    }

    private fun closeSession(sessionId: String, envelope: BusEnvelope) {
        rememberTerminal(sessionId)
        val session = active?.takeIf { it.sessionId == sessionId } ?: return
        if (session.openDelivered) {
            forward(session, envelope.path, envelope.id, envelope.payload)
        }
        terminate(session, "session_closed")
    }

    private fun handleCameraEnvelope(envelope: BusEnvelope) {
        val sessionId = envelope.payload.optString("sessionId")
        val session = active?.takeIf { it.sessionId == sessionId } ?: return
        if (session.openDelivered) {
            if (!forward(session, envelope)) terminate(session, "forward_failed")
        } else {
            val binary = envelope.binary
            if (binary != null) {
                val pendingBytes = session.pending.sumOf { it.binary?.size ?: 0 }
                val pendingChunks = session.pending.count { it.binary != null }
                if (pendingBytes + binary.size > MAX_PENDING_BINARY_BYTES ||
                    pendingChunks >= MAX_PENDING_BINARY_CHUNKS
                ) {
                    terminate(session, "pending_binary_limit")
                    return
                }
            }
            session.pending += envelope.snapshot()
        }
    }

    private fun terminate(session: Session, reason: String) {
        if (active !== session) return
        scheduler.cancel(timeoutKey(session.sessionId))
        deliverLifecycle(session, BusPaths.PLUGIN_CLOSE, reason)
        runtime.unbind(session.principal)
        rememberTerminal(session.sessionId)
        active = null
        logger("camera companion closed session=${session.sessionId} reason=$reason")
    }

    private fun deliverLifecycle(session: Session, path: String, type: String): Boolean {
        val id = UUID.randomUUID().toString()
        return runtime.deliver(
            session.principal,
            path,
            id,
            JSONObject()
                .put("version", 1)
                .put("type", type)
                .put("id", id)
                .put("pluginId", session.principal.descriptor.id)
                .put("sessionId", session.sessionId),
        )
    }

    private fun rememberTerminal(sessionId: String) {
        terminalSessionIds += sessionId
        while (terminalSessionIds.size > MAX_TERMINAL_SESSION_IDS) {
            terminalSessionIds.remove(terminalSessionIds.first())
        }
    }

    private fun timeoutKey(sessionId: String): String = "camera-registration:$sessionId"

    private fun BusEnvelope.snapshot(): BusEnvelope = copy(
        payload = JSONObject(payload.toString()),
        binary = binary?.copyOf(),
    )

    companion object {
        const val REGISTRATION_TIMEOUT_MS = 5_000L
        private const val MAX_PENDING_BINARY_BYTES = 8 * 1024 * 1024
        private const val MAX_PENDING_BINARY_CHUNKS = 64
        private const val MAX_TERMINAL_SESSION_IDS = 64
    }
}
