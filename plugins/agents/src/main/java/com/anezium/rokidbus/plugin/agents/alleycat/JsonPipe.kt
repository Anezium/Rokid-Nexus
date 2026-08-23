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

    /**
     * The byte transport has no timed read. Slice-1 tests supply every frame
     * up front, so this just delegates to the blocking read.
     */
    override fun receiveJson(timeoutMs: Long): JSONObject? = receiveJson()

    override fun close() = Unit
}
