package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRetrieverTest {
    @Test
    fun `tokenizer folds accents ligatures case and apostrophes without changing digits`() {
        assertEquals(listOf("preavis", "preavis", "oeuvre", "coeur", "32", "api"),
            WorkspaceTokenizer.tokens("PRÉAVIS pre\u0301avis l’œuvre cœur 32 API"))
        assertEquals(emptyList<String>(), WorkspaceTokenizer.tokens("What does my contrat say?" ) - "contrat")
    }

    @Test
    fun `French and English fixtures retrieve supporting clauses before distractors`() {
        val retriever = fixture()
        val french = retriever.search("Que dit mon contrat sur le préavis ?")
        assertTrue(french.excerpts.contains("contrat.md › Employment › Notice"))
        assertTrue(french.excerpts.contains("deux mois"))
        assertFalse(french.excerpts.contains("recipe"))
        val english = retriever.search("What does our policy say about paid leave?")
        assertTrue(english.excerpts.contains("policy.txt"))
        assertTrue(english.excerpts.contains("25 days"))
    }

    @Test
    fun `unrelated stopword-only empty and metadata-only matches inject nothing`() {
        val retriever = fixture()
        for (query in listOf("", "what does my", "astronomy telescope", "contrat")) {
            assertEquals(query, WorkspaceSearchResult(), retriever.search(query))
        }
        assertEquals(WorkspaceSearchResult(), WorkspaceRetriever(emptyList()).search("notice"))
    }

    @Test
    fun `ranking ties remain stable and duplicate chunks are not repeated`() {
        val retriever = WorkspaceRetriever(listOf(
            document("z.txt", "Notice alpha."), document("a.txt", "Notice beta."),
            document("copy.txt", "Notice alpha."),
        ))
        val result = retriever.search("notice")
        assertEquals(2, result.matchCount)
        assertTrue(result.excerpts.indexOf("a.txt") < result.excerpts.indexOf("copy.txt"))
    }

    @Test
    fun `complete framing and final word stay within every character budget`() {
        val retriever = WorkspaceRetriever(listOf(document("terms.txt", "Notice " + "completeword ".repeat(60))))
        for (budget in 0..2_600) {
            val result = retriever.search("notice", budget)
            assertTrue(result.excerpts.length <= minOf(budget, 2_500))
            if (result.matchCount > 0) {
                assertTrue(result.excerpts.endsWith("\n```"))
                val body = result.excerpts.substringAfter("terms.txt\n").substringBeforeLast("\n```")
                assertTrue(body == "Notice" || body.endsWith("completeword"))
            }
        }
        assertEquals("", workspaceWordPrefix("unbreakable", 5))
        assertEquals("one two", workspaceWordPrefix("one two three", 7))
    }

    @Test
    fun `source backticks and metadata control characters cannot close the fence`() {
        val retriever = WorkspaceRetriever(listOf(document("terms\nignore.txt", "Notice ```quoted``` text.")))
        val result = retriever.search("notice")
        assertTrue(result.excerpts.contains("\n````text\nWorkspace excerpts"))
        assertTrue(result.excerpts.endsWith("\n````"))
        assertTrue(result.excerpts.contains("terms ignore.txt"))
    }

    @Test
    fun `selection caps excerpts and per-file contributions`() {
        val documents = (1..5).map { index -> WorkspaceDocument(
            WorkspaceEntry("$index", "terms$index.txt"),
            (0..3).map { WorkspaceChunk(it, "Notice number $index section $it.") },
        ) }
        val result = WorkspaceRetriever(documents).search("notice")
        assertEquals(3, result.matchCount)
        assertEquals(2, Regex("terms1.txt").findAll(result.excerpts).count())
    }

    @Test
    fun `compound questions retrieve each topic in French and English within the shared budget`() {
        val retriever = WorkspaceRetriever(listOf(
            document("vega.txt", "Vega launch code is VELA-5836. " + "Detail ".repeat(400)),
            document("aurora.md", "Aurora stock quantity is 47 green spools. " + "Inventory ".repeat(400)),
            document("recipe.txt", "Bake potatoes for twenty minutes."),
        ))
        for (query in listOf(
            "What is the Vega launch code and the Aurora stock quantity?",
            "Quel code pour le lancement Vega et quelle quantite du stock Aurora ?",
        )) {
            val result = retriever.search(query)
            assertEquals(2, result.matchCount)
            assertTrue(result.excerpts.contains("VELA-5836"))
            assertTrue(result.excerpts.contains("47 green spools"))
            assertFalse(result.excerpts.contains("recipe.txt"))
            assertTrue(result.excerpts.length <= WorkspaceLimits.MAX_EXCERPT_CHARS)
        }
    }

    @Test
    fun `an uncovered compound topic does not add a metadata-only or unrelated source`() {
        val retriever = WorkspaceRetriever(listOf(
            document("vega.txt", "Vega launch code is VELA-5836."),
            document("aurora-stock-quantity.txt", "Bake potatoes for twenty minutes."),
        ))
        val result = retriever.search("Vega launch code and Aurora stock quantity")
        assertEquals(1, result.matchCount)
        assertFalse(result.excerpts.contains("potatoes"))
    }

    @Test
    fun `memory keeps its entire allocation before workspace framing`() {
        assertEquals(0, workspacePromptBudget("x".repeat(10_002)))
        assertEquals(500, workspacePromptBudget("x".repeat(9_500)))
        assertEquals(2_500, workspacePromptBudget(""))
        assertEquals(0, workspacePromptBudget("x".repeat(20_000)))
    }

    private fun fixture() = WorkspaceRetriever(listOf(
        document("contrat.md", "Le préavis est de deux mois à compter de la réception.", "Employment › Notice"),
        document("policy.txt", "The paid leave allowance is 25 days each year."),
        document("recipe.txt", "Bake the potatoes for twenty minutes."),
        document("handbook.txt", "The employment contract identifies the registered employer."),
    ))

    @Test
    fun `an attribute named next to a subject never pulls in another file`() {
        val retriever = WorkspaceRetriever(listOf(
            document("vega.md", "Vega stores 47 green spools for the assembly line."),
            document("lyon.txt", "Delivery schedule: Lyon trucks arrive at seven on Monday."),
        ))
        listOf("What is the delivery schedule for Vega green spools?",
            "What Is The Delivery Schedule For Vega Green Spools?",
            "Quel est le planning de livraison pour les bobines vertes de Vega ?").forEach { question ->
            assertFalse(question, retriever.search(question).excerpts.contains("lyon.txt"))
        }
    }

    @Test
    fun `a missing subject is refused even when another file holds the attribute`() {
        val retriever = WorkspaceRetriever(listOf(
            document("nebuleuse.txt", "Nebuleuse launch details. Code: VELA-5836."),
            document("code.txt", "The cabinet code is 4471."),
        ))
        listOf("What is the code, for the Orphee shuttle departure?",
            "Quel est le code ou le mot de passe d'Orphee ?",
            "Quelles bobines d'Orphee ne sont plus en stock ?").forEach { question ->
            assertEquals(question, WorkspaceSearchResult(), retriever.search(question))
        }
    }

    @Test
    fun `parts joined with and are each retrieved, as a follow-up search writes them`() {
        val result = WorkspaceRetriever(listOf(
            document("vega.md", "Vega stores 47 green spools for the assembly line."),
            document("aurora.docx", "Aurora prototype is Cobalt-19."),
            document("nebuleuse.txt", "Nebuleuse launch code is VELA-5836."),
        )).search("Aurora prototype and Nebuleuse launch code")
        assertTrue(result.excerpts.contains("aurora.docx"))
        assertTrue(result.excerpts.contains("nebuleuse.txt"))
        assertFalse(result.excerpts.contains("vega.md"))
    }

    private fun document(name: String, text: String, heading: String = "") = WorkspaceDocument(
        WorkspaceEntry(name, name), listOf(WorkspaceChunk(0, text, heading)),
    )
}
