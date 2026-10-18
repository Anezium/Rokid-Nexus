package com.anezium.rokidbus.plugin.nav

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** A tile response: [headers] by lower-case name, [body] only for a 200 and never beyond the bound asked for. */
internal class TileResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray? = null)

/** Received policy still applies when reading the body fails, including cancellation. */
internal class TileResponseFailure(val headers: Map<String, String>, cause: IOException) : IOException(cause)

/** One request in flight; [cancel] may come from any thread and makes [execute] fail rather than succeed. */
internal interface TileCall {
    /** @throws IOException on any failure to obtain a whole response, cancellation included. */
    fun execute(): TileResponse

    fun cancel()
}

internal fun interface TileTransport {
    fun call(url: String, headers: Map<String, String>, maxBodyBytes: Int): TileCall
}

/**
 * Plain HTTPS GETs with an identifying User-Agent, as the tile service's policy requires: no
 * redirect followed, no `no-cache` sent, the body read to a bound and gzip kept as it came (the
 * tile is inflated, bounded, only when decoded).
 */
internal class HttpTileTransport(private val userAgent: String = USER_AGENT) : TileTransport {
    override fun call(url: String, headers: Map<String, String>, maxBodyBytes: Int): TileCall = object : TileCall {
        @Volatile private var connection: HttpURLConnection? = null

        @Volatile private var cancelled = false

        override fun execute(): TileResponse {
            if (cancelled) throw IOException("cancelled")
            val request = URI(url).toURL().openConnection() as HttpURLConnection
            connection = request
            var receivedHeaders: Map<String, String>? = null
            try {
                request.connectTimeout = CONNECT_TIMEOUT_MS
                request.readTimeout = READ_TIMEOUT_MS
                request.instanceFollowRedirects = false
                request.setRequestProperty("User-Agent", userAgent)
                request.setRequestProperty("Accept", "application/vnd.mapbox-vector-tile")
                // Asked for explicitly, Android leaves the body compressed: its size is bounded as sent.
                request.setRequestProperty("Accept-Encoding", "gzip")
                headers.forEach(request::setRequestProperty)
                if (cancelled) throw IOException("cancelled")
                val code = request.responseCode
                val responseHeaders = request.headerFields.entries
                    .filter { it.key != null && it.value.isNotEmpty() }
                    .associate { it.key.lowercase(Locale.ROOT) to it.value.joinToString(",") }
                receivedHeaders = responseHeaders
                if (code != HttpURLConnection.HTTP_OK) return TileResponse(code, responseHeaders)
                val declared = request.contentLengthLong
                if (declared > maxBodyBytes) throw IOException("tile response too large")
                val body = request.inputStream.use { input ->
                    val output = ByteArrayOutputStream(if (declared in 1..maxBodyBytes.toLong()) declared.toInt() else 64 * 1024)
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        if (cancelled) throw IOException("cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > maxBodyBytes) throw IOException("tile response too large")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (cancelled) throw IOException("cancelled")
                return TileResponse(code, responseHeaders, body)
            } catch (failure: IOException) {
                throw receivedHeaders?.let { TileResponseFailure(it, failure) } ?: failure
            } finally {
                request.disconnect()
                connection = null
            }
        }

        override fun cancel() {
            cancelled = true
            connection?.disconnect()
        }
    }

    companion object {
        const val USER_AGENT = "RokidNexus-Navigation (+https://github.com/Anezium/Rokid-Nexus)"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 10_000
    }
}

/** What the map can say about its streets: some drawn, being fetched, or none to be had. */
internal enum class BasemapState { NONE, LOADING, READY, UNAVAILABLE }

/**
 * The street tiles of one walking journey. Created with the journey, closed with it, and it asks
 * for nothing on its own: only [demand], called with the tiles the map views on screen need,
 * starts any work. Each tile is read from the cache or fetched on a worker, never on the main
 * thread, at most [MAX_CONCURRENT] at a time, decoded, converted into the journey's projection,
 * and handed back to the main thread, where [tiles] changes and [onChanged] runs.
 *
 * Tiles no view needs any more are dropped from the queue and their requests cancelled; after
 * [close] nothing is started and no late result is applied. Failures back off per tile, and a
 * `429` or `503` stops every request for what the server asks (at least a minute); a hard cap of
 * [MAX_REQUESTS_PER_MINUTE] guards against any loop. None of this ever touches the guidance: a
 * missing tile only means that part of the map shows the route alone.
 */
internal class NavBasemap(
    private val source: BasemapSource,
    private val line: RouteLine,
    private val cache: BasemapCache?,
    private val transport: TileTransport,
    private val post: (Runnable) -> Unit,
    private val onChanged: () -> Unit,
    private val executor: ExecutorService = Executors.newFixedThreadPool(MAX_CONCURRENT),
    private val elapsed: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val prepare: (TileKey, ByteArray, RouteLine) -> LocalTile = { key, body, route ->
        LocalTile.of(ShortbreadTiles.decode(key, body), route)
    },
) {
    private class Loaded(val tile: LocalTile, val freshUntilMs: Long, val reusable: Boolean = true)

    private inner class Job(val key: TileKey, val revalidate: Boolean, val network: Boolean) : Runnable {
        @Volatile var cancelled = false

        @Volatile var invalidatedStorage = false
            private set

        @Volatile private var call: TileCall? = null

        fun cancel() {
            cancelled = true
            call?.cancel()
        }

        private fun invalidateStorage() {
            invalidatedStorage = true
            cache?.remove(source, key)
        }

        override fun run() {
            val outcome = try {
                load()
            } catch (_: Exception) {
                // Nothing a tile does may reach the journey; a crash in decoding is a failed tile.
                Outcome.Failed(stale = null)
            }
            post(Runnable { finished(this, outcome) })
        }

        private fun load(): Outcome {
            if (cancelled || !displayed(key)) return Outcome.Cancelled
            val stored = cache?.read(source, key)?.let { entry ->
                try {
                    entry.freshness to prepare(key, entry.body, line)
                } catch (_: MvtFormatException) {
                    cache.remove(source, key)
                    null
                }
            }
            val mustValidate = stored?.first?.requiresValidation == true
            val storedTile = stored?.second?.takeUnless { mustValidate }
            fun failure() = Outcome.Failed(stale = storedTile, discardPrevious = mustValidate)
            val now = wallClock()
            if (stored != null && !revalidate) {
                val (freshness, tile) = stored
                if (freshness.isFresh(now)) return Outcome.Loaded(tile, freshness.freshUntilMs())
                // Allowed on screen while it is checked again.
                if (freshness.mayShowWhileRevalidating(now)) post(Runnable { shown(this, tile) })
            }
            if (cancelled || !displayed(key)) return Outcome.Cancelled
            if (!network) return if (mustValidate) failure() else storedTile?.let { Outcome.Failed(stale = it) } ?: Outcome.Deferred
            val conditions = buildMap {
                stored?.first?.etag?.let { put("If-None-Match", it) }
                stored?.first?.lastModified?.let { put("If-Modified-Since", it) }
            }
            val request = transport.call(source.url(key), conditions, MAX_RESPONSE_BYTES).also { call = it }
            if (cancelled || !displayed(key)) return Outcome.Cancelled
            post(Runnable { requested() })
            val response = try {
                request.execute()
            } catch (error: IOException) {
                if (error is TileResponseFailure && TileFreshness.of(wallClock(), error.headers) == null) invalidateStorage()
                return if (cancelled) Outcome.Cancelled else failure()
            }
            val received = wallClock()
            val responseFreshness = TileFreshness.of(received, response.headers)
            // Once received, no-store is a storage instruction even if the map disappeared
            // during execute/decode. Keep it separate from the geometry publication outcome.
            if (responseFreshness == null) invalidateStorage()
            if (cancelled || !displayed(key)) return Outcome.Cancelled
            return when (response.code) {
                HttpURLConnection.HTTP_OK -> {
                    val freshness = responseFreshness
                    val body = response.body ?: return if (freshness == null) Outcome.Evicted else failure()
                    val tile = try {
                        prepare(key, body, line)
                    } catch (_: MvtFormatException) {
                        return if (freshness == null) Outcome.Evicted else failure()
                    }
                    if (cancelled || !displayed(key)) return Outcome.Cancelled
                    if (freshness != null) cache?.write(source, key, CachedTile(body, freshness)) else cache?.remove(source, key)
                    Outcome.Loaded(tile, freshness?.freshUntilMs() ?: received, reusable = freshness != null && !freshness.requiresValidation)
                }
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    val (previous, tile) = stored ?: return Outcome.Failed(stale = null)
                    val freshness = previous.revalidated(received, response.headers)
                    if (freshness == null) {
                        invalidateStorage()
                        return Outcome.Evicted
                    }
                    cache.refresh(source, key, freshness)
                    Outcome.Loaded(tile, freshness.freshUntilMs(), reusable = !freshness.requiresValidation)
                }
                HttpURLConnection.HTTP_NO_CONTENT -> {
                    cache?.remove(source, key)
                    // An empty tile is a real answer: nothing to draw there.
                    val empty = LocalTile(key, emptyList(), emptyList())
                    Outcome.Loaded(empty, responseFreshness?.freshUntilMs() ?: received,
                        reusable = responseFreshness != null && !responseFreshness.requiresValidation)
                }
                429, HttpURLConnection.HTTP_UNAVAILABLE -> Outcome.Throttled(retryAfterS(response.headers), stale = storedTile, discardPrevious = mustValidate)
                else -> failure()
            }
        }
    }

    private sealed interface Outcome {
        class Loaded(val tile: LocalTile, val freshUntilMs: Long, val reusable: Boolean = true) : Outcome

        /** No new copy; [stale] is the stored one, shown while nothing better can be had. */
        class Failed(val stale: LocalTile?, val discardPrevious: Boolean = false) : Outcome

        class Throttled(val retryAfterS: Long?, val stale: LocalTile?, val discardPrevious: Boolean = false) : Outcome

        /** Requests are paused; the tile is asked again once they resume. */
        object Deferred : Outcome

        object Cancelled : Outcome

        object Evicted : Outcome
    }

    private val loaded = LinkedHashMap<TileKey, Loaded>(16, 0.75f, true)
    private val jobs = HashMap<TileKey, Job>()
    private val queue = ArrayDeque<Pair<TileKey, Boolean>>()
    private val retryAt = HashMap<TileKey, Long>()
    private val failures = HashMap<TileKey, Int>()
    private val requestTimes = ArrayDeque<Long>()
    private var demanded: List<TileKey> = emptyList()
    @Volatile private var displayed: (TileKey) -> Boolean = { true }
    private var pausedUntil = 0L
    private var throttles = 0
    private var closed = false

    /** The tiles to draw now; replaced, never mutated. */
    var tiles: NavBasemapTiles = NavBasemapTiles(source.attribution, source.maxZoom, emptyMap())
        private set

    /** For the demanded views: some streets loaded, still loading, or none to be had now. */
    val state: BasemapState
        get() = when {
            demanded.isEmpty() -> BasemapState.NONE
            demanded.any(loaded::containsKey) -> BasemapState.READY
            demanded.any { it in jobs || queue.any { (queued, _) -> queued == it } } -> BasemapState.LOADING
            else -> BasemapState.UNAVAILABLE
        }

    /**
     * The tiles the map views on screen need, nearest first; an empty list when no map is shown.
     * Main thread only.
     */
    fun demand(keys: List<TileKey>, displayed: (TileKey) -> Boolean = { true }) {
        if (closed) return
        this.displayed = displayed
        demanded = keys.distinct()
        val wanted = demanded.toSet()
        if (loaded.entries.removeAll { !it.value.reusable && it.key !in wanted }) {
            tiles = NavBasemapTiles(source.attribution, source.maxZoom, loaded.mapValues { it.value.tile })
        }
        jobs.values.filter { it.key !in wanted }.forEach(Job::cancel)
        queue.removeAll { (key, _) -> key !in wanted }
        val now = elapsed()
        val wall = wallClock()
        for (key in demanded) {
            if (key in jobs || queue.any { (queued, _) -> queued == key }) continue
            if ((retryAt[key] ?: Long.MIN_VALUE) > now) continue
            val current = loaded[key]
            if (current != null && wall < current.freshUntilMs) continue
            queue.addLast(key to (current != null))
        }
        pump()
    }

    /** The journey ended: cancel everything, start nothing more, apply nothing late. */
    fun close() {
        if (closed) return
        closed = true
        queue.clear()
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        executor.shutdownNow()
        loaded.clear()
        tiles = NavBasemapTiles(source.attribution, source.maxZoom, emptyMap())
        demanded = emptyList()
    }

    private fun pump() {
        while (!closed && jobs.size < MAX_CONCURRENT && queue.isNotEmpty()) {
            val (key, revalidate) = queue.removeFirst()
            val job = Job(key, revalidate, network = networkAllowed())
            // A stored copy can still be shown while requests are paused; a revalidation cannot run.
            if (!job.network && revalidate) continue
            jobs[key] = job
            try {
                executor.execute(job)
            } catch (_: RejectedExecutionException) {
                jobs.remove(key)
                return
            }
        }
    }

    private fun networkAllowed(): Boolean {
        val now = elapsed()
        if (now < pausedUntil) return false
        while (requestTimes.isNotEmpty() && now - requestTimes.first() >= MINUTE_MS) requestTimes.removeFirst()
        // Requests already sent this minute, and every job that may still send one.
        return requestTimes.size + jobs.values.count { it.network } < MAX_REQUESTS_PER_MINUTE
    }

    private fun requested() {
        if (!closed) requestTimes.addLast(elapsed())
    }

    private fun shown(job: Job, tile: LocalTile) {
        if (closed || job.cancelled || job.invalidatedStorage || jobs[job.key] !== job || job.key !in demanded || !displayed(job.key)) return
        if (loaded[job.key]?.tile === tile) return
        put(job.key, Loaded(tile, 0L))
    }

    private fun finished(job: Job, outcome: Outcome) {
        if (closed || jobs[job.key] !== job) return
        jobs.remove(job.key)
        // Storage invalidation survives a view change between the response and its callback.
        if (job.invalidatedStorage || outcome is Outcome.Evicted || (outcome is Outcome.Loaded && !outcome.reusable) ||
            (outcome is Outcome.Failed && outcome.discardPrevious) || (outcome is Outcome.Throttled && outcome.discardPrevious)) discard(job.key)
        if (job.cancelled || job.key !in demanded || !displayed(job.key)) {
            pump()
            return
        }
        val now = elapsed()
        when (outcome) {
            is Outcome.Loaded -> {
                // However short the response's lifetime, one tile is not asked again within a minute.
                retryAt[job.key] = now + MIN_REFRESH_MS
                failures.remove(job.key)
                throttles = 0
                put(job.key, Loaded(outcome.tile, outcome.freshUntilMs, outcome.reusable))
            }
            is Outcome.Failed -> {
                backOff(job.key, now)
                if (outcome.discardPrevious) discard(job.key)
                // Offline or refused: the stored copy is still real street data, shown until a new one comes.
                if (!job.invalidatedStorage) outcome.stale?.let { stale -> if (loaded[job.key]?.tile == null) put(job.key, Loaded(stale, 0L)) }
            }
            is Outcome.Throttled -> {
                if (outcome.discardPrevious) discard(job.key)
                throttles++
                val wait = (outcome.retryAfterS?.times(1000) ?: (MIN_PAUSE_MS shl (throttles - 1).coerceAtMost(4)))
                    .coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS)
                pausedUntil = now + wait
                retryAt[job.key] = pausedUntil
                if (!job.invalidatedStorage) outcome.stale?.let { stale -> if (loaded[job.key]?.tile == null) put(job.key, Loaded(stale, 0L)) }
            }
            Outcome.Deferred -> retryAt[job.key] = maxOf(pausedUntil, now + MIN_RETRY_MS)
            Outcome.Cancelled -> Unit
            Outcome.Evicted -> {
                discard(job.key)
                retryAt[job.key] = now + MIN_REFRESH_MS
            }
        }
        pump()
    }

    private fun backOff(key: TileKey, now: Long) {
        val count = (failures[key] ?: 0) + 1
        failures[key] = count
        retryAt[key] = now + (MIN_RETRY_MS shl (count - 1).coerceAtMost(4)).coerceAtMost(MAX_RETRY_MS)
    }

    private fun discard(key: TileKey) {
        if (loaded.remove(key) == null) return
        tiles = NavBasemapTiles(source.attribution, source.maxZoom, loaded.mapValues { it.value.tile })
        post(Runnable { if (!closed) onChanged() })
    }

    private fun put(key: TileKey, entry: Loaded) {
        loaded[key] = entry
        // Least recently drawn first, never a tile a view on screen needs.
        val iterator = loaded.entries.iterator()
        while (loaded.size > MAX_MEMORY_TILES && iterator.hasNext()) {
            if (iterator.next().key !in demanded) iterator.remove()
        }
        tiles = NavBasemapTiles(source.attribution, source.maxZoom, loaded.mapValues { it.value.tile })
        // Posted, so the journey redraws from a settled state rather than from inside this one.
        post(Runnable { if (!closed) onChanged() })
    }

    private fun retryAfterS(headers: Map<String, String>): Long? =
        headers["retry-after"]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.coerceAtMost(MAX_PAUSE_MS / 1000)

    companion object {
        const val MAX_CONCURRENT = 2
        const val MAX_REQUESTS_PER_MINUTE = 30
        const val MAX_MEMORY_TILES = 24

        /** A compressed tile response; the densest city tiles measured are about a tenth of it. */
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val MIN_REFRESH_MS = 60_000L
        const val MIN_RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 8 * 60_000L
        const val MIN_PAUSE_MS = 60_000L
        const val MAX_PAUSE_MS = 30 * 60_000L
        private const val MINUTE_MS = 60_000L
    }
}
