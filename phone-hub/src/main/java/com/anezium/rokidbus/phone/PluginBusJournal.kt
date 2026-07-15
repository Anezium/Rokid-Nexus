package com.anezium.rokidbus.phone

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class PluginBusJournal(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    enum class Category {
        REGISTRATION,
        LIFECYCLE,
        SURFACE,
        INPUT,
        BINARY,
        TRANSPORT,
        LAUNCHER,
    }

    enum class Direction {
        PLUGIN_TO_HUB,
        HUB_TO_PLUGIN,
        GLASSES_TO_HUB,
        HUB_TO_GLASSES,
    }

    enum class Verdict {
        OK,
        REJECTED,
    }

    data class Event(
        val timestampMillis: Long,
        val pluginId: String?,
        val category: Category,
        val direction: Direction,
        val path: String?,
        val sizeBytes: Int?,
        val verdict: Verdict,
        val reason: String?,
    )

    // B1 deliberately defaults on; the later Developer-mode settings task owns this switch.
    val enabled = AtomicBoolean(true)

    private val lock = Any()
    private val events = ArrayDeque<Event>()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun record(
        pluginId: String? = null,
        category: Category,
        direction: Direction,
        path: String? = null,
        sizeBytes: Int? = null,
        verdict: Verdict = Verdict.OK,
        reason: String? = null,
    ) {
        if (!enabled.get()) return
        try {
            val event = Event(
                timestampMillis = System.currentTimeMillis(),
                pluginId = pluginId?.take(MAX_PLUGIN_ID_CHARS),
                category = category,
                direction = direction,
                path = path?.take(MAX_PATH_CHARS),
                sizeBytes = sizeBytes,
                verdict = verdict,
                reason = reason?.take(MAX_REASON_CHARS),
            )
            synchronized(lock) {
                if (events.size >= capacity) events.removeFirst()
                events.addLast(event)
            }
        } catch (_: Throwable) {
            // Diagnostics must never affect bus routing.
        }
    }

    fun snapshot(): List<Event> = try {
        synchronized(lock) { events.toList() }
    } catch (_: Throwable) {
        emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 500
        internal const val MAX_PLUGIN_ID_CHARS = 128
        internal const val MAX_PATH_CHARS = 256
        internal const val MAX_REASON_CHARS = 192
    }
}
