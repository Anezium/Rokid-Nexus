package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspaceDocumentExtractorTest {
    @Test
    fun `UTF-8 BOM and Unicode survive while malformed text and binary fail`() {
        assertEquals("Préavis de deux mois.", extract("\uFEFFPréavis de deux mois.".toByteArray(), WorkspaceFileType.TEXT))
        assertTrue(runCatching { extract(byteArrayOf(0xC3.toByte()), WorkspaceFileType.TEXT) }.isFailure)
        assertTrue(runCatching { extract("binary\u0000data".toByteArray(), WorkspaceFileType.TEXT) }.isFailure)
    }

    @Test
    fun `Word body text preserves Unicode entities paragraph breaks tabs and line breaks`() {
        val xml = body("<w:p><w:r><w:t>Préavis &amp; leave</w:t><w:tab/><w:t>25</w:t><w:br/><w:t>days</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>Second paragraph.</w:t></w:r></w:p>")
        assertEquals("Préavis & leave\t25\ndays\n\nSecond paragraph.\n\n", extract(zip("word/document.xml" to xml), WorkspaceFileType.DOCX))
    }

    @Test
    fun `DOCTYPE entities malformed XML missing body and broken ZIP fail closed`() {
        val values = listOf(zip("unrelated.xml" to "ignore"),
            zip("word/document.xml" to "<broken"),
            zip("word/document.xml" to "<!DOCTYPE document [<!ENTITY x SYSTEM 'https://invalid.test/secret'>]>" + body("<w:p><w:t>&x;</w:t></w:p>")),
            zip("word/document.xml" to "<!DOCTYPE document [<!ENTITY x 'expanded'>]>" + body("<w:p><w:t>&x;</w:t></w:p>")),
            "not a zip".toByteArray())
        values.forEach { assertTrue(runCatching { extract(it, WorkspaceFileType.DOCX) }.isFailure) }
    }

    @Test
    fun `actual input decompression and entry counts enforce bounds`() {
        assertEquals(WorkspaceDocumentStatus.TOO_LARGE, (runCatching {
            extract(ByteArray(WorkspaceLimits.MAX_TEXT_BYTES + 1) { 'a'.code.toByte() }, WorkspaceFileType.TEXT)
        }.exceptionOrNull() as WorkspaceReadException).status)
        val oversized = zip("ignored.bin" to "a".repeat(WorkspaceLimits.MAX_TEXT_BYTES + 1))
        assertTrue(runCatching { extract(oversized, WorkspaceFileType.DOCX) }.isFailure)
        val entries = (0..128).map { "entry$it" to "" }.toTypedArray()
        assertTrue(runCatching { extract(zip(*entries), WorkspaceFileType.DOCX) }.isFailure)
    }

    @Test
    fun `malformed document errors do not print source-derived parser diagnostics`() {
        val captured = ByteArrayOutputStream()
        val previous = System.err
        val diagnostic = PrintStream(captured)
        try {
            System.setErr(diagnostic)
            val result = runCatching { extract(zip("word/document.xml" to body(
                "<w:p><w:t>private contract clause</w:t></private-clause>")), WorkspaceFileType.DOCX) }
            assertTrue(result.isFailure)
            assertEquals("invalid_docx", result.exceptionOrNull()!!.message)
            assertEquals("", captured.toString("UTF-8"))
        } finally {
            System.setErr(previous)
            diagnostic.close()
        }
    }

    private fun extract(bytes: ByteArray, type: WorkspaceFileType) =
        ByteArrayInputStream(bytes).use { WorkspaceDocumentExtractor.extract(it, type) }

    private fun body(text: String) = "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body>$text</w:body></w:document>"

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        } }
        return output.toByteArray()
    }
}
