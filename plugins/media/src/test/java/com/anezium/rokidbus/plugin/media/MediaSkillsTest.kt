package com.anezium.rokidbus.plugin.media

import com.anezium.rokidbus.shared.skills.SkillCatalogParseResult
import com.anezium.rokidbus.shared.skills.SkillCatalogParser
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import com.anezium.rokidbus.shared.skills.SkillSchemaValidator
import com.anezium.rokidbus.shared.skills.SkillValidation
import com.anezium.rokidbus.shared.skills.SkillLimits
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MediaSkillsTest {
    private class Access : MediaSkillAccess {
        var enabled = true
        var available = listOf(MediaSkillSession("session-1", "Player A", "playing", "Private title", "Private artist"))
        var metadataRead = false
        var pauseResult = MediaPauseOutcome.PAUSED
        var readFailure: Exception? = null
        val paused = mutableListOf<String>()
        override fun hasAccess() = enabled
        override fun sessions(includeMetadata: Boolean): List<MediaSkillSession> {
            readFailure?.let { throw it }
            metadataRead = includeMetadata
            return available
        }
        override fun pause(reference: String, remainingMs: () -> Long, cancelled: () -> Boolean): MediaPauseOutcome {
            paused += reference
            return pauseResult
        }
    }
    private val access = Access()
    private val skills = MediaSkills(access)
    private fun run(operation: String, arguments: JSONObject = JSONObject()) =
        skills.run(operation, arguments, { 10_000L }, { false })
    private fun data(operation: String, arguments: JSONObject = JSONObject()): JSONObject {
        val result = (run(operation, arguments) as MediaSkillOutcome.Completed).data
        val catalog = (SkillCatalogParser.parse(File("src/main/res/raw/nexus_skills.json").readText())
            as SkillCatalogParseResult.Valid).catalog
        assertEquals(SkillValidation.Valid, SkillSchemaValidator.validate(result, catalog.operation(operation)!!.output))
        return result
    }

    @Test fun `read returns metadata and focus without a HUD session`() {
        val result = data(MediaSkills.NOW_PLAYING)
        assertEquals("Private title", result.getString("title"))
        assertEquals("session-1", result.getJSONObject("focus").getString("session"))
        assertTrue(access.metadataRead)
        assertTrue(access.paused.isEmpty())
    }

    @Test fun `pause without metadata permission reads no metadata and returns none`() {
        val result = data(MediaSkills.PAUSE)
        assertFalse(access.metadataRead)
        assertFalse(result.toString().contains("Private"))
        assertEquals(listOf("session-1"), access.paused)
        assertFalse(result.getBoolean("already_paused"))
    }

    @Test fun `two playing sessions require a choice without transport or title disclosure`() {
        access.available += MediaSkillSession("session-2", "Player B", "playing", "Other private title")
        val choice = run(MediaSkills.PAUSE) as MediaSkillOutcome.Choice
        assertEquals(listOf("Player A", "Player B"), choice.choices.map { it.label })
        assertEquals(listOf("session-1", "session-2"), choice.choices.map { it.reference.value })
        assertFalse(choice.toString().contains("private", true))
        assertTrue(access.paused.isEmpty())
        assertTrue(run(MediaSkills.NOW_PLAYING) is MediaSkillOutcome.Choice)
    }

    @Test fun `a selected player is used exactly even if another player is playing`() {
        access.available += MediaSkillSession("session-2", "Player B", "paused")
        access.pauseResult = MediaPauseOutcome.ALREADY_PAUSED
        val result = data(MediaSkills.PAUSE, JSONObject().put("session", "session-2"))
        assertTrue(result.getBoolean("already_paused"))
        assertEquals(listOf("session-2"), access.paused)
    }

    @Test fun `a destroyed or unknown ref is never redirected to the remaining player`() {
        assertEquals(MediaSkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE),
            run(MediaSkills.PAUSE, JSONObject().put("session", "destroyed")))
        assertTrue(access.paused.isEmpty())
    }

    @Test fun `missing access fails before reading sessions or sending pause`() {
        access.enabled = false
        assertEquals(MediaSkillOutcome.Failed(SkillErrorCodes.SETUP_REQUIRED), run(MediaSkills.PAUSE))
        assertEquals(MediaSkillOutcome.Failed(SkillErrorCodes.SETUP_REQUIRED), run(MediaSkills.NOW_PLAYING))
        assertTrue(access.paused.isEmpty())
    }

    @Test fun `sent but unconfirmed and lost confirmation never claim completed`() {
        access.pauseResult = MediaPauseOutcome.ACCEPTED
        assertEquals(MediaSkillOutcome.Accepted, run(MediaSkills.PAUSE))
        access.pauseResult = MediaPauseOutcome.UNKNOWN
        assertEquals(MediaSkillOutcome.Unknown, run(MediaSkills.PAUSE))
        access.pauseResult = MediaPauseOutcome.STALE
        assertEquals(MediaSkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE), run(MediaSkills.PAUSE))
        access.pauseResult = MediaPauseOutcome.UNSUPPORTED
        assertEquals(MediaSkillOutcome.Failed(SkillErrorCodes.UNSUPPORTED_OPERATION), run(MediaSkills.PAUSE))
    }

    @Test fun `an empty read clears focus while pause without a session fails`() {
        access.available = emptyList()
        val result = data(MediaSkills.NOW_PLAYING)
        assertEquals("no_session", result.getString("state"))
        assertFalse(result.getJSONObject("focus").has("session"))
        assertEquals(MediaSkillOutcome.Failed(SkillErrorCodes.UNAVAILABLE), run(MediaSkills.PAUSE))
    }

    @Test fun `only a unique playing session is selected when paused players also exist`() {
        access.available += MediaSkillSession("session-2", "Player B", "paused")
        data(MediaSkills.PAUSE)
        assertEquals(listOf("session-1"), access.paused)
    }

    @Test fun `an expired or cancelled call cannot dispatch`() {
        assertEquals(MediaSkillOutcome.Failed(SkillErrorCodes.DEADLINE_EXCEEDED),
            skills.run(MediaSkills.PAUSE, JSONObject(), { 0 }, { false }))
        assertEquals(MediaSkillOutcome.Failed(SkillErrorCodes.DEADLINE_EXCEEDED),
            skills.run(MediaSkills.PAUSE, JSONObject(), { 10_000 }, { true }))
        assertTrue(access.paused.isEmpty())
    }

    @Test fun `long blank and duplicate player labels remain selectable within contract limits`() {
        access.available = listOf(
            MediaSkillSession("one", "x".repeat(80), "playing"),
            MediaSkillSession("two", "x".repeat(80), "playing"),
            MediaSkillSession("three", " ", "playing"),
        )
        val choice = run(MediaSkills.PAUSE) as MediaSkillOutcome.Choice
        assertTrue(choice.choices.all { it.label.isNotBlank() && it.label.length <= SkillLimits.MAX_CHOICE_LABEL_CHARS })
        assertEquals(3, choice.choices.map { it.label }.distinct().size)
        assertTrue(access.paused.isEmpty())
    }

    @Test fun `too many playing sessions fail without hiding an ambiguous target`() {
        access.available = (1..9).map { MediaSkillSession("ref-$it", "Player $it", "playing") }
        assertEquals(MediaSkillOutcome.Failed(SkillErrorCodes.UNAVAILABLE), run(MediaSkills.PAUSE))
        assertTrue(access.paused.isEmpty())
    }

    @Test fun `exceptions while selecting a target are known failures before dispatch`() {
        access.readFailure = SecurityException("access revoked")
        assertEquals(MediaSkillOutcome.Failed(SkillErrorCodes.SETUP_REQUIRED), run(MediaSkills.PAUSE))
        access.readFailure = IllegalStateException("too many sessions")
        assertEquals(MediaSkillOutcome.Failed(SkillErrorCodes.UNAVAILABLE), run(MediaSkills.PAUSE))
        assertTrue(access.paused.isEmpty())
    }

    @Test fun `paused players alone mean nothing is playing without asking for a choice`() {
        access.available = listOf(MediaSkillSession("one", "Player A", "paused"), MediaSkillSession("two", "Player B", "paused"))
        assertEquals("no_session", data(MediaSkills.NOW_PLAYING).getString("state"))
        assertTrue(run(MediaSkills.PAUSE) is MediaSkillOutcome.Choice)
    }
}
