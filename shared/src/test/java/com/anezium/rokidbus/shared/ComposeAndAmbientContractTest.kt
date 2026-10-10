package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeAndAmbientContractTest {
    private val captureId = "cap_0123456789"

    @Test
    fun `dictation messages round trip and a completion carries its text`() {
        val start = ComposeDictateStart(captureId, "fr-FR")
        assertEquals(start, ComposeContract.parseDictateStart(ComposeContract.dictateStart(start)))
        assertEquals(ComposeDictateStart(captureId), ComposeContract.parseDictateStart(ComposeContract.dictateStart(ComposeDictateStart(captureId))))
        val partial = ComposeDictatePartial(captureId, "J'arr")
        assertEquals(partial, ComposeContract.parseDictatePartial(ComposeContract.dictatePartial(partial)))
        val done = ComposeDictateFinal(captureId, ComposeDictationReason.COMPLETED, "J'arrive")
        assertEquals(done, ComposeContract.parseDictateFinal(ComposeContract.dictateFinal(done)))
        val stop = ComposeDictateStop(captureId, cancel = true)
        assertEquals(stop, ComposeContract.parseDictateStop(ComposeContract.dictateStop(stop)))

        // A completion without text is not a completion; another reason never yields a draft.
        assertNull(ComposeContract.parseDictateFinal(JSONObject().put("captureId", captureId).put("reason", "completed")))
        assertNull(ComposeContract.parseDictateFinal(JSONObject().put("captureId", captureId).put("reason", "completed").put("text", "  ")))
        val busy = ComposeContract.parseDictateFinal(JSONObject().put("captureId", captureId).put("reason", "busy").put("text", "half"))
        assertEquals(ComposeDictateFinal(captureId, ComposeDictationReason.BUSY, null), busy)
        assertNull(ComposeContract.parseDictateFinal(JSONObject().put("captureId", captureId).put("reason", "tired")))
        ComposeDictationReason.entries.filter { it != ComposeDictationReason.COMPLETED }.forEach { reason ->
            assertEquals(reason, ComposeContract.parseDictateFinal(ComposeContract.dictateFinal(ComposeDictateFinal(captureId, reason)))!!.reason)
        }
    }

    @Test
    fun `typing messages round trip and an empty commit is an explicit discard`() {
        val start = ComposeTypeStart(captureId, "Oui", maxChars = 500)
        assertEquals(start, ComposeContract.parseTypeStart(ComposeContract.typeStart(start)))
        val text = ComposeTypeText(captureId, "Oui, j'arrive")
        assertEquals(text, ComposeContract.parseTypeText(ComposeContract.typeText(text)))
        val discard = ComposeTypeDone(captureId, committed = true, text = "")
        assertEquals(discard, ComposeContract.parseTypeDone(ComposeContract.typeDone(discard)))
        val cancelled = ComposeTypeDone(captureId, committed = false)
        assertEquals(cancelled, ComposeContract.parseTypeDone(ComposeContract.typeDone(cancelled)))
        assertEquals(captureId, ComposeContract.parseTypeEnd(ComposeContract.typeEnd(captureId)))
        // A commit without its text says nothing; never read it as a discard.
        assertNull(ComposeContract.parseTypeDone(JSONObject().put("captureId", captureId).put("committed", true)))
    }

    @Test
    fun `capture ids, limits and paths are enforced`() {
        listOf("short", "has space 123", "x".repeat(65), "").forEach { assertFalse(it, ComposeContract.isValidCaptureId(it)) }
        assertNull(ComposeContract.parseTypeText(JSONObject().put("captureId", "short").put("text", "x")))
        assertNull(ComposeContract.parseTypeText(JSONObject().put("captureId", captureId).put("text", "x".repeat(1001))))
        assertNull(ComposeContract.parseTypeStart(JSONObject().put("captureId", captureId).put("initialText", "").put("maxChars", 1001)))
        assertNull(ComposeContract.parseTypeStart(JSONObject().put("captureId", captureId).put("initialText", "abc").put("maxChars", 2)))
        assertNull(ComposeContract.parseDictateStart(JSONObject().put("captureId", captureId).put("language", "fr FR")))
        assertNull(ComposeContract.parseDictateStop(JSONObject().put("captureId", captureId)))
        assertEquals(1000, ComposeContract.clip("x".repeat(1500)).length)
        assertTrue(ComposeContract.glassesToPhonePaths.none { it in ComposeContract.phoneToGlassesPaths })
        assertEquals(9, (ComposeContract.glassesToPhonePaths + ComposeContract.phoneToGlassesPaths).size)
    }

    @Test
    fun `ambient sizes round trip and unknown sizes leave an owner automatic`() {
        val edit = AmbientSizeEdit("nav", AmbientSize.MINIMAP)
        assertEquals(edit, AmbientPreferencesContract.parseSizeEdit(AmbientPreferencesContract.sizeEditPayload(edit)))
        val reset = AmbientSizeEdit("nav", null)
        val resetPayload = AmbientPreferencesContract.sizeEditPayload(reset)
        assertTrue(resetPayload.isNull("size"))
        assertEquals(reset, AmbientPreferencesContract.parseSizeEdit(resetPayload))
        assertNull(AmbientPreferencesContract.parseSizeEdit(JSONObject().put("ownerPluginId", "nav")))
        assertNull(AmbientPreferencesContract.parseSizeEdit(JSONObject().put("ownerPluginId", "nav").put("size", "huge")))

        val sizes = AmbientSizes(4, mapOf("nav" to AmbientSize.PANEL, "media" to AmbientSize.TEXT))
        assertEquals(sizes, AmbientPreferencesContract.parseSizes(JSONObject(AmbientPreferencesContract.sizesPayload(sizes).toString())))
        val future = JSONObject().put("revision", 5).put("sizes", JSONObject().put("nav", "hologram").put("media", "text"))
        assertEquals(AmbientSizes(5, mapOf("media" to AmbientSize.TEXT)), AmbientPreferencesContract.parseSizes(future))
        assertNull(AmbientPreferencesContract.parseSizes(JSONObject().put("revision", 5).put("sizes", JSONObject().put("Bad Id", "text"))))
    }

    @Test
    fun `foreground policy defaults home to residents and unknown apps to signals`() {
        val policy = AmbientPolicy(2, mapOf("com.spotify.music" to ForegroundAmbientMode.NOTHING))
        assertEquals(policy, AmbientPreferencesContract.parsePolicy(JSONObject(AmbientPreferencesContract.policyPayload(policy).toString())))
        assertEquals(ForegroundAmbientMode.NOTHING, AmbientPreferencesContract.modeFor(policy, "com.spotify.music", isHome = false))
        assertEquals(ForegroundAmbientMode.SIGNALS, AmbientPreferencesContract.modeFor(policy, "com.example.app", isHome = false))
        assertEquals(ForegroundAmbientMode.RESIDENTS, AmbientPreferencesContract.modeFor(policy, "com.rokid.home", isHome = true))
        assertNull(AmbientPreferencesContract.parsePolicy(JSONObject().put("revision", 1).put("modes", JSONObject().put("not a package", "signals"))))
        assertNull(AmbientPreferencesContract.parsePolicy(JSONObject().put("revision", -1).put("modes", JSONObject())))
        assertEquals(
            AmbientPolicy(1, emptyMap()),
            AmbientPreferencesContract.parsePolicy(JSONObject().put("revision", 1).put("modes", JSONObject().put("com.example.app", "dim"))),
        )
    }
}
