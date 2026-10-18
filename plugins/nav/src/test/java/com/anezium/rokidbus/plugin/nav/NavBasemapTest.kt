package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.plugin.nav.MvtFixtures.line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * The journey's tile loader: nothing without a view asking, bounded and cancellable work, results
 * that never outlive their journey, the cache and its validators, and back-off instead of storms.
 */
class NavBasemapTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val route = RouteLine(listOf(GeoPoint(52.5163, 13.3765), GeoPoint(52.5163, 13.3710)))
    private val tile = MvtFixtures.gzip(MvtFixtures.shortbread(listOf(line(0 to 0, 4096 to 4096, tags = mapOf("kind" to "residential")))))
    private val keys = (0 until 5).map { TileKey(14, 8800 + it, 5373) }
    private var elapsed = 1_000_000L
    private var wall = 1_800_000_000_000L
    private var executor = ManualExecutor()
    private val posted = ArrayDeque<Runnable>()
    private val transport = FakeTransport()
    private var changes = 0

    @Test
    fun `nothing is asked for until a view needs tiles, then only those tiles`() {
        val basemap = basemap()
        assertEquals(BasemapState.NONE, basemap.state)
        settle()
        assertTrue(transport.urls.isEmpty())
        basemap.demand(emptyList())
        settle()
        assertTrue(transport.urls.isEmpty())

        basemap.demand(listOf(keys[0]))
        assertEquals(BasemapState.LOADING, basemap.state)
        settle()
        assertEquals(listOf("https://vector.openstreetmap.org/shortbread_v1/14/8800/5373.mvt"), transport.urls)
        assertEquals(setOf(keys[0]), basemap.tiles.tiles.keys)
        assertEquals(BasemapState.READY, basemap.state)
        assertEquals(1, changes)
        // Fresh: asking again within its lifetime costs nothing.
        basemap.demand(listOf(keys[0]))
        settle()
        assertEquals(1, transport.urls.size)
    }

    @Test
    fun `two requests at most, and a tile no view needs any more is cancelled, never shown`() {
        val basemap = basemap()
        basemap.demand(keys.take(4))
        assertEquals(2, executor.pending)
        // The view moved before either request went out: neither goes, and the rest never queue.
        basemap.demand(listOf(keys[3]))
        settle()
        assertEquals(listOf(OsmShortbreadSource.url(keys[3])), transport.urls)
        assertEquals(setOf(keys[3]), basemap.tiles.tiles.keys)

        // A request already on the wire is cancelled too, and what it would have brought is dropped.
        transport.during = { basemap.demand(listOf(keys[4])) }
        basemap.demand(listOf(keys[2]))
        settle()
        assertEquals(listOf(keys[3], keys[2], keys[4]).map(OsmShortbreadSource::url), transport.urls)
        assertTrue(transport.calls[1].cancelled)
        assertFalse(keys[2] in basemap.tiles.tiles.keys)
        assertTrue(keys[4] in basemap.tiles.tiles.keys)
        // A cancellation is not a failure: the tile can be asked for again at once.
        basemap.demand(listOf(keys[2]))
        settle()
        assertTrue(keys[2] in basemap.tiles.tiles.keys)
    }

    @Test
    fun `after close nothing more starts and a late result is not applied`() {
        val basemap = basemap()
        basemap.demand(listOf(keys[0]))
        executor.runAll()
        basemap.close()
        drainPosted()
        assertTrue(basemap.tiles.tiles.isEmpty())
        assertEquals(0, changes)
        basemap.demand(listOf(keys[1]))
        settle()
        assertEquals(1, transport.urls.size)
        assertTrue(executor.isShutdown)
        assertEquals(BasemapState.NONE, basemap.state)
    }

    @Test
    fun `a fresh cached tile needs no request, a stale one is shown and checked again with its validator`() {
        val cache = BasemapCache(folder.root) { wall }
        val headers = mapOf("cache-control" to "max-age=300,stale-while-revalidate=3600", "last-modified" to "Sun, 18 Oct 2026 05:10:24 GMT")
        cache.write(OsmShortbreadSource, keys[0], CachedTile(tile, TileFreshness.of(wall, headers)!!))

        val first = basemap(cache)
        first.demand(listOf(keys[0]))
        settle()
        assertTrue(transport.urls.isEmpty())
        assertEquals(setOf(keys[0]), first.tiles.tiles.keys)
        first.close()

        wall += 600_000
        elapsed += 600_000
        transport.respond = { _, _ -> TileResponse(304, mapOf("cache-control" to "max-age=300")) }
        val second = basemap(cache)
        second.demand(listOf(keys[0]))
        settle()
        assertEquals(1, transport.urls.size)
        assertEquals("Sun, 18 Oct 2026 05:10:24 GMT", transport.headers.single()["If-Modified-Since"])
        assertEquals(setOf(keys[0]), second.tiles.tiles.keys)
        assertEquals(wall, cache.read(OsmShortbreadSource, keys[0])!!.freshness.generatedAtMs)
    }

    @Test
    fun `offline, the stored copy is still drawn and the tile is not asked again at once`() {
        val cache = BasemapCache(folder.root) { wall }
        cache.write(OsmShortbreadSource, keys[0], CachedTile(tile, TileFreshness(wall, 300)))
        wall += 24 * 60 * 60 * 1000L
        transport.respond = { _, _ -> throw IOException("offline") }
        val basemap = basemap(cache)
        basemap.demand(listOf(keys[0], keys[1]))
        settle()
        assertEquals(setOf(keys[0]), basemap.tiles.tiles.keys)
        assertEquals(BasemapState.READY, basemap.state)
        assertEquals(2, transport.urls.size)
        basemap.demand(listOf(keys[0], keys[1]))
        settle()
        assertEquals(2, transport.urls.size)
        elapsed += NavBasemap.MIN_RETRY_MS
        basemap.demand(listOf(keys[0], keys[1]))
        settle()
        assertEquals(4, transport.urls.size)
        // Nothing stored and nothing reachable: honestly unavailable, never an invented tile.
        val other = basemap()
        other.demand(listOf(keys[2]))
        settle()
        assertTrue(other.tiles.tiles.isEmpty())
        assertEquals(BasemapState.UNAVAILABLE, other.state)
    }

    @Test
    fun `a 429 pauses every request for what the server asks`() {
        transport.respond = { _, _ -> TileResponse(429, mapOf("retry-after" to "120")) }
        val basemap = basemap()
        basemap.demand(listOf(keys[0]))
        settle()
        assertEquals(1, transport.urls.size)
        transport.respond = { _, _ -> TileResponse(200, emptyMap(), tile) }
        basemap.demand(listOf(keys[0], keys[1]))
        settle()
        assertEquals(1, transport.urls.size)
        elapsed += 120_000
        basemap.demand(listOf(keys[0], keys[1]))
        settle()
        assertEquals(3, transport.urls.size)
        assertEquals(setOf(keys[0], keys[1]), basemap.tiles.tiles.keys)
    }

    @Test
    fun `a malformed tile is neither shown nor cached`() {
        val cache = BasemapCache(folder.root) { wall }
        transport.respond = { _, _ -> TileResponse(200, mapOf("cache-control" to "max-age=300"), byteArrayOf(0x1a, 0x7f, 0x01)) }
        val basemap = basemap(cache)
        basemap.demand(listOf(keys[0]))
        settle()
        assertTrue(basemap.tiles.tiles.isEmpty())
        assertNull(cache.read(OsmShortbreadSource, keys[0]))
        assertEquals(BasemapState.UNAVAILABLE, basemap.state)
    }

    @Test
    fun `however short a tile's lifetime, it is not asked again within a minute`() {
        transport.respond = { _, _ -> TileResponse(200, mapOf("cache-control" to "no-store"), tile) }
        val basemap = basemap()
        repeat(30) {
            elapsed += 1_000
            wall += 1_000
            basemap.demand(listOf(keys[0]))
            settle()
        }
        assertEquals(1, transport.urls.size)
        elapsed += NavBasemap.MIN_REFRESH_MS
        basemap.demand(listOf(keys[0]))
        settle()
        assertEquals(2, transport.urls.size)
    }

    @Test
    fun `no more than thirty requests go out in a minute`() {
        transport.respond = { _, _ -> throw IOException("unreachable") }
        val basemap = basemap()
        val many = (0 until 40).map { TileKey(14, 100 + it, 100) }
        repeat(20) {
            basemap.demand(many)
            settle()
        }
        assertEquals(NavBasemap.MAX_REQUESTS_PER_MINUTE, transport.urls.size)
    }

    private fun basemap(cache: BasemapCache? = null) = NavBasemap(
        // Each journey has its own workers; closing one shuts only those.
        executor = ManualExecutor().also { executor = it },
        source = OsmShortbreadSource,
        line = route,
        cache = cache,
        transport = transport,
        post = { posted.addLast(it) },
        onChanged = { changes++ },
        elapsed = { elapsed },
        wallClock = { wall },
    )

    private fun drainPosted() {
        while (posted.isNotEmpty()) posted.removeFirst().run()
    }

    /** Runs every queued job and every result they post, until nothing is left to do. */
    private fun settle() {
        while (executor.pending > 0 || posted.isNotEmpty()) {
            executor.runAll()
            drainPosted()
        }
    }

    private class ManualExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var shut = false
        val pending: Int get() = tasks.size

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }

        override fun execute(command: Runnable) {
            if (shut) throw RejectedExecutionException()
            tasks.addLast(command)
        }

        override fun shutdown() {
            shut = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shut = true
            return tasks.toMutableList().also { tasks.clear() }
        }

        override fun isShutdown() = shut

        override fun isTerminated() = shut && tasks.isEmpty()

        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private inner class FakeTransport : TileTransport {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        val calls = mutableListOf<Call>()
        var respond: (String, Map<String, String>) -> TileResponse = { _, _ -> TileResponse(200, mapOf("cache-control" to "max-age=300"), tile) }

        /** Runs once, inside the next request, as if the view changed while it was on the wire. */
        var during: (() -> Unit)? = null

        inner class Call(private val url: String, private val requestHeaders: Map<String, String>) : TileCall {
            @Volatile var cancelled = false

            override fun execute(): TileResponse {
                urls += url
                headers += requestHeaders
                during?.also { during = null }?.invoke()
                if (cancelled) throw IOException("cancelled")
                return respond(url, requestHeaders)
            }

            override fun cancel() {
                cancelled = true
            }
        }

        override fun call(url: String, headers: Map<String, String>, maxBodyBytes: Int): TileCall = Call(url, headers).also(calls::add)
    }
}
