package com.anezium.rokidbus.plugin.assistant

import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

internal class WorkspaceReadException(val status: WorkspaceDocumentStatus) : IOException(status.name)
internal data class WorkspaceBytes(val size: Int, val data: ByteArray?)

internal fun readWorkspaceBytes(input: InputStream, maxBytes: Int, capture: Boolean = true): WorkspaceBytes {
    val output = if (capture) ByteArrayOutputStream(minOf(maxBytes, 8_192).coerceAtLeast(0)) else null
    val buffer = ByteArray(8_192)
    var total = 0
    while (true) {
        if (Thread.currentThread().isInterrupted) throw IOException("cancelled")
        val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - total + 1))
        if (count < 0) break
        total += count
        if (total > maxBytes) throw WorkspaceReadException(WorkspaceDocumentStatus.TOO_LARGE)
        output?.write(buffer, 0, count)
    }
    return WorkspaceBytes(total, output?.toByteArray())
}

internal object WorkspaceDocumentExtractor {
    fun extract(input: InputStream, type: WorkspaceFileType): String = when (type) {
        WorkspaceFileType.TEXT, WorkspaceFileType.MARKDOWN -> {
            val bytes = readWorkspaceBytes(input, WorkspaceLimits.MAX_TEXT_BYTES).data!!
            val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                .removePrefix("\uFEFF")
            if (value.any { it.isISOControl() && it !in "\r\n\t" }) {
                throw WorkspaceReadException(WorkspaceDocumentStatus.INVALID_TEXT)
            }
            value
        }
        WorkspaceFileType.DOCX -> extractDocx(readWorkspaceBytes(input, WorkspaceLimits.MAX_DOCX_BYTES).data!!)
        WorkspaceFileType.PDF -> throw IllegalArgumentException("PDF pages are read by WorkspacePdfReader")
    }

    private fun extractDocx(bytes: ByteArray): String {
        val xml = readWorkspaceZipEntry(bytes, "word/document.xml")
        val text = StringBuilder()
        val handler = object : DefaultHandler2() {
            private var inText = false
            override fun error(error: org.xml.sax.SAXParseException) { throw SAXException("invalid_docx") }
            override fun fatalError(error: org.xml.sax.SAXParseException) { throw SAXException("invalid_docx") }
            override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                throw SAXException("invalid_docx")
            }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
                throw SAXException("invalid_docx")
            }
            override fun startElement(uri: String, localName: String, qName: String, attrs: org.xml.sax.Attributes) {
                if (uri != WORD_NAMESPACE) return
                inText = localName == "t"
                if (localName == "br") text.append('\n')
                if (localName == "tab") text.append('\t')
            }
            override fun endElement(uri: String, localName: String, qName: String) {
                if (uri != WORD_NAMESPACE) return
                if (localName == "t") inText = false
                if (localName == "p") text.append("\n\n")
            }
            override fun characters(chars: CharArray, start: Int, length: Int) {
                if (inText) text.append(chars, start, length)
            }
        }
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.entityResolver = handler
        reader.parse(InputSource(ByteArrayInputStream(xml)))
        return text.toString()
    }

    private const val WORD_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
}

private fun readWorkspaceZipEntry(bytes: ByteArray, name: String): ByteArray {
    var found: ByteArray? = null
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entries = 0
        var total = 0
        while (true) {
            val entry = zip.nextEntry ?: break
            if (++entries > WorkspaceLimits.MAX_ZIP_ENTRIES) {
                throw WorkspaceReadException(WorkspaceDocumentStatus.TOO_LARGE)
            }
            val body = readWorkspaceBytes(zip, WorkspaceLimits.MAX_TEXT_BYTES - total, capture = entry.name == name)
            total += body.size
            if (body.data != null) {
                check(found == null)
                found = body.data
            }
        }
    }
    return found ?: throw SAXException("invalid_docx")
}
