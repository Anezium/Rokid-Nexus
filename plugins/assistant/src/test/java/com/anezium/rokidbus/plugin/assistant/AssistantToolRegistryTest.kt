package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.shared.skills.SkillLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantToolRegistryTest {
    @Test
    fun `each nonvisual local tool label is shown during execution then restored`() = runTest {
        val expectedLabels = linkedMapOf(
            TAKE_NOTE_TOOL_NAME to "Saving the note…",
            LIST_NOTES_TOOL_NAME to "Reading your notes…",
            SEARCH_NOTES_TOOL_NAME to "Searching your notes…",
            DELETE_NOTE_TOOL_NAME to "Deleting the note…",
            SET_REMINDER_TOOL_NAME to "Setting the reminder…",
            LIST_REMINDERS_TOOL_NAME to "Checking your reminders…",
            CANCEL_REMINDER_TOOL_NAME to "Cancelling the reminder…",
            SET_TIMER_TOOL_NAME to "Starting the timer…",
        )

        expectedLabels.forEach { (name, label) ->
            val progress = mutableListOf<String>()
            val tool = TestAssistantTool(
                name = name,
                progressLabel = label,
                executor = { _, _ ->
                    assertEquals(listOf(label), progress)
                    AssistantToolResult.Json("{}")
                },
            )
            val phase = AssistantToolRegistry(
                definitions = listOf(tool),
                progressReporter = progress::add,
            ).newExecutionPhase(TOOLS_WITHOUT_VISION)

            phase.execute(AssistantToolCall("call-$name", name, "{}"))

            assertEquals(name, listOf(label, "Thinking…"), progress)
        }
    }

    @Test
    fun `validation and refusal paths restore thinking without showing a work label`() = runTest {
        val progress = mutableListOf<String>()
        val tool = TestAssistantTool(
            name = "save_note",
            sideEffecting = true,
            progressLabel = "Saving the note…",
        )
        val phase = AssistantToolRegistry(
            definitions = listOf(tool),
            progressReporter = progress::add,
        ).newExecutionPhase(TOOLS_WITHOUT_VISION)

        phase.execute(AssistantToolCall("invalid", tool.name, "not-json"))
        assertEquals(listOf("Thinking…"), progress)

        progress.clear()
        phase.execute(AssistantToolCall("first", tool.name, "{}"))
        assertEquals(listOf("Saving the note…", "Thinking…"), progress)

        progress.clear()
        phase.execute(AssistantToolCall("refused", tool.name, "{}"))
        assertEquals(listOf("Thinking…"), progress)

        progress.clear()
        phase.execute(AssistantToolCall("unknown", "unknown_tool", "{}"))
        assertEquals(listOf("Thinking…"), progress)
    }

    @Test
    fun `successful visual handoff retires progress while failures restore thinking`() = runTest {
        val progress = mutableListOf<String>()
        var result: AssistantToolResult = AssistantToolResult.Json("{}")
        val tool = TestAssistantTool(
            name = "render_card",
            progressLabel = "Drawing the card…",
            retiresProgressOnSuccess = true,
            executor = { _, _ -> result },
        )
        val phase = AssistantToolRegistry(
            definitions = listOf(tool),
            progressReporter = progress::add,
        ).newExecutionPhase(TOOLS_WITHOUT_VISION)

        phase.execute(AssistantToolCall("shown", tool.name, "{}"))
        assertEquals(listOf("Drawing the card…"), progress)

        progress.clear()
        result = AssistantToolResult.Error("render_card_failed")
        phase.execute(AssistantToolCall("failed", tool.name, "{}"))
        assertEquals(listOf("Drawing the card…", "Thinking…"), progress)
    }

    @Test
    fun `throw and cancellation paths restore thinking`() = runTest {
        suspend fun executeThrowingTool(throwable: Throwable): Pair<Throwable?, List<String>> {
            val progress = mutableListOf<String>()
            val tool = TestAssistantTool(
                name = "lookup_note",
                progressLabel = "Reading your notes…",
                executor = { _, _ -> throw throwable },
            )
            val phase = AssistantToolRegistry(
                definitions = listOf(tool),
                progressReporter = progress::add,
            ).newExecutionPhase(TOOLS_WITHOUT_VISION)
            val thrown = runCatching {
                phase.execute(AssistantToolCall("call", tool.name, "{}"))
            }.exceptionOrNull()
            return thrown to progress
        }

        val (mappedFailure, failureProgress) = executeThrowingTool(IllegalStateException("boom"))
        assertEquals(null, mappedFailure)
        assertEquals(listOf("Reading your notes…", "Thinking…"), failureProgress)

        val cancellation = CancellationException("cancelled")
        val (thrownCancellation, cancellationProgress) = executeThrowingTool(cancellation)
        assertTrue(thrownCancellation === cancellation)
        assertEquals(listOf("Reading your notes…", "Thinking…"), cancellationProgress)
    }

    @Test
    fun `multiple registered tools are available without provider changes`() {
        val registry = AssistantToolRegistry(
            definitions = listOf(
                TestAssistantTool("first_tool"),
                TestAssistantTool(
                    name = "second_tool",
                    available = { context ->
                        context.session.active && "notes" in context.session.grantedCapabilities
                    },
                ),
            ),
            sessionContext = {
                AssistantToolSessionContext(
                    active = true,
                    grantedCapabilities = setOf("notes"),
                )
            },
        )

        val available = registry.availableDefinitions(TOOLS_WITHOUT_VISION)

        assertEquals(listOf("first_tool", "second_tool"), available.map { it.name })
        assertTrue(available.all { it.parametersSchema.toJsonObject().has("type") })
    }

    @Test
    fun `provider without tool support receives no definitions`() {
        val registry = AssistantToolRegistry(listOf(TestAssistantTool("fake_tool")))

        val available = registry.availableDefinitions(
            AssistantProviderFeatures(supportsTools = false, supportsVision = true),
        )

        assertTrue(available.isEmpty())
    }

    @Test
    fun `phase executes at most the turn's call budget`() = runTest {
        var executions = 0
        val progress = mutableListOf<String>()
        val tool = TestAssistantTool(
            name = "lookup_note",
            progressLabel = "Reading your notes…",
            executor = { call, _ ->
                executions += 1
                AssistantToolResult.Json("result:${call.callId}")
            },
        )
        val phase = AssistantToolRegistry(
            definitions = listOf(tool),
            progressReporter = progress::add,
        ).newExecutionPhase(TOOLS_WITHOUT_VISION)

        val budget = SkillLimits.ASSISTANT_MAX_EXECUTED_CALLS
        val results = (1..budget + 1).map { index ->
            phase.execute(AssistantToolCall("call-$index", tool.name, "{}"))
        }

        assertEquals(budget, executions)
        assertEquals(
            AssistantToolResult.Error(TOOL_ERROR_ALREADY_USED),
            results.last(),
        )
        assertTrue(phase.budgetExhausted)
        assertEquals("Thinking…", progress.last())
        assertEquals(budget, progress.count { it == "Reading your notes…" })
        assertEquals(budget + 1, progress.count { it == "Thinking…" })
    }

    @Test
    fun `side effecting tool executes at most once per phase`() = runTest {
        var executions = 0
        val tool = TestAssistantTool(
            name = "save_note",
            sideEffecting = true,
            executor = { _, _ ->
                executions += 1
                AssistantToolResult.Json("saved")
            },
        )
        val phase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(TOOLS_WITHOUT_VISION)

        val first = phase.execute(AssistantToolCall("call-1", tool.name, "{}"))
        val second = phase.execute(AssistantToolCall("call-2", tool.name, "{}"))

        assertEquals(AssistantToolResult.Json("saved"), first)
        assertEquals(AssistantToolResult.Error(TOOL_ERROR_ALREADY_USED), second)
        assertEquals(1, executions)
    }

    @Test
    fun `duplicate call id returns memoized result without reexecution`() = runTest {
        var executions = 0
        val tool = TestAssistantTool(
            name = "lookup_note",
            executor = { call, _ ->
                executions += 1
                AssistantToolResult.Json("first:${call.argumentsJson}")
            },
        )
        val phase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(TOOLS_WITHOUT_VISION)

        val first = phase.execute(AssistantToolCall("same-id", tool.name, "{}"))
        val duplicate = phase.execute(
            AssistantToolCall("same-id", tool.name, "not-the-original-call"),
        )

        assertEquals(first, duplicate)
        assertEquals(1, executions)
    }

    @Test
    fun `invalid arguments return structured error without execution`() = runTest {
        var executions = 0
        val tool = TestAssistantTool(
            name = "lookup_note",
            executor = { _, _ ->
                executions += 1
                AssistantToolResult.Json("unexpected")
            },
        )
        val phase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(TOOLS_WITHOUT_VISION)

        val malformed = phase.execute(AssistantToolCall("bad-json", tool.name, "not-json"))
        val unexpected = phase.execute(
            AssistantToolCall("bad-property", tool.name, """{"extra":true}"""),
        )

        assertEquals(AssistantToolResult.Error(TOOL_ERROR_INVALID_CALL), malformed)
        assertEquals(AssistantToolResult.Error(TOOL_ERROR_INVALID_CALL), unexpected)
        assertEquals(0, executions)
    }

    @Test
    fun `unavailable tool called anyway returns invalid call`() = runTest {
        var executions = 0
        val unavailable = TestAssistantTool(
            name = "lookup_note",
            available = { false },
            executor = { _, _ ->
                executions += 1
                AssistantToolResult.Json("unexpected")
            },
        )
        val phase = AssistantToolRegistry(listOf(unavailable))
            .newExecutionPhase(TOOLS_WITHOUT_VISION)

        val result = phase.execute(AssistantToolCall("call-1", unavailable.name, "{}"))

        assertEquals(AssistantToolResult.Error(TOOL_ERROR_INVALID_CALL), result)
        assertEquals(0, executions)
    }

    @Test
    fun `executor exception maps to a tool scoped failure`() = runTest {
        val tool = TestAssistantTool(
            name = "lookup_note",
            executor = { _, _ -> error("database unavailable") },
        )
        val phase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(TOOLS_WITHOUT_VISION)

        val result = phase.execute(AssistantToolCall("call-1", tool.name, "{}"))

        assertEquals(AssistantToolResult.Error("lookup_note_failed"), result)
    }

    @Test
    fun `take photo exception keeps capture failed wire code`() = runTest {
        val phase = testToolRegistry(
            executor = { error("camera failed") },
        ).newExecutionPhase(
            AssistantProviderFeatures(supportsTools = true, supportsVision = true),
        )

        val result = phase.execute(
            AssistantToolCall("call-photo", TAKE_PHOTO_TOOL_NAME, "{}"),
        )

        assertEquals(AssistantToolResult.Error(TOOL_ERROR_CAPTURE_FAILED), result)
    }

    private companion object {
        val TOOLS_WITHOUT_VISION = AssistantProviderFeatures(
            supportsTools = true,
            supportsVision = false,
        )
    }
}
