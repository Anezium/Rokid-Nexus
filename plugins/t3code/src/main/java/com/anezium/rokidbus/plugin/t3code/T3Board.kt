package com.anezium.rokidbus.plugin.t3code

import java.time.Duration
import java.time.Instant

internal enum class T3BoardTone {
    ALERT,
    NORMAL,
    DIM,
}

internal data class T3BoardRow(
    val threadId: String,
    val text: String,
    val badge: String,
    val sub: String,
    val tone: T3BoardTone,
)

internal data class T3BoardPresentation(
    val totalThreads: Int,
    val rows: List<T3BoardRow>,
)

internal object T3Board {
    fun build(
        threads: List<T3BoardThread>,
        now: Instant = Instant.now(),
    ): T3BoardPresentation {
        val active = threads.asSequence()
            .filter { it.archivedAt == null && it.deletedAt == null }
            .sortedByDescending { parseInstant(it.updatedAt) ?: Instant.EPOCH }
            .toList()
        return T3BoardPresentation(
            totalThreads = active.size,
            rows = active.take(MAX_BOARD_THREADS).map { thread -> row(thread, now) },
        )
    }

    fun relativeAge(isoTime: String, now: Instant = Instant.now()): String {
        val updated = parseInstant(isoTime) ?: return "?"
        val minutes = Duration.between(updated, now).toMinutes().coerceAtLeast(0)
        return when {
            minutes < 60 -> "${minutes}m"
            minutes < 24 * 60 -> "${minutes / 60}h"
            else -> "${minutes / (24 * 60)}d"
        }
    }

    fun providerTag(instanceId: String): String = when (instanceId.lowercase()) {
        "claudeagent" -> "CC"
        "codex" -> "CX"
        "opencode" -> "OC"
        else -> instanceId.filter(Char::isLetterOrDigit).take(2).uppercase()
    }

    fun modelShort(slug: String): String = slug.substringAfterLast('/').ifBlank { slug }.take(36)

    private fun row(thread: T3BoardThread, now: Instant): T3BoardRow {
        val session = thread.session
        val updated = parseInstant(thread.updatedAt)
        val old = updated != null && Duration.between(updated, now).toHours() > 6
        val idleLike = session?.status?.lowercase() in setOf("idle", "ready", "stopped", "interrupted")
        val tone = when {
            thread.hasPendingApprovals || thread.hasPendingUserInput || session?.lastError != null ->
                T3BoardTone.ALERT
            idleLike && old -> T3BoardTone.DIM
            else -> T3BoardTone.NORMAL
        }
        return T3BoardRow(
            threadId = thread.id,
            text = collapseWhitespace(thread.title).ifBlank { "Untitled thread" }.take(80),
            badge = providerTag(thread.modelSelection.instanceId),
            sub = "${modelShort(thread.modelSelection.model)} · ${relativeAge(thread.updatedAt, now)}",
            tone = tone,
        )
    }

    private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

    const val MAX_BOARD_THREADS = 40
}

internal fun collapseWhitespace(value: String): String = value.trim().replace(Regex("\\s+"), " ")
