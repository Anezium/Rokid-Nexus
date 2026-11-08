package com.anezium.rokidbus.plugin.meta

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.PageTemplateContract
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Which messages of a conversation get a thumbnail (launcher bundle v5), as pure choices over
 * the page's limits: at most [PageTemplateContract.MAX_MESSAGE_IMAGES] images, each within
 * [PageTemplateContract.MAX_MESSAGE_IMAGE_BYTES] and all of them within
 * [PageTemplateContract.MAX_MESSAGE_IMAGES_TOTAL_BYTES], newest first. Nothing here touches the
 * network; the fetch and the encoding live in [ThumbnailLoader] and [MessageThumbnailEncoder].
 */
internal object MetaThumbnails {
    /** The newest messages whose preview can be fetched, as many as a page may show. */
    fun candidates(history: List<HistoryMessage>): List<HistoryMessage> =
        history.asReversed().filter { fetchable(it.preview) }.take(PageTemplateContract.MAX_MESSAGE_IMAGES).asReversed()

    /**
     * Plain HTTPS previews are fetched, and WhatsApp's own thumbnails are read from its local
     * history; anything else is kept as its caption alone.
     */
    fun fetchable(preview: String?): Boolean =
        preview != null && preview.length <= MAX_URL_CHARS &&
            (preview.startsWith("https://", ignoreCase = true) || stored(preview))

    /** A thumbnail WhatsApp carried inline and its engine stored: read locally, never fetched. */
    fun stored(preview: String): Boolean = preview.startsWith(WHATSAPP_THUMBNAIL_PREFIX)

    /** The engine's `ThumbnailPrefix`. */
    const val WHATSAPP_THUMBNAIL_PREFIX = "whatsapp-thumbnail:"

    /**
     * Which of the messages, given each one's decoded thumbnail size (null for none), keep their
     * image: the newest ones, until the count or the total budget is reached. The rest show
     * their caption alone.
     */
    fun keep(sizes: List<Int?>): Set<Int> {
        val kept = HashSet<Int>()
        var total = 0
        for (index in sizes.indices.reversed()) {
            val size = sizes[index] ?: continue
            if (size <= 0 || size > PageTemplateContract.MAX_MESSAGE_IMAGE_BYTES) continue
            if (kept.size >= PageTemplateContract.MAX_MESSAGE_IMAGES || total + size > PageTemplateContract.MAX_MESSAGE_IMAGES_TOTAL_BYTES) break
            kept += index
            total += size
        }
        return kept
    }

    /** Decoded size of a thumbnail, the figure the page budget counts. */
    fun decodedSize(artwork: LauncherArtwork): Int = artwork.bytes().size

    private const val MAX_URL_CHARS = 2_048
}

/**
 * A fetched image reduced to a conversation thumbnail: both edges at most
 * [PageTemplateContract.MAX_MESSAGE_IMAGE_EDGE] px and the JPEG within
 * [PageTemplateContract.MAX_MESSAGE_IMAGE_BYTES], lowering the quality until it fits.
 */
internal object MessageThumbnailEncoder {
    private const val FIRST_QUALITY = 70
    private const val LAST_QUALITY = 25
    private const val QUALITY_STEP = 15

    fun encode(image: ByteArray): LauncherArtwork? {
        if (image.isEmpty()) return null
        val maxEdge = PageTemplateContract.MAX_MESSAGE_IMAGE_EDGE
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge / (sampleSize * 2) >= maxEdge) sampleSize *= 2
        var bitmap = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            ?: return null
        try {
            val decodedLongEdge = maxOf(bitmap.width, bitmap.height)
            if (decodedLongEdge > maxEdge) {
                val scale = maxEdge.toFloat() / decodedLongEdge
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }
            var quality = FIRST_QUALITY
            while (true) {
                val output = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
                val bytes = output.toByteArray()
                if (bytes.size <= PageTemplateContract.MAX_MESSAGE_IMAGE_BYTES) {
                    return runCatching {
                        LauncherArtwork(bitmap.width, bitmap.height, Base64.getEncoder().encodeToString(bytes), LauncherArtwork.JPEG)
                    }.getOrNull()
                }
                if (quality <= LAST_QUALITY) return null
                quality -= QUALITY_STEP
            }
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * Fetches message previews off the main thread and keeps the thumbnails for the session. Each
 * preview is asked for once: a failure is remembered too, so a conversation that keeps
 * publishing never hammers the CDN. URLs, names and contents are never logged. [onReady] runs on
 * the caller's main handler after every finished fetch so the visible pages can be published
 * again.
 */
internal class ThumbnailLoader(
    private val onReady: () -> Unit,
    /** Reads a stored thumbnail ([MetaThumbnails.stored]) and answers once, null when there is none. */
    private val readStored: (String, (ByteArray?) -> Unit) -> Unit = { _, done -> done(null) },
) {
    private val worker = Executors.newSingleThreadExecutor()
    /** Message id to its thumbnail; guarded by this. */
    private val ready = HashMap<String, LauncherArtwork>()
    /** Message ids asked for already, whatever came of it; guarded by this. */
    private val asked = HashSet<String>()

    @Synchronized fun get(messageId: String): LauncherArtwork? = ready[messageId]

    /** Queues the fetch of every candidate not asked for yet. */
    fun request(messages: List<HistoryMessage>) {
        for (message in MetaThumbnails.candidates(messages)) {
            val preview = message.preview ?: continue
            val fresh = synchronized(this) { asked.add(message.id) }
            if (!fresh) continue
            if (MetaThumbnails.stored(preview)) {
                readStored(preview) { bytes -> if (bytes != null) worker.execute { keep(message.id, bytes) } }
            } else {
                worker.execute { runCatching { fetch(preview) }.getOrNull()?.let { keep(message.id, it) } }
            }
        }
    }

    private fun keep(messageId: String, image: ByteArray) {
        val artwork = runCatching { MessageThumbnailEncoder.encode(image) }.getOrNull() ?: return
        synchronized(this) { ready[messageId] = artwork }
        onReady()
    }

    @Synchronized fun clear() {
        ready.clear()
        asked.clear()
    }

    private fun fetch(url: String): ByteArray? {
        val connection = URL(url).openConnection() as? HttpURLConnection ?: return null
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "image/*")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            if (connection.contentLengthLong > MAX_BODY_BYTES) return null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > MAX_BODY_BYTES) return null
                    output.write(buffer, 0, read)
                }
                return output.toByteArray().takeIf { it.isNotEmpty() }
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000
        /** A CDN photo is a few hundred KiB; anything past this is not a preview. */
        const val MAX_BODY_BYTES = 4L * 1024 * 1024
    }
}
