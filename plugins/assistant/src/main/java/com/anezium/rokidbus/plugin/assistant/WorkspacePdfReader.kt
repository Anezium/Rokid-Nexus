package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException

internal data class WorkspacePagedText(val pages: List<String>, val complete: Boolean)

internal interface WorkspacePdfReader {
    /**
     * Returns the text layer of each page in order. [shouldStop] is asked before every page with the
     * characters read so far; stopping, or a document longer than the page cap, leaves the result
     * incomplete. A password-protected document throws [WorkspaceReadException] with PROTECTED.
     */
    fun read(bytes: ByteArray, shouldStop: (characters: Int) -> Boolean): WorkspacePagedText
}

internal class PdfBoxWorkspacePdfReader(context: Context) : WorkspacePdfReader {
    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    override fun read(bytes: ByteArray, shouldStop: (characters: Int) -> Boolean): WorkspacePagedText = try {
        readPages(bytes, shouldStop)
    } catch (_: StackOverflowError) {
        // Deeply nested forms in a malformed file must not take the indexing thread down.
        throw WorkspaceReadException(WorkspaceDocumentStatus.UNREADABLE)
    }

    private fun readPages(bytes: ByteArray, shouldStop: (characters: Int) -> Boolean): WorkspacePagedText {
        val document = try {
            PDDocument.load(bytes, "", null, null, MemoryUsageSetting.setupMainMemoryOnly(MAX_SCRATCH_BYTES))
        } catch (_: InvalidPasswordException) {
            throw WorkspaceReadException(WorkspaceDocumentStatus.PROTECTED)
        } catch (_: LinkageError) {
            // Certificate-encrypted PDFs need the BouncyCastle classes this build leaves out.
            throw WorkspaceReadException(WorkspaceDocumentStatus.PROTECTED)
        }
        return document.use {
            val stripper = PDFTextStripper().apply { paragraphEnd = lineSeparator }
            val pageCount = it.numberOfPages
            val pages = mutableListOf<String>()
            var characters = 0
            for (page in 1..minOf(pageCount, WorkspaceLimits.MAX_PDF_PAGES)) {
                if (Thread.currentThread().isInterrupted) throw IOException("cancelled")
                if (shouldStop(characters)) return@use WorkspacePagedText(pages, complete = false)
                stripper.startPage = page
                stripper.endPage = page
                val text = stripper.getText(it)
                pages += text
                characters += text.length
            }
            WorkspacePagedText(pages, complete = pageCount <= WorkspaceLimits.MAX_PDF_PAGES)
        }
    }

    private companion object {
        const val MAX_SCRATCH_BYTES = 64L * 1_024 * 1_024
    }
}
