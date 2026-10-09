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
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal data class WorkspacePagedText(val pages: List<String>, val complete: Boolean)

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
                else WorkspacePagedText(listOf(recognizeImage(bytes)), complete = true)
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
                var characters = 0
                for (page in firstPage..lastPage) {
                    if (Thread.currentThread().isInterrupted) throw IOException("cancelled")
                    if (shouldStop(characters)) return WorkspacePagedText(pages, complete = false)
                    stripper.startPage = page
                    stripper.endPage = page
                    val text = stripper.getText(it).ifBlank {
                        (scanner ?: openScanner(bytes).also { opened -> scanner = opened })
                            .recognize(page - 1)
                    }
                    pages += text
                    characters += text.length
                }
                return WorkspacePagedText(pages, complete = lastPage == pageCount)
            }
        } finally {
            scanner?.close()
        }
    }

    private fun recognizeImage(bytes: ByteArray): String {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val edge = maxOf(info.size.width, info.size.height)
            if (edge > RECOGNITION_EDGE) {
                val scale = RECOGNITION_EDGE.toFloat() / edge
                decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1))
            }
        }
        return try {
            recognize(bitmap)
        } finally {
            bitmap.recycle()
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
            val renderer = renderer?.takeIf { index < it.pageCount } ?: return ""
            val bitmap = renderer.openPage(index).use { page ->
                val scale = RECOGNITION_EDGE.toFloat() / maxOf(page.width, page.height, 1)
                Bitmap.createBitmap((page.width * scale).roundToInt().coerceAtLeast(1),
                    (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
            return try {
                recognize(bitmap)
            } finally {
                bitmap.recycle()
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
    }
}
