package com.anezium.rokidbus.plugin.nav

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** HTTP freshness as the tile policy asks, and a bounded, evictable cache of geographic tiles only. */
class BasemapCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_800_000_000_000L
    private val key = TileKey(14, 8800, 5373)

    @Test
    fun `the tile service's own headers give five fresh minutes, then an hour shown while checked again`() {
        val headers = mapOf(
            "cache-control" to "public,max-age=300,stale-while-revalidate=3600,stale-if-error=86400",
            "last-modified" to "Sun, 18 Oct 2026 05:10:24 GMT",
            "age" to "20",
        )
        val freshness = TileFreshness.of(now, headers)!!
        assertEquals(now - 20_000, freshness.generatedAtMs)
        assertEquals(300, freshness.lifetimeS)
        assertEquals(3600, freshness.staleWhileRevalidateS)
        assertEquals(86_400, freshness.staleIfErrorS)
        assertEquals("Sun, 18 Oct 2026 05:10:24 GMT", freshness.lastModified)
        assertTrue(freshness.isFresh(now + 279_000))
        assertFalse(freshness.isFresh(now + 280_000))
        assertTrue(freshness.mayShowWhileRevalidating(now + 3_000_000))
        assertFalse(freshness.mayShowWhileRevalidating(now + 3_880_000))
    }

    @Test
    fun `no-store is not kept, no-cache is checked every time, and nothing said means seven days`() {
        assertNull(TileFreshness.of(now, mapOf("cache-control" to "no-store")))
        assertEquals(0, TileFreshness.of(now, mapOf("cache-control" to "no-cache, max-age=600"))!!.lifetimeS)
        assertEquals(TileFreshness.DEFAULT_LIFETIME_S, TileFreshness.of(now, emptyMap())!!.lifetimeS)
        val expires = TileFreshness.of(now, mapOf("date" to "Sun, 18 Oct 2026 09:00:00 GMT", "expires" to "Sun, 18 Oct 2026 10:00:00 GMT"))!!
        assertEquals(3600, expires.lifetimeS)
        // An Expires nobody can read is already expired.
        assertEquals(0, TileFreshness.of(now, mapOf("expires" to "0"))!!.lifetimeS)
        // A 304 renews the copy's freshness and keeps its validator.
        val stored = TileFreshness.of(now, mapOf("cache-control" to "max-age=300", "etag" to "\"v1\""))!!
        val renewed = stored.revalidated(now + 600_000, mapOf("cache-control" to "max-age=300"))
        assertEquals(now + 600_000, renewed.generatedAtMs)
        assertEquals("\"v1\"", renewed.etag)
    }

    @Test
    fun `a stored tile comes back whole with its freshness, and a 304 renews it in place`() {
        val cache = cache()
        val freshness = TileFreshness(now, 300, 3600, 86_400, "Sun, 18 Oct 2026 05:10:24 GMT", null)
        cache.write(OsmShortbreadSource, key, CachedTile(byteArrayOf(1, 2, 3), freshness))
        val read = cache.read(OsmShortbreadSource, key)!!
        assertArrayEquals(byteArrayOf(1, 2, 3), read.body)
        assertEquals(freshness, read.freshness)
        cache.refresh(OsmShortbreadSource, key, freshness.copy(generatedAtMs = now + 1000))
        assertEquals(now + 1000, cache.read(OsmShortbreadSource, key)!!.freshness.generatedAtMs)
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.read(OsmShortbreadSource, key)!!.body)
        assertNull(cache.read(OsmShortbreadSource, TileKey(14, 1, 1)))
    }

    @Test
    fun `the least recently used tiles go first, over either bound`() {
        val cache = cache(maxEntries = 3)
        val keys = (0 until 4).map { TileKey(14, it, 0) }
        keys.take(3).forEach { tile ->
            now += 1_000
            cache.write(OsmShortbreadSource, tile, CachedTile(ByteArray(100), TileFreshness(now, 300)))
        }
        // Reading the oldest makes it the most recently used.
        now += 1_000
        assertNotNull(cache.read(OsmShortbreadSource, keys[0]))
        now += 1_000
        cache.write(OsmShortbreadSource, keys[3], CachedTile(ByteArray(100), TileFreshness(now, 300)))
        assertNotNull(cache.read(OsmShortbreadSource, keys[0]))
        assertNull(cache.read(OsmShortbreadSource, keys[1]))
        assertNotNull(cache.read(OsmShortbreadSource, keys[3]))

        val small = cache(maxBytes = 500, root = folder.newFolder("small"))
        keys.forEach { tile ->
            now += 1_000
            small.write(OsmShortbreadSource, tile, CachedTile(ByteArray(200), TileFreshness(now, 300)))
        }
        assertTrue(files(folder.root.resolve("small")).sumOf { it.length() } <= 500)
        assertNotNull(small.read(OsmShortbreadSource, keys[3]))
        assertNull(small.read(OsmShortbreadSource, keys[0]))
    }

    @Test
    fun `a tile older than the cache keeps anything, or one that cannot be read, is dropped`() {
        val cache = cache(maxAgeMs = 10_000)
        cache.write(OsmShortbreadSource, key, CachedTile(byteArrayOf(1), TileFreshness(now, 300)))
        now += 10_001
        assertNull(cache.read(OsmShortbreadSource, key))
        assertTrue(files(folder.root).isEmpty())

        cache.write(OsmShortbreadSource, key, CachedTile(byteArrayOf(1), TileFreshness(now, 300)))
        files(folder.root).single().writeBytes(byteArrayOf(9, 9, 9))
        assertNull(cache.read(OsmShortbreadSource, key))
        assertTrue(files(folder.root).isEmpty())
    }

    private fun cache(
        maxBytes: Long = BasemapCache.DEFAULT_MAX_BYTES,
        maxEntries: Int = BasemapCache.DEFAULT_MAX_ENTRIES,
        maxAgeMs: Long = BasemapCache.DEFAULT_MAX_AGE_MS,
        root: File = folder.root,
    ) = BasemapCache(root, maxBytes, maxEntries, maxAgeMs) { now }

    private fun files(root: File) = root.walkTopDown().filter { it.isFile }.toList()
}
