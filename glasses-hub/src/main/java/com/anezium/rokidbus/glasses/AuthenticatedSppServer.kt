package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.SppAuthProtocol
import com.anezium.rokidbus.shared.SppPairingKeyStore
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal interface SppPeer : Closeable {
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
) {
    private class Connection(val peer: SppPeer, val key: ByteArray) {
        var session: SppAuthProtocol.Session? = null
        var cancelTimeout: (() -> Unit)? = null
    }

    private val lock = Any()
    private val recentNonces = SppAuthProtocol.RecentNonces()
    private var pending: Connection? = null
    @Volatile private var active: Connection? = null
    private var lastAttemptMs: Long? = null

    fun isConnected(): Boolean = active != null

    fun accept(peer: SppPeer) {
        synchronized(lock) {
            val now = nowMs()
            val last = lastAttemptMs
            if (active != null || pending != null || (last != null && now - last < 1_000L)) {
                close(peer)
                return
            }
            lastAttemptMs = now
            val key = keys.load()
            if (key == null) {
                close(peer)
                return
            }
            val candidate = Connection(peer, key)
            pending = candidate
            candidate.cancelTimeout = schedule(SppAuthProtocol.HANDSHAKE_TIMEOUT_MS) {
                synchronized(lock) {
                    if (pending === candidate) {
                        pending = null
                        close(candidate.peer)
                    }
                }
            }
            execute { serve(candidate) }
        }
    }

    fun installKey(key: ByteArray) = synchronized(lock) {
        val previous = keys.load()
        if (previous != null && MessageDigest.isEqual(previous, key)) return@synchronized
        if (!keys.save(key)) return@synchronized
        pending?.let {
            pending = null
            it.cancelTimeout?.invoke()
            close(it.peer)
        }
        active?.let {
            active = null
            close(it.peer)
            onConnected(false)
        }
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

    private fun serve(connection: Connection) {
        try {
            val peer = connection.peer
            val session = SppAuthProtocol.accept(peer.input, peer.output, connection.key, recentNonces)
            synchronized(lock) {
                if (pending !== connection) return
                pending = null
                connection.cancelTimeout?.invoke()
                connection.session = session
                active = connection
                onConnected(true)
            }
            while (true) {
                val envelope = session.read(peer.input) ?: break
                synchronized(lock) {
                    if (active !== connection) return
                    onEnvelope(envelope)
                }
            }
        } catch (_: Exception) {
            // Authentication failures intentionally carry no peer data into logs.
        } finally {
            retire(connection)
        }
    }

    private fun retire(connection: Connection) = synchronized(lock) {
        connection.cancelTimeout?.invoke()
        close(connection.peer)
        if (pending === connection) pending = null
        if (active === connection) {
            active = null
            onConnected(false)
        }
    }

    private fun close(peer: SppPeer) {
        runCatching { peer.close() }
    }
}
