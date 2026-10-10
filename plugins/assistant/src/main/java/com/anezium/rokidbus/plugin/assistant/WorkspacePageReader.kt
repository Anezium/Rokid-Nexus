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
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** One page a read reached, with what was observed on it; an image is page 1. */
internal data class WorkspaceReadPage(val page: Int, val text: String, val state: WorkspacePageState)

/**
 * The pages one read reached, in order. [pageCount] is the document's actual total; [complete] means
 * the read reached the last page within the page cap rather than stopping early.
 */
internal data class WorkspacePagedText(
    val pages: List<WorkspaceReadPage>,
    val pageCount: Int?,
    val complete: Boolean,
)

/** What inspection observed without extracting any page text. */
internal data class WorkspacePageInfo(val pageCount: Int)

internal interface WorkspacePageReader {
    /**
     * Reads each page from [firstPage] (1-based) in order. [skip] is asked first and a skipped page is
     * neither extracted nor reported; [shouldStop] is then asked with the characters read so far, and
     * stopping leaves the page unattempted. A failed, empty, or too dense page is still reported with
     * its state, so a cursor moves past it. A password-protected document throws
     * [WorkspaceReadException] with PROTECTED.
     */
    fun read(
        type: WorkspaceFileType,
        bytes: ByteArray,
        firstPage: Int,
        shouldStop: (characters: Int) -> Boolean,
        skip: (page: Int) -> Boolean = { false },
    ): WorkspacePagedText

    /** The actual page count, without text extraction, recognition, or rendering. */
    fun inspect(type: WorkspaceFileType, bytes: ByteArray): WorkspacePageInfo

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
        skip: (page: Int) -> Boolean,
    ): WorkspacePagedText = guarded {
        when (type) {
            WorkspaceFileType.PDF -> readPdf(bytes, firstPage, shouldStop, skip)
            WorkspaceFileType.IMAGE -> when {
                firstPage > 1 || skip(1) -> WorkspacePagedText(emptyList(), 1, complete = true)
                shouldStop(0) -> WorkspacePagedText(emptyList(), 1, complete = false)
                else -> WorkspacePagedText(listOf(recognized(1, decodeImage(bytes, RECOGNITION_EDGE))), 1, complete = true)
            }
            else -> throw IllegalArgumentException("$type is not read page by page")
        }
    }

    override fun inspect(type: WorkspaceFileType, bytes: ByteArray): WorkspacePageInfo = guarded {
        when (type) {
            WorkspaceFileType.PDF -> loadPdf(bytes).use { WorkspacePageInfo(it.numberOfPages.coerceAtLeast(1)) }
            WorkspaceFileType.IMAGE -> WorkspacePageInfo(1)
            else -> throw IllegalArgumentException("$type has no pages")
        }
    }

    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (_: StackOverflowError) {
        // Deeply nested forms in a malformed file must not take the indexing thread down.
        throw WorkspaceReadException(WorkspaceDocumentStatus.UNREADABLE)
    } catch (_: OutOfMemoryError) {
        // A last resort behind the glyph budget: the allocation that failed is gone with this file.
        throw WorkspaceReadException(WorkspaceDocumentStatus.TOO_LARGE)
    }

    private fun loadPdf(bytes: ByteArray): PDDocument = try {
        PDDocument.load(bytes, "", null, null, MemoryUsageSetting.setupMainMemoryOnly(MAX_SCRATCH_BYTES))
    } catch (_: InvalidPasswordException) {
        throw WorkspaceReadException(WorkspaceDocumentStatus.PROTECTED)
    } catch (_: LinkageError) {
        // Certificate-encrypted PDFs need the BouncyCastle classes this build leaves out.
        throw WorkspaceReadException(WorkspaceDocumentStatus.PROTECTED)
    }

    private fun readPdf(
        bytes: ByteArray,
        firstPage: Int,
        shouldStop: (characters: Int) -> Boolean,
        skip: (page: Int) -> Boolean,
    ): WorkspacePagedText {
        val document = loadPdf(bytes)
        var scanner: PdfPageScanner? = null
        try {
            document.use {
                val stripper = BoundedTextStripper().apply { paragraphEnd = lineSeparator }
                val pageCount = it.numberOfPages
                val lastPage = minOf(pageCount, WorkspaceLimits.MAX_PDF_PAGES)
                val pages = mutableListOf<WorkspaceReadPage>()
                var characters = 0
                for (page in firstPage..lastPage) {
                    if (Thread.currentThread().isInterrupted) throw IOException("cancelled")
                    if (skip(page)) continue
                    if (shouldStop(characters)) return WorkspacePagedText(pages, pageCount, complete = false)
                    stripper.startPage = page
                    stripper.endPage = page
                    stripper.glyphs = 0
                    val layer = try {
                        stripper.getText(it)
                    } catch (_: PageTooDenseException) {
                        // Marked and passed, so later pages still read; this page's text is lost.
                        pages += WorkspaceReadPage(page, "", WorkspacePageState(WorkspaceTextState.TRUNCATED))
                        continue
                    }
                    val read = if (layer.isNotBlank()) {
                        val visual = if (hasVisual(it.getPage(page - 1))) WorkspaceVisualState.PRESENT
                        else WorkspaceVisualState.ABSENT
                        WorkspaceReadPage(page, layer, WorkspacePageState(WorkspaceTextState.NATIVE, visual))
                    } else {
                        // A scanned page is a picture: its recognized text may miss what it shows.
                        (scanner ?: openScanner(bytes).also { opened -> scanner = opened }).recognize(page)
                    }
                    pages += read
                    characters += read.text.length
                }
                return WorkspacePagedText(pages, pageCount, complete = true)
            }
        } finally {
            scanner?.close()
        }
    }

    /**
     * A renderer that never produced the page is FAILED and unavailable; a rendered page whose
     * recognition failed or timed out is FAILED but renderable; a blank result is EMPTY. None of
     * them passes for successful empty recognition.
     */
    private fun recognized(page: Int, bitmap: Bitmap?): WorkspaceReadPage {
        bitmap ?: return WorkspaceReadPage(page, "",
            WorkspacePageState(WorkspaceTextState.FAILED, render = WorkspaceRenderState.UNAVAILABLE))
        val text = try {
            recognize(bitmap)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("cancelled")
        } catch (_: Exception) {
            return WorkspaceReadPage(page, "",
                WorkspacePageState(WorkspaceTextState.FAILED, render = WorkspaceRenderState.AVAILABLE))
        }
        return if (text.isBlank()) {
            WorkspaceReadPage(page, "", WorkspacePageState(WorkspaceTextState.EMPTY, render = WorkspaceRenderState.AVAILABLE))
        } else {
            WorkspaceReadPage(page, text, WorkspacePageState(WorkspaceTextState.OCR, WorkspaceVisualState.PRESENT,
                WorkspaceRenderState.AVAILABLE))
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
        picture || countDrawnShapes(page) >= MIN_VISUAL_SHAPES
    } catch (_: Exception) {
        false
    }

    /**
     * Counts painted paths whose bounds cover a meaningful share of the page: full-page backgrounds
     * and hairline rules or underlines do not count. Bounds ignore transforms; this is only a hint.
     */
    private fun countDrawnShapes(page: PDPage): Int {
        val box = page.mediaBox
        val pageArea = (box.width * box.height).takeIf { it > 0f } ?: return 0
        val parser = PDFStreamParser(page)
        val operands = mutableListOf<Float>()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        fun point(x: Float, y: Float) {
            minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
        }
        fun resetPath() {
            minX = Float.MAX_VALUE; minY = Float.MAX_VALUE; maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE
        }
        var shapes = 0
        var tokens = 0
        while (tokens++ < MAX_VISUAL_TOKENS && shapes < MIN_VISUAL_SHAPES) {
            val token = parser.parseNextToken() ?: break
            if (token is COSNumber) {
                operands += token.floatValue()
                continue
            }
            if (token !is Operator) continue
            when (token.name) {
                "m", "l" -> operands.takeLast(2).takeIf { it.size == 2 }?.let { point(it[0], it[1]) }
                "c", "v", "y" -> operands.chunked(2).filter { it.size == 2 }.forEach { point(it[0], it[1]) }
                "re" -> operands.takeLast(4).takeIf { it.size == 4 }?.let { (x, y, w, h) ->
                    point(x, y)
                    point(x + w, y + h)
                }
                "n" -> resetPath()
                in PAINT_OPERATORS -> {
                    if (maxX >= minX && maxY >= minY) {
                        val share = (maxX - minX) * (maxY - minY) / pageArea
                        if (share in MIN_SHAPE_SHARE..MAX_SHAPE_SHARE) shapes++
                    }
                    resetPath()
                }
            }
            operands.clear()
        }
        return shapes
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

    /**
     * Takes ownership of [bitmap]. It is recycled when recognition ends, not when the wait does: a
     * cancelled or timed-out wait leaves the task still reading it.
     */
    private fun recognize(bitmap: Bitmap): String {
        val task = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        }
        task.addOnCompleteListener(Executor { it.run() }) { bitmap.recycle() }
        val result = Tasks.await(task, RECOGNITION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return result.textBlocks.joinToString("\n\n") { it.text }
    }

    /** Renders scanned pages through the platform renderer, which needs the document as a file. */
    private inner class PdfPageScanner(
        private val file: File,
        private val descriptor: ParcelFileDescriptor,
        private val renderer: PdfRenderer?,
    ) : Closeable {
        fun recognize(page: Int): WorkspaceReadPage = recognized(page, try {
            render(page - 1, RECOGNITION_EDGE)
        } catch (_: Exception) {
            null
        })

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

    /** Stops a page whose glyph count alone could exhaust memory before the character cap applies. */
    private class BoundedTextStripper : PDFTextStripper() {
        var glyphs = 0

        override fun processTextPosition(text: TextPosition) {
            if (++glyphs > MAX_PAGE_GLYPHS) throw PageTooDenseException()
            super.processTextPosition(text)
        }
    }

    // Unchecked: PdfBox swallows IOExceptions raised inside some operators.
    private class PageTooDenseException : RuntimeException("page text exceeds the glyph budget")

    private companion object {
        const val MAX_PAGE_GLYPHS = 150_000
        const val MAX_SCRATCH_BYTES = 64L * 1_024 * 1_024
        const val RECOGNITION_EDGE = 2_000
        const val RECOGNITION_TIMEOUT_SECONDS = 30L
        const val VIEW_EDGE = 1_600
        const val MIN_VISUAL_IMAGE_PIXELS = 200 * 200
        const val MIN_VISUAL_SHAPES = 3
        const val MIN_SHAPE_SHARE = 0.002f
        const val MAX_SHAPE_SHARE = 0.8f
        const val MAX_VISUAL_TOKENS = 20_000
        val PAINT_OPERATORS = setOf("f", "F", "f*", "S", "s", "B", "B*", "b", "b*")
        const val VIEW_JPEG_QUALITY = 80
        const val MAX_VIEW_JPEG_BYTES = 1_000_000
    }
}
