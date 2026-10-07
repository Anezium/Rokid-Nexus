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
        val access = FakeWorkspaceSearchAccess()
        var builds = 0
        fun build(): String { builds++; return prompt(EXCERPTS, listOf(SEARCH_WORKSPACE_TOOL_NAME)) }
        val request = ChatRequest(userText = "Notice?", systemPrompt = build(), workspaceVersion = access.searchVersion())
        val dispatch = request.forCurrentWorkspace(access) { error("Do not rebuild a valid prompt") }
        assertEquals(request.systemPrompt, dispatch.systemPrompt)
        assertEquals(1, builds)
        dispatch.beforeSend!!.invoke()
        access.generation++
        assertTrue(runCatching { dispatch.beforeSend!!.invoke() }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, builds)
    }

    @Test
    fun `a changed turn rebuilds once without excerpts or the fallback while retaining Memory verbatim`() {
        for (change in listOf("folder", "off", "revision", "missing-controller")) {
            val access = FakeWorkspaceSearchAccess()
            var builds = 0
            fun build(workspace: String, names: List<String>): String { builds++; return prompt(workspace, names) }
            val request = ChatRequest(userText = "Notice?", workspaceVersion = access.searchVersion(),
                systemPrompt = build(EXCERPTS, listOf(SEARCH_WORKSPACE_TOOL_NAME)))
            when (change) {
                "folder" -> access.generation++
                "off" -> access.available = false
                "revision" -> access.revision++
            }
            val dispatch = request.forCurrentWorkspace(access.takeUnless { change == "missing-controller" }) {
                build("", emptyList())
            }
            assertEquals(change, 2, builds)
            assertEquals(prompt("", emptyList()), dispatch.systemPrompt)
            assertFalse(dispatch.systemPrompt!!.contains("```text\nWorkspace excerpts"))
            assertFalse(dispatch.systemPrompt!!.contains(EXCERPTS))
            assertFalse(dispatch.systemPrompt!!.contains(SEARCH_WORKSPACE_TOOL_NAME))
            assertTrue(dispatch.systemPrompt!!.contains("What the user has told you about themselves:\n$MEMORY"))
            assertNull(dispatch.workspaceVersion)
            assertNull(dispatch.beforeSend)
            assertTrue(AssistantToolRegistry(listOf(SearchWorkspaceTool { access }))
                .availableDefinitions(SearchWorkspaceToolTest.FEATURES, dispatch.workspaceVersion).isEmpty())
        }
    }

    @Test
    fun `a question with no Workspace contribution keeps its single prompt`() {
        val request = ChatRequest(userText = "Hello", systemPrompt = prompt("", emptyList()))
        assertSame(request, request.forCurrentWorkspace(null) { error("No Workspace prompt to rebuild") })
    }

    companion object {
        private const val MEMORY = "Saved account context.\n\nManual notes remain unchanged."
        private val EXCERPTS = WorkspaceProviderTest.EXCERPTS
        private fun prompt(workspace: String, names: List<String>) = NexusAgentPolicy.buildSystemPrompt(
            memory = MEMORY, workspace = workspace, workspaceEnabled = true, availableToolNames = names,
        )
    }
}
