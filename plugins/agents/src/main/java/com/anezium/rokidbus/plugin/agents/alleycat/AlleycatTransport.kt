package com.anezium.rokidbus.plugin.agents.alleycat

/**
 * Byte pipe used by the framing codec. Slice 1 has no Iroh or sockets;
 * tests and later slices supply the implementation.
 */
interface AlleycatTransport {
    fun send(bytes: ByteArray)

    /** Returns exactly [length] bytes or throws. */
    fun receiveExactly(length: Int): ByteArray
}
