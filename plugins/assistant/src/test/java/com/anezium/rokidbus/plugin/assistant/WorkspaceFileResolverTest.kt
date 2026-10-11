package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFileResolverTest {
    private val orion = document("orion", "orion.pdf")
    private val annex = document("annex", "orion-annex.pdf")
    private val planA = document("plan-a", "plan.pdf", "a/plan.pdf")
    private val planB = document("plan-b", "plan.pdf", "b/plan.pdf")
    private val resume = document("resume", "resume.pdf")
    private val accented = document("resume-accent", "Résumé.pdf")
    private val code = document("code", "code.txt")
    private val catalog = listOf(orion, annex, planA, planB, code)

    @Test
    fun `written and spoken file names resolve to the one document they name`() {
        for (question in listOf("dans orion pdf ou a lieu la reunion de lancement", "What does orion.pdf say about the venue?",
            "Dans ORION.PDF, quel est le lieu ?", "dans le fichier orion, où a lieu la réunion ?", "in the orion document")) {
            val resolution = WorkspaceFileResolver.resolve(question, catalog)
            assertEquals(question, WorkspaceResolutionState.RESOLVED, resolution.state)
            assertEquals(question, "orion", resolution.document!!.entry.documentId)
        }
        assertEquals(setOf("orion", "pdf"), WorkspaceFileResolver.resolve("dans orion pdf ou a lieu", catalog).nameTerms)
        val path = WorkspaceFileResolver.resolve("open a/plan.pdf please", catalog)
        assertEquals(WorkspaceResolutionState.RESOLVED, path.state)
        assertEquals("plan-a", path.document!!.entry.documentId)
    }

    @Test
    fun `a whole bare name resolves but a fragment of a longer name does not`() {
        val bare = WorkspaceFileResolver.resolve("Where is the Orion launch meeting?", catalog)
        assertEquals(WorkspaceResolutionState.RESOLVED, bare.state)
        assertEquals("orion", bare.document!!.entry.documentId)
        // "annex" alone is part of orion-annex.pdf, not its name: the reference stays unresolved.
        assertEquals(WorkspaceResolutionState.MISSING, WorkspaceFileResolver.resolve("dans annex pdf", listOf(annex)).state)
        assertEquals(WorkspaceResolutionState.RESOLVED, WorkspaceFileResolver.resolve("dans orion annex pdf", catalog).state)
        assertEquals("annex", WorkspaceFileResolver.resolve("dans orion annex pdf", catalog).document!!.entry.documentId)
        assertEquals(WorkspaceResolutionState.NONE, WorkspaceFileResolver.resolve("quel pdf parle de Lyon ?", catalog).state)
        assertEquals(WorkspaceResolutionState.AMBIGUOUS,
            WorkspaceFileResolver.resolve("compare orion.pdf with code.txt", catalog).state)
    }

    @Test
    fun `duplicate and accent-folded names are ambiguous, never the first match`() {
        val duplicate = WorkspaceFileResolver.resolve("what is in plan pdf", catalog)
        assertEquals(WorkspaceResolutionState.AMBIGUOUS, duplicate.state)
        assertNull(duplicate.document)
        assertEquals(setOf("plan-a", "plan-b"), duplicate.choices.map { it.entry.documentId }.toSet())
        val folded = WorkspaceFileResolver.resolve("lis resume pdf", listOf(resume, accented))
        assertEquals(WorkspaceResolutionState.AMBIGUOUS, folded.state)
        val many = (1..8).map { document("copy$it", "notes.pdf", "dir$it/notes.pdf") }
        val capped = WorkspaceFileResolver.resolve("notes pdf", many)
        assertEquals(WorkspaceLimits.MAX_CHOICES, capped.choices.size)
        assertEquals(3, capped.moreChoices)
    }

    @Test
    fun `a named file that is absent is missing, and an unverified label is unavailable`() {
        assertEquals(WorkspaceResolutionState.MISSING,
            WorkspaceFileResolver.resolve("dans orphee pdf quel est le code", catalog).state)
        assertEquals(WorkspaceResolutionState.MISSING,
            WorkspaceFileResolver.resolve("What does orphee.pdf say?", catalog).state)
        for (question in listOf("dans le pdf orphee quel est le code", "dans le fichier orphee quel est le code",
            "in the file orphee what is the code", "orphee.pdf")) {
            assertEquals(question, WorkspaceResolutionState.MISSING, WorkspaceFileResolver.resolve(question, catalog).state)
            assertEquals(question, WorkspaceResolutionState.MISSING, WorkspaceFileResolver.resolve(question, emptyList()).state)
        }
        val shortened = document("long", "x".repeat(40) + ".pdf").copy(lookupSafe = false)
        val unavailable = WorkspaceFileResolver.resolve("what does ${"x".repeat(40)} pdf say", listOf(shortened))
        assertEquals(WorkspaceResolutionState.UNAVAILABLE, unavailable.state)
    }

    @Test
    fun `model references resolve only among permitted documents`() {
        val permitted = mapOf("orion.pdf" to orion, "w1" to code)
        assertEquals(orion, WorkspaceFileResolver.reference("orion.pdf", permitted))
        assertEquals(orion, WorkspaceFileResolver.reference("ORION.PDF", permitted))
        assertEquals(orion, WorkspaceFileResolver.reference("orion.pdf › page 3${WorkspaceRetriever.VISUAL_MARK}", permitted))
        assertEquals(orion, WorkspaceFileResolver.reference("orion pdf", permitted))
        assertEquals(code, WorkspaceFileResolver.reference("w1", permitted))
        assertNull(WorkspaceFileResolver.reference("a/plan.pdf", permitted))
        assertNull(WorkspaceFileResolver.reference("orion-annex.pdf", permitted))
        assertTrue(WorkspaceFileResolver.words("Œuvre Évaluée_2") == listOf("oeuvre", "evaluee", "2"))
    }

    private fun document(id: String, name: String, path: String = name) =
        WorkspaceDocument(WorkspaceEntry(id, name, path, modifiedAtMs = 10, sizeBytes = 10), emptyList(), lookupSafe = true)
}
