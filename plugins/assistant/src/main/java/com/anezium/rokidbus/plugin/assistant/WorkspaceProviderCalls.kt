package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

internal class WorkspaceProviderCheckException : Exception()

internal class WorkspaceProviderCalls(
    private val timeoutMs: Long = WorkspaceLimits.CHECK_TIMEOUT_MS,
) : AutoCloseable {
    private class Call {
        val abandoned = AtomicBoolean()
        val finished = CompletableDeferred<Unit>()
    }

    private val lock = Any()
    private val calls = mutableMapOf<String, Call>()
    private val workers = executor(2, "workspace-provider")
    private val cancellations = executor(1, "workspace-cancel")

    suspend fun <T> await(treeUri: String, cancel: () -> Unit = {}, block: () -> T): T = withTimeout(timeoutMs) {
        var call: Call
        while (true) {
            val (candidate, acquired) = synchronized(lock) {
                val existing = calls[treeUri]
                if (existing != null) existing to false else {
                    if (calls.size >= 2) throw WorkspaceProviderCheckException()
                    Call().also { calls[treeUri] = it } to true
                }
            }
            call = candidate
            if (acquired) break
            if (call.abandoned.get()) throw WorkspaceProviderCheckException()
            call.finished.await()
        }
        val active = call
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                active.abandoned.set(true)
                // Cancellation itself may make a Binder call; never run it on the deadline's thread.
                runCatching { cancellations.execute { runCatching(cancel) } }
            }
            try {
                workers.execute {
                    try {
                        if (continuation.isActive) {
                            continuation.resume(block(), onCancellation = { _, value, _ ->
                                if (value is Closeable) runCatching { value.close() }
                            })
                        }
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    } finally {
                        finish(treeUri, active)
                    }
                }
            } catch (_: Exception) {
                finish(treeUri, active)
                continuation.resumeWithException(WorkspaceProviderCheckException())
            }
        }
    }

    private fun finish(treeUri: String, call: Call) {
        synchronized(lock) { if (calls[treeUri] === call) calls.remove(treeUri) }
        call.finished.complete(Unit)
    }

    override fun close() {
        workers.shutdownNow()
        cancellations.shutdownNow()
    }

    private fun executor(threads: Int, name: String) = ThreadPoolExecutor(threads, threads, 0L,
        TimeUnit.MILLISECONDS, ArrayBlockingQueue(2), { task -> Thread(task, name).apply { isDaemon = true } })
}
