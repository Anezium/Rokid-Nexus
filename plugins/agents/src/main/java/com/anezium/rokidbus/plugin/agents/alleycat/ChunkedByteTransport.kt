package com.anezium.rokidbus.plugin.agents.alleycat

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Byte pipe with a producer/consumer buffer. Iroh's recv stream is owned by
 * one reader thread that [enqueue]s chunks; [receiveExactly] waits on the
 * queue so a timeout cannot strand a UniFFI `readExact`.
 */
class ChunkedByteTransport : AlleycatTransport {
    private val inbound = LinkedBlockingQueue<Any>()
    private val leftover = ArrayDeque<Byte>()
    private val failure = AtomicReference<Throwable?>()
    private val outbound = java.io.ByteArrayOutputStream()
    private var sender: ((ByteArray) -> Unit)? = null

    fun setSender(send: (ByteArray) -> Unit) {
        sender = send
    }

    fun enqueue(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        inbound.put(bytes.copyOf())
    }

    fun fail(error: Throwable) {
        failure.set(error)
        inbound.offer(error)
    }

    fun written(): ByteArray = synchronized(outbound) { outbound.toByteArray() }

    override fun send(bytes: ByteArray) {
        failure.get()?.let { throw wrap(it) }
        synchronized(outbound) { outbound.write(bytes) }
        val send = sender ?: return
        send(bytes)
    }

    override fun receiveExactly(length: Int): ByteArray =
        take(length, Long.MAX_VALUE) ?: throw AlleycatException("iroh stream closed")

    override fun receiveExactly(length: Int, timeoutMs: Long): ByteArray? {
        if (timeoutMs <= 0L) {
            return if (available() >= length) receiveExactly(length) else {
                failure.get()?.let { throw wrap(it) }
                null
            }
        }
        return take(length, timeoutMs)
    }

    override fun close() {
        fail(AlleycatException("iroh stream closed"))
    }

    @Synchronized
    private fun available(): Int = leftover.size

    private fun take(length: Int, timeoutMs: Long): ByteArray? {
        val out = ByteArray(length)
        var filled = 0
        val deadline = if (timeoutMs == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            System.currentTimeMillis() + timeoutMs
        }
        while (filled < length) {
            failure.get()?.let { throw wrap(it) }
            val fromLeftover = drainLeftover(out, filled, length - filled)
            filled += fromLeftover
            if (filled >= length) break
            val wait = if (deadline == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
            }
            if (wait == 0L && timeoutMs != Long.MAX_VALUE) {
                if (filled == 0) return null
                throw AlleycatException("truncated Iroh frame")
            }
            val item = if (wait == Long.MAX_VALUE) {
                inbound.take()
            } else {
                inbound.poll(wait, TimeUnit.MILLISECONDS)
            }
            if (item == null) {
                failure.get()?.let { throw wrap(it) }
                if (filled == 0) return null
                throw AlleycatException("truncated Iroh frame")
            }
            when (item) {
                is ByteArray -> {
                    for (b in item) leftover.addLast(b)
                }
                is Throwable -> {
                    failure.compareAndSet(null, item)
                    throw wrap(item)
                }
                else -> throw AlleycatException("iroh stream closed")
            }
        }
        return out
    }

    @Synchronized
    private fun drainLeftover(out: ByteArray, offset: Int, need: Int): Int {
        var n = 0
        while (n < need && leftover.isNotEmpty()) {
            out[offset + n] = leftover.removeFirst()
            n++
        }
        return n
    }

    private fun wrap(error: Throwable): AlleycatException =
        error as? AlleycatException
            ?: AlleycatException(error.message ?: "iroh stream failed", error)
}
