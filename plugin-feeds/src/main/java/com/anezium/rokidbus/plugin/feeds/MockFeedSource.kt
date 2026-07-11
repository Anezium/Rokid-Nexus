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
                    hasMedia = index == 3 || index == 11,
                )
            },
            nextCursor = null,
        )
    }

    private companion object {
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
