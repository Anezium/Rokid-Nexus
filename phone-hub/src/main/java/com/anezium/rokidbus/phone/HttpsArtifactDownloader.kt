package com.anezium.rokidbus.phone

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import org.json.JSONObject

internal class HttpsArtifactDownloader(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : ArtifactDownloader {
    override fun download(
        url: String,
        destination: File,
        isCancelled: () -> Boolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        val parsedUrl = URL(url)
        require(parsedUrl.protocol == "https") { "Artifact URL must use HTTPS" }
        try {
            downloadOnce(parsedUrl, destination, isCancelled, onProgress)
        } catch (failure: IOException) {
            if (failure !is SocketTimeoutException &&
                (failure !is ArtifactHttpException || failure.status !in GATEWAY_ERRORS)
            ) throw failure
            val release = githubRelease(parsedUrl) ?: throw failure
            checkCancelled(isCancelled)
            // GitHub's browser download route can time out while its asset API and CDN work.
            // Resolve the same tag and filename once; never substitute a different release.
            try {
                val assetUrl = resolveAsset(release, isCancelled)
                downloadOnce(assetUrl, destination, isCancelled, onProgress, "application/octet-stream")
            } catch (fallbackFailure: IOException) {
                fallbackFailure.addSuppressed(failure)
                throw fallbackFailure
            }
        }
    }

    private fun downloadOnce(
        url: URL,
        destination: File,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long?) -> Unit,
        accept: String? = null,
    ) {
        checkCancelled(isCancelled)
        val connection = connection(url, accept)
        try {
            checkResponse(connection)
            checkCancelled(isCancelled)
            val total = connection.contentLengthLong.takeIf { it >= 0L }
            destination.outputStream().buffered().use { output ->
                connection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var lastEmit = clock()
                    onProgress(0L, total)
                    while (true) {
                        checkCancelled(isCancelled)
                        val count = input.read(buffer)
                        if (count < 0) break
                        checkCancelled(isCancelled)
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = clock()
                        // APKs span thousands of buffers; keep progress off the UI thread's hot path.
                        if (now - lastEmit >= PROGRESS_EMIT_INTERVAL_MS) {
                            onProgress(downloaded, total)
                            lastEmit = now
                        }
                    }
                    onProgress(downloaded, total)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private data class GitHubRelease(val repository: String, val tag: String, val decodedTag: String, val filename: String)

    private fun githubRelease(url: URL): GitHubRelease? {
        if (!url.host.equals("github.com", ignoreCase = true) || url.userInfo != null ||
            url.port !in listOf(-1, 443)
        ) return null
        val match = RELEASE_PATH.matchEntire(url.path) ?: return null
        val decoded = runCatching {
            listOf(match.groupValues[3], match.groupValues[4]).map {
                URLDecoder.decode(it.replace("+", "%2B"), "UTF-8")
            }
        }.getOrNull() ?: return null
        return GitHubRelease("${match.groupValues[1]}/${match.groupValues[2]}", match.groupValues[3], decoded[0], decoded[1])
    }

    private fun resolveAsset(release: GitHubRelease, isCancelled: () -> Boolean): URL {
        checkCancelled(isCancelled)
        val url = URL("https://api.github.com/repos/${release.repository}/releases/tags/${release.tag}")
        val connection = connection(url, "application/vnd.github+json")
        val metadata = try {
            checkResponse(connection)
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkCancelled(isCancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_METADATA_BYTES) throw IOException("GitHub release metadata is too large")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
        val assetId = try {
            val releaseMetadata = JSONObject(metadata)
            if (releaseMetadata.optString("tag_name") != release.decodedTag || releaseMetadata.optBoolean("draft")) {
                throw IOException("GitHub returned a different or unpublished release")
            }
            val assets = releaseMetadata.getJSONArray("assets")
            val matching = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .filter { it.optString("name") == release.filename && it.optString("state") == "uploaded" }
            matching.singleOrNull()?.optLong("id", -1L)?.takeIf { it > 0L }
        } catch (failure: Exception) {
            throw IOException("GitHub release metadata is invalid", failure)
        } ?: throw IOException("The requested APK is unavailable in the GitHub release")
        // Construct the official endpoint instead of following a metadata-supplied URL.
        return URL("https://api.github.com/repos/${release.repository}/releases/assets/$assetId")
    }

    private fun connection(url: URL, accept: String?): HttpURLConnection = openConnection(url).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 30_000
        useCaches = false
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Rokid-Nexus-Store/1")
        if (accept != null) {
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
    }

    private fun checkResponse(connection: HttpURLConnection) {
        val status = connection.responseCode
        if (connection.url.protocol != "https") throw IOException("Artifact redirect left HTTPS")
        if (status !in 200..299) throw ArtifactHttpException(status)
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw DownloadCancelledException()
    }

    private class ArtifactHttpException(val status: Int) : IOException("Artifact download failed with HTTP $status")

    private companion object {
        const val PROGRESS_EMIT_INTERVAL_MS = 250L
        const val MAX_METADATA_BYTES = 2 * 1024 * 1024
        val GATEWAY_ERRORS = setOf(502, 503, 504)
        val RELEASE_PATH = Regex("^/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/releases/download/([^/]+)/([^/]+)$")
    }
}
