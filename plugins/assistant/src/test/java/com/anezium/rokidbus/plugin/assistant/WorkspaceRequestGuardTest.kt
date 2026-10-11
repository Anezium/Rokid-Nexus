package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRequestGuardTest {
    @Test
    fun `a valid turn reuses its first prompt and guards later sends without rebuilding`() {
        val turn = FakeWorkspaceTurn()
        var builds = 0
        fun build(): String { builds++; return prompt(EXCERPTS, listOf(SEARCH_WORKSPACE_TOOL_NAME)) }
        val request = ChatRequest(userText = "Notice?", systemPrompt = build(), workspaceVersion = turn.version,
            workspaceTurn = turn)
        val dispatch = request.forCurrentWorkspace(true) { error("Do not rebuild a valid prompt") }
        assertEquals(request.systemPrompt, dispatch.systemPrompt)
        assertEquals(1, builds)
        dispatch.beforeSend!!.invoke()
        assertTrue(turn.evidence)
        turn.generation++
        assertTrue(runCatching { dispatch.beforeSend!!.invoke() }.exceptionOrNull() is IllegalStateException)
        assertFalse(turn.finalEffectsAllowed())
        assertEquals(1, builds)
    }

    @Test
    fun `a changed turn rebuilds once without excerpts or Workspace tools while retaining Memory verbatim`() {
        for (change in listOf("folder", "off", "revision", "withdrawn", "missing-turn")) {
            val turn = FakeWorkspaceTurn()
            var builds = 0
            fun build(workspace: String, names: List<String>): String { builds++; return prompt(workspace, names) }
            val request = ChatRequest(userText = "Notice?", workspaceVersion = turn.version,
                workspaceTurn = turn.takeUnless { change == "missing-turn" },
                systemPrompt = build(EXCERPTS, listOf(SEARCH_WORKSPACE_TOOL_NAME, VIEW_WORKSPACE_PAGE_TOOL_NAME)))
            when (change) {
                "folder" -> turn.generation++
                "off" -> turn.available = false
                "revision" -> turn.revision++
                "withdrawn" -> turn.withdraw("source_changed")
            }
            val dispatch = request.forCurrentWorkspace(true) { build("", emptyList()) }
            assertEquals(change, 2, builds)
            assertEquals(prompt("", emptyList()), dispatch.systemPrompt)
            assertFalse(dispatch.systemPrompt!!.contains("```text\nWorkspace excerpts"))
            assertFalse(dispatch.systemPrompt!!.contains(EXCERPTS))
            assertFalse(dispatch.systemPrompt!!.contains(SEARCH_WORKSPACE_TOOL_NAME))
            assertFalse(dispatch.systemPrompt!!.contains(VIEW_WORKSPACE_PAGE_TOOL_NAME))
            assertTrue(dispatch.systemPrompt!!.contains("What the user has told you about themselves:\n$MEMORY"))
            assertNull(dispatch.workspaceVersion)
            assertNull(dispatch.workspaceTurn)
            assertNull(dispatch.beforeSend)
            // Nothing was supplied, so the unrelated answer keeps its final effects.
            assertTrue(turn.finalEffectsAllowed())
            assertTrue(AssistantToolRegistry(listOf(SearchWorkspaceTool(), ViewWorkspacePageTool()))
                .availableDefinitions(SearchWorkspaceToolTest.FEATURES, dispatch.workspaceVersion, dispatch.workspaceTurn)
                .isEmpty())
        }
    }

    @Test
    fun `withdrawal before any Workspace content is supplied lets the answer continue`() {
        val turn = FakeWorkspaceTurn()
        val request = ChatRequest(userText = "What time is it?", systemPrompt = prompt("", listOf(SEARCH_WORKSPACE_TOOL_NAME)),
            workspaceVersion = turn.version, workspaceTurn = turn).forCurrentWorkspace(false) { error("unused") }
        request.beforeSend!!.invoke()
        turn.withdraw("source_changed")
        request.beforeSend!!.invoke()
        assertFalse(turn.evidence)
        assertTrue(turn.finalEffectsAllowed())
    }

    @Test
    fun `a question with no Workspace contribution keeps its single prompt`() {
        val request = ChatRequest(userText = "Hello", systemPrompt = prompt("", emptyList()))
        assertSame(request, request.forCurrentWorkspace(false) { error("No Workspace prompt to rebuild") })
    }

    companion object {
        private const val MEMORY = "Saved account context.\n\nManual notes remain unchanged."
        private val EXCERPTS = WorkspaceProviderTest.EXCERPTS
        private fun prompt(workspace: String, names: List<String>) = NexusAgentPolicy.buildSystemPrompt(
            memory = MEMORY, workspace = workspace, workspaceEnabled = true, availableToolNames = names,
        )
    }
}
