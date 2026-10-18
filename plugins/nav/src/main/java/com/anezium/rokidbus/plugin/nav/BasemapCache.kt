package com.anezium.rokidbus.plugin.nav

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * What one tile response allows, read from its headers (RFC 9111): how long it is fresh, how long
 * a stale copy may still be shown while it is checked again or while the server fails, and the
 * validators to check it with. Without any expiry the tile service's policy asks for at least
 * [DEFAULT_LIFETIME_S].
 */
internal data class TileFreshness(
    /** Wall-clock time the response was generated, by our clock: received, less its `Age`. */
    val generatedAtMs: Long,
    val lifetimeS: Long,
    val staleWhileRevalidateS: Long = 0,
    val staleIfErrorS: Long = 0,
    val lastModified: String? = null,
    val etag: String? = null,
) {
    fun freshUntilMs(): Long = generatedAtMs + lifetimeS * 1000

    fun isFresh(nowMs: Long): Boolean = nowMs < freshUntilMs()

    /** Stale, but explicitly allowed on screen while a new copy is fetched. */
    fun mayShowWhileRevalidating(nowMs: Long): Boolean = nowMs < freshUntilMs() + staleWhileRevalidateS * 1000

    /** The same copy, confirmed unchanged by a 304 at [receivedAtMs] with [headers]. */
    fun revalidated(receivedAtMs: Long, headers: Map<String, String>): TileFreshness =
        of(receivedAtMs, headers)?.let { fresh -> fresh.copy(lastModified = fresh.lastModified ?: lastModified, etag = fresh.etag ?: etag) }
            ?: copy(generatedAtMs = receivedAtMs)

    companion object {
        const val DEFAULT_LIFETIME_S = 7L * 24 * 60 * 60
        private const val MAX_LIFETIME_S = 365L * 24 * 60 * 60
        private const val MAX_VALIDATOR_CHARS = 256

        /** The freshness of a response with [headers] (names lower-case) received at [receivedAtMs], or null for `no-store`. */
        fun of(receivedAtMs: Long, headers: Map<String, String>): TileFreshness? {
            val directives = headers["cache-control"].orEmpty().split(',').associate { part ->
                val name = part.substringBefore('=').trim().lowercase(Locale.ROOT)
                name to part.substringAfter('=', "").trim().trim('"')
            }
            if ("no-store" in directives) return null
            fun seconds(name: String): Long? = directives[name]?.toLongOrNull()?.takeIf { it >= 0 }?.coerceAtMost(MAX_LIFETIME_S)
            val age = headers["age"]?.trim()?.toLongOrNull()?.coerceIn(0, MAX_LIFETIME_S) ?: 0L
            val generatedAt = receivedAtMs - age * 1000
            val lifetime = when {
                "no-cache" in directives -> 0L
                seconds("max-age") != null -> seconds("max-age")!!
                headers["expires"] != null -> {
                    val expires = httpDate(headers["expires"])
                    val date = httpDate(headers["date"]) ?: receivedAtMs
                    // An Expires that cannot be read means already expired (RFC 9111 §5.3).
                    if (expires == null) 0L else ((expires - date) / 1000).coerceIn(0, MAX_LIFETIME_S)
                }
                else -> DEFAULT_LIFETIME_S
            }
            return TileFreshness(
                generatedAtMs = generatedAt,
                lifetimeS = lifetime,
                staleWhileRevalidateS = seconds("stale-while-revalidate") ?: 0,
                staleIfErrorS = seconds("stale-if-error") ?: 0,
                lastModified = headers["last-modified"]?.takeIf { it.length <= MAX_VALIDATOR_CHARS && it.none(Char::isISOControl) },
                etag = headers["etag"]?.takeIf { it.length <= MAX_VALIDATOR_CHARS && it.none(Char::isISOControl) },
            )
        }

        private fun httpDate(value: String?): Long? {
            value ?: return null
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
            return try {
                format.parse(value.trim())?.time
            } catch (_: ParseException) {
                null
            }
        }
    }
}

/** One cached tile: its response body as received (still compressed) and its freshness. */
internal class CachedTile(val body: ByteArray, val freshness: TileFreshness)

/**
 * The phone's tile cache: geographic tiles only, as the tile service requires, in the app's cache
 * directory so Android may clear it at will. It holds at most [maxBytes] in [maxEntries] tiles and
 * drops the least recently used first, and anything received more than [maxAgeMs] ago. Nothing
 * about a journey is stored with a tile. Safe to call from several worker threads.
 */
internal class BasemapCache(
    private val root: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun read(source: BasemapSource, key: TileKey): CachedTile? {
        val file = fileOf(source, key)
        if (!file.isFile) return null
        val entry = try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) throw IOException("not a tile entry")
                val freshness = TileFreshness(
                    generatedAtMs = input.readLong(),
                    lifetimeS = input.readLong(),
                    staleWhileRevalidateS = input.readLong(),
                    staleIfErrorS = input.readLong(),
                    lastModified = input.readUTF().ifEmpty { null },
                    etag = input.readUTF().ifEmpty { null },
                )
                val length = input.readInt()
                if (length !in 0..MAX_ENTRY_BYTES) throw IOException("entry too large")
                val body = ByteArray(length)
                input.readFully(body)
                CachedTile(body, freshness)
            }
        } catch (_: IOException) {
            file.delete()
            return null
        }
        if (wallClock() - entry.freshness.generatedAtMs > maxAgeMs) {
            file.delete()
            return null
        }
        file.setLastModified(wallClock())
        return entry
    }

    @Synchronized
    fun write(source: BasemapSource, key: TileKey, tile: CachedTile) {
        if (tile.body.size > MAX_ENTRY_BYTES) return
        val file = fileOf(source, key)
        file.parentFile?.mkdirs()
        val bytes = ByteArrayOutputStream(tile.body.size + 128).also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeLong(tile.freshness.generatedAtMs)
                output.writeLong(tile.freshness.lifetimeS)
                output.writeLong(tile.freshness.staleWhileRevalidateS)
                output.writeLong(tile.freshness.staleIfErrorS)
                output.writeUTF(tile.freshness.lastModified.orEmpty())
                output.writeUTF(tile.freshness.etag.orEmpty())
                output.writeInt(tile.body.size)
                output.write(tile.body)
            }
        }.toByteArray()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) {
                file.delete()
                if (!temporary.renameTo(file)) throw IOException("could not store the tile")
            }
        } catch (_: IOException) {
            temporary.delete()
            return
        }
        file.setLastModified(wallClock())
        trim()
    }

    /** Only the freshness changed: a 304 confirmed the stored copy. */
    @Synchronized
    fun refresh(source: BasemapSource, key: TileKey, freshness: TileFreshness) {
        val current = read(source, key) ?: return
        write(source, key, CachedTile(current.body, freshness))
    }

    @Synchronized
    fun remove(source: BasemapSource, key: TileKey) {
        fileOf(source, key).delete()
    }

    /** Over a bound, the least recently used tiles go first; expired ones always go. */
    @Synchronized
    fun trim() {
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(SUFFIX) }.toList()
        val now = wallClock()
        val kept = files.filter { file ->
            // The file's own time is its last use; one untouched beyond the age bound is gone either way.
            if (now - file.lastModified() > maxAgeMs) !file.delete() else true
        }.sortedByDescending { it.lastModified() }
        var total = 0L
        kept.forEachIndexed { index, file ->
            total += file.length()
            if (index >= maxEntries || total > maxBytes) file.delete()
        }
    }

    private fun fileOf(source: BasemapSource, key: TileKey): File =
        File(root, "${source.id}/${key.z}/${key.x}/${key.y}$SUFFIX")

    companion object {
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES = 1024
        const val DEFAULT_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
        private const val MAGIC = 0x4e425431
        private const val SUFFIX = ".tile"
    }
}
