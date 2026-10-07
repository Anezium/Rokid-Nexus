package com.anezium.rokidbus.plugin.assistant

import org.json.JSONArray
import org.json.JSONObject

internal object WorkspaceIndexJson {
    fun render(index: WorkspaceIndex): String {
        validate(index)
        return JSONObject().put("version", 1).put("generation", index.generation)
            .put("indexedAtMs", index.indexedAtMs).put("skippedFiles", index.skippedFiles)
            .put("documents", JSONArray().apply {
                index.documents.forEach { document ->
                    val entry = document.entry
                    put(JSONObject().put("id", entry.documentId).put("name", entry.name)
                        .put("path", entry.relativePath).put("modifiedAtMs", entry.modifiedAtMs)
                        .put("sizeBytes", entry.sizeBytes).put("type", entry.type?.name)
                        .put("status", document.status.name).put("chunks", JSONArray().apply {
                            document.chunks.forEach { chunk ->
                                put(JSONObject().put("ordinal", chunk.ordinal).put("text", chunk.text)
                                    .put("heading", chunk.headingPath).put("paragraph", chunk.paragraph))
                            }
                        }))
                }
            }).toString().also {
                require(it.toByteArray(Charsets.UTF_8).size <= WorkspaceLimits.MAX_INDEX_BYTES)
            }
    }

    fun parse(text: String): WorkspaceIndex {
        require(text.toByteArray(Charsets.UTF_8).size <= WorkspaceLimits.MAX_INDEX_BYTES)
        val root = JSONObject(text)
        require(root.getInt("version") == 1)
        val documents = root.getJSONArray("documents")
        require(documents.length() <= WorkspaceLimits.MAX_FILES)
        var totalChunks = 0
        val index = WorkspaceIndex(
            generation = root.getLong("generation"), indexedAtMs = root.getLong("indexedAtMs"),
            skippedFiles = root.getInt("skippedFiles"),
            documents = List(documents.length()) { position ->
                val document = documents.getJSONObject(position)
                val chunks = document.getJSONArray("chunks")
                totalChunks += chunks.length()
                require(totalChunks <= WorkspaceLimits.MAX_CHUNKS)
                WorkspaceDocument(
                    entry = WorkspaceEntry(
                        documentId = document.getString("id"), name = document.getString("name"),
                        relativePath = document.getString("path"),
                        modifiedAtMs = document.nullableLong("modifiedAtMs"),
                        sizeBytes = document.nullableLong("sizeBytes"),
                        type = WorkspaceFileType.valueOf(document.getString("type")),
                    ),
                    status = WorkspaceDocumentStatus.valueOf(document.getString("status")),
                    chunks = List(chunks.length()) { ordinal ->
                        val chunk = chunks.getJSONObject(ordinal)
                        WorkspaceChunk(chunk.getInt("ordinal"), chunk.getString("text"),
                            chunk.getString("heading"), chunk.getInt("paragraph"))
                    },
                )
            },
        )
        validate(index)
        return index
    }

    private fun validate(index: WorkspaceIndex) {
        require(index.generation >= 0 && index.indexedAtMs > 0 && index.skippedFiles in 0..WorkspaceLimits.MAX_ENTRIES)
        require(index.documents.size <= WorkspaceLimits.MAX_FILES && index.chunkCount <= WorkspaceLimits.MAX_CHUNKS)
        require(index.characterCount <= WorkspaceLimits.MAX_TOTAL_CHARS)
        require(index.documents.map { it.entry.documentId }.distinct().size == index.documents.size)
        index.documents.forEach { document ->
            val entry = document.entry
            require(entry.documentId.isNotEmpty() && entry.documentId.length <= 1_024 && entry.type != null)
            require(entry.name.length <= 96 && entry.relativePath.length <= 160)
            require(document.chunks.sumOf { it.text.length } <= WorkspaceLimits.MAX_FILE_CHARS)
            require(document.chunks.map { it.ordinal }.distinct().size == document.chunks.size)
            if (document.chunks.isNotEmpty()) {
                require(entry.hasReliableMetadata())
                require(document.status in setOf(WorkspaceDocumentStatus.INDEXED, WorkspaceDocumentStatus.TRUNCATED))
            }
            document.chunks.forEach { chunk ->
                require(chunk.ordinal >= 0 && chunk.paragraph >= 0 && chunk.headingPath.length <= 160)
                require(chunk.text.isNotBlank() && chunk.text.length <= WorkspaceLimits.MAX_CHUNK_CHARS)
            }
        }
    }

    private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
}
