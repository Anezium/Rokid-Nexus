package com.anezium.rokidbus.plugin.t3code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class T3CommandFactoryTest {
    @Test
    fun `thread turn start contains client ids bootstrap nulls and truncated single line title`() {
        val ids = ArrayDeque(listOf("command-id", "thread-id", "message-id"))
        val prompt = "Build the new\nT3 Code glasses integration and make this title deliberately longer than sixty characters"
        val selection = T3ModelSelection(
            instanceId = "codex",
            model = "gpt-5.6-codex",
            options = listOf(T3ModelOptionValue("reasoningEffort", "high")),
        )

        val result = T3CommandFactory.threadTurnStart(
            projectId = "project-id",
            modelSelection = selection,
            prompt = prompt,
            now = Instant.parse("2026-07-29T12:34:56Z"),
            nextId = { ids.removeFirst() },
        )
        val command = result.payload
        val createThread = command.getJSONObject("bootstrap").getJSONObject("createThread")

        assertEquals("thread.turn.start", command.getString("type"))
        assertEquals("command-id", command.getString("commandId"))
        assertEquals("thread-id", result.threadId)
        assertEquals("thread-id", command.getString("threadId"))
        assertEquals("message-id", command.getJSONObject("message").getString("messageId"))
        assertEquals("user", command.getJSONObject("message").getString("role"))
        assertEquals(0, command.getJSONObject("message").getJSONArray("attachments").length())
        assertEquals("full-access", command.getString("runtimeMode"))
        assertEquals("default", command.getString("interactionMode"))
        assertEquals("project-id", createThread.getString("projectId"))
        assertTrue(createThread.isNull("branch"))
        assertTrue(createThread.isNull("worktreePath"))
        assertEquals(60, createThread.getString("title").length)
        assertFalse(createThread.getString("title").contains('\n'))
        assertEquals("reasoningEffort", command.getJSONObject("modelSelection").getJSONArray("options").getJSONObject(0).getString("id"))
        assertEquals("high", createThread.getJSONObject("modelSelection").getJSONArray("options").getJSONObject(0).getString("value"))
        assertEquals("2026-07-29T12:34:56Z", createThread.getString("createdAt"))
        assertEquals(command.getString("createdAt"), createThread.getString("createdAt"))
    }
}
