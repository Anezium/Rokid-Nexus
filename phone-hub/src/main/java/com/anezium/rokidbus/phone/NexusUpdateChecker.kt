package com.anezium.rokidbus.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class NexusSemVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
) : Comparable<NexusSemVersion> {
    override fun compareTo(other: NexusSemVersion): Int =
        compareValuesBy(this, other, NexusSemVersion::major, NexusSemVersion::minor, NexusSemVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val VERSION_PATTERN = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
        private val APP_TAG_PATTERN = Regex("^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")

        fun parse(versionName: String): NexusSemVersion? = parseMatch(VERSION_PATTERN.matchEntire(versionName))

        /** Only bare app tags are accepted; plugin and SDK tags cannot match this shape. */
        fun fromAppTag(tag: String): NexusSemVersion? = parseMatch(APP_TAG_PATTERN.matchEntire(tag))

        private fun parseMatch(match: MatchResult?): NexusSemVersion? {
            match ?: return null
            val parts = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
            return NexusSemVersion(parts[0], parts[1], parts[2])
        }
    }
}

data class InstalledNexusVersion(
    val versionName: String,
    val versionCode: Long,
)

data class NexusAppRelease(
    val version: NexusSemVersion,
    val apkUrl: String,
    val sha256: String?,
) {
    val versionName: String = version.toString()
    val versionLabel: String = "Rokid Nexus $versionName"
}

internal object NexusUpdatePolicy {
    fun isUpdateAvailable(release: NexusAppRelease?, installed: InstalledNexusVersion): Boolean {
        val installedSemVersion = NexusSemVersion.parse(installed.versionName) ?: return false
        return release != null && release.version > installedSemVersion
    }
}

class NexusUpdateParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class NexusUpdateCacheRecord(
    val body: String,
    val etag: String?,
    val lastFetchEpochMillis: Long,
)

internal interface NexusUpdateCache {
    fun read(): NexusUpdateCacheRecord?
    fun write(record: NexusUpdateCacheRecord)
}

internal data class NexusUpdateHttpResponse(
    val statusCode: Int,
    val body: String? = null,
    val etag: String? = null,
)

internal fun interface NexusUpdateTransport {
    @Throws(IOException::class)
    fun fetch(ifNoneMatch: String?): NexusUpdateHttpResponse
}

sealed interface NexusUpdateCheckResult {
    data class Available(val release: NexusAppRelease) : NexusUpdateCheckResult
    data class Current(val latestRelease: NexusAppRelease?) : NexusUpdateCheckResult
    data class Failure(val error: Throwable) : NexusUpdateCheckResult
}

class NexusUpdateChecker internal constructor(
    private val transport: NexusUpdateTransport,
    private val cache: NexusUpdateCache,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioExecutor: Executor = DEFAULT_IO_EXECUTOR,
    private val callbackExecutor: Executor = Executor(Runnable::run),
) {
    fun checkAsync(
        installed: InstalledNexusVersion,
        allowNetwork: Boolean,
        callback: (NexusUpdateCheckResult) -> Unit,
    ) {
        ioExecutor.execute {
            val result = check(installed, allowNetwork)
            callbackExecutor.execute { callback(result) }
        }
    }

    /** Blocking seam for tests. Android callers use [checkAsync]. */
    internal fun check(
        installed: InstalledNexusVersion,
        allowNetwork: Boolean,
    ): NexusUpdateCheckResult {
        if (NexusSemVersion.parse(installed.versionName) == null) {
            return NexusUpdateCheckResult.Failure(
                NexusUpdateParseException("Installed app version is not semantic: ${installed.versionName}"),
            )
        }
        val snapshot = if (allowNetwork) load() else cachedRelease()
        return when (snapshot) {
            is ReleaseLoadResult.Success -> if (NexusUpdatePolicy.isUpdateAvailable(snapshot.release, installed)) {
                NexusUpdateCheckResult.Available(requireNotNull(snapshot.release))
            } else {
                NexusUpdateCheckResult.Current(snapshot.release)
            }
            is ReleaseLoadResult.Failure -> NexusUpdateCheckResult.Failure(snapshot.error)
        }
    }

    private fun load(): ReleaseLoadResult {
        val cached = runCatching { cache.read() }.getOrNull()
        val response = runCatching { transport.fetch(cached?.etag) }
        return response.fold(
            onSuccess = { handleResponse(it, cached) },
            onFailure = { failure -> cachedResult(cached) ?: ReleaseLoadResult.Failure(failure) },
        )
    }

    private fun cachedRelease(): ReleaseLoadResult {
        val cached = runCatching { cache.read() }.getOrNull()
        return cachedResult(cached)
            ?: ReleaseLoadResult.Failure(IOException("No cached app release is available"))
    }

    private fun handleResponse(
        response: NexusUpdateHttpResponse,
        cached: NexusUpdateCacheRecord?,
    ): ReleaseLoadResult = when (response.statusCode) {
        HttpURLConnection.HTTP_OK -> {
            val body = response.body
                ?: return cachedResult(cached)
                    ?: ReleaseLoadResult.Failure(NexusUpdateParseException("Release response body is missing"))
            val release = runCatching { parseLatestAppRelease(body) }.getOrElse { failure ->
                return cachedResult(cached) ?: ReleaseLoadResult.Failure(failure)
            }
            val record = NexusUpdateCacheRecord(body, response.etag, clock())
            runCatching { cache.write(record) }
            ReleaseLoadResult.Success(release)
        }
        HttpURLConnection.HTTP_NOT_MODIFIED -> {
            if (cached == null) {
                ReleaseLoadResult.Failure(IOException("GitHub returned 304 without a release cache"))
            } else {
                val refreshed = cached.copy(lastFetchEpochMillis = clock())
                runCatching { cache.write(refreshed) }
                cachedResult(refreshed)
                    ?: ReleaseLoadResult.Failure(NexusUpdateParseException("Cached releases are malformed"))
            }
        }
        else -> cachedResult(cached)
            ?: ReleaseLoadResult.Failure(IOException("GitHub releases request failed with HTTP ${response.statusCode}"))
    }

    private fun cachedResult(record: NexusUpdateCacheRecord?): ReleaseLoadResult.Success? {
        record ?: return null
        val release = runCatching { parseLatestAppRelease(record.body) }.getOrNull() ?: return null
        return ReleaseLoadResult.Success(release)
    }

    private sealed interface ReleaseLoadResult {
        data class Success(val release: NexusAppRelease?) : ReleaseLoadResult
        data class Failure(val error: Throwable) : ReleaseLoadResult
    }

    companion object {
        const val RELEASES_URL =
            "https://api.github.com/repos/Anezium/Rokid-Nexus/releases?per_page=100"

        private val DEFAULT_IO_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nexus-app-update-check").apply { isDaemon = true }
        }

        fun create(context: Context): NexusUpdateChecker {
            val appContext = context.applicationContext
            return NexusUpdateChecker(
                transport = HttpsNexusUpdateTransport(URL(RELEASES_URL)),
                cache = FileNexusUpdateCache(File(appContext.filesDir, "app-update")),
                callbackExecutor = Executor { runnable -> Handler(Looper.getMainLooper()).post(runnable) },
            )
        }

        @Throws(NexusUpdateParseException::class)
        internal fun parseLatestAppRelease(body: String): NexusAppRelease? {
            val asset = NexusReleaseAssetResolver.parseLatest(body, NexusReleaseArtifact.PHONE)
                ?: return null
            return NexusAppRelease(asset.version, asset.apkUrl, asset.sha256)
        }
    }
}

internal class HttpsNexusUpdateTransport(private val url: URL) : NexusUpdateTransport {
    init {
        require(url.protocol == "https") { "GitHub releases URL must use HTTPS" }
    }

    override fun fetch(ifNoneMatch: String?): NexusUpdateHttpResponse {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Rokid-Nexus-Updater/1")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            ifNoneMatch?.let { setRequestProperty("If-None-Match", it) }
        }
        return try {
            val status = connection.responseCode
            val body = if (status == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                null
            }
            NexusUpdateHttpResponse(status, body, connection.getHeaderField("ETag"))
        } finally {
            connection.disconnect()
        }
    }
}

private class FileNexusUpdateCache(private val directory: File) : NexusUpdateCache {
    private val bodyFile = File(directory, "github-releases.json")
    private val metadataFile = File(directory, "cache.json")

    override fun read(): NexusUpdateCacheRecord? = runCatching {
        if (!bodyFile.isFile || !metadataFile.isFile) return null
        val metadata = org.json.JSONObject(metadataFile.readText(StandardCharsets.UTF_8))
        NexusUpdateCacheRecord(
            body = bodyFile.readText(StandardCharsets.UTF_8),
            etag = metadata.optString("etag").takeUnless { metadata.isNull("etag") || it.isBlank() },
            lastFetchEpochMillis = metadata.getLong("lastFetchEpochMillis"),
        )
    }.getOrNull()

    override fun write(record: NexusUpdateCacheRecord) {
        directory.mkdirs()
        writeAtomically(bodyFile, record.body)
        val metadata = org.json.JSONObject()
            .put("etag", record.etag ?: org.json.JSONObject.NULL)
            .put("lastFetchEpochMillis", record.lastFetchEpochMillis)
            .toString()
        writeAtomically(metadataFile, metadata)
    }

    private fun writeAtomically(target: File, value: String) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(value, StandardCharsets.UTF_8)
        runCatching {
            java.nio.file.Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            java.nio.file.Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            temporary.delete()
            throw IOException("Could not replace ${target.name}", it)
        }
    }
}
