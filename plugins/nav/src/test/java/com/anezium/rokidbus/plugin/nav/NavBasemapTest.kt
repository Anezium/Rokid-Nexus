package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.plugin.nav.MvtFixtures.line
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private val warnings = mutableListOf<String>()

    @Test
    fun `an expired rendered lease cannot start a queued request or apply a late completed tile`() {
        val loader = basemap()
        var displayed = true
        loader.demand(listOf(keys[0])) { displayed }
        displayed = false
        settle()
        assertTrue(transport.urls.isEmpty())
        assertTrue(loader.tiles.tiles.isEmpty())
        displayed = true
        loader.demand(listOf(keys[0])) { displayed }
        executor.runAll()
        displayed = false
        drainPosted()
        assertEquals(1, transport.urls.size)
        assertTrue(loader.tiles.tiles.isEmpty())
    }

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
        assertEquals(1, changes)
        assertEquals(listOf("basemap rejected cause=MvtFormatException"), warnings)
    }

    @Test
    fun `cached and fetched rejections warn once per tile across retries without location or exception text`() {
        val cache = BasemapCache(folder.root) { wall }
        keys.take(2).forEach { cache.write(OsmShortbreadSource, it, CachedTile(tile, TileFreshness(wall, 300))) }
        val basemap = basemap(cache) { _, _, _ -> throw MvtFormatException("private provider or location data") }
        basemap.demand(keys.take(2))
        settle()
        assertEquals(2, transport.urls.size)
        assertEquals(List(2) { "basemap rejected cause=MvtFormatException" }, warnings)
        assertEquals(BasemapState.UNAVAILABLE, basemap.state)
        assertTrue(basemap.tiles.tiles.isEmpty())
        keys.take(2).forEach { assertNull(cache.read(OsmShortbreadSource, it)) }
        elapsed += NavBasemap.MIN_RETRY_MS
        basemap.demand(keys.take(2))
        settle()
        assertEquals(4, transport.urls.size)
        assertEquals(2, warnings.size)
        assertEquals(BasemapState.UNAVAILABLE, basemap.state)
    }

    @Test
    fun `unexpected basemap failures also warn with the class alone`() {
        val basemap = basemap { _, _, _ -> throw IllegalStateException("private provider or location data") }
        basemap.demand(listOf(keys[0]))
        settle()
        assertEquals(listOf("basemap rejected cause=IllegalStateException"), warnings)
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

    @Test
    fun `metadata-light 304 preserves disk policy and next refresh timing including no-cache`() {
        for (policy in listOf("max-age=300,stale-while-revalidate=600,stale-if-error=900", "no-cache,max-age=300")) {
            val cache = BasemapCache(folder.newFolder()) { wall }
            cache.write(OsmShortbreadSource, keys[0], CachedTile(tile, TileFreshness.of(wall - 600_000,
                mapOf("cache-control" to policy, "etag" to "v1"))!!))
            transport.urls.clear()
            transport.headers.clear()
            transport.respond = { _, _ -> TileResponse(304, mapOf("etag" to "v2", "age" to "10")) }
            val loader = basemap(cache)
            loader.demand(listOf(keys[0]))
            settle()
            val stored = cache.read(OsmShortbreadSource, keys[0])!!.freshness
            assertEquals(if (policy.startsWith("no-cache")) 0L else 300L, stored.lifetimeS)
            assertEquals(policy, stored.cacheControl)
            assertEquals("v2", stored.etag)
            assertEquals(wall - 10_000, stored.generatedAtMs)
            elapsed += NavBasemap.MIN_REFRESH_MS
            wall += NavBasemap.MIN_REFRESH_MS
            loader.demand(listOf(keys[0]))
            settle()
            assertEquals(if (policy.startsWith("no-cache")) 2 else 1, transport.urls.size)
            elapsed += 301_000
            wall += 301_000
            loader.demand(listOf(keys[0]))
            settle()
            assertEquals(if (policy.startsWith("no-cache")) 3 else 2, transport.urls.size)
            loader.close()
        }
    }

    @Test
    fun `304 replaces supplied cache policy and no-store evicts disk and already shown stale tile`() {
        val cache = BasemapCache(folder.root) { wall }
        cache.write(OsmShortbreadSource, keys[0], CachedTile(tile, TileFreshness.of(wall - 600_000,
            mapOf("cache-control" to "max-age=300,stale-while-revalidate=3600", "etag" to "v1"))!!))
        transport.respond = { _, _ -> TileResponse(304, mapOf("cache-control" to "max-age=60")) }
        val loader = basemap(cache)
        loader.demand(listOf(keys[0]))
        settle()
        assertEquals(60L, cache.read(OsmShortbreadSource, keys[0])!!.freshness.lifetimeS)
        assertEquals(0L, cache.read(OsmShortbreadSource, keys[0])!!.freshness.staleWhileRevalidateS)
        elapsed += 61_000
        wall += 61_000
        transport.respond = { _, _ -> TileResponse(304, mapOf("cache-control" to "no-store")) }
        loader.demand(listOf(keys[0]))
        settle()
        assertNull(cache.read(OsmShortbreadSource, keys[0]))
        assertTrue(loader.tiles.tiles.isEmpty())
        assertTrue(folder.root.walkTopDown().none { it.isFile && it.name.endsWith(".tile") })
        loader.close()

        transport.respond = { _, _ -> throw IOException("offline") }
        val reopened = basemap(cache)
        reopened.demand(listOf(keys[0]))
        settle()
        assertTrue(reopened.tiles.tiles.isEmpty())
        assertTrue(transport.headers.last().isEmpty())
    }

    @Test
    fun `no-cache remains stored for validators but cannot be resurrected offline after revalidation fails`() {
        val cache = BasemapCache(folder.root) { wall }
        cache.write(OsmShortbreadSource, keys[0], CachedTile(tile, TileFreshness.of(wall,
            mapOf("cache-control" to "no-cache,max-age=300,stale-while-revalidate=3600", "etag" to "v1"))!!))
        transport.respond = { _, _ -> TileResponse(304, emptyMap()) }
        val loader = basemap(cache)
        loader.demand(listOf(keys[0]))
        settle()
        assertTrue(loader.tiles.tiles.isNotEmpty())
        assertTrue(cache.read(OsmShortbreadSource, keys[0])!!.freshness.requiresValidation)
        elapsed += NavBasemap.MIN_REFRESH_MS
        wall += NavBasemap.MIN_REFRESH_MS
        transport.respond = { _, _ -> throw IOException("offline") }
        loader.demand(listOf(keys[0]))
        settle()
        assertTrue(loader.tiles.tiles.isEmpty())
        assertEquals("v1", cache.read(OsmShortbreadSource, keys[0])!!.freshness.etag)
    }

    @Test
    fun `200 no-store removes an older disk copy and cannot be reused after view hide`() {
        val cache = BasemapCache(folder.root) { wall }
        cache.write(OsmShortbreadSource, keys[0], CachedTile(tile, TileFreshness(wall - 600_000, 300)))
        transport.respond = { _, _ -> TileResponse(200, mapOf("cache-control" to "no-store"), tile) }
        val loader = basemap(cache)
        loader.demand(listOf(keys[0]))
        settle()
        assertNull(cache.read(OsmShortbreadSource, keys[0]))
        loader.demand(emptyList())
        assertTrue(loader.tiles.tiles.isEmpty())
        elapsed += NavBasemap.MIN_REFRESH_MS
        transport.respond = { _, _ -> throw IOException("offline") }
        loader.demand(listOf(keys[0]))
        settle()
        assertTrue(loader.tiles.tiles.isEmpty())
    }

    @Test
    fun `no-store invalidation survives hiding before the response callback and cannot resurrect RAM streets`() {
        val cache = BasemapCache(folder.root) { wall }
        cache.write(OsmShortbreadSource, keys[0], CachedTile(tile, TileFreshness.of(wall,
            mapOf("cache-control" to "max-age=300", "etag" to "v1"))!!))
        val loader = basemap(cache)
        loader.demand(listOf(keys[0]))
        settle()
        assertTrue(loader.tiles.tiles.isNotEmpty())
        elapsed += 600_000
        wall += 600_000
        transport.respond = { _, _ -> TileResponse(304, mapOf("cache-control" to "no-store")) }
        loader.demand(listOf(keys[0]))
        executor.runAll()
        loader.demand(emptyList())
        drainPosted()
        assertNull(cache.read(OsmShortbreadSource, keys[0]))
        assertTrue(loader.tiles.tiles.isEmpty())
        transport.respond = { _, _ -> throw IOException("offline") }
        loader.demand(listOf(keys[0]))
        settle()
        assertTrue(loader.tiles.tiles.isEmpty())
    }

    @Test
    fun `received no-store evicts disk and RAM when demand ends after execute or during decode`() {
        for (duringDecode in listOf(false, true)) {
            val cache = BasemapCache(folder.newFolder()) { wall }
            lateinit var loader: NavBasemap
            var hideDuringPreparation = false
            loader = basemap(cache) { key, body, line ->
                val prepared = LocalTile.of(ShortbreadTiles.decode(key, body), line)
                if (hideDuringPreparation) loader.demand(emptyList())
                prepared
            }
            transport.respond = { _, _ -> TileResponse(200, mapOf("cache-control" to "max-age=300"), tile) }
            loader.demand(listOf(keys[0]))
            settle()
            assertTrue(loader.tiles.tiles.isNotEmpty())
            elapsed += 600_000
            wall += 600_000
            transport.respond = { _, _ ->
                if (duringDecode) hideDuringPreparation = true else loader.demand(emptyList())
                TileResponse(200, mapOf("cache-control" to "no-store"), tile)
            }
            loader.demand(listOf(keys[0]))
            settle()
            assertNull(cache.read(OsmShortbreadSource, keys[0]))
            assertTrue("duringDecode=$duringDecode", loader.tiles.tiles.isEmpty())
            hideDuringPreparation = false
            transport.respond = { _, _ -> throw IOException("offline") }
            loader.demand(listOf(keys[0]))
            settle()
            assertTrue(loader.tiles.tiles.isEmpty())
            assertTrue(transport.headers.last().isEmpty())
            loader.close()
        }
    }

    @Test
    fun `production HTTP no-store headers evict prior cache when declared or streamed bodies are rejected`() {
        for (declared in listOf(false, true)) for (hideBeforeCatch in listOf(false, true)) {
            // Match HttpServer's real Date before advancing the fixture clock for expiry.
            wall = System.currentTimeMillis()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val source = object : BasemapSource by OsmShortbreadSource {
                override fun url(key: TileKey) = "http://127.0.0.1:${server.address.port}/tile"
            }
            val rejectBody = AtomicBoolean()
            server.createContext("/tile") { exchange ->
                val rejected = rejectBody.get()
                exchange.responseHeaders.add("Cache-Control", if (rejected) "no-store" else "max-age=300")
                exchange.responseHeaders.add("ETag", "v1")
                val body = if (rejected) ByteArray(2048) else tile
                exchange.sendResponseHeaders(200, if (rejected && !declared) 0 else body.size.toLong())
                // The client may close as soon as it rejects Content-Length.
                try { exchange.responseBody.use { it.write(body) } } catch (_: IOException) { exchange.close() }
            }
            server.start()
            val cache = BasemapCache(folder.newFolder()) { wall }
            val sentHeaders = mutableListOf<Map<String, String>>()
            var offline = false
            lateinit var loader: NavBasemap
            val http = HttpTileTransport()
            val transport = TileTransport { url, headers, limit ->
                sentHeaders += headers
                val call = http.call(url, headers, minOf(limit, 1024))
                object : TileCall {
                    override fun execute(): TileResponse {
                        if (offline) throw IOException("offline")
                        return try { call.execute() } catch (failure: IOException) {
                            assertTrue(failure is TileResponseFailure)
                            assertEquals("no-store", (failure as TileResponseFailure).headers["cache-control"])
                            if (hideBeforeCatch) loader.demand(emptyList())
                            throw failure
                        }
                    }
                    override fun cancel() = call.cancel()
                }
            }
            loader = basemap(cache, source, transport)
            try {
                loader.demand(listOf(keys[0]))
                settle()
                assertTrue(loader.tiles.tiles.isNotEmpty())
                assertTrue(cache.read(source, keys[0]) != null)
                elapsed += 600_000
                wall += 600_000
                rejectBody.set(true)
                loader.demand(listOf(keys[0]))
                settle()
                assertEquals("v1", sentHeaders.last()["If-None-Match"])
                assertNull(cache.read(source, keys[0]))
                assertTrue("declared=$declared hideBeforeCatch=$hideBeforeCatch", loader.tiles.tiles.isEmpty())
                loader.demand(emptyList())
                offline = true
                elapsed += NavBasemap.MIN_REFRESH_MS
                loader.demand(listOf(keys[0]))
                settle()
                assertTrue(loader.tiles.tiles.isEmpty())
                assertTrue(sentHeaders.last().isEmpty())
                loader.close()
                val reopened = basemap(cache, source, transport)
                reopened.demand(listOf(keys[0]))
                settle()
                assertTrue(reopened.tiles.tiles.isEmpty())
                assertTrue(sentHeaders.last().isEmpty())
                reopened.close()
            } finally {
                loader.close()
                server.stop(0)
            }
        }
    }

    private fun basemap(cache: BasemapCache? = null,
        source: BasemapSource = OsmShortbreadSource,
        transport: TileTransport = this.transport,
        prepare: (TileKey, ByteArray, RouteLine) -> LocalTile = { key, body, line -> LocalTile.of(ShortbreadTiles.decode(key, body), line) },
    ) = NavBasemap(
        // Each journey has its own workers; closing one shuts only those.
        executor = ManualExecutor().also { executor = it },
        source = source,
        line = route,
        cache = cache,
        transport = transport,
        post = { posted.addLast(it) },
        onChanged = { changes++ },
        elapsed = { elapsed },
        wallClock = { wall },
        prepare = prepare,
        warn = warnings::add,
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
