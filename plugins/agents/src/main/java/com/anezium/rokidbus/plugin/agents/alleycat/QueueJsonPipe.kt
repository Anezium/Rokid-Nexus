package com.anezium.rokidbus.plugin.agents.alleycat

import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Message-oriented JSON pipe: each [sendJson] / [receiveJson] is exactly one
 * JSON object, with no length prefix. Used by unit tests and as the inbound
 * queue behind a websocket.
 */
class QueueJsonPipe : JsonPipe {
    private val inbound = LinkedBlockingQueue<Any>()
    private val outbound = mutableListOf<JSONObject>()
    private val failure = AtomicReference<Throwable?>()

    @Synchronized
    fun enqueue(message: JSONObject) {
        inbound.put(JSONObject(message.toString()))
    }

    fun enqueueJson(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw AlleycatException("websocket text is not JSON", e)
        }
        enqueue(json)
    }

    @Synchronized
    fun sent(): List<JSONObject> = outbound.map { JSONObject(it.toString()) }

    /** Wire form of each sent message: a JSON object, never a u32-prefixed frame. */
    @Synchronized
    fun sentText(): List<String> = outbound.map { it.toString() }

    fun fail(error: Throwable) {
        failure.set(error)
        inbound.offer(error)
    }

    override fun sendJson(message: JSONObject) {
        failure.get()?.let { throw wrap(it) }
        synchronized(this) {
            outbound += JSONObject(message.toString())
        }
    }

    override fun receiveJson(): JSONObject = takeInbound(Long.MAX_VALUE)
        ?: throw AlleycatException("json pipe closed")

    override fun receiveJson(timeoutMs: Long): JSONObject? {
        if (timeoutMs <= 0L) {
            return pollInbound()
        }
        return takeInbound(timeoutMs)
    }

    override fun close() {
        fail(AlleycatException("json pipe closed"))
    }

    private fun pollInbound(): JSONObject? {
        failure.get()?.let { throw wrap(it) }
        return when (val item = inbound.poll()) {
            null -> {
                failure.get()?.let { throw wrap(it) }
                null
            }
            is JSONObject -> item
            is Throwable -> {
                failure.compareAndSet(null, item)
                throw wrap(item)
            }
            else -> throw AlleycatException("json pipe closed")
        }
    }

    private fun takeInbound(timeoutMs: Long): JSONObject? {
        failure.get()?.let { throw wrap(it) }
        val item = if (timeoutMs == Long.MAX_VALUE) {
            inbound.take()
        } else {
            inbound.poll(timeoutMs, TimeUnit.MILLISECONDS)
        }
        if (item == null) {
            failure.get()?.let { throw wrap(it) }
            return null
        }
        return when (item) {
            is JSONObject -> item
            is Throwable -> {
                failure.compareAndSet(null, item)
                throw wrap(item)
            }
            else -> throw AlleycatException("json pipe closed")
        }
    }

    private fun wrap(error: Throwable): AlleycatException =
        error as? AlleycatException
            ?: AlleycatException(error.message ?: "json pipe failed", error)
}
