package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.SppAuthProtocol
import com.anezium.rokidbus.shared.SppKeyProvisioning
import com.anezium.rokidbus.shared.SppPairingKeyStore
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal interface SppPeer : Closeable {
    val bonded: Boolean
    val input: InputStream
    val output: OutputStream
}

/** Owns admission and publication, so a pending socket can never become an outbound route. */
internal class AuthenticatedSppServer(
    private val keys: SppPairingKeyStore,
    private val execute: (() -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val nowMs: () -> Long,
    private val onConnected: (Boolean) -> Unit,
    private val onEnvelope: (BusEnvelope) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private class Connection(val peer: SppPeer, val key: ByteArray) {
        var session: SppAuthProtocol.Session? = null
        var helloReceived = false
        val timeouts = mutableListOf<() -> Unit>()
    }

    private val lock = Any()
    private val callbacks = Any()
    private var announced: Connection? = null // Guarded by callbacks, never used for admission.
    private val recentNonces = SppAuthProtocol.RecentNonces()
    private var pending: Connection? = null
    @Volatile private var active: Connection? = null
    private var lastAttemptMs: Long? = null

    fun isConnected(): Boolean = active != null

    fun accept(peer: SppPeer) {
        var displaced: Connection? = null
        val candidate = synchronized(keys) {
            val key = runCatching { keys.load() }.getOrNull() ?: return@synchronized null
            synchronized(lock) admission@{
                val now = nowMs()
                val last = lastAttemptMs
                if (active != null ||
                    (pending != null && (!peer.bonded || pending!!.peer.bonded)) ||
                    (!peer.bonded && last != null && now - last < 1_000L)
                ) return@admission null
                if (!peer.bonded) lastAttemptMs = now
                displaced = pending
                Connection(peer, key).also { pending = it }
            }
        }
        if (candidate == null) {
            close(peer)
            return
        }
        displaced?.let(::dispose)
        armDeadline(candidate, SppAuthProtocol.HANDSHAKE_TIMEOUT_MS, helloOnly = false)
        armDeadline(candidate, SppAuthProtocol.HELLO_TIMEOUT_MS, helloOnly = true)
        execute { serve(candidate) }
    }

    fun installKey(key: ByteArray) {
        val (retired, replaced) = synchronized(keys) {
            val loaded = runCatching { keys.load() }
            val previous = loaded.getOrNull()
            if (previous != null && MessageDigest.isEqual(previous, key)) return
            if (!keys.save(key)) return
            val retired = synchronized(lock) {
                listOfNotNull(pending, active).also {
                    pending = null
                    active = null
                }
            }
            retired to (loaded.isFailure || previous != null)
        }
        retired.forEach(::dispose)
        log(if (replaced) "SPP pairing key replaced" else "SPP pairing key installed")
        execute { publishState() }
    }

    fun send(envelope: BusEnvelope): Boolean {
        val connection = active ?: return false
        return try {
            connection.session!!.write(connection.peer.output, envelope)
            true
        } catch (_: Exception) {
            retire(connection)
            false
        }
    }

    private fun armDeadline(connection: Connection, delay: Long, helloOnly: Boolean) {
        val cancel = schedule(delay) {
            val expired = synchronized(lock) {
                (pending === connection && (!helloOnly || !connection.helloReceived)).also {
                    if (it) pending = null
                }
            }
            if (expired) dispose(connection)
        }
        val keep = synchronized(lock) {
            (pending === connection).also { if (it) connection.timeouts += cancel }
        }
        if (!keep) cancel()
    }

    private fun serve(connection: Connection) {
        try {
            val peer = connection.peer
            val session = SppAuthProtocol.accept(
                peer.input, peer.output, connection.key, recentNonces,
                onHello = { synchronized(lock) { connection.helloReceived = true } },
            )
            val published = synchronized(lock) {
                (pending === connection).also {
                    if (it) {
                        pending = null
                        connection.session = session
                        active = connection
                    }
                }
            }
            if (!published) return
            cancelDeadlines(connection)
            publishState()
            while (true) {
                val envelope = session.read(peer.input) ?: break
                if (SppKeyProvisioning.isReserved(envelope.path)) continue
                synchronized(callbacks) {
                    // Invalidation prevents new dispatch; an already admitted callback may finish.
                    if (synchronized(lock) { active !== connection }) return
                    log("SPP RX ${envelope.path} id=${envelope.id} payloadBytes=${envelope.payload.toString().length} binaryBytes=${envelope.binary?.size ?: 0}")
                    onEnvelope(envelope)
                }
            }
        } catch (_: Exception) {
            // Authentication failures intentionally carry no peer data into logs.
        } finally {
            retire(connection)
        }
    }

    private fun publishState() = synchronized(callbacks) {
        var current = synchronized(lock) { active }
        if (announced !== current) {
            if (announced != null) {
                announced = null
                onConnected(false)
            }
            current = synchronized(lock) { active }
            if (current != null) {
                announced = current
                onConnected(true)
            }
        }
    }

    private fun retire(connection: Connection) {
        val wasActive = synchronized(lock) {
            if (pending === connection) pending = null
            (active === connection).also { if (it) active = null }
        }
        dispose(connection)
        if (wasActive) execute { publishState() }
    }

    private fun cancelDeadlines(connection: Connection) {
        val cancellations = synchronized(lock) {
            connection.timeouts.toList().also { connection.timeouts.clear() }
        }
        cancellations.forEach { it() }
    }

    private fun dispose(connection: Connection) {
        cancelDeadlines(connection)
        close(connection.peer)
    }

    private fun close(peer: SppPeer) {
        runCatching { peer.close() }
    }
}
