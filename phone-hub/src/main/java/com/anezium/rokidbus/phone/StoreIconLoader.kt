package com.anezium.rokidbus.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal object StoreIconCachePolicy {
    const val TTL_MILLIS = 7L * 24L * 60L * 60L * 1_000L

    fun cacheFile(directory: File, url: String): File =
        File(directory, "${sha256(url)}.png")

    fun isFresh(lastModifiedEpochMillis: Long, nowEpochMillis: Long): Boolean {
        if (lastModifiedEpochMillis <= 0L || nowEpochMillis < lastModifiedEpochMillis) return false
        return nowEpochMillis - lastModifiedEpochMillis <= TTL_MILLIS
    }

    fun isFresh(file: File, nowEpochMillis: Long): Boolean =
        file.isFile && isFresh(file.lastModified(), nowEpochMillis)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}

internal class StoreIconLoader(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioExecutor: Executor = DEFAULT_IO_EXECUTOR,
    private val callbackExecutor: Executor = Executor { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    },
) {
    private val cacheDirectory = File(context.applicationContext.cacheDir, CACHE_DIRECTORY)
    private val lock = Any()
    private val failedUrls = mutableSetOf<String>()
    private val inFlight = mutableMapOf<String, MutableList<(Bitmap) -> Unit>>()
    private val memoryCache = object : LinkedHashMap<String, Bitmap>(
        MEMORY_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Bitmap>?,
        ): Boolean = size > MEMORY_CACHE_ENTRIES
    }

    fun load(url: String, onLoaded: (Bitmap) -> Unit) {
        if (validatedHttpsUrl(url) == null) return

        val cached: Bitmap?
        val shouldStart: Boolean
        synchronized(lock) {
            cached = memoryCache[url]
            if (cached != null || url in failedUrls) {
                shouldStart = false
            } else {
                val callbacks = inFlight[url]
                if (callbacks != null) {
                    callbacks += onLoaded
                    shouldStart = false
                } else {
                    inFlight[url] = mutableListOf(onLoaded)
                    shouldStart = true
                }
            }
        }
        cached?.let(onLoaded)
        if (!shouldStart) return

        ioExecutor.execute {
            val bitmap = runCatching { loadBitmap(url) }.getOrNull()
            val callbacks = synchronized(lock) {
                if (bitmap != null) {
                    memoryCache[url] = bitmap
                } else {
                    failedUrls += url
                }
                inFlight.remove(url).orEmpty()
            }
            if (bitmap != null) {
                callbackExecutor.execute {
                    callbacks.forEach { callback -> runCatching { callback(bitmap) } }
                }
            }
        }
    }

    private fun loadBitmap(url: String): Bitmap? {
        val target = StoreIconCachePolicy.cacheFile(cacheDirectory, url)
        if (StoreIconCachePolicy.isFresh(target, clock())) {
            decodePng(target)?.let { return it }
        }
        return download(url, target)
    }

    private fun download(url: String, target: File): Bitmap? {
        cacheDirectory.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        var connection: HttpURLConnection? = null
        return try {
            val activeConnection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                useCaches = false
                setRequestProperty("Accept", "image/png")
                setRequestProperty("User-Agent", "Rokid-Nexus-Store/1")
            }
            connection = activeConnection
            if (activeConnection.responseCode != HttpURLConnection.HTTP_OK) return null
            if (!activeConnection.url.protocol.equals("https", ignoreCase = true)) return null
            val contentLength = activeConnection.contentLengthLong
            if (contentLength > MAX_DOWNLOAD_BYTES) return null

            activeConnection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES) {
                            throw IOException("Store icon exceeds download limit")
                        }
                        output.write(buffer, 0, read)
                    }
                    if (total == 0L) return null
                }
            }

            val bitmap = decodePng(temporary) ?: return null
            replaceCacheFile(temporary, target)
            target.setLastModified(clock())
            bitmap
        } finally {
            connection?.disconnect()
            temporary.delete()
        }
    }

    private fun decodePng(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (bounds.outMimeType != "image/png") return null
        if (width <= 0 || height <= 0 || width > MAX_EDGE_PIXELS || height > MAX_EDGE_PIXELS) {
            return null
        }
        if (width.toLong() * height.toLong() > MAX_TOTAL_PIXELS) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun replaceCacheFile(temporary: File, target: File) {
        runCatching {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrThrow()
    }

    companion object {
        private const val CACHE_DIRECTORY = "store-icons"
        private const val MEMORY_CACHE_ENTRIES = 16
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 15_000
        private const val MAX_DOWNLOAD_BYTES = 4L * 1_024L * 1_024L
        private const val MAX_EDGE_PIXELS = 2_048
        private const val MAX_TOTAL_PIXELS = 4_194_304L

        private val DEFAULT_IO_EXECUTOR = Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "nexus-store-icon").apply { isDaemon = true }
        }
    }
}
