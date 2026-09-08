package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTextInputTest {
    @Test
    fun `phone send consumes its entry before a duplicate or reentrant send can launch again`() {
        val fixture = Fixture()
        val entry = fixture.begin(AssistantTextEntryKind.PHONE_QUESTION)
        fixture.onQuestion = {
            assertNull(fixture.input.active)
            assertEquals(AssistantTextInputStatus.EXPIRED, fixture.input.submitPhone(entry.id, "again"))
        }

        assertEquals(AssistantTextInputStatus.SENT, fixture.input.submitPhone(entry.id, "  question  "))
        assertEquals(AssistantTextInputStatus.EXPIRED, fixture.input.submitPhone(entry.id, "duplicate"))
        assertEquals(listOf("  question  "), fixture.questions)
        assertTrue(fixture.notes.isEmpty())
    }

    @Test
    fun `HUD submit reaches the same question callback once and never saves a note`() {
        val fixture = Fixture()
        val entry = fixture.begin(AssistantTextEntryKind.HUD_QUESTION)

        assertEquals(AssistantTextInputStatus.SENT, fixture.input.commitSurface("assistant:${entry.id}", "question", false))
        assertEquals(AssistantTextInputStatus.EXPIRED, fixture.input.commitSurface(entry.id, "duplicate", false))
        assertEquals(listOf("question"), fixture.questions)
        assertTrue(fixture.notes.isEmpty())
    }

    @Test
    fun `surface callbacks cannot commit a phone entry and phone sends cannot commit a note`() {
        val fixture = Fixture()
        val phone = fixture.begin(AssistantTextEntryKind.PHONE_QUESTION)
        assertEquals(AssistantTextInputStatus.EXPIRED, fixture.input.commitSurface(phone.id, "wrong route", false))
        assertEquals(phone, fixture.input.active)
        fixture.input.clear()
        val note = fixture.begin(AssistantTextEntryKind.NOTE)
        assertEquals(AssistantTextInputStatus.EXPIRED, fixture.input.submitPhone(note.id, "wrong route"))
        assertEquals(note, fixture.input.active)
        assertEquals(AssistantTextInputStatus.SENT, fixture.input.commitSurface(note.id, "note only", false))
        assertEquals(listOf("note only"), fixture.notes)
        assertTrue(fixture.questions.isEmpty())
    }

    @Test
    fun `wrong surface and cancelled text never consume another editor or call a provider`() {
        val fixture = Fixture()
        val entry = fixture.begin(AssistantTextEntryKind.HUD_QUESTION)
        assertEquals(AssistantTextInputStatus.EXPIRED, fixture.input.commitSurface("other-owner", "wrong", false))
        assertEquals(AssistantTextInputStatus.EXPIRED, fixture.input.commitSurface("relay:${entry.id}", "wrong", false))
        assertEquals(AssistantTextInputStatus.EXPIRED, fixture.input.commitSurface("assistant:relay:${entry.id}", "wrong", true))
        assertFalse(fixture.input.cancel("other-owner"))
        assertEquals(entry, fixture.input.active)

        assertEquals(AssistantTextInputStatus.CANCELLED, fixture.input.commitSurface(entry.id, "must be ignored", true))
        assertEquals(AssistantTextInputStatus.EXPIRED, fixture.input.commitSurface(entry.id, "late", false))
        assertNull(fixture.input.active)
        assertTrue(fixture.questions.isEmpty())
        assertTrue(fixture.notes.isEmpty())
    }

    @Test
    fun `reopen and voice replacement invalidate earlier question and note identities`() {
        val fixture = Fixture()
        val old = fixture.begin(AssistantTextEntryKind.NOTE)
        fixture.input.clear()
        val current = fixture.begin(AssistantTextEntryKind.HUD_QUESTION)
        assertNotEquals(old.id, current.id)

        assertEquals(AssistantTextInputStatus.EXPIRED, fixture.input.commitSurface("assistant:${old.id}", "old note", false))
        assertFalse(fixture.input.cancel(old.id))
        assertEquals(current, fixture.input.active)
        assertEquals(AssistantTextInputStatus.SENT, fixture.input.commitSurface(current.id, "new question", false))
        assertEquals(listOf("new question"), fixture.questions)
        assertTrue(fixture.notes.isEmpty())
    }

    @Test
    fun `closed disconnected and busy sessions reject begin and final submit`() {
        listOf(
            AssistantTextInputStatus.NOT_OPEN,
            AssistantTextInputStatus.DISCONNECTED,
            AssistantTextInputStatus.BUSY,
        ).forEach { unavailable ->
            val fixture = Fixture()
            fixture.availability = unavailable
            assertEquals(unavailable, fixture.input.begin(AssistantTextEntryKind.PHONE_QUESTION).status)
            assertNull(fixture.input.active)
            fixture.availability = AssistantTextInputStatus.READY
            val entry = fixture.begin(AssistantTextEntryKind.PHONE_QUESTION)
            fixture.availability = unavailable

            assertEquals(unavailable, fixture.input.submitPhone(entry.id, "never send"))
            assertNull(fixture.input.active)
            assertTrue(fixture.questions.isEmpty())
        }
    }

    @Test
    fun `incomplete provider configuration rejects entry and keeps an existing phone draft retryable`() {
        listOf(AssistantTextInputStatus.AUTH_REQUIRED, AssistantTextInputStatus.MODEL_REQUIRED).forEach { missing ->
            val fixture = Fixture()
            fixture.availability = missing
            assertEquals(missing, fixture.input.begin(AssistantTextEntryKind.PHONE_QUESTION).status)
            assertNull(fixture.input.active)

            fixture.availability = AssistantTextInputStatus.READY
            val entry = fixture.begin(AssistantTextEntryKind.PHONE_QUESTION)
            fixture.availability = missing
            assertEquals(missing, fixture.input.submitPhone(entry.id, "keep my draft"))
            assertEquals(entry, fixture.input.active)
            assertTrue(fixture.questions.isEmpty())

            fixture.availability = AssistantTextInputStatus.READY
            assertEquals(AssistantTextInputStatus.SENT, fixture.input.submitPhone(entry.id, "keep my draft"))
            assertEquals(listOf("keep my draft"), fixture.questions)
        }
    }

    @Test
    fun `one text entry blocks another without replacing the original`() {
        val fixture = Fixture()
        val entry = fixture.begin(AssistantTextEntryKind.PHONE_QUESTION)
        assertEquals(AssistantTextInputStatus.BUSY, fixture.input.availableFor(AssistantTextEntryKind.NOTE))
        assertEquals(AssistantTextInputStatus.BUSY, fixture.input.begin(AssistantTextEntryKind.HUD_QUESTION).status)
        assertEquals(entry, fixture.input.active)
        assertTrue(fixture.input.cancel(entry.id))
        assertEquals(AssistantTextInputStatus.READY, fixture.input.availableFor(AssistantTextEntryKind.NOTE))
    }

    @Test
    fun `phone invalid drafts stay editable with the same UTF16 limit as HUD input`() {
        val fixture = Fixture()
        val entry = fixture.begin(AssistantTextEntryKind.PHONE_QUESTION)
        assertEquals(AssistantTextInputStatus.EMPTY, fixture.input.submitPhone(entry.id, " \n \t"))
        assertEquals(entry, fixture.input.active)
        val limit = "\uD83D\uDE00".repeat(256)
        assertEquals(512, limit.length)
        assertEquals(AssistantTextInputStatus.TOO_LONG, fixture.input.submitPhone(entry.id, limit + "x"))
        assertEquals(entry, fixture.input.active)
        assertEquals(AssistantTextInputStatus.SENT, fixture.input.submitPhone(entry.id, limit))
        assertEquals(listOf(limit), fixture.questions)
    }

    @Test
    fun `empty and oversized HUD commits end their one-shot field without calling anything`() {
        val fixture = Fixture()
        val empty = fixture.begin(AssistantTextEntryKind.HUD_QUESTION)
        assertEquals(AssistantTextInputStatus.EMPTY, fixture.input.commitSurface(empty.id, "  ", false))
        assertNull(fixture.input.active)
        val oversized = fixture.begin(AssistantTextEntryKind.NOTE)
        assertEquals(AssistantTextInputStatus.TOO_LONG, fixture.input.commitSurface(oversized.id, "x".repeat(513), false))
        assertNull(fixture.input.active)
        assertTrue(fixture.questions.isEmpty())
        assertTrue(fixture.notes.isEmpty())
    }

    @Test
    fun `surface rejection releases only its pending entry and cannot cancel its replacement`() {
        val fixture = Fixture()
        val rejected = fixture.begin(AssistantTextEntryKind.HUD_QUESTION)
        assertTrue(fixture.input.cancel(rejected.id))
        val replacement = fixture.begin(AssistantTextEntryKind.PHONE_QUESTION)
        assertFalse(fixture.input.cancel(rejected.id))
        assertEquals(replacement, fixture.input.active)
    }

    private class Fixture {
        var availability = AssistantTextInputStatus.READY
        var onQuestion: (() -> Unit)? = null
        val questions = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val input = AssistantTextInput(
            availability = { availability },
            onQuestion = {
                questions += it
                onQuestion?.invoke()
                AssistantTextInputStatus.SENT
            },
            onNote = { notes += it },
        )

        fun begin(kind: AssistantTextEntryKind): AssistantTextEntry {
            val start = input.begin(kind)
            assertEquals(AssistantTextInputStatus.READY, start.status)
            assertNotNull(start.entry)
            return checkNotNull(start.entry)
        }
    }
}
