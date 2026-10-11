package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId

class ProductivityToolsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `all text tools are available without vision and publish closed schemas`() {
        val tools = tools()
        val registry = AssistantToolRegistry(tools)

        val available = registry.availableDefinitions(NO_VISION)

        assertEquals(PRODUCTIVITY_TOOL_NAMES, available.map(AssistantToolDefinition::name).toSet())
        available.forEach { tool ->
            assertFalse(tool.parametersSchema.toJsonObject().getBoolean("additionalProperties"))
        }
        assertEquals(
            setOf(TAKE_NOTE_TOOL_NAME, DELETE_NOTE_TOOL_NAME, SET_REMINDER_TOOL_NAME, CANCEL_REMINDER_TOOL_NAME, SET_TIMER_TOOL_NAME),
            available.filter(AssistantToolDefinition::sideEffecting).map(AssistantToolDefinition::name).toSet(),
        )
        assertEquals(
            mapOf(
                TAKE_NOTE_TOOL_NAME to "Saving the note…",
                LIST_NOTES_TOOL_NAME to "Reading your notes…",
                SEARCH_NOTES_TOOL_NAME to "Searching your notes…",
                DELETE_NOTE_TOOL_NAME to "Deleting the note…",
                SET_REMINDER_TOOL_NAME to "Setting the reminder…",
                LIST_REMINDERS_TOOL_NAME to "Checking your reminders…",
                CANCEL_REMINDER_TOOL_NAME to "Cancelling the reminder…",
                SET_TIMER_TOOL_NAME to "Starting the timer…",
            ),
            available.associate { tool -> tool.name to tool.progressLabel },
        )
    }

    @Test
    fun `take list and search notes return compact model readable shapes`() = runTest {
        val noteStore = noteStore()
        val take = TakeNoteTool(noteStore)
        val longText = "Findable phrase " + "word ".repeat(500)

        val saved = execute(
            take,
            """{"text":${JSONObject.quote(longText)},"title":"Reference"}""",
        ) as AssistantToolResult.Json
        val savedJson = JSONObject(saved.text)
        assertTrue(savedJson.getBoolean("saved"))
        assertTrue(savedJson.getBoolean("truncated"))
        assertTrue(savedJson.getString("id").startsWith("n_"))

        val listed = execute(ListNotesTool(noteStore) { PARIS_ZONE }, "{}") as AssistantToolResult.Json
        val listedJson = JSONObject(listed.text)
        val first = listedJson.getJSONArray("notes").getJSONObject(0)
        assertEquals(1, listedJson.getInt("total"))
        assertTrue(first.getString("preview").length <= 120)
        assertTrue(first.getString("created_at").endsWith("+02:00"))

        val searched = execute(
            SearchNotesTool(noteStore) { PARIS_ZONE },
            """{"query":"FINDABLE"}""",
        ) as AssistantToolResult.Json
        val match = JSONObject(searched.text).getJSONArray("notes").getJSONObject(0)
        assertTrue(match.getString("matched_snippet").contains("Findable", ignoreCase = true))
    }

    @Test
    fun `delete note reports success then note not found`() = runTest {
        val store = noteStore()
        val note = (store.save("Delete me") as AssistantNoteSaveResult.Saved).note
        val tool = DeleteNoteTool(store)

        val deleted = execute(tool, """{"id":"${note.id}"}""") as AssistantToolResult.Json
        val missing = execute(tool, """{"id":"${note.id}"}""")

        assertTrue(JSONObject(deleted.text).getBoolean("deleted"))
        assertEquals(AssistantToolResult.Error("note_not_found"), missing)
    }

    @Test
    fun `set reminder rejects bad iso past and too far times`() = runTest {
        val now = NOW_EPOCH_MS
        val tool = SetReminderTool(reminderStore(), RecordingScheduler(), epochClock = { now })

        assertEquals(
            AssistantToolResult.Error(TOOL_ERROR_INVALID_CALL),
            execute(tool, """{"time":"tomorrow","label":"Bad"}"""),
        )
        assertEquals(
            AssistantToolResult.Error("time_in_past"),
            execute(tool, """{"time":"2026-08-07T08:00:00+02:00","label":"Past"}"""),
        )
        assertEquals(
            AssistantToolResult.Error("time_too_far"),
            execute(tool, """{"time":"2028-08-07T12:00:00+02:00","label":"Far"}"""),
        )
    }

    @Test
    fun `set reminder persists before scheduling and reports approximate fallback`() = runTest {
        val now = NOW_EPOCH_MS
        val store = reminderStore()
        val scheduler = RecordingScheduler(exact = false)
        val tool = SetReminderTool(store, scheduler, epochClock = { now })

        val result = execute(
            tool,
            """{"time":"2026-08-07T14:20:00+02:00","label":"Call Sam"}""",
        ) as AssistantToolResult.Json
        val json = JSONObject(result.text)

        assertEquals("Call Sam", json.getString("label"))
        assertEquals(20L, json.getLong("in_minutes"))
        assertFalse(json.getBoolean("exact"))
        assertEquals(json.getString("id"), scheduler.scheduled.single().id)
        assertNotNull(store.reminder(json.getString("id")))
    }

    @Test
    fun `set timer validates bounds and stores elapsed realtime deadline`() = runTest {
        val store = reminderStore()
        val scheduler = RecordingScheduler()
        val tool = SetTimerTool(
            store = store,
            scheduler = scheduler,
            epochClock = { NOW_EPOCH_MS },
            elapsedClock = { 10_000L },
            zoneId = { PARIS_ZONE },
        )

        assertEquals(
            AssistantToolResult.Error("bad_duration"),
            execute(tool, """{"duration_seconds":4,"label":null}"""),
        )
        assertEquals(
            AssistantToolResult.Error("bad_duration"),
            execute(tool, """{"duration_seconds":86401,"label":null}"""),
        )
        val result = execute(
            tool,
            """{"duration_seconds":90,"label":"Tea"}""",
        ) as AssistantToolResult.Json
        val json = JSONObject(result.text)
        val reminder = store.reminder(json.getString("id"))!!

        assertEquals(90L, json.getLong("in_seconds"))
        assertTrue(json.getString("fires_at").endsWith("+02:00"))
        assertEquals(100_000L, reminder.elapsedRealtimeDeadlineMs)
        assertEquals(AssistantReminderKind.TIMER, reminder.kind)
    }

    @Test
    fun `ambiguous cancellation includes candidates and does not cancel an alarm`() = runTest {
        val ids = ArrayDeque(listOf("r_aaaaaaaa", "r_bbbbbbbb"))
        val store = AssistantReminderStore(
            filesDir = temporaryFolder.root,
            idGenerator = ids::removeFirst,
        )
        saveReminder(store, "Lunch", 2_000L)
        saveReminder(store, "lunch", 3_000L)
        val scheduler = RecordingScheduler()

        val result = execute(
            CancelReminderTool(store, scheduler),
            """{"id_or_label":"LUNCH"}""",
        ) as AssistantToolResult.Error
        val details = JSONObject(result.detailsJson!!)

        assertEquals("ambiguous", result.code)
        assertEquals(2, details.getJSONArray("candidates").length())
        assertTrue(scheduler.cancelled.isEmpty())
        assertEquals(2, store.pending().size)
    }

    @Test
    fun `two set reminder calls in one phase reject the second as already used`() = runTest {
        val now = NOW_EPOCH_MS
        val tool = SetReminderTool(reminderStore(), RecordingScheduler(), epochClock = { now })
        val phase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(NO_VISION)

        val first = phase.execute(
            AssistantToolCall(
                "call-1",
                SET_REMINDER_TOOL_NAME,
                """{"time":"2026-08-07T14:20:00+02:00","label":"First"}""",
            ),
        )
        val second = phase.execute(
            AssistantToolCall(
                "call-2",
                SET_REMINDER_TOOL_NAME,
                """{"time":"2026-08-07T14:30:00+02:00","label":"Second"}""",
            ),
        )

        assertTrue(first is AssistantToolResult.Json)
        assertEquals(AssistantToolResult.Error(TOOL_ERROR_ALREADY_USED), second)
    }

    @Test
    fun `unexpected properties are rejected before a side effect`() = runTest {
        val store = noteStore()

        val result = execute(
            TakeNoteTool(store),
            """{"text":"private","title":null,"extra":true}""",
        )

        assertEquals(AssistantToolResult.Error(TOOL_ERROR_INVALID_CALL), result)
        assertTrue(store.notes().isEmpty())
    }

    private suspend fun execute(
        tool: AssistantToolDefinition,
        arguments: String,
    ): AssistantToolResult = AssistantToolRegistry(listOf(tool))
        .newExecutionPhase(NO_VISION)
        .execute(AssistantToolCall("call", tool.name, arguments))

    @Test
    fun `every productivity tool schema is accepted by strict function calling`() {
        tools().forEach { assertTrue(it.name, it.parametersSchema.isStrictCompatible()) }
    }

    private fun tools(): List<AssistantToolDefinition> = assistantProductivityTools(
        noteStore = noteStore(),
        reminderStore = reminderStore(),
        reminderScheduler = RecordingScheduler(),
        epochClock = { NOW_EPOCH_MS },
        elapsedClock = { 10_000L },
        zoneId = { PARIS_ZONE },
    )

    private fun noteStore(): AssistantNoteStore = AssistantNoteStore(
        filesDir = temporaryFolder.root,
        idGenerator = { "n_12345678" },
        clock = { NOW_EPOCH_MS },
    )

    private fun reminderStore(): AssistantReminderStore = AssistantReminderStore(
        filesDir = temporaryFolder.root,
        idGenerator = { "r_12345678" },
    )

    private fun saveReminder(
        store: AssistantReminderStore,
        label: String,
        epoch: Long,
    ) {
        store.save(
            label = label,
            epochMillis = epoch,
            originalIso = "time-$epoch",
            createdAtMs = 1_000L,
            kind = AssistantReminderKind.REMINDER,
        )
    }

    private class RecordingScheduler(
        private val exact: Boolean = true,
    ) : AssistantReminderScheduler {
        val scheduled = mutableListOf<AssistantReminder>()
        val cancelled = mutableListOf<String>()

        override fun schedule(
            reminder: AssistantReminder,
            afterBoot: Boolean,
            lateIfImmediate: Boolean,
        ): ReminderScheduleOutcome {
            scheduled += reminder
            return ReminderScheduleOutcome(exact = exact, deliveredImmediately = false)
        }

        override fun cancel(id: String) {
            cancelled += id
        }
    }

    private companion object {
        val NO_VISION = AssistantProviderFeatures(supportsTools = true, supportsVision = false)
        val PARIS_ZONE: ZoneId = ZoneId.of("Europe/Paris")
        const val NOW_EPOCH_MS = 1_786_104_000_000L
    }
}
