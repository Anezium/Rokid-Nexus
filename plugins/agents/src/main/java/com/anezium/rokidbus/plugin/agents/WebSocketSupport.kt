package com.anezium.rokidbus.plugin.agents

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI
import kotlin.math.roundToLong
import kotlin.random.Random

internal sealed interface ConnectionOutcome {
    data object Retry : ConnectionOutcome
    data class RetryWithDetail(val detail: String) : ConnectionOutcome
    data class AuthFailed(val detail: String) : ConnectionOutcome
    /** Terminal, non-retrying failure that is not a re-pair (version/ALPN). */
    data class Failed(val detail: String) : ConnectionOutcome
}

internal fun reconnectDelayMs(attempt: Int, random: Random = Random.Default): Long {
    val base = when (attempt.coerceAtLeast(0)) {
        0 -> 1_000L
        1 -> 2_000L
        2 -> 4_000L
        else -> 5_000L
    }
    return (base * random.nextDouble(0.8, 1.2)).roundToLong().coerceIn(800L, 6_000L)
}

internal class ReconnectBackoff(
    private val delayForAttempt: (Int) -> Long = { attempt -> reconnectDelayMs(attempt) },
) {
    private var attempt = 0

    @Synchronized
    fun reset() {
        attempt = 0
    }

    @Synchronized
    fun nextDelayMs(): Long = delayForAttempt(attempt++)
}

internal data class GenerationAdvance<T>(
    val generation: Long,
    val previous: T?,
)

/**
 * Owns a resource for one client generation. A superseded generation can only
 * clear the value it installed, never a newer generation's value.
 */
internal class GenerationSlot<T> {
    private var generation = 0L
    private var value: T? = null

    @Synchronized
    fun advance(): GenerationAdvance<T> {
        generation += 1L
        return GenerationAdvance(generation, value.also { value = null })
    }

    @Synchronized
    fun isCurrent(candidate: Long): Boolean = generation == candidate

    @Synchronized
    fun install(candidate: Long, newValue: T): Boolean {
        if (generation != candidate) return false
        value = newValue
        return true
    }

    @Synchronized
    fun current(): T? = value

    @Synchronized
    fun clear(candidate: Long): T? {
        if (generation != candidate) return null
        return value.also { value = null }
    }
}

internal const val NETWORK_STEP_TIMEOUT_MS = 15_000L

internal class ConnectionDeadlines(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = NETWORK_STEP_TIMEOUT_MS,
    private val onTimeout: (String) -> Unit,
) {
    private data class Entry(val token: Any, val job: Job)

    private val entries = mutableMapOf<String, Entry>()

    init {
        require(timeoutMs > 0L)
    }

    fun arm(key: String, detail: String) {
        val token = Any()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(timeoutMs)
            val shouldNotify = synchronized(this@ConnectionDeadlines) {
                if (entries[key]?.token !== token) {
                    false
                } else {
                    entries.remove(key)
                    true
                }
            }
            if (shouldNotify) onTimeout(detail)
        }
        synchronized(this) {
            entries.remove(key)?.job?.cancel()
            entries[key] = Entry(token, job)
        }
        job.start()
    }

    @Synchronized
    fun clear(key: String) {
        entries.remove(key)?.job?.cancel()
    }

    @Synchronized
    fun clearAll() {
        entries.values.forEach { it.job.cancel() }
        entries.clear()
    }
}

internal fun webSocketUrl(hostInput: String, port: Int): String {
    val input = hostInput.trim().trimEnd('/')
    if (input.startsWith("ws://", ignoreCase = true) ||
        input.startsWith("wss://", ignoreCase = true)
    ) {
        val uri = URI(input)
        val scheme = uri.scheme.lowercase()
        val host = uri.host ?: throw IllegalArgumentException("Invalid WebSocket host")
        val renderedHost = if (host.contains(':')) "[$host]" else host
        val path = uri.rawPath?.takeIf { it.isNotBlank() && it != "/" }.orEmpty()
        return "$scheme://$renderedHost:$port$path"
    }
    val renderedHost = if (input.contains(':') && !input.startsWith("[")) "[$input]" else input
    return "ws://$renderedHost:$port"
}
