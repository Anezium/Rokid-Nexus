package com.anezium.rokidbus.glasses.audio

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.GlassesAudioRelayContract as C
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal interface AudioCaptureConnection : Closeable {
    val input: InputStream
    val output: OutputStream
}

internal interface AudioCaptureAttachment : Closeable {
    val token: ByteArray
    fun start(): Boolean
    fun accept(): AudioCaptureConnection
}

internal class GlassesAudioRelaySession(
    private val capture: () -> AudioCaptureAttachment,
    private val send: (BusEnvelope) -> Boolean,
    private val closePeer: () -> Unit,
    private val execute: (() -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val nowMs: () -> Long,
) : Closeable {
    enum class State { AWAITING_START, ATTACHING_CAPTURE, STREAMING, CLOSED }
    private val lock = Any()
    @Volatile var state = State.AWAITING_START
        private set
    private var deadline = nowMs() + C.START_TIMEOUT_MS
    private var lastPeer = nowMs()
    private var lastLocalKeepalive = nowMs()
    private var lastStatus = nowMs()
    private var attachment: AudioCaptureAttachment? = null
    private var local: AudioCaptureConnection? = null
    private var cancelTick: (() -> Unit)? = null
    @Volatile private var lastSeq = -1L
    @Volatile private var silent = false

    fun begin() { cancelTick = schedule(250, ::tick) }

    fun receive(envelope: BusEnvelope) {
        if (state == State.CLOSED) return
        if (envelope.v != C.VERSION || envelope.binary != null) { finish("UNSUPPORTED"); return }
        val awaiting = synchronized(lock) { lastPeer = nowMs(); state == State.AWAITING_START }
        if (awaiting) {
            val start = C.validateStart(envelope.payload)
            if (envelope.path != BusPaths.CORE_AUDIO_RELAY_START || start !is C.ValidationResult.Valid || C.OPUS !in start.value.codecs) {
                finish("UNSUPPORTED"); return
            }
            synchronized(lock) {
                if (state != State.AWAITING_START) return
                state = State.ATTACHING_CAPTURE
                deadline = nowMs() + C.CAPTURE_ATTACH_TIMEOUT_MS
            }
            execute(::attach)
        } else when (envelope.path) {
            BusPaths.CORE_AUDIO_RELAY_STOP -> if (envelope.payload.length() == 0) close() else finish("UNSUPPORTED")
            BusPaths.CORE_AUDIO_RELAY_KEEPALIVE -> if (envelope.payload.length() != 0) finish("UNSUPPORTED")
            else -> finish("UNSUPPORTED")
        }
    }

    private fun attach() {
        var authenticated = false
        try {
            val pending = capture()
            synchronized(lock) {
                if (state == State.CLOSED) { pending.close(); return }
                attachment = pending
            }
            if (!pending.start()) { finish("BRIDGE_UNAVAILABLE"); return }
            val connection = pending.accept()
            synchronized(lock) {
                if (state == State.CLOSED) { connection.close(); return }
                local = connection
            }
            val token = ByteArray(32)
            DataInputStream(connection.input).readFully(token)
            if (!MessageDigest.isEqual(token, pending.token)) { finish("BRIDGE_UNAVAILABLE"); return }
            authenticated = true
            val header = LocalCaptureProtocol.read(connection.input)
            check(header?.type == LocalCaptureProtocol.HEADER)
            val ready = JSONObject(String(header!!.body, Charsets.UTF_8)).put("v", C.VERSION)
            val validated = C.validateReady(ready)
            check(validated is C.ValidationResult.Valid && validated.value.codec == C.OPUS)
            if (!send(BusEnvelope(BusPaths.CORE_AUDIO_RELAY_READY, payload = ready))) { close(); return }
            synchronized(lock) {
                if (state == State.CLOSED) return
                state = State.STREAMING
            }
            val packets = mutableListOf<ByteArray>()
            var firstPacketMs = 0L
            fun flush() {
                if (packets.isEmpty()) return
                check(send(C.frame(++lastSeq, firstPacketMs, packets)))
                packets.clear()
            }
            while (state != State.CLOSED) {
                val record = LocalCaptureProtocol.read(connection.input) ?: error("Capture EOF")
                when (record.type) {
                    LocalCaptureProtocol.PACKET -> {
                        if (packets.isEmpty()) firstPacketMs = nowMs()
                        packets += record.body
                        if (packets.size == C.PACKETS_PER_FRAME) flush()
                    }
                    LocalCaptureProtocol.STATUS -> {
                        val status = JSONObject(String(record.body, Charsets.UTF_8)).get("silent") as Boolean
                        if (status) flush()
                        silent = status
                        check(sendStatus())
                    }
                    else -> error("Unexpected capture record")
                }
            }
        } catch (_: Exception) {
            finish(if (!authenticated) "BRIDGE_UNAVAILABLE" else "CAPTURE_FAILED")
        }
    }

    private fun sendStatus(): Boolean = send(BusEnvelope(BusPaths.CORE_AUDIO_RELAY_STATUS, payload = C.status(lastSeq, silent)))

    private fun tick() {
        val now = nowMs()
        val expired = synchronized(lock) {
            when {
                state == State.CLOSED -> return
                state == State.AWAITING_START && now >= deadline -> "START"
                state == State.ATTACHING_CAPTURE && now >= deadline -> "BRIDGE_UNAVAILABLE"
                now - lastPeer >= C.PEER_TIMEOUT_MS -> "PEER"
                else -> null
            }
        }
        if (expired != null) { finish(expired.takeUnless { it == "START" || it == "PEER" }); return }
        if (now - lastLocalKeepalive >= C.LOCAL_KEEPALIVE_INTERVAL_MS) {
            lastLocalKeepalive = now
            val connection = synchronized(lock) { local }
            if (connection != null) execute {
                runCatching { LocalCaptureProtocol.write(connection.output, LocalCaptureProtocol.KEEPALIVE) }
                    .onFailure { finish("CAPTURE_FAILED") }
            }
        }
        if (state == State.STREAMING && now - lastStatus >= C.KEEPALIVE_INTERVAL_MS) {
            lastStatus = now
            execute { if (state == State.STREAMING && !sendStatus()) close() }
        }
        if (state != State.CLOSED) cancelTick = schedule(250, ::tick)
    }

    override fun close() = finish(null)

    private fun finish(error: String?) {
        val resources = synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            cancelTick?.invoke()
            local to attachment
        }
        runCatching { resources.first?.close() }
        runCatching { resources.second?.close() }
        // A blocked error write must never hold the speaker muted or keep RFCOMM alive.
        if (error == null) closePeer() else {
            schedule(250) { closePeer() }
            execute {
                try { send(BusEnvelope(BusPaths.CORE_AUDIO_RELAY_ERROR, payload = C.error(error))) }
                finally { closePeer() }
            }
        }
    }
}
