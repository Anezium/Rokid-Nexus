package com.anezium.rokidbus.plugin.meta

import java.util.concurrent.Executor

internal interface OwnedWhatsAppEngine {
    fun cancel()
    fun close()
}

internal class SessionCallNotStarted : IllegalStateException("Request did not start")

internal class OwnedWhatsAppSession<E : OwnedWhatsAppEngine>(
    private val factory: (String) -> E,
    private val worker: Executor,
    private val cancellation: Executor,
    private val post: (() -> Unit) -> Unit,
    private val now: () -> Long,
) {
    private val lock = Any()
    private val owners = mutableMapOf<Any, Long>()
    private var nextEpoch = 0L
    private var engine: E? = null

    fun acquire(owner: Any) = synchronized(lock) {
        if (owner !in owners) owners[owner] = ++nextEpoch
    }

    fun release(owner: Any, finish: ((E) -> Unit)? = null) {
        val (current, last) = synchronized(lock) {
            owners.remove(owner)
            val current = engine
            val last = owners.isEmpty()
            if (last) engine = null
            current to last
        }
        if (current == null) return
        if (last) {
            cancellation.execute { runCatching { current.cancel() } }
            worker.execute { runCatching { current.close() } }
        } else if (finish != null) worker.execute { runCatching { finish(current) } }
    }

    private fun active(owner: Any, epoch: Long) = synchronized(lock) { owners[owner] == epoch }

    fun <T> call(path: String, owner: Any, work: (E) -> T, done: (Result<T>) -> Unit) {
        val epoch = synchronized(lock) { owners[owner] } ?: return
        val deadline = now() + 10_000
        worker.execute {
            if (!active(owner, epoch)) return@execute
            var workStarted = false
            val result = try {
                if (now() >= deadline) throw SessionCallNotStarted()
                val current = synchronized(lock) { engine } ?: factory(path).also { opened ->
                    val retained = synchronized(lock) {
                        if (owners.isEmpty()) false else { engine = opened; true }
                    }
                    if (!retained) {
                        opened.cancel()
                        opened.close()
                        return@execute
                    }
                }
                if (!active(owner, epoch)) return@execute
                if (now() >= deadline) throw SessionCallNotStarted()
                workStarted = true
                Result.success(work(current))
            } catch (error: SessionCallNotStarted) {
                Result.failure(error)
            } catch (_: Exception) {
                Result.failure(if (workStarted) IllegalStateException("WhatsApp unavailable; check linked devices") else SessionCallNotStarted())
            } catch (_: LinkageError) {
                Result.failure(if (workStarted) IllegalStateException("Embedded WhatsApp engine unavailable") else SessionCallNotStarted())
            }
            post { if (active(owner, epoch)) done(result) }
        }
    }
}
