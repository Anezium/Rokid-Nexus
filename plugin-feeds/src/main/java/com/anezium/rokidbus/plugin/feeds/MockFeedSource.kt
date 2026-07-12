package com.anezium.rokidbus.plugin.feeds

import java.time.Instant
import java.time.temporal.ChronoUnit

class MockFeedSource(
    private val now: () -> Instant = Instant::now,
) : FeedSource {
    override fun fetchPage(cursor: String?): FeedPage {
        if (cursor != null) return FeedPage(emptyList(), null)
        val instant = now()
        return FeedPage(
            posts = SAMPLE_TEXTS.mapIndexed { index, sample ->
                FeedPost(
                    id = "demo-${index + 1}",
                    authorName = sample.first,
                    authorHandle = sample.second,
                    text = sample.third,
                    createdAt = instant.minus((index * 37L) + 2L, ChronoUnit.MINUTES),
                    source = FeedSourceKind.DEMO.tag,
                    media = sampleMedia(index),
                )
            },
            nextCursor = null,
        )
    }

    private companion object {
        fun sampleMedia(index: Int): List<FeedMedia> = when (index) {
            3 -> (1..3).map { mediaIndex ->
                FeedMedia(
                    type = FeedMediaType.PHOTO,
                    url = "https://example.invalid/demo/photo-$mediaIndex-large.jpg",
                    previewUrl = "https://example.invalid/demo/photo-$mediaIndex-small.jpg",
                    altText = if (mediaIndex == 1) "Green light reflecting on wet pavement after the rain." else "",
                    durationMs = null,
                )
            }
            11 -> listOf(
                FeedMedia(
                    type = FeedMediaType.GIF,
                    url = "https://example.invalid/demo/sketch.gif.mp4",
                    previewUrl = "https://example.invalid/demo/sketch-poster.jpg",
                    altText = "A sketchbook page flipping in the station breeze.",
                    durationMs = 4_200L,
                ),
            )
            12 -> listOf(
                FeedMedia(
                    type = FeedMediaType.VIDEO,
                    url = "https://example.invalid/demo/hud.mp4",
                    previewUrl = "https://example.invalid/demo/hud-poster.jpg",
                    altText = "A monochrome card advancing with one swipe.",
                    durationMs = 12_000L,
                ),
            )
            else -> emptyList()
        }

        val SAMPLE_TEXTS = listOf(
            Triple("Maya Chen", "maya", "Morning walk, clear sky, and exactly enough coffee."),
            Triple("Noah", "noah-builds", "Shipped the tiny fix that makes the whole interaction feel calmer."),
            Triple("Ari Sol", "arisol", "A good wearable interface disappears until the exact second you need it."),
            Triple("Leonie", "leonie.photo", "Green light on wet pavement after the rain."),
            Triple("Samir", "samir", "Today I learned: constraints are often the fastest route to a distinctive design."),
            Triple("Inez Park", "inez", "Two trains, one paperback, zero notifications."),
            Triple("Theo", "theo-labs", "Prototype note:\nkeep the first gesture obvious\nkeep the exit even more obvious"),
            Triple("Rae", "rae", "Small tools earn trust by doing one job well."),
            Triple("Jon Bell", "jonb", "The build is green. The desk is not."),
            Triple(
                "Amara",
                "amara",
                ("Long post demo: " +
                    "A social feed on a heads-up display should respect attention, preserve context, and make every movement predictable. "
                        .repeat(3)).take(280),
            ),
            Triple("Owen", "owen", "Offline demos are underrated. They turn a network dependency into a reliable conversation."),
            Triple("Nia", "nia", "Sketchbook page from the station platform."),
            Triple("Mikkel", "mikkel", "One card. One post. One swipe axis."),
            Triple("Zoe", "zoe", "Remember to look through the display, not only at it."),
            Triple("Devon", "devon", "End of the bundled demo feed."),
        )
    }
}
