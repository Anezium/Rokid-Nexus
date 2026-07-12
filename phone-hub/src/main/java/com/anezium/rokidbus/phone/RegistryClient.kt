package com.anezium.rokidbus.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor
import java.util.concurrent.Executors

data class RegistryFeed(
    val version: Int,
    val plugins: List<RegistryPlugin>,
)

data class RegistryPlugin(
    val id: String,
    val name: String,
    val category: String,
    val summary: String,
    val description: String,
    val author: String,
    val sourceUrl: String,
    val publishedAt: String,
    val iconAsset: String,
    val screenshotAssets: List<String>,
    val listingDescriptionMarkdown: String,
    val releases: List<RegistryRelease>,
    val nexus: RegistryNexus,
    val artifact: RegistryArtifact,
)

data class RegistryRelease(
    val version: String,
    val date: String,
    val notes: String,
)

data class RegistryNexus(
    val pluginId: String,
    val apiVersion: Int,
    val capabilities: List<String>,
    val launchable: Boolean,
    val settingsActivity: String?,
    val minHostVersionCode: Long,
)

data class RegistryArtifact(
    val target: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
)

class RegistryParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class RegistryCacheRecord(
    val body: String,
    val etag: String?,
    val lastFetchEpochMillis: Long,
)

interface RegistryCache {
    fun read(): RegistryCacheRecord?
    fun write(record: RegistryCacheRecord)
}

data class RegistryHttpResponse(
    val statusCode: Int,
    val body: String? = null,
    val etag: String? = null,
)

fun interface RegistryTransport {
    @Throws(IOException::class)
    fun fetch(ifNoneMatch: String?): RegistryHttpResponse
}

enum class RegistrySource { NETWORK, CACHE }

data class RegistrySnapshot(
    val feed: RegistryFeed,
    val source: RegistrySource,
    val etag: String?,
    val lastFetchEpochMillis: Long,
)

sealed interface RegistryLoadResult {
    data class Success(val snapshot: RegistrySnapshot) : RegistryLoadResult
    data class Failure(val error: Throwable) : RegistryLoadResult
}

class RegistryClient(
    private val transport: RegistryTransport,
    private val cache: RegistryCache,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioExecutor: Executor = DEFAULT_IO_EXECUTOR,
    private val callbackExecutor: Executor = Executor(Runnable::run),
) {
    fun refresh(callback: (RegistryLoadResult) -> Unit) {
        ioExecutor.execute {
            val result = load()
            callbackExecutor.execute { callback(result) }
        }
    }

    /** Blocking seam for deterministic unit tests. UI callers use [refresh]. */
    fun load(): RegistryLoadResult {
        val cached = runCatching { cache.read() }.getOrNull()
        val response = runCatching { transport.fetch(cached?.etag) }
        return response.fold(
            onSuccess = { handleResponse(it, cached) },
            onFailure = { failure -> cachedResult(cached) ?: RegistryLoadResult.Failure(failure) },
        )
    }

    private fun handleResponse(
        response: RegistryHttpResponse,
        cached: RegistryCacheRecord?,
    ): RegistryLoadResult = when (response.statusCode) {
        HttpURLConnection.HTTP_OK -> {
            val body = response.body
                ?: return cachedResult(cached) ?: RegistryLoadResult.Failure(
                    RegistryParseException("Registry response body is missing"),
                )
            val parsed = runCatching { parse(body) }.getOrElse { failure ->
                return cachedResult(cached) ?: RegistryLoadResult.Failure(failure)
            }
            val record = RegistryCacheRecord(body, response.etag, clock())
            runCatching { cache.write(record) }
            RegistryLoadResult.Success(record.toSnapshot(parsed, RegistrySource.NETWORK))
        }
        HttpURLConnection.HTTP_NOT_MODIFIED -> {
            if (cached == null) {
                RegistryLoadResult.Failure(IOException("Registry returned 304 without a cache"))
            } else {
                val refreshed = cached.copy(lastFetchEpochMillis = clock())
                runCatching { cache.write(refreshed) }
                cachedResult(refreshed)
                    ?: RegistryLoadResult.Failure(RegistryParseException("Cached registry body is malformed"))
            }
        }
        else -> cachedResult(cached)
            ?: RegistryLoadResult.Failure(IOException("Registry request failed with HTTP ${response.statusCode}"))
    }

    private fun cachedResult(record: RegistryCacheRecord?): RegistryLoadResult.Success? {
        if (record == null) return null
        val parsed = runCatching { parse(record.body) }.getOrNull() ?: return null
        return RegistryLoadResult.Success(record.toSnapshot(parsed, RegistrySource.CACHE))
    }

    private fun RegistryCacheRecord.toSnapshot(feed: RegistryFeed, source: RegistrySource) =
        RegistrySnapshot(feed, source, etag, lastFetchEpochMillis)

    companion object {
        const val FEED_URL =
            "https://raw.githubusercontent.com/Anezium/RokidBrew-Registry/main/dist/nexus-plugins.v1.json"
        const val SUPPORTED_VERSION = 1

        private val DEFAULT_IO_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nexus-registry").apply { isDaemon = true }
        }

        fun create(context: Context): RegistryClient {
            val appContext = context.applicationContext
            return RegistryClient(
                transport = HttpsRegistryTransport(URL(FEED_URL)),
                cache = FileRegistryCache(File(appContext.filesDir, "store-registry")),
                callbackExecutor = Executor { runnable -> Handler(Looper.getMainLooper()).post(runnable) },
            )
        }

        @Throws(RegistryParseException::class)
        fun parse(body: String): RegistryFeed {
            val root = try {
                JSONObject(body)
            } catch (failure: Exception) {
                throw RegistryParseException("Registry body is not a JSON object", failure)
            }
            val version = root.requiredInt("version")
            if (version != SUPPORTED_VERSION) {
                throw RegistryParseException("Unsupported registry version: $version")
            }
            val pluginsJson = root.requiredArray("plugins")
            val plugins = buildList {
                for (index in 0 until pluginsJson.length()) {
                    val plugin = pluginsJson.optJSONObject(index)
                        ?: throw RegistryParseException("plugins[$index] is not an object")
                    add(parsePlugin(plugin, index))
                }
            }
            return RegistryFeed(version, plugins)
        }

        private fun parsePlugin(value: JSONObject, index: Int): RegistryPlugin {
            val path = "plugins[$index]"
            if (value.requiredString("kind", path) != "nexus-plugin") {
                throw RegistryParseException("$path.kind is not nexus-plugin")
            }
            val listing = value.requiredObject("listing", path)
            val nexus = value.requiredObject("nexus", path)
            val artifact = value.requiredObject("artifact", path)
            val releasesJson = value.requiredArray("releases", path)
            val releases = buildList {
                for (releaseIndex in 0 until releasesJson.length()) {
                    val release = releasesJson.optJSONObject(releaseIndex)
                        ?: throw RegistryParseException("$path.releases[$releaseIndex] is not an object")
                    add(
                        RegistryRelease(
                            version = release.requiredString("version", "$path.releases[$releaseIndex]"),
                            date = release.requiredString("date", "$path.releases[$releaseIndex]"),
                            notes = release.requiredString("notes", "$path.releases[$releaseIndex]", allowEmpty = true),
                        ),
                    )
                }
            }
            return RegistryPlugin(
                id = value.requiredString("id", path),
                name = value.requiredString("name", path),
                category = value.requiredString("category", path),
                summary = value.requiredString("summary", path),
                description = value.requiredString("description", path),
                author = value.requiredString("author", path),
                sourceUrl = value.requiredHttpsUrl("sourceUrl", path),
                publishedAt = value.requiredString("publishedAt", path),
                iconAsset = value.requiredString("iconAsset", path),
                screenshotAssets = value.requiredStringList("screenshotAssets", path),
                listingDescriptionMarkdown = listing.requiredString("descriptionMarkdown", "$path.listing", allowEmpty = true),
                releases = releases,
                nexus = RegistryNexus(
                    pluginId = nexus.requiredString("pluginId", "$path.nexus"),
                    apiVersion = nexus.requiredInt("apiVersion", "$path.nexus"),
                    capabilities = nexus.requiredStringList("capabilities", "$path.nexus"),
                    launchable = nexus.requiredBoolean("launchable", "$path.nexus"),
                    settingsActivity = nexus.optionalString("settingsActivity", "$path.nexus"),
                    minHostVersionCode = nexus.requiredLong("minHostVersionCode", "$path.nexus"),
                ),
                artifact = RegistryArtifact(
                    target = artifact.requiredString("target", "$path.artifact"),
                    url = artifact.requiredHttpsUrl("url", "$path.artifact"),
                    sha256 = artifact.requiredSha256("sha256", "$path.artifact"),
                    sizeBytes = artifact.requiredLong("sizeBytes", "$path.artifact"),
                    packageName = artifact.requiredString("packageName", "$path.artifact"),
                    versionCode = artifact.requiredLong("versionCode", "$path.artifact"),
                    versionName = artifact.requiredString("versionName", "$path.artifact"),
                ),
            )
        }

        private fun JSONObject.requiredObject(key: String, path: String = "root"): JSONObject =
            optJSONObject(key) ?: throw RegistryParseException("$path.$key is not an object")

        private fun JSONObject.requiredArray(key: String, path: String = "root"): JSONArray =
            optJSONArray(key) ?: throw RegistryParseException("$path.$key is not an array")

        private fun JSONObject.requiredString(
            key: String,
            path: String = "root",
            allowEmpty: Boolean = false,
        ): String {
            val value = opt(key) as? String
                ?: throw RegistryParseException("$path.$key is not a string")
            if (!allowEmpty && value.isBlank()) throw RegistryParseException("$path.$key is blank")
            return value
        }

        private fun JSONObject.optionalString(key: String, path: String): String? {
            if (!has(key) || isNull(key)) return null
            return (opt(key) as? String)
                ?.takeIf(String::isNotBlank)
                ?: throw RegistryParseException("$path.$key is not a non-blank string or null")
        }

        private fun JSONObject.requiredInt(key: String, path: String = "root"): Int {
            val value = opt(key) as? Number
                ?: throw RegistryParseException("$path.$key is not an integer")
            val longValue = value.toLong()
            if (value.toDouble() != longValue.toDouble() || longValue !in 0..Int.MAX_VALUE) {
                throw RegistryParseException("$path.$key is not a non-negative integer")
            }
            return longValue.toInt()
        }

        private fun JSONObject.requiredLong(key: String, path: String): Long {
            val value = opt(key) as? Number
                ?: throw RegistryParseException("$path.$key is not an integer")
            val longValue = value.toLong()
            if (value.toDouble() != longValue.toDouble() || longValue < 0L) {
                throw RegistryParseException("$path.$key is not a non-negative integer")
            }
            return longValue
        }

        private fun JSONObject.requiredBoolean(key: String, path: String): Boolean =
            (opt(key) as? Boolean) ?: throw RegistryParseException("$path.$key is not a boolean")

        private fun JSONObject.requiredStringList(key: String, path: String): List<String> {
            val values = requiredArray(key, path)
            return buildList {
                for (index in 0 until values.length()) {
                    val item = values.opt(index) as? String
                        ?: throw RegistryParseException("$path.$key[$index] is not a string")
                    if (item.isBlank()) throw RegistryParseException("$path.$key[$index] is blank")
                    add(item)
                }
            }
        }

        private fun JSONObject.requiredHttpsUrl(key: String, path: String): String {
            val value = requiredString(key, path)
            val url = runCatching { URL(value) }.getOrNull()
            if (url?.protocol != "https") throw RegistryParseException("$path.$key is not an HTTPS URL")
            return value
        }

        private fun JSONObject.requiredSha256(key: String, path: String): String {
            val value = requiredString(key, path).lowercase()
            if (!value.matches(Regex("[0-9a-f]{64}"))) {
                throw RegistryParseException("$path.$key is not a SHA-256 digest")
            }
            return value
        }
    }
}

private class HttpsRegistryTransport(private val url: URL) : RegistryTransport {
    init {
        require(url.protocol == "https") { "Registry URL must use HTTPS" }
    }

    override fun fetch(ifNoneMatch: String?): RegistryHttpResponse {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Rokid-Nexus-Store/1")
            ifNoneMatch?.let { setRequestProperty("If-None-Match", it) }
        }
        return try {
            val status = connection.responseCode
            val body = if (status == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                null
            }
            RegistryHttpResponse(status, body, connection.getHeaderField("ETag"))
        } finally {
            connection.disconnect()
        }
    }
}

private class FileRegistryCache(private val directory: File) : RegistryCache {
    private val bodyFile = File(directory, "nexus-plugins.v1.json")
    private val metadataFile = File(directory, "cache.json")

    override fun read(): RegistryCacheRecord? = runCatching {
        if (!bodyFile.isFile || !metadataFile.isFile) return null
        val metadata = JSONObject(metadataFile.readText(StandardCharsets.UTF_8))
        RegistryCacheRecord(
            body = bodyFile.readText(StandardCharsets.UTF_8),
            etag = metadata.optString("etag").takeIf(String::isNotBlank),
            lastFetchEpochMillis = metadata.getLong("lastFetchEpochMillis"),
        )
    }.getOrNull()

    override fun write(record: RegistryCacheRecord) {
        directory.mkdirs()
        writeAtomically(bodyFile, record.body)
        val metadata = JSONObject()
            .put("etag", record.etag ?: JSONObject.NULL)
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
