package com.anezium.rokidbus.plugin.assistant

internal object WorkspaceChunker {
    fun chunk(text: String, markdown: Boolean = false): List<WorkspaceChunk> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val chunks = mutableListOf<WorkspaceChunk>()
        val headings = sortedMapOf<Int, String>()
        val paragraph = mutableListOf<String>()
        var pending = ""
        var pendingHeading = ""
        var pendingParagraph = 0
        var paragraphNumber = 0
        var fence: Char? = null
        var fenceLength = 0

        fun flushChunk() {
            if (pending.isNotBlank()) {
                chunks += WorkspaceChunk(chunks.size, pending, pendingHeading, pendingParagraph)
                pending = ""
            }
        }

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            val value = paragraph.joinToString("\n").trim()
            paragraph.clear()
            val heading = workspaceLabel(headings.values.joinToString(" › "))
            val position = paragraphNumber++
            val pieces = splitParagraph(value)
            for (piece in pieces) {
                if (pending.isNotEmpty() && (pendingHeading != heading ||
                    pending.length + piece.length + 2 > WorkspaceLimits.MAX_CHUNK_CHARS)
                ) flushChunk()
                if (pending.isEmpty()) {
                    pendingHeading = heading
                    pendingParagraph = position
                    pending = piece
                } else {
                    pending += "\n\n$piece"
                }
                if (pending.length >= WorkspaceLimits.TARGET_CHUNK_CHARS) flushChunk()
            }
        }

        fun setHeading(level: Int, title: String) {
            flushParagraph()
            flushChunk()
            headings.keys.filter { it >= level }.toList().forEach(headings::remove)
            headings[level] = workspaceLabel(title)
        }

        var lineIndex = 0
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val trimmed = line.trim()
            val marker = if (markdown) FENCE.find(trimmed)?.value else null
            if (marker != null && (fence == null || marker.first() == fence && marker.length >= fenceLength)) {
                if (fence == null) {
                    fence = marker.first()
                    fenceLength = marker.length
                } else {
                    fence = null
                }
                paragraph += line
            } else if (markdown && fence == null) {
                val atx = ATX.matchEntire(trimmed)
                val underline = lines.getOrNull(lineIndex + 1)?.trim()
                when {
                    atx != null -> setHeading(atx.groupValues[1].length,
                        atx.groupValues[2].replace(Regex("\\s+#+$"), ""))
                    trimmed.isNotEmpty() && underline != null && SETEXT.matches(underline) -> {
                        setHeading(if (underline.first() == '=') 1 else 2, trimmed)
                        lineIndex++
                    }
                    trimmed.isEmpty() -> flushParagraph()
                    else -> paragraph += line
                }
            } else if (trimmed.isEmpty()) {
                flushParagraph()
            } else {
                paragraph += line
            }
            lineIndex++
        }
        flushParagraph()
        flushChunk()
        return chunks
    }

    private fun splitParagraph(paragraph: String): List<String> {
        val result = mutableListOf<String>()
        var remaining = paragraph
        while (remaining.isNotBlank()) {
            if (remaining.length <= WorkspaceLimits.MAX_CHUNK_CHARS) {
                result += remaining
                break
            }
            val prefix = workspaceWordPrefix(remaining, WorkspaceLimits.MAX_CHUNK_CHARS)
            if (prefix.isEmpty()) {
                remaining = remaining.dropWhile { !it.isWhitespace() }.trimStart()
                continue
            }
            val sentence = prefix.indexOfLast { it == '.' || it == '!' || it == '?' }
            val split = if (sentence >= 399 && sentence + 1 < prefix.length &&
                prefix[sentence + 1].isWhitespace()
            ) sentence + 1 else prefix.length
            result += remaining.take(split).trimEnd()
            remaining = remaining.drop(split).trimStart()
        }
        return result
    }

    private val ATX = Regex("^(#{1,6})\\s+(.+)$")
    private val SETEXT = Regex("^(={3,}|-{3,})$")
    private val FENCE = Regex("^(`{3,}|~{3,})")
}
