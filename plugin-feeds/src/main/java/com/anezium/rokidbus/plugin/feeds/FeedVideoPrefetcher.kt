package com.anezium.rokidbus.plugin.feeds

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL

/** Downloads one bounded VOD asset before Wi-Fi Direct changes the phone's network topology. */
internal class FeedVideoPrefetcher(
    private val cacheDir: File,
    private val addressResolver: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) {
    init {
        cacheDir.listFiles { file -> file.name.startsWith(TEMP_FILE_PREFIX) }
            .orEmpty()
            .forEach { it.delete() }
    }

    fun prepare(variant: FeedMediaVariant, keepRunning: () -> Boolean): File {
        val output = File.createTempFile(TEMP_FILE_PREFIX, ".media", cacheDir)
        return try {
            when (variant.container) {
                FeedMediaContainer.MP4 -> FileOutputStream(output).use { sink ->
                    download(variant.url, sink, 0L, MAX_MEDIA_BYTES, keepRunning)
                }
                FeedMediaContainer.HLS -> prepareHls(variant.url, output, keepRunning)
            }
            require(output.length() in 1..MAX_MEDIA_BYTES) { "Video download is empty or oversized" }
            output
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }

    private fun prepareHls(url: String, output: File, keepRunning: () -> Boolean) {
        var playlistUrl = validate(URL(url))
        var playlist = fetchText(playlistUrl, keepRunning)
        if (playlist.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF:") }) {
            playlistUrl = selectRendition(playlistUrl, playlist)
            playlist = fetchText(playlistUrl, keepRunning)
        }
        require(playlist.lineSequence().any { it.trim() == "#EXT-X-ENDLIST" }) {
            "Live HLS playlists are not supported"
        }
        require(playlist.lineSequence().none {
            it.startsWith("#EXT-X-KEY:") && !it.contains("METHOD=NONE")
        }) { "Encrypted HLS is not supported" }
        require(playlist.lineSequence().none { it.startsWith("#EXT-X-BYTERANGE:") }) {
            "Byte-range HLS is not supported"
        }
        val durationSeconds = playlist.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("#EXTINF:") }
            .sumOf { it.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0 }
        require(durationSeconds in 0.001..MAX_DURATION_SECONDS) { "HLS duration is unsupported" }

        val segments = playlist.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .take(MAX_HLS_SEGMENTS + 1)
            .toList()
        require(segments.isNotEmpty() && segments.size <= MAX_HLS_SEGMENTS) {
            "HLS segment count is unsupported"
        }
        val resources = buildList {
            playlist.lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("#EXT-X-MAP:") }
                ?.let(::quotedUri)
                ?.let(::add)
            addAll(segments)
        }
        FileOutputStream(output).use { sink ->
            var total = 0L
            resources.forEach { reference ->
                total = download(
                    URI(playlistUrl.toString()).resolve(reference).toURL().toString(),
                    sink,
                    total,
                    MAX_MEDIA_BYTES,
                    keepRunning,
                )
            }
        }
    }

    private fun selectRendition(base: URL, playlist: String): URL {
        val lines = playlist.lineSequence().map(String::trim).toList()
        val candidates = buildList {
            lines.forEachIndexed { index, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF:")) return@forEachIndexed
                val uri = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
                    ?: return@forEachIndexed
                val bandwidth = attribute(line, "BANDWIDTH")?.toLongOrNull() ?: Long.MAX_VALUE
                val resolution = attribute(line, "RESOLUTION")?.split('x')?.mapNotNull(String::toIntOrNull)
                val width = resolution?.getOrNull(0)
                val height = resolution?.getOrNull(1)
                val codecs = attribute(line, "CODECS")
                if (bandwidth <= MAX_BITRATE && (width == null || width <= 1280) &&
                    (height == null || height <= 720) &&
                    (codecs.isNullOrBlank() || codecs.contains("avc1", ignoreCase = true))
                ) add(Triple(uri, bandwidth, (width ?: 0) * (height ?: 0)))
            }
        }
        val selected = candidates.maxWithOrNull(compareBy<Triple<String, Long, Int>> { it.second }.thenBy { it.third })
            ?: throw IOException("No compatible HLS rendition")
        return validate(URI(base.toString()).resolve(selected.first).toURL())
    }

    private fun fetchText(url: URL, keepRunning: () -> Boolean): String {
        val bytes = java.io.ByteArrayOutputStream().use { sink ->
            download(url.toString(), sink, 0L, MAX_PLAYLIST_BYTES.toLong(), keepRunning)
            sink.toByteArray()
        }
        return String(bytes, Charsets.UTF_8).also {
            require(it.startsWith("#EXTM3U")) { "Invalid HLS playlist" }
        }
    }

    private fun download(
        url: String,
        sink: java.io.OutputStream,
        initialBytes: Long,
        maxBytes: Long,
        keepRunning: () -> Boolean,
    ): Long {
        var current = validate(URL(url))
        repeat(MAX_REDIRECTS + 1) { redirect ->
            check(keepRunning()) { "Video download cancelled" }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("Accept", "video/*,application/vnd.apple.mpegurl,application/x-mpegURL")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val status = connection.responseCode
                if (status in REDIRECTS) {
                    if (redirect == MAX_REDIRECTS) throw IOException("Too many video redirects")
                    val location = connection.getHeaderField("Location")
                        ?.takeIf(String::isNotBlank)
                        ?: throw IOException("Video redirect has no destination")
                    current = validate(URL(current, location))
                    return@repeat
                }
                if (status !in 200..299) throw IOException("Video HTTP $status")
                val declared = connection.contentLengthLong
                if (declared > 0L && initialBytes + declared > maxBytes) {
                    throw IOException("Video exceeds the download budget")
                }
                var total = initialBytes
                connection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        check(keepRunning()) { "Video download cancelled" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw IOException("Video exceeds the download budget")
                        sink.write(buffer, 0, count)
                    }
                }
                return total
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Video request did not complete")
    }

    private fun validate(url: URL): URL {
        if (url.protocol != "https" || url.userInfo != null || !isTrustedHost(url.host)) {
            throw IOException("Unsupported video destination")
        }
        val addresses = addressResolver(url.host)
        if (addresses.isEmpty() || addresses.any(::isPrivateOrSpecial)) {
            throw IOException("Video destination is not public")
        }
        return url
    }

    private fun isTrustedHost(host: String): Boolean {
        val normalized = host.lowercase()
        return ALLOWED_DOMAINS.any { normalized == it || normalized.endsWith(".$it") }
    }

    private fun isPrivateOrSpecial(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        return when (address) {
            is Inet4Address -> {
                val bytes = address.address.map { it.toInt() and 0xff }
                bytes[0] == 0 || bytes[0] == 100 && bytes[1] in 64..127 ||
                    bytes[0] == 198 && bytes[1] in 18..19 || bytes[0] >= 224
            }
            is Inet6Address -> address.address.first().toInt() and 0xfe == 0xfc
            else -> true
        }
    }

    private fun quotedUri(line: String): String? =
        Regex("""(?:^|,)URI="([^"]+)"""").find(line.substringAfter(':'))?.groupValues?.getOrNull(1)

    private fun attribute(line: String, name: String): String? =
        Regex("""(?:^|,)${Regex.escape(name)}=([^,]+)""")
            .find(line.substringAfter(':'))
            ?.groupValues
            ?.getOrNull(1)
            ?.trim('"')

    private companion object {
        const val MAX_MEDIA_BYTES = 96L * 1024L * 1024L
        const val MAX_PLAYLIST_BYTES = 1024 * 1024
        const val MAX_HLS_SEGMENTS = 512
        const val MAX_BITRATE = 4_500_000L
        const val MAX_DURATION_SECONDS = 10.0 * 60.0
        const val MAX_REDIRECTS = 4
        const val TIMEOUT_MS = 12_000
        const val BUFFER_BYTES = 32 * 1024
        const val USER_AGENT = "RokidNexus/0.1 (+https://github.com/Anezium)"
        const val TEMP_FILE_PREFIX = "nexus-feeds-video-"
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        val ALLOWED_DOMAINS = setOf("bsky.app", "twimg.com")
    }
}
