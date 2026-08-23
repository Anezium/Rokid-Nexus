package com.anezium.rokidbus.plugin.agents

import android.content.Context
import com.anezium.rokidbus.plugin.agents.alleycat.AlleycatConnectSequence
import com.anezium.rokidbus.plugin.agents.alleycat.AlleycatException
import com.anezium.rokidbus.plugin.agents.alleycat.ChunkedByteTransport
import com.anezium.rokidbus.plugin.agents.alleycat.FramedJsonPipe
import com.anezium.rokidbus.plugin.agents.alleycat.JsonPipe
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import computer.iroh.presetN0
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Opens an Alleycat framed pipe over Iroh QUIC.
 *
 * Android caveat (plan 022): iroh does not use the system DNS resolver or
 * the system CA store. [IrohAndroidInit.ensure] must run before the first
 * [Endpoint.bind] so `ndk_context` can read `LinkProperties` nameservers.
 * A pinned relay from the pairing payload is applied as [RelayMode] and on
 * the dial [EndpointAddr]. Failures are not swallowed — they become a
 * readable [AlleycatException] (DNS/TLS/ALPN).
 *
 * ALPN is exactly [AlleycatConnectSequence.ALPN]. There is no fallback probe
 * of other ALPNs or protocol versions.
 */
class IrohAlleycatOpener(
    private val context: Context,
    private val secrets: AlleycatSecretStore,
) : AlleycatStreamOpener {
    @Volatile
    private var endpoint: Endpoint? = null
    private var bound = false
    private var boundRelay: String? = null

    override fun open(computer: AlleycatComputer): JsonPipe {
        IrohAndroidInit.ensure(context)
        val ep = bindWithPinnedRelay(computer.relay)
        val addr = endpointAddr(computer)
        val conn = try {
            runBlocking {
                ep.connect(addr, AlleycatConnectSequence.ALPN_BYTES)
            }
        } catch (e: Throwable) {
            throw mapIrohFailure(e, "Iroh connect")
        }
        val negotiated = try {
            String(conn.alpn(), Charsets.US_ASCII)
        } catch (e: Throwable) {
            connClose(conn)
            throw mapIrohFailure(e, "Iroh ALPN")
        }
        if (negotiated != AlleycatConnectSequence.ALPN) {
            connClose(conn)
            throw AlleycatException(
                "ALPN mismatch: peer negotiated '$negotiated' (expected ${AlleycatConnectSequence.ALPN})",
            )
        }
        val bi = try {
            runBlocking { conn.openBi() }
        } catch (e: Throwable) {
            connClose(conn)
            throw mapIrohFailure(e, "Iroh openBi")
        }
        val send = bi.send()
        val recv = bi.recv()
        val bytes = ChunkedByteTransport()
        val closed = AtomicBoolean(false)
        bytes.setSender { payload ->
            try {
                runBlocking { send.writeAll(payload) }
            } catch (e: Throwable) {
                throw mapIrohFailure(e, "Iroh send")
            }
        }
        thread(name = "alleycat-iroh-recv", isDaemon = true) {
            try {
                while (!closed.get()) {
                    val chunk = runBlocking { recv.read(65_536.toUInt()) }
                    if (chunk.isEmpty()) {
                        bytes.fail(AlleycatException("Iroh stream closed"))
                        break
                    }
                    bytes.enqueue(chunk)
                }
            } catch (e: Throwable) {
                bytes.fail(mapIrohFailure(e, "Iroh recv"))
            }
        }
        val framed = FramedJsonPipe(bytes)
        return object : JsonPipe by framed {
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                framed.close()
                try {
                    runBlocking { send.finish() }
                } catch (_: Throwable) {
                }
                connClose(conn)
            }
        }
    }

    @Synchronized
    fun bindWithPinnedRelay(relay: String?): Endpoint {
        val existing = endpoint
        if (existing != null && !existing.isClosed() && bound && boundRelay == relay) {
            return existing
        }
        if (existing != null && !existing.isClosed()) {
            try {
                runBlocking { existing.shutdown() }
            } catch (_: Throwable) {
            }
            endpoint = null
        }
        IrohAndroidInit.ensure(context)
        val secret = secrets.getEndpointSecret()
        val mode = if (relay.isNullOrBlank()) {
            null
        } else {
            try {
                RelayMode.customFromUrls(listOf(relay))
            } catch (e: Throwable) {
                throw AlleycatException(
                    "pinned relay '$relay' is not a valid Iroh relay URL",
                    e,
                )
            }
        }
        val options = EndpointOptions(
            preset = presetN0(),
            alpns = listOf(AlleycatConnectSequence.ALPN_BYTES),
            secretKey = secret,
            relayMode = mode,
        )
        val next = try {
            runBlocking { Endpoint.bind(options) }
        } catch (e: Throwable) {
            throw mapIrohFailure(e, "Iroh bind")
        }
        if (secret == null) {
            try {
                secrets.putEndpointSecret(next.secretKey().toBytes())
            } catch (e: Throwable) {
                try {
                    runBlocking { next.shutdown() }
                } catch (_: Throwable) {
                }
                throw AlleycatException(
                    "could not persist the Iroh endpoint identity in encrypted storage",
                    e,
                )
            }
        }
        endpoint = next
        bound = true
        boundRelay = relay
        return next
    }

    private fun endpointAddr(computer: AlleycatComputer): EndpointAddr {
        val id = parseEndpointId(computer.nodeId)
        return EndpointAddr(id, computer.relay, emptyList())
    }

    companion object {
        fun parseEndpointId(nodeId: String): EndpointId {
            try {
                return EndpointId.fromString(nodeId)
            } catch (first: Throwable) {
                val hex = nodeId.trim()
                if (hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    return try {
                        EndpointId.fromBytes(bytes)
                    } catch (e: Throwable) {
                        throw AlleycatException("node_id is not an iroh endpoint id", e)
                    }
                }
                throw AlleycatException("node_id is not an iroh endpoint id", first)
            }
        }

        fun mapIrohFailure(error: Throwable, where: String): AlleycatException {
            val msg = (error.message ?: error.toString()).take(MAX_STATUS_DETAIL_CHARS)
            val lower = msg.lowercase()
            val detail = when {
                lower.contains("alpn") || lower.contains("protocol negotiation") ->
                    "ALPN mismatch: expected ${AlleycatConnectSequence.ALPN} ($msg)"
                lower.contains("dns") || lower.contains("resolve") || lower.contains("nameserver") ->
                    "$where DNS failed: $msg. Android needs IrohAndroid.installAndroidContext " +
                        "and ACCESS_NETWORK_STATE; iroh does not use system DNS."
                lower.contains("tls") || lower.contains("certificate") ||
                    lower.contains("pkix") || lower.contains("trust") ->
                    "$where TLS failed: $msg. The Iroh bindings use their own trust store, " +
                        "not the system CA."
                else -> "$where failed: $msg"
            }
            return if (error is AlleycatException) error else AlleycatException(detail, error)
        }

        private fun connClose(conn: Connection) {
            try {
                conn.close(0L, byteArrayOf())
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * Installs the JNI Android context iroh needs to read platform DNS.
 * Fail-closed: a missing native library or a failed install is a
 * [AlleycatException], not a silent Google-DNS fallback.
 */
internal object IrohAndroidInit {
    @Volatile
    private var installed = false

    fun ensure(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            try {
                val clazz = sequenceOf(
                    "computer.iroh.IrohAndroid",
                    "computer.iroh.android.IrohAndroid",
                ).firstNotNullOfOrNull { name ->
                    try {
                        Class.forName(name)
                    } catch (_: ClassNotFoundException) {
                        null
                    }
                } ?: throw ClassNotFoundException("computer.iroh.IrohAndroid")
                val method = clazz.getMethod("installAndroidContext", Context::class.java)
                method.invoke(null, context.applicationContext)
                installed = true
            } catch (e: ClassNotFoundException) {
                throw AlleycatException(
                    "Iroh Android bindings are missing IrohAndroid. " +
                        "Depend on computer.iroh:iroh-android so DNS via LinkProperties can be installed.",
                    e,
                )
            } catch (e: UnsatisfiedLinkError) {
                throw AlleycatException(
                    "Iroh native library failed to load: ${e.message}. " +
                        "The Android AAR must ship libiroh_ffi.so for this ABI.",
                    e,
                )
            } catch (e: Throwable) {
                throw AlleycatException(
                    "IrohAndroid.installAndroidContext failed: ${e.message}. " +
                        "Without it iroh cannot read Android DNS/CA.",
                    e,
                )
            }
        }
    }
}
