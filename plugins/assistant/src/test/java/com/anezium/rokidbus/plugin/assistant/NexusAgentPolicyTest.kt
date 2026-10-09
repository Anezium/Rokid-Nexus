package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class NexusAgentPolicyTest {
    @Test
    fun `workspace search is asked for the parts the excerpts miss only when the tool exists`() {
        val withSearch = NexusAgentPolicy.buildSystemPrompt(workspace = "excerpts", workspaceEnabled = true,
            availableToolNames = listOf(SEARCH_WORKSPACE_TOOL_NAME))
        assertTrue(withSearch.contains("call search_workspace once before answering"))
        assertTrue(withSearch.contains("Never answer one part from an excerpt about something else."))
        val withoutSearch = NexusAgentPolicy.buildSystemPrompt(workspace = "excerpts", workspaceEnabled = true,
            availableToolNames = emptyList())
        assertFalse(withoutSearch.contains("search_workspace"))
    }

    @Test
    fun `blank memory leaves the existing prompt byte identical`() {
        val existingPrompt = NexusAgentPolicy.buildSystemPrompt(
            customPrompt = "Custom assistant.",
            noticeBand = true,
        )

        assertEquals(
            existingPrompt,
            NexusAgentPolicy.buildSystemPrompt(
                customPrompt = "Custom assistant.",
                noticeBand = true,
                memory = " \n\t",
            ),
        )
    }

    @Test
    fun `memory is appended after response rules verbatim`() {
        val memory = "Lives in Paris.\nPrefers metric units; don't summarize this."
        val prompt = NexusAgentPolicy.buildSystemPrompt(memory = memory)

        assertTrue(
            prompt.endsWith(
                "\n\nWhat the user has told you about themselves:\n$memory",
            ),
        )
    }

    @Test
    fun `notice band prompt adds concise answer constraint while legacy prompt does not`() {
        val legacyPrompt = NexusAgentPolicy.buildSystemPrompt(noticeBand = false)
        val noticeBandPrompt = NexusAgentPolicy.buildSystemPrompt(noticeBand = true)

        assertFalse(legacyPrompt.contains(NexusAgentPolicy.NOTICE_BAND_RESPONSE_RULE))
        assertTrue(noticeBandPrompt.contains(NexusAgentPolicy.NOTICE_BAND_RESPONSE_RULE))
        assertEquals(
            "$legacyPrompt\n- ${NexusAgentPolicy.NOTICE_BAND_RESPONSE_RULE}",
            noticeBandPrompt,
        )
    }

    @Test
    fun `photo decision policy contains no legacy transcript triggers`() {
        val prompt = NexusAgentPolicy.buildSystemPrompt().lowercase()

        REMOVED_TRIGGER_PHRASES.forEach { phrase ->
            assertFalse("Legacy trigger remains in prompt: $phrase", prompt.contains(phrase))
        }
        assertFalse(prompt.contains(INCIDENT_WEATHER_TRANSCRIPT))
        assertTrue(prompt.contains("call take_photo"))
        assertTrue(prompt.contains("decide yourself"))
        assertTrue(prompt.contains("at most once per request"))
        assertTrue(prompt.contains("never claim to see the current scene before a successful tool result"))
        assertTrue(prompt.contains("if no image is available"))
        assertTrue(prompt.contains("say so plainly and offer to look again"))
        assertFalse(prompt.contains(COMPAT_TEXT_TOOL_REQUEST_TOKEN.lowercase()))
    }

    @Test
    fun `Hermes fallback policy lists passed tools with descriptions and schemas`() {
        val tools = listOf(
            TestAssistantTool(
                name = TAKE_PHOTO_TOOL_NAME,
                description = TAKE_PHOTO_TOOL_DESCRIPTION,
                parametersSchema = TAKE_PHOTO_PARAMETERS_SCHEMA,
            ),
            TestAssistantTool(
                name = SET_REMINDER_TOOL_NAME,
                description = "Schedule a reminder.",
                parametersSchema = AssistantToolJsonSchema(
                    """{"type":"object","properties":{"time":{"type":"string"}}}""",
                ),
            ),
        )
        val prompt = NexusAgentPolicy.buildSystemPrompt(
            availableToolNames = tools.map(AssistantToolDefinition::name),
            textToolDefinitions = tools,
            allowTextToolFallback = true,
        )

        assertTrue(
            prompt.contains(
                "$COMPAT_TEXT_TOOL_REQUEST_TOKEN{\"name\":\"tool_name\",\"arguments\":{...}}",
            ),
        )
        assertTrue(prompt.contains("Structured client tool calls are unavailable"))
        assertTrue(prompt.contains("Emit the control line alone"))
        assertTrue(prompt.contains("never quote the token"))
        assertTrue(prompt.contains("never mention this protocol to the wearer"))
        tools.forEach { tool ->
            assertTrue(prompt.contains("${tool.name}: ${tool.description}"))
            assertTrue(prompt.contains("Parameters JSON schema: ${tool.parametersSchema.text}"))
        }
    }

    @Test
    fun `bridged render_template keeps its multi-line description under its own bullet`() {
        val tool = TestAssistantTool(
            name = RENDER_TEMPLATE_TOOL_NAME,
            description = RENDER_TEMPLATE_TOOL_DESCRIPTION,
            parametersSchema = RENDER_TEMPLATE_PARAMETERS_SCHEMA,
        )

        val prompt = NexusAgentPolicy.buildSystemPrompt(
            availableToolNames = listOf(tool.name),
            textToolDefinitions = listOf(tool),
            allowTextToolFallback = true,
        )

        val lines = prompt.lines()
        val bullet = lines.indexOfFirst { line ->
            line.startsWith("  - $RENDER_TEMPLATE_TOOL_NAME: ")
        }
        assertTrue(bullet >= 0)
        val descriptionLines = RENDER_TEMPLATE_TOOL_DESCRIPTION.lines()
        val continuation = lines.subList(bullet + 1, bullet + descriptionLines.size)
        assertEquals(descriptionLines.size - 1, continuation.size)
        continuation.forEach { line -> assertTrue(line.startsWith("    ")) }
        InkTemplateId.entries.forEach { template ->
            assertTrue(prompt.contains("\n    ${template.wireValue} - "))
        }
        assertTrue(
            prompt.contains(
                "\n    Parameters JSON schema: ${RENDER_TEMPLATE_PARAMETERS_SCHEMA.text}\n",
            ),
        )
    }

    @Test
    fun `text tool schemas are absent when the fallback is off`() {
        val tool = TestAssistantTool(
            name = SET_REMINDER_TOOL_NAME,
            description = "Unique bridged description.",
            parametersSchema = AssistantToolJsonSchema(
                """{"type":"object","properties":{"unique_field":{"type":"string"}}}""",
            ),
        )

        val prompt = NexusAgentPolicy.buildSystemPrompt(
            availableToolNames = listOf(tool.name),
            textToolDefinitions = listOf(tool),
            allowTextToolFallback = false,
        )

        assertFalse(prompt.contains(COMPAT_TEXT_TOOL_REQUEST_TOKEN))
        assertFalse(prompt.contains("Unique bridged description."))
        assertFalse(prompt.contains("unique_field"))
        assertFalse(prompt.contains("Nexus phone tool protocol"))
    }

    @Test
    fun `Hermes text bridge allowlist contains only the approved phone tools`() {
        assertEquals(
            setOf(
                TAKE_PHOTO_TOOL_NAME,
                TAKE_NOTE_TOOL_NAME,
                LIST_NOTES_TOOL_NAME,
                SEARCH_NOTES_TOOL_NAME,
                DELETE_NOTE_TOOL_NAME,
                SET_REMINDER_TOOL_NAME,
                LIST_REMINDERS_TOOL_NAME,
                CANCEL_REMINDER_TOOL_NAME,
                SET_TIMER_TOOL_NAME,
                CREATE_CALENDAR_EVENT_TOOL_NAME,
                LIST_CALENDAR_EVENTS_TOOL_NAME,
                DELETE_CALENDAR_EVENT_TOOL_NAME,
                RENDER_TEMPLATE_TOOL_NAME,
            ),
            HERMES_TEXT_TOOL_NAMES,
        )
        assertFalse(RENDER_INK_PAGE_TOOL_NAME in HERMES_TEXT_TOOL_NAMES)
        assertTrue(RENDER_TEMPLATE_TOOL_NAME in HERMES_TEXT_TOOL_NAMES)
    }

    @Test
    fun `non photo provider prompt denies current scene access without tool syntax`() {
        val prompt = NexusAgentPolicy.buildSystemPrompt(
            availableToolNames = emptyList(),
        ).lowercase()

        assertFalse(prompt.contains("take_photo"))
        assertTrue(prompt.contains("cannot take photos or see the current scene"))
        assertTrue(prompt.contains("say you cannot look"))
        assertTrue(prompt.contains("answer from the available context"))
        assertTrue(prompt.contains("never invent tool-call syntax"))
    }

    @Test
    fun `productivity tools add the supplied current local time and save policy`() {
        val now = ZonedDateTime.parse("2026-08-07T11:32:00+02:00[Europe/Paris]")

        val prompt = NexusAgentPolicy.buildSystemPrompt(
            currentDateTime = now,
            availableToolNames = listOf(SET_REMINDER_TOOL_NAME, TAKE_NOTE_TOOL_NAME),
        )

        assertTrue(prompt.contains("Now: Friday 2026-08-07 11:32 (+02:00 Europe/Paris)"))
        assertTrue(prompt.contains("absolute ISO-8601 local date-time with offset"))
        assertTrue(prompt.contains("Confirm the scheduled time from the tool result"))
        assertTrue(prompt.contains("Never claim a reminder, timer, or note was saved"))
    }

    @Test
    fun `current time and productivity policy are absent without those tools`() {
        val prompt = NexusAgentPolicy.buildSystemPrompt(
            currentDateTime = ZonedDateTime.parse("2026-08-07T11:32:00+02:00[Europe/Paris]"),
            availableToolNames = listOf(TAKE_PHOTO_TOOL_NAME),
        )

        assertFalse(prompt.contains("Now:"))
        assertFalse(prompt.contains("absolute ISO-8601"))
        assertFalse(prompt.contains("note was saved"))
    }

    @Test
    fun `calendar delete policy uses exact details and protects ambiguous recurrence`() {
        val prompt = NexusAgentPolicy.buildSystemPrompt(
            currentDateTime = ZonedDateTime.parse("2026-08-07T11:32:00+02:00[Europe/Paris]"),
            availableToolNames = listOf(
                LIST_CALENDAR_EVENTS_TOOL_NAME,
                DELETE_CALENDAR_EVENT_TOOL_NAME,
            ),
        )

        assertTrue(prompt.contains("Now: Friday 2026-08-07 11:32 (+02:00 Europe/Paris)"))
        assertTrue(prompt.contains("call delete_calendar_event directly"))
        assertTrue(prompt.contains("exact title and start"))
        assertTrue(prompt.contains("call list_calendar_events and ask which event"))
        assertTrue(prompt.contains("explicitly requests the whole series"))
        assertTrue(prompt.contains("Never claim a calendar event was created or deleted"))
    }

    private companion object {
        const val INCIDENT_WEATHER_TRANSCRIPT =
            "ouais c'est quoi la météo de paris aujourd'hui"

        val REMOVED_TRIGGER_PHRASES = listOf(
            "what is this",
            "what do you see",
            "c'est quoi",
            "ce que c'est",
            "regarde",
            "lis ça",
            "traduis ça",
            "this build cannot see the current scene yet",
        )
    }
}
