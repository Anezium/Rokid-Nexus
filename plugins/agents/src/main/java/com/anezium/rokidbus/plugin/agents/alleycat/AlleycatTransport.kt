package com.anezium.rokidbus.plugin.agents.alleycat

/**
 * Byte pipe used by the framing codec. Slice 1 has no Iroh or sockets;
 * tests and later slices supply the implementation.
 */
interface AlleycatTransport {
    fun send(bytes: ByteArray)

    /** Returns exactly [length] bytes or throws. */
    fun receiveExactly(length: Int): ByteArray

    /**
     * Returns exactly [length] bytes, or null if nothing arrived within
     * [timeoutMs]. Mid-frame timeout is an error, not a hang.
     */
    fun receiveExactly(length: Int, timeoutMs: Long): ByteArray? = receiveExactly(length)

    fun close() {}
}
