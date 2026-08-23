package com.anezium.rokidbus.plugin.agents.alleycat

import org.json.JSONObject

/**
 * One JSON-RPC message in, one JSON-RPC message out.
 *
 * Alleycat QUIC streams wrap each message in a big-endian u32 length prefix.
 * A websocket text frame *is* the message: there is no length prefix. Callers
 * that speak Codex app-server JSON-RPC should depend on this, not on
 * [AlleycatTransport].
 */
interface JsonPipe {
    fun sendJson(message: JSONObject)

    /** Blocks until the next JSON object arrives, or throws. */
    fun receiveJson(): JSONObject

    /**
     * Waits up to [timeoutMs] for the next object. Returns null on timeout.
     * Throws if the pipe has failed or been closed.
     */
    fun receiveJson(timeoutMs: Long): JSONObject?

    fun close()
}

/** Alleycat stream: u32+JSON framing over a byte pipe. */
class FramedJsonPipe(
    private val transport: AlleycatTransport,
) : JsonPipe {
    override fun sendJson(message: JSONObject) {
        AlleycatFraming.write(transport, message)
    }

    override fun receiveJson(): JSONObject = AlleycatFraming.read(transport)

    override fun receiveJson(timeoutMs: Long): JSONObject? {
        val header = transport.receiveExactly(4, timeoutMs.coerceAtLeast(0L)) ?: return null
        return AlleycatFraming.readBody(transport, header)
    }

    override fun close() = transport.close()
}
