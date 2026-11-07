package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.SppAuthProtocol
import com.anezium.rokidbus.shared.GlassesAudioRelayContract as C
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal interface PhoneAudioRelayConnection : Closeable {
    val input: InputStream
    val output: OutputStream
    fun authenticate(nonces: SppAuthProtocol.RecentNonces): SppAuthProtocol.Session
}

internal interface AudioRelayPlayback : Closeable {
    fun offer(seq: Long, packets: List<ByteArray>)
}

/** Dedicated reader and writer. No bus router, link-state setter or CXR audio fallback is available here. */
internal class PhoneAudioRelayClient(
    private val connection: () -> PhoneAudioRelayConnection,
    private val player: (C.Ready, () -> Unit) -> AudioRelayPlayback,
    private val status: (PhoneAudioRelayStatus) -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val nowMs: () -> Long,
    private val launch: (() -> Unit) -> Unit = { Thread(it, "rokidbus-audio").apply { isDaemon = true }.start() },
    private val waitForRetry: (SppReconnectBackoff, Long) -> Unit = { backoff, revision -> backoff.awaitRetry(revision) },
) : Closeable {
    private class Attempt(val wire: PhoneAudioRelayConnection) {
        val closed = AtomicBoolean()
        val writePending = AtomicBoolean()
        @Volatile var session: SppAuthProtocol.Session? = null
        @Volatile var player: AudioRelayPlayback? = null
        @Volatile var ready = false
        @Volatile var lastPeer = 0L
        @Volatile var lastKeepalive = 0L
        @Volatile var readyDeadline = 0L
        @Volatile var cancelTick: (() -> Unit)? = null
    }
    private val lock = Any()
    private val writes = Executors.newSingleThreadExecutor()
    private val nonces = SppAuthProtocol.RecentNonces()
    private val backoff = SppReconnectBackoff()
    private val running = AtomicBoolean()
    @Volatile private var desired = false
    @Volatile private var closed = false
    @Volatile private var current: Attempt? = null

    fun setDesired(value: Boolean) {
        val previous = synchronized(lock) {
            if (closed && value) return
            if (desired == value) return
            desired = value
            current
        }
        backoff.reset()
        if (!value) previous?.let {
            // Queue a best-effort stop first; closing must not wait for a blocked writer.
            runCatching { writes.execute { runCatching { it.session?.write(it.wire.output, BusEnvelope(BusPaths.CORE_AUDIO_RELAY_STOP)) } } }
            retire(it)
        } else startLoop()
    }

    private fun startLoop() {
        if (!running.compareAndSet(false, true)) return
        launch {
            try {
                while (desired && !closed) {
                    val revision = backoff.beginAttempt()
                    var attempt: Attempt? = null
                    try {
                        statusIfDesired(PhoneAudioRelayStatus.CONNECTING)
                        val candidate = Attempt(connection())
                        attempt = candidate
                        synchronized(lock) {
                            if (!desired || closed) { candidate.wire.close(); break }
                            current = candidate
                        }
                        check(desired && !closed && !candidate.closed.get())
                        val session = candidate.wire.authenticate(nonces)
                        check(desired && !closed && !candidate.closed.get())
                        candidate.session = session
                        candidate.lastPeer = nowMs()
                        candidate.lastKeepalive = nowMs()
                        candidate.readyDeadline = nowMs() + C.START_TIMEOUT_MS
                        candidate.cancelTick = schedule(250) { tick(candidate) }
                        session.write(candidate.wire.output, BusEnvelope(BusPaths.CORE_AUDIO_RELAY_START, payload = C.start()))
                        var lastSeq = -1L
                        while (!candidate.closed.get() && desired) {
                            val envelope = session.read(candidate.wire.input) ?: break
                            check(envelope.v == C.VERSION)
                            candidate.lastPeer = nowMs()
                            if (envelope.path == BusPaths.CORE_AUDIO_RELAY_ERROR) { reportError(candidate, envelope); break }
                            if (!candidate.ready) {
                                check(envelope.path == BusPaths.CORE_AUDIO_RELAY_READY && envelope.binary == null)
                                val ready = C.validateReady(envelope.payload)
                                check(ready is C.ValidationResult.Valid && ready.value.codec == C.OPUS)
                                val playback = player(ready.value) { retire(candidate) }
                                candidate.player = playback
                                if (candidate.closed.get() || !desired) { playback.close(); break }
                                candidate.ready = true
                                backoff.connected()
                                publish(candidate, PhoneAudioRelayStatus.ACTIVE)
                            } else when (envelope.path) {
                                BusPaths.CORE_AUDIO_RELAY_FRAME -> {
                                    val frame = C.validateFrame(envelope)
                                    check(frame is C.ValidationResult.Valid)
                                    val seq = envelope.payload.getLong("seq")
                                    check(seq > lastSeq)
                                    lastSeq = seq
                                    candidate.player!!.offer(seq, frame.value)
                                }
                                BusPaths.CORE_AUDIO_RELAY_STATUS -> check(envelope.binary == null && C.validateStatus(envelope.payload) is C.ValidationResult.Valid)
                                else -> error("Unexpected audio envelope")
                            }
                        }
                    } catch (_: Exception) {
                        // Peer data and codec buffers never enter logs.
                    } finally {
                        attempt?.let(::retire)
                        synchronized(lock) { if (current === attempt) current = null }
                    }
                    if (desired && !closed) waitForRetry(backoff, revision)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                running.set(false)
                if (desired && !closed) startLoop()
            }
        }
    }

    private fun reportError(attempt: Attempt, envelope: BusEnvelope) {
        check(envelope.binary == null)
        val error = C.validateError(envelope.payload)
        check(error is C.ValidationResult.Valid)
        if (error.value == "BRIDGE_UNAVAILABLE") publish(attempt, PhoneAudioRelayStatus.SETUP_NEEDED)
    }

    private fun tick(attempt: Attempt) {
        if (attempt.closed.get()) return
        val now = nowMs()
        if ((!attempt.ready && now >= attempt.readyDeadline) || now - attempt.lastPeer >= C.PEER_TIMEOUT_MS) {
            retire(attempt); return
        }
        if (now - attempt.lastKeepalive >= C.KEEPALIVE_INTERVAL_MS && attempt.writePending.compareAndSet(false, true)) {
            attempt.lastKeepalive = now
            writes.execute {
                try {
                    if (!attempt.closed.get()) attempt.session!!.write(attempt.wire.output, BusEnvelope(BusPaths.CORE_AUDIO_RELAY_KEEPALIVE))
                } catch (_: Exception) { retire(attempt) }
                finally { attempt.writePending.set(false) }
            }
        }
        if (!attempt.closed.get()) attempt.cancelTick = schedule(250) { tick(attempt) }
    }

    private fun publish(attempt: Attempt, value: PhoneAudioRelayStatus) = synchronized(lock) {
        if (current === attempt && desired && !closed && !attempt.closed.get()) status(value)
    }
    private fun statusIfDesired(value: PhoneAudioRelayStatus) = synchronized(lock) { if (desired && !closed) status(value) }
    private fun retire(attempt: Attempt) {
        if (!attempt.closed.compareAndSet(false, true)) return
        attempt.cancelTick?.invoke()
        runCatching { attempt.wire.close() }
        runCatching { attempt.player?.close() }
    }
    override fun close() {
        closed = true
        setDesired(false)
        current?.let(::retire)
        backoff.reset()
        writes.shutdownNow()
    }
}
