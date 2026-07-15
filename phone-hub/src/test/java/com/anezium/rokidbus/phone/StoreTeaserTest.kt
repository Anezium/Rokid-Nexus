package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreTeaserTest {
    @Test
    fun `teaser counts accepted registry packages that are not installed`() {
        val feed = RegistryFeed(
            1,
            listOf(
                plugin("feeds", "com.example.feeds"),
                plugin("notes", "com.example.notes"),
                plugin("weather", "com.example.weather"),
            ),
        )

        assertEquals(
            2,
            StoreTeaser.newPluginCount(feed, setOf("com.example.notes")),
        )
        assertEquals(
            "2 new plugins",
            StoreTeaser.subtitle(feed, setOf("com.example.notes")),
        )
    }

    @Test
    fun `teaser uses zero and singular forms`() {
        val feed = RegistryFeed(1, listOf(plugin("feeds", "com.example.feeds")))

        assertEquals("1 new plugin", StoreTeaser.subtitle(feed, emptySet()))
        assertEquals("No new plugins", StoreTeaser.subtitle(feed, setOf("com.example.feeds")))
        assertEquals("No new plugins", StoreTeaser.subtitle(RegistryFeed(1, emptyList()), emptySet()))
    }

    private fun plugin(id: String, packageName: String) = RegistryPlugin(
        id = id,
        name = id.replaceFirstChar(Char::uppercase),
        category = "Utility",
        summary = "$id summary.",
        description = "$id description.",
        author = "Author",
        sourceUrl = "https://example.com/$id",
        publishedAt = "2026-07-15",
        iconAsset = "$id.png",
        screenshotAssets = emptyList(),
        listingDescriptionMarkdown = id,
        releases = emptyList(),
        nexus = RegistryNexus(id, 3, listOf("surfaces"), true, null, 1),
        artifact = RegistryArtifact(
            target = "phone",
            url = "https://example.com/$id.apk",
            sha256 = "ab".repeat(32),
            sizeBytes = 123,
            packageName = packageName,
            versionCode = 1,
            versionName = "1.0.0",
        ),
    )
}
