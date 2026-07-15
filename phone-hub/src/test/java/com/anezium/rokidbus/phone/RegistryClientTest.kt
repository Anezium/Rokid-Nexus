package com.anezium.rokidbus.phone

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryClientTest {
    private class MemoryCache(var record: RegistryCacheRecord? = null) : RegistryCache {
        override fun read(): RegistryCacheRecord? = record
        override fun write(record: RegistryCacheRecord) { this.record = record }
    }

    @Test
    fun `parses a valid feed into immutable values`() {
        val feed = RegistryClient.parse(validFeed())

        assertEquals(1, feed.version)
        assertEquals("feeds", feed.plugins.single().nexus.pluginId)
        assertEquals("com.anezium.rokidbus.plugin.feeds", feed.plugins.single().artifact.packageName)
        assertEquals(7L, feed.plugins.single().artifact.versionCode)
    }

    @Test
    fun `accepts the published empty catalogue shape`() {
        assertTrue(RegistryClient.parse("{\"version\":1,\"plugins\":[]}").plugins.isEmpty())
    }

    @Test
    fun `ignores unknown fields at every level`() {
        val body = validFeed(
            rootExtra = ",\"futureRoot\":true",
            pluginExtra = ",\"futurePlugin\":{\"value\":1}",
            nexusExtra = ",\"futureNexus\":\"ok\"",
            artifactExtra = ",\"futureArtifact\":[1,2]",
        )

        assertEquals("Feeds", RegistryClient.parse(body).plugins.single().name)
    }

    @Test(expected = RegistryParseException::class)
    fun `rejects an unknown top level version`() {
        RegistryClient.parse("{\"version\":2,\"plugins\":[]}")
    }

    @Test
    fun `serves a valid cache when transport is offline`() {
        val cache = MemoryCache(RegistryCacheRecord(validFeed(), "etag-1", 123L))
        val client = RegistryClient(
            transport = RegistryTransport { throw IOException("offline") },
            cache = cache,
            clock = { 999L },
        )

        val result = client.load() as RegistryLoadResult.Success

        assertEquals(RegistrySource.CACHE, result.snapshot.source)
        assertEquals(123L, result.snapshot.lastFetchEpochMillis)
        assertEquals("feeds", result.snapshot.feed.plugins.single().id)
    }

    @Test
    fun `cached snapshot never invokes transport`() {
        val cache = MemoryCache(RegistryCacheRecord(validFeed(), "etag-1", 123L))
        var fetchCount = 0
        val client = RegistryClient(
            transport = RegistryTransport {
                fetchCount += 1
                RegistryHttpResponse(500)
            },
            cache = cache,
        )

        val snapshot = client.cachedSnapshot()

        assertEquals(0, fetchCount)
        assertEquals(RegistrySource.CACHE, snapshot?.source)
        assertEquals("feeds", snapshot?.feed?.plugins?.single()?.id)
    }

    @Test
    fun `sends the cached etag and refreshes cache on network success`() {
        val cache = MemoryCache(RegistryCacheRecord(validFeed(), "old-etag", 123L))
        var requestedEtag: String? = null
        val client = RegistryClient(
            transport = RegistryTransport { etag ->
                requestedEtag = etag
                RegistryHttpResponse(200, validFeed(), "new-etag")
            },
            cache = cache,
            clock = { 999L },
        )

        val result = client.load() as RegistryLoadResult.Success

        assertEquals("old-etag", requestedEtag)
        assertEquals(RegistrySource.NETWORK, result.snapshot.source)
        assertEquals("new-etag", cache.record?.etag)
        assertEquals(999L, cache.record?.lastFetchEpochMillis)
    }

    @Test
    fun `rejects malformed body without poisoning an empty cache`() {
        val cache = MemoryCache()
        val client = RegistryClient(
            transport = RegistryTransport { RegistryHttpResponse(200, "{not-json", "bad") },
            cache = cache,
        )

        val result = client.load()

        assertTrue(result is RegistryLoadResult.Failure)
        assertTrue((result as RegistryLoadResult.Failure).error is RegistryParseException)
        assertEquals(null, cache.record)
    }

    private fun validFeed(
        rootExtra: String = "",
        pluginExtra: String = "",
        nexusExtra: String = "",
        artifactExtra: String = "",
    ): String = """
        {
          "version": 1,
          "plugins": [{
            "id": "feeds",
            "kind": "nexus-plugin",
            "name": "Feeds",
            "category": "Social",
            "summary": "Read social feeds.",
            "description": "A longer description.",
            "author": "Anezium",
            "sourceUrl": "https://github.com/Anezium/Rokid-Nexus",
            "publishedAt": "2026-07-12",
            "iconAsset": "feeds-icon.png",
            "screenshotAssets": ["feeds-1.jpg"],
            "listing": {"descriptionMarkdown": "Feed details."},
            "releases": [{"version":"0.1.0","date":"2026-07-12","notes":"First."}],
            "nexus": {
              "pluginId": "feeds",
              "apiVersion": 3,
              "capabilities": ["surfaces"],
              "launchable": true,
              "settingsActivity": ".FeedsSettingsActivity",
              "minHostVersionCode": 6
              $nexusExtra
            },
            "artifact": {
              "target": "phone",
              "url": "https://github.com/Anezium/Rokid-Nexus/releases/download/feeds-v0.1.0/feeds-phone-release.apk",
              "sha256": "${"ab".repeat(32)}",
              "sizeBytes": 1234,
              "packageName": "com.anezium.rokidbus.plugin.feeds",
              "versionCode": 7,
              "versionName": "0.1.0"
              $artifactExtra
            }
            $pluginExtra
          }]
          $rootExtra
        }
    """.trimIndent()
}
