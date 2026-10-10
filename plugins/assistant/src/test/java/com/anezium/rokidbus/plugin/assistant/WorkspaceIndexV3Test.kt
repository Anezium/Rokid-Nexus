package com.anezium.rokidbus.plugin.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WorkspaceIndexV3Test {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `a reloaded index uses its persisted postings and answers exactly as a rebuilt one`() {
        val directory = temporary.newFolder()
        val store = WorkspaceStore(directory)
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val index = sampleIndex(store.snapshot().state.settings.generation)
        assertTrue(store.publish(index))
        val written = File(directory, "workspace-index.json").readText()
        assertTrue(JSONObject(written).getBoolean("lexicalPersisted"))
        assertEquals(3, JSONObject(written).getInt("version"))

        val before = WorkspaceLexicalIndex.bodyTokenizations
        val reloaded = WorkspaceStore(directory).snapshot()
        assertEquals(before, WorkspaceLexicalIndex.bodyTokenizations)
        assertEquals(index, reloaded.state.index)
        val rebuilt = WorkspaceRetriever(index.documents)
        val withoutCache = WorkspaceRetriever(WorkspaceIndexJson.decode(
            JSONObject(written).put("lexicalPersisted", false).toString()).also { assertNull(it.lexical) }.index.documents)
        val afterBuilds = WorkspaceLexicalIndex.bodyTokenizations
        repeat(3) {
            for (query in QUERIES) {
                for (mode in WorkspaceQueryMode.values()) {
                    val cached = reloaded.retriever!!.search(query, mode = mode)
                    assertEquals("$query $mode", rebuilt.search(query, mode = mode), cached)
                    assertEquals("$query $mode", withoutCache.search(query, mode = mode), cached)
                }
                assertEquals(rebuilt.scoped("orion", query, setOf("orion", "pdf"), 600, "Scope"),
                    reloaded.retriever!!.scoped("orion", query, setOf("orion", "pdf"), 600, "Scope"))
            }
        }
        assertEquals(afterBuilds, WorkspaceLexicalIndex.bodyTokenizations)
    }

    @Test
    fun `postings that do not fit are omitted while every retained chunk stays`() {
        val generation = 0L
        val index = sampleIndex(generation)
        val lexical = WorkspaceLexicalIndex.build(index.documents.flatMap { document -> document.chunks.map { it.text } })
        val full = WorkspaceIndexJson.encode(index, lexical)
        assertTrue(full.lexicalPersisted)
        val withoutCache = WorkspaceIndexJson.encode(index, null).bytes
        val tight = WorkspaceIndexJson.encode(index, lexical, maxBytes = withoutCache + 10)
        assertFalse(tight.lexicalPersisted)
        val restored = WorkspaceIndexJson.decode(tight.text)
        assertNull(restored.lexical)
        assertEquals(index, restored.index)
        assertTrue(runCatching { WorkspaceIndexJson.encode(index, lexical, maxBytes = withoutCache - 30) }.isFailure)
    }

    @Test
    fun `the worst-case catalog fits its reservation and dense non-ASCII text keeps all chunks`() {
        val documents = (0 until WorkspaceLimits.MAX_FILES).map { file ->
            val id = "doc-$file-" + "\"/:é".repeat(255)
            val runs = (1..WorkspaceLimits.MAX_PDF_PAGES).map { page ->
                WorkspacePageRun(page, page, if (page % 2 == 0) WorkspacePageState(WorkspaceTextState.EMPTY,
                    render = WorkspaceRenderState.AVAILABLE) else WorkspacePageState(WorkspaceTextState.NATIVE,
                    WorkspaceVisualState.ABSENT))
            }
            val chunks = (0 until 25).map { ordinal ->
                WorkspaceChunk(ordinal, (0 until 40).joinToString(" ") { word -> cyrillic(file * 1_000 + ordinal * 40 + word) },
                    page = ordinal * 2 + 1)
            }
            WorkspaceDocument(
                WorkspaceEntry(id.take(1_024), "Ж" + "\"".repeat(91) + ".pdf",
                    ("dossier/" + "\"".repeat(140) + "/$file.pdf").take(160), modifiedAtMs = 10, sizeBytes = 20,
                    type = WorkspaceFileType.PDF),
                chunks, pageCount = WorkspaceLimits.MAX_PDF_PAGES, pageRuns = runs,
                sourceDigest = "a".repeat(64), lookupSafe = true, coverageKnown = true,
            )
        }
        val index = WorkspaceIndex(0, documents, 1_000)
        assertEquals(WorkspaceLimits.MAX_CHUNKS, index.chunkCount)
        assertTrue(index.characterCount <= WorkspaceLimits.MAX_TOTAL_CHARS)
        assertEquals(WorkspaceLimits.MAX_PAGE_RUNS, documents.sumOf { it.pageRuns.size })
        assertTrue(WorkspaceIndexJson.catalogBytes(index) <= WorkspaceLimits.MAX_CATALOG_BYTES)
        val lexical = WorkspaceLexicalIndex.build(documents.flatMap { document -> document.chunks.map { it.text } })
        assertEquals(100_000, lexical.terms.size)
        val encoded = WorkspaceIndexJson.encode(index, lexical)
        assertTrue(encoded.bytes <= WorkspaceLimits.MAX_INDEX_BYTES)
        assertEquals(encoded.bytes, encoded.text.toByteArray(Charsets.UTF_8).size)
        val decoded = WorkspaceIndexJson.decode(encoded.text)
        assertEquals(index, decoded.index)
        val retriever = WorkspaceRetriever(decoded.index.documents, decoded.lexical)
        assertEquals(WorkspaceRetriever(index.documents).search(cyrillic(42_007), mode = WorkspaceQueryMode.CONSTRAINED),
            retriever.search(cyrillic(42_007), mode = WorkspaceQueryMode.CONSTRAINED))
        assertTrue(retriever.search(cyrillic(42_007), mode = WorkspaceQueryMode.CONSTRAINED).matchCount == 1)
        // One more distinct term than the cache ceiling: the cache is dropped, the text is not.
        val extra = index.copy(documents = documents.mapIndexed { position, document ->
            if (position != 0) document else document.copy(chunks = document.chunks.mapIndexed { ordinal, chunk ->
                if (ordinal == 0) chunk.copy(text = chunk.text.substringBeforeLast(' ') + " " + cyrillic(1_000_000) +
                    " " + cyrillic(1_000_001)) else chunk
            })
        })
        val overLexicon = WorkspaceLexicalIndex.build(extra.documents.flatMap { document -> document.chunks.map { it.text } })
        assertFalse(overLexicon.fitsCache)
        val omitted = WorkspaceIndexJson.encode(extra, overLexicon)
        assertFalse(omitted.lexicalPersisted)
        assertEquals(extra, WorkspaceIndexJson.decode(omitted.text).index)
    }

    @Test
    fun `damaged schema, catalog, or posting cache fails closed`() {
        val good = JSONObject(WorkspaceIndexJson.encode(sampleIndex(0), WorkspaceLexicalIndex.build(
            sampleIndex(0).documents.flatMap { document -> document.chunks.map { it.text } })).text)
        fun mutated(change: JSONObject.() -> Unit) = JSONObject(good.toString()).apply(change).toString()
        val damaged = listOf(
            mutated { put("version", 4) },
            mutated { put("extractionVersion", 2) },
            mutated { put("inventoryComplete", false) },
            mutated { getJSONArray("postings").put(0, JSONArray("[1,1,0,1]")) },
            mutated { getJSONArray("postings").put(0, JSONArray("[0,0]")) },
            mutated { getJSONArray("postings").put(0, JSONArray("[999,1]")) },
            mutated { getJSONArray("lexicon").put(0, "zzzz") },
            mutated { getJSONArray("tokenCounts").remove(0) },
            mutated { put("lexicon", JSONArray((0..WorkspaceLexicalIndex.MAX_TERMS).map { "t$it" })) },
            mutated { getJSONArray("documents").getJSONArray(0).put(12, JSONArray("[[1,2,1,0,0],[2,3,2,0,0]]")) },
            mutated { getJSONArray("documents").getJSONArray(0).put(9, 0) },
            mutated { getJSONArray("documents").getJSONArray(0).put(8, "not-a-digest") },
            mutated { getJSONArray("documents").put(JSONArray(getJSONArray("documents").getJSONArray(0).toString())) },
        )
        damaged.forEachIndexed { position, text ->
            assertTrue("case $position", runCatching { WorkspaceIndexJson.decode(text) }.isFailure)
        }
        val directory = temporary.newFolder()
        WorkspaceStore(directory).selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        File(directory, "workspace-index.json").writeText(damaged[3])
        val state = WorkspaceStore(directory).snapshot().state
        assertNull(state.index)
        assertEquals(WorkspaceProblem.INVALID_INDEX, state.problem)
        assertFalse(File(directory, "workspace-index.json").exists())
    }

    @Test
    fun `version 1 and 2 indexes migrate conservatively without reselection`() {
        val v1 = WorkspaceIndexJson.decode(legacy(1, pdfStatus = "INDEXED"))
        assertEquals(listOf("notes.txt"), v1.index.documents.map { it.entry.name })
        val text = v1.index.documents.single()
        assertTrue(text.coverageKnown)
        assertFalse(text.lookupSafe)

        val v2 = WorkspaceIndexJson.decode(legacy(2, pdfStatus = "PENDING", pagesRead = 2))
        assertEquals(2, v2.schemaVersion)
        val pdf = v2.index.documents.single { it.entry.name == "orion.pdf" }
        assertEquals(3, pdf.nextPage)
        assertEquals(2, pdf.pagesRead)
        assertNull(pdf.pageCount)
        assertNull(pdf.sourceDigest)
        assertFalse(pdf.lookupSafe)
        assertFalse(pdf.coverageKnown)
        assertEquals(listOf(WorkspacePageRun(2, 2, WorkspacePageState.LEGACY_TEXT)), pdf.pageRuns)
        assertEquals(WorkspaceTextState.UNATTEMPTED, pdf.pageState(1).text)
        assertTrue(pdf.chunks.single().visual)
        val image = v2.index.documents.single { it.entry.name == "board.jpg" }
        assertEquals(listOf(WorkspacePageRun(1, 1, WorkspacePageState.LEGACY_TEXT)), image.pageRuns)
        assertEquals(1, image.catalogPage(image.chunks.single()))
        assertTrue(runCatching { WorkspaceIndexJson.decode(legacy(2).replace("\"version\":2", "\"version\":7")) }.isFailure)
        assertTrue(runCatching { WorkspaceIndexJson.decode(legacy(2).replace("\"PENDING\"", "\"NOPE\"")) }.isFailure)
    }

    @Test
    fun `an unchanged upgrade keeps settings and epoch while a failed write keeps the old file`() {
        val directory = temporary.newFolder()
        var fail = false
        val operations = object : AssistantAtomicFileOperations {
            override fun atomicReplace(source: File, target: File) {
                if (fail) throw IOException("fixture failure")
                NioAssistantAtomicFileOperations.atomicReplace(source, target)
            }
            override fun replace(source: File, target: File) = error("Unexpected non-atomic fallback")
        }
        WorkspaceStore(directory).selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val legacyText = legacy(2, generation = 1)
        File(directory, "workspace-index.json").writeText(legacyText)
        File(directory, ".workspace-index.json.tmp").writeText("interrupted replacement")
        val store = WorkspaceStore(directory, operations)
        val loaded = store.snapshot()
        assertNotNull(loaded.state.index)
        assertEquals(WorkspaceStoreTest.TREE, loaded.state.settings.treeUri)
        fail = true
        assertTrue(runCatching { store.publish(loaded.state.index!!.copy(indexedAtMs = 2_000)) }.isFailure)
        assertEquals(legacyText, File(directory, "workspace-index.json").readText())
        assertEquals(loaded, store.snapshot())
        fail = false
        val upgraded = loaded.state.index!!.let { index -> index.copy(indexedAtMs = 3_000, documents = index.documents.map {
            it.copy(lookupSafe = true, sourceDigest = "b".repeat(64))
        }) }
        assertTrue(store.publish(upgraded))
        assertEquals(loaded.epoch, store.snapshot().epoch)
        assertEquals(3, JSONObject(File(directory, "workspace-index.json").readText()).getInt("version"))
    }

    @Test
    fun `semantic retraction ignores additive coverage but catches textless replacement`() {
        val entry = WorkspaceEntry("scan", "scan.pdf", modifiedAtMs = 10, sizeBytes = 20)
        val textless = WorkspaceDocument(entry, emptyList(), WorkspaceDocumentStatus.NO_TEXT, pageCount = 3,
            sourceDigest = "c".repeat(64), pageRuns = listOf(WorkspacePageRun(1, 3, WorkspacePageState(
                WorkspaceTextState.EMPTY, render = WorkspaceRenderState.AVAILABLE))), lookupSafe = true)
        val before = WorkspaceIndex(0, listOf(textless), 1_000)
        assertTrue(workspaceRetracts(before, before.copy(documents = listOf(textless.copy(
            entry = entry.copy(modifiedAtMs = 11), sourceDigest = "d".repeat(64))))))
        assertTrue(workspaceRetracts(before, before.copy(documents = emptyList())))
        assertTrue(workspaceRetracts(before, before.copy(documents = listOf(textless.copy(pageCount = 4)))))
        assertTrue(workspaceRetracts(before, before.copy(documents = listOf(textless.copy(pageRuns = listOf(
            WorkspacePageRun(1, 3, WorkspacePageState(WorkspaceTextState.EMPTY, render = WorkspaceRenderState.UNAVAILABLE))))))))
        val legacy = WorkspaceDocument(entry, listOf(WorkspaceChunk(0, "Annex text.", page = 2)),
            WorkspaceDocumentStatus.INDEXED, pageRuns = listOf(WorkspacePageRun(2, 2, WorkspacePageState.LEGACY_TEXT)))
        val old = WorkspaceIndex(0, listOf(legacy), 1_000)
        assertFalse(workspaceRetracts(old, old.copy(documents = listOf(legacy.copy(sourceDigest = "e".repeat(64),
            pageCount = 3, lookupSafe = true, pageRuns = WorkspacePageCatalog.with(legacy.pageRuns,
                mapOf(1 to WorkspacePageState.LEGACY_UNKNOWN, 3 to WorkspacePageState.LEGACY_UNKNOWN)))))))
        val nothing = WorkspaceDocument(entry, emptyList(), WorkspaceDocumentStatus.TOO_LARGE)
        assertFalse(workspaceRetracts(WorkspaceIndex(0, listOf(nothing), 1_000), WorkspaceIndex(0, emptyList(), 1_000)))
    }

    private fun sampleIndex(generation: Long) = WorkspaceIndex(generation, listOf(
        WorkspaceDocument(WorkspaceEntry("notes", "notes.txt", modifiedAtMs = 10, sizeBytes = 30),
            listOf(WorkspaceChunk(0, "Jean Martin badge code is 1188. Notice is two months."),
                WorkspaceChunk(1, "Jean et Marie Dupont share locker 77.", "Lockers", 1)),
            sourceDigest = "1".repeat(64), lookupSafe = true, coverageKnown = true),
        WorkspaceDocument(WorkspaceEntry("orion", "orion.pdf", modifiedAtMs = 10, sizeBytes = 30),
            listOf(WorkspaceChunk(0, "Projet Orion. Calendrier général.", page = 1),
                WorkspaceChunk(1, "Le code du casier Orion-47 est 3912.", page = 2),
                WorkspaceChunk(2, "Annexe. La réunion de lancement aura lieu à Lyon, salle Bellecour.", page = 3, visual = true)),
            pageCount = 4, sourceDigest = "2".repeat(64), lookupSafe = true,
            pageRuns = listOf(WorkspacePageRun(1, 3, WorkspacePageState(WorkspaceTextState.NATIVE, WorkspaceVisualState.ABSENT)),
                WorkspacePageRun(4, 4, WorkspacePageState(WorkspaceTextState.EMPTY, render = WorkspaceRenderState.AVAILABLE)))),
    ), 1_000)

    private fun legacy(version: Int, pdfStatus: String = "PENDING", pagesRead: Int = 2, generation: Long = 0): String =
        """{"version":$version,"generation":$generation,"indexedAtMs":1000,"skippedFiles":0,"documents":[""" +
            """{"id":"notes","name":"notes.txt","path":"notes.txt","modifiedAtMs":10,"sizeBytes":5,"type":"TEXT",""" +
            """"status":"INDEXED","chunks":[{"ordinal":0,"text":"Notice","heading":"","paragraph":0}]},""" +
            """{"id":"orion","name":"orion.pdf","path":"orion.pdf","modifiedAtMs":10,"sizeBytes":5,"type":"PDF",""" +
            """"status":"$pdfStatus","pagesRead":$pagesRead,"chunks":[{"ordinal":0,"text":"Annex","heading":"",""" +
            """"paragraph":0,"page":2,"visual":true}]},""" +
            """{"id":"board","name":"board.jpg","path":"board.jpg","modifiedAtMs":10,"sizeBytes":5,"type":"IMAGE",""" +
            """"status":"INDEXED","pagesRead":0,"chunks":[{"ordinal":0,"text":"Sprint","heading":"","paragraph":0,""" +
            """"page":0,"visual":true}]}]}"""

    companion object {
        private val QUERIES = listOf("notice", "Jean et Marie Dupont", "code de Vega; casier Orion",
            "dans orion pdf ou a lieu la reunion de lancement", "Orion", "badge 1188 ? locker")

        /** A distinct Cyrillic word for each number, so every chunk carries its own terms. */
        fun cyrillic(number: Int): String {
            val letters = "абвгдежзиклмнопрстуфхцчшщ"
            var value = number
            val word = StringBuilder("ю")
            repeat(7) {
                word.append(letters[value % letters.length])
                value /= letters.length
            }
            return word.toString()
        }
    }
}
