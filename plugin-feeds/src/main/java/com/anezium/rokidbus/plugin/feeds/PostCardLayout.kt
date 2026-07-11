package com.anezium.rokidbus.plugin.feeds

import java.time.Duration
import java.time.Instant

data class FeedCardContent(
    val title: String,
    val lines: List<String>,
    val footer: String,
    val truncated: Boolean,
    val pageIndex: Int,
    val pageCount: Int,
) {
    fun contentKey(): String = "$title|$footer|${lines.joinToString("\n")}"
}

object PostCardLayout {
    const val LINE_CHARS = 26
    const val CARD_ROWS = 12
    const val BODY_ROWS = CARD_ROWS - 1

    fun layout(
        post: FeedPost,
        now: Instant,
        position: Int,
        total: Int,
        expanded: Boolean = false,
        requestedPage: Int = 0,
    ): FeedCardContent {
        val displayText = buildString {
            append(post.text.trim())
            if (post.hasMedia) {
                if (isNotEmpty()) append(' ')
                append("[+media]")
            }
        }.ifBlank { "(no text)" }
        val bodyRows = wrap(displayText)
        val pageCount = ((bodyRows.size + BODY_ROWS - 1) / BODY_ROWS).coerceAtLeast(1)
        val truncated = pageCount > 1
        val pageIndex = if (expanded) requestedPage.coerceIn(0, pageCount - 1) else 0
        val visibleBody = if (expanded) {
            bodyRows.drop(pageIndex * BODY_ROWS).take(BODY_ROWS)
        } else {
            bodyRows.take(BODY_ROWS).toMutableList().also { rows ->
                if (truncated && rows.isNotEmpty()) rows[rows.lastIndex] = ellipsize(rows.last(), LINE_CHARS)
            }
        }
        val footer = buildString {
            append(post.source)
            append(' ')
            append(position.coerceAtLeast(0) + 1)
            append('/')
            append(total.coerceAtLeast(1))
            if (expanded && pageCount > 1) {
                append(" p")
                append(pageIndex + 1)
                append('/')
                append(pageCount)
            }
        }
        return FeedCardContent(
            title = "Feeds",
            lines = listOf(header(post, now)) + visibleBody,
            footer = footer,
            truncated = truncated,
            pageIndex = pageIndex,
            pageCount = pageCount,
        )
    }

    fun message(message: String, footer: String): FeedCardContent = FeedCardContent(
        title = "Feeds",
        lines = wrap(message).take(CARD_ROWS),
        footer = footer.take(LINE_CHARS),
        truncated = false,
        pageIndex = 0,
        pageCount = 1,
    )

    fun relativeAge(createdAt: Instant, now: Instant): String {
        val minutes = Duration.between(createdAt, now).toMinutes().coerceAtLeast(0)
        return when {
            minutes < 60 -> "${minutes}m"
            minutes < 24 * 60 -> "${minutes / 60}h"
            else -> "${minutes / (24 * 60)}d"
        }
    }

    internal fun wrap(value: String, width: Int = LINE_CHARS): List<String> {
        require(width > 1)
        val rows = mutableListOf<String>()
        value.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { paragraph ->
            if (paragraph.isBlank()) {
                rows += ""
            } else {
                var current = ""
                paragraph.trim().split(Regex("\\s+")).forEach { word ->
                    var remainder = word
                    while (remainder.length > width) {
                        if (current.isNotEmpty()) {
                            rows += current
                            current = ""
                        }
                        rows += remainder.take(width)
                        remainder = remainder.drop(width)
                    }
                    if (remainder.isEmpty()) return@forEach
                    current = when {
                        current.isEmpty() -> remainder
                        current.length + 1 + remainder.length <= width -> "$current $remainder"
                        else -> {
                            rows += current
                            remainder
                        }
                    }
                }
                if (current.isNotEmpty()) rows += current
            }
        }
        return rows.ifEmpty { listOf("") }
    }

    private fun header(post: FeedPost, now: Instant): String {
        val age = relativeAge(post.createdAt, now)
        val suffix = " \u00b7 $age"
        val maxName = (LINE_CHARS - suffix.length).coerceAtLeast(1)
        return post.authorName.ifBlank { post.authorHandle.ifBlank { "Unknown" } }
            .take(maxName) + suffix
    }

    private fun ellipsize(value: String, width: Int): String = when {
        width <= 1 -> "\u2026".take(width)
        value.length >= width -> value.take(width - 1) + "\u2026"
        else -> value + "\u2026"
    }
}
