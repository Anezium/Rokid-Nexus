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
    fun contentKey(): String = "$title|$footer|${lines.joinToString("\n")}".hashCode().toString()
}

object PostCardLayout {
    const val LINE_CHARS = 26
    const val CARD_ROWS = 12
    const val BODY_ROWS = CARD_ROWS - 2

    fun layout(
        post: FeedPost,
        now: Instant,
        position: Int,
        total: Int,
        expanded: Boolean = false,
        requestedPage: Int = 0,
        footerOverride: String? = null,
    ): FeedCardContent {
        val displayText = buildString {
            append(post.text.trim())
            mediaMarker(post.media)?.let { marker ->
                if (isNotEmpty()) append(' ')
                append(marker)
            }
        }.ifBlank { "(no text)" }
        val bodyRows = wrap(displayText)
        val header = header(post, now)
        val bodyRowCount = CARD_ROWS - header.size
        val pageCount = ((bodyRows.size + bodyRowCount - 1) / bodyRowCount).coerceAtLeast(1)
        val truncated = pageCount > 1
        val pageIndex = if (expanded) requestedPage.coerceIn(0, pageCount - 1) else 0
        val visibleBody = if (expanded) {
            bodyRows.drop(pageIndex * bodyRowCount).take(bodyRowCount)
        } else {
            bodyRows.take(bodyRowCount).toMutableList().also { rows ->
                if (truncated && rows.isNotEmpty()) rows[rows.lastIndex] = ellipsize(rows.last(), LINE_CHARS)
            }
        }
        val footer = footerOverride ?: buildString {
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
            lines = header + visibleBody,
            footer = footer.take(LINE_CHARS),
            truncated = truncated,
            pageIndex = pageIndex,
            pageCount = pageCount,
        )
    }

    fun renderGalleryItem(
        post: FeedPost,
        mediaIndex: Int,
        now: Instant = Instant.now(),
    ): FeedCardContent {
        val index = mediaIndex.coerceIn(0, post.media.lastIndex)
        val item = post.media[index]
        val descriptor = when (item.type) {
            FeedMediaType.PHOTO -> "Photo ${index + 1}/${post.media.size}"
            FeedMediaType.GIF -> "[GIF]"
            FeedMediaType.VIDEO -> "[Video${duration(item.durationMs)?.let { " $it" }.orEmpty()}]"
        }
        val body = buildString {
            append(descriptor)
            item.altText.trim().takeIf(String::isNotBlank)?.let {
                append('\n')
                append(it)
            }
        }
        val header = header(post, now)
        val bodyRows = wrap(body)
        val availableRows = CARD_ROWS - header.size
        val truncated = bodyRows.size > availableRows
        val visibleBody = bodyRows.take(availableRows).toMutableList().also { rows ->
            if (truncated && rows.isNotEmpty()) rows[rows.lastIndex] = ellipsize(rows.last(), LINE_CHARS)
        }
        return FeedCardContent(
            title = "Feeds",
            lines = header + visibleBody,
            footer = "${post.source} media ${index + 1}/${post.media.size}".take(LINE_CHARS),
            truncated = truncated,
            pageIndex = index,
            pageCount = post.media.size,
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

    internal fun header(post: FeedPost, now: Instant): List<String> {
        val age = relativeAge(post.createdAt, now)
        val suffix = " \u00b7 $age"
        val maxName = (LINE_CHARS - suffix.length).coerceAtLeast(1)
        val firstLine = post.authorName.ifBlank { post.authorHandle.ifBlank { "Unknown" } }
            .take(maxName) + suffix
        val handle = post.authorHandle.trim().takeIf(String::isNotBlank)
        return if (handle == null) listOf(firstLine) else listOf(firstLine, "@$handle".take(LINE_CHARS))
    }

    internal fun mediaMarker(media: List<FeedMedia>): String? {
        val first = media.firstOrNull() ?: return null
        val marker = when (first.type) {
            FeedMediaType.PHOTO -> {
                val photoCount = media.count { it.type == FeedMediaType.PHOTO }
                if (photoCount > 1) "[$photoCount photos]" else "[photo]"
            }
            FeedMediaType.GIF -> "[GIF]"
            FeedMediaType.VIDEO -> "[video${duration(first.durationMs)?.let { " $it" }.orEmpty()}]"
        }
        val alt = first.altText.trim().replace(Regex("\\s+"), " ")
        if (alt.isBlank()) return marker
        val available = (LINE_CHARS * 2 - marker.length - 3).coerceAtLeast(0)
        val shortened = if (alt.length <= available) alt else ellipsize(alt, available)
        return "$marker ($shortened)"
    }

    private fun duration(durationMs: Long?): String? = durationMs
        ?.coerceAtLeast(0L)
        ?.div(1_000L)
        ?.let { seconds -> "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" }

    private fun ellipsize(value: String, width: Int): String = when {
        width <= 1 -> "\u2026".take(width)
        value.length >= width -> value.take(width - 1) + "\u2026"
        else -> value + "\u2026"
    }
}
