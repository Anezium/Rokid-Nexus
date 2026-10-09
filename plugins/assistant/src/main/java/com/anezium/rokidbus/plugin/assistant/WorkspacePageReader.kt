package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** [visualPages] holds indexes into [pages] whose page shows a chart, table, drawing, or picture. */
internal data class WorkspacePagedText(
    val pages: List<String>,
    val complete: Boolean,
    val visualPages: Set<Int> = emptySet(),
)

internal interface WorkspacePageReader {
    /**
     * Returns the text of each page from [firstPage] (1-based) in order; an image is a single page.
     * [shouldStop] is asked before every page with the characters read so far in this call; stopping,
     * or a document longer than the page cap, leaves the result incomplete. A password-protected
     * document throws [WorkspaceReadException] with PROTECTED.
     */
    fun read(
        type: WorkspaceFileType,
        bytes: ByteArray,
        firstPage: Int,
        shouldStop: (characters: Int) -> Boolean,
    ): WorkspacePagedText

    /** A JPEG of one page (1-based) small enough to send to a vision model, or null when it cannot render. */
    fun render(type: WorkspaceFileType, bytes: ByteArray, page: Int): ByteArray?
}

/**
 * Uses a PDF's text layer when a page has one and on-device text recognition otherwise, so scans and
 * photos become searchable text at indexing time and a question never waits for recognition.
 */
internal class AndroidWorkspacePageReader(context: Context) : WorkspacePageReader {
    private val cacheDir = context.applicationContext.cacheDir
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    override fun read(
        type: WorkspaceFileType,
        bytes: ByteArray,
        firstPage: Int,
        shouldStop: (characters: Int) -> Boolean,
    ): WorkspacePagedText = try {
        when (type) {
            WorkspaceFileType.PDF -> readPdf(bytes, firstPage, shouldStop)
            WorkspaceFileType.IMAGE ->
                if (shouldStop(0)) WorkspacePagedText(emptyList(), complete = false)
                else WorkspacePagedText(listOf(recognizeImage(bytes)), complete = true, visualPages = setOf(0))
            else -> throw IllegalArgumentException("$type is not read page by page")
        }
    } catch (_: StackOverflowError) {
        // Deeply nested forms in a malformed file must not take the indexing thread down.
        throw WorkspaceReadException(WorkspaceDocumentStatus.UNREADABLE)
    }

    private fun readPdf(bytes: ByteArray, firstPage: Int, shouldStop: (characters: Int) -> Boolean): WorkspacePagedText {
        val document = try {
            PDDocument.load(bytes, "", null, null, MemoryUsageSetting.setupMainMemoryOnly(MAX_SCRATCH_BYTES))
        } catch (_: InvalidPasswordException) {
            throw WorkspaceReadException(WorkspaceDocumentStatus.PROTECTED)
        } catch (_: LinkageError) {
            // Certificate-encrypted PDFs need the BouncyCastle classes this build leaves out.
            throw WorkspaceReadException(WorkspaceDocumentStatus.PROTECTED)
        }
        var scanner: PdfPageScanner? = null
        try {
            document.use {
                val stripper = PDFTextStripper().apply { paragraphEnd = lineSeparator }
                val pageCount = it.numberOfPages
                val lastPage = minOf(pageCount, WorkspaceLimits.MAX_PDF_PAGES)
                val pages = mutableListOf<String>()
                val visual = mutableSetOf<Int>()
                var characters = 0
                for (page in firstPage..lastPage) {
                    if (Thread.currentThread().isInterrupted) throw IOException("cancelled")
                    if (shouldStop(characters)) return WorkspacePagedText(pages, complete = false, visual)
                    stripper.startPage = page
                    stripper.endPage = page
                    val layer = stripper.getText(it)
                    if (layer.isNotBlank() && hasVisual(it.getPage(page - 1))) visual += pages.size
                    val text = layer.ifBlank {
                        (scanner ?: openScanner(bytes).also { opened -> scanner = opened })
                            .recognize(page - 1)
                    }
                    pages += text
                    characters += text.length
                }
                return WorkspacePagedText(pages, complete = lastPage == pageCount, visual)
            }
        } finally {
            scanner?.close()
        }
    }

    override fun render(type: WorkspaceFileType, bytes: ByteArray, page: Int): ByteArray? {
        val bitmap = when (type) {
            WorkspaceFileType.PDF -> openScanner(bytes).use { it.render(page - 1, VIEW_EDGE) }
            WorkspaceFileType.IMAGE -> if (page == 1) decodeImage(bytes, VIEW_EDGE) else null
            else -> null
        } ?: return null
        var current = bitmap
        try {
            while (true) {
                val output = ByteArrayOutputStream()
                if (!current.compress(Bitmap.CompressFormat.JPEG, VIEW_JPEG_QUALITY, output)) return null
                if (output.size() <= MAX_VIEW_JPEG_BYTES) return output.toByteArray()
                val scaled = Bitmap.createScaledBitmap(current, (current.width * 0.8f).roundToInt().coerceAtLeast(1),
                    (current.height * 0.8f).roundToInt().coerceAtLeast(1), true)
                if (scaled === current) return null
                current.recycle()
                current = scaled
            }
        } finally {
            current.recycle()
        }
    }

    /**
     * Flags a text page that also carries a picture or a drawing worth looking at, so its excerpt can
     * tell the model to view it. Logos and rules stay below the thresholds; a scanned page is read
     * by recognition instead.
     */
    private fun hasVisual(page: PDPage): Boolean = try {
        val resources = page.resources
        val picture = resources?.xObjectNames?.any { name ->
            resources.isImageXObject(name) && (resources.getXObject(name) as? PDImageXObject)
                ?.let { it.width * it.height >= MIN_VISUAL_IMAGE_PIXELS } == true
        } == true
        picture || run {
            val parser = PDFStreamParser(page)
            var painted = 0
            var tokens = 0
            while (tokens++ < MAX_VISUAL_TOKENS) {
                val token = parser.parseNextToken() ?: break
                if (token is Operator && token.name in PAINT_OPERATORS && ++painted >= MIN_VISUAL_PAINTS) break
            }
            painted >= MIN_VISUAL_PAINTS
        }
    } catch (_: Exception) {
        false
    }

    private fun recognizeImage(bytes: ByteArray): String {
        val bitmap = decodeImage(bytes, RECOGNITION_EDGE)
        return try {
            recognize(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeImage(bytes: ByteArray, maxEdge: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val edge = maxOf(info.size.width, info.size.height)
            if (edge > maxEdge) {
                val scale = maxEdge.toFloat() / edge
                decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1))
            }
        }

    private fun recognize(bitmap: Bitmap): String {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)),
            RECOGNITION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return result.textBlocks.joinToString("\n\n") { it.text }
    }

    /** Renders scanned pages through the platform renderer, which needs the document as a file. */
    private inner class PdfPageScanner(
        private val file: File,
        private val descriptor: ParcelFileDescriptor,
        private val renderer: PdfRenderer?,
    ) : Closeable {
        fun recognize(index: Int): String {
            val bitmap = render(index, RECOGNITION_EDGE) ?: return ""
            return try {
                recognize(bitmap)
            } finally {
                bitmap.recycle()
            }
        }

        fun render(index: Int, edge: Int): Bitmap? {
            val renderer = renderer?.takeIf { index in 0 until it.pageCount } ?: return null
            return renderer.openPage(index).use { page ->
                val scale = edge.toFloat() / maxOf(page.width, page.height, 1)
                Bitmap.createBitmap((page.width * scale).roundToInt().coerceAtLeast(1),
                    (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }

        override fun close() {
            renderer?.close()
            descriptor.close()
            file.delete()
        }
    }

    private fun openScanner(bytes: ByteArray): PdfPageScanner {
        val file = File.createTempFile("workspace-", ".pdf", cacheDir)
        try {
            file.writeBytes(bytes)
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            // The platform renderer refuses some encrypted files that PdfBox can still read; their
            // textless pages then stay empty.
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (_: Exception) {
                null
            }
            return PdfPageScanner(file, descriptor, renderer)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private companion object {
        const val MAX_SCRATCH_BYTES = 64L * 1_024 * 1_024
        const val RECOGNITION_EDGE = 2_000
        const val RECOGNITION_TIMEOUT_SECONDS = 30L
        const val VIEW_EDGE = 1_600
        const val MIN_VISUAL_IMAGE_PIXELS = 200 * 200
        const val MIN_VISUAL_PAINTS = 4
        const val MAX_VISUAL_TOKENS = 20_000
        val PAINT_OPERATORS = setOf("f", "F", "f*", "S", "s", "B", "B*", "b", "b*")
        const val VIEW_JPEG_QUALITY = 80
        const val MAX_VIEW_JPEG_BYTES = 1_000_000
    }
}
