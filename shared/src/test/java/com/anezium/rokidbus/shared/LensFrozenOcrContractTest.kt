package com.anezium.rokidbus.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensFrozenOcrContractTest {
    @Test
    fun `protected lens namespace includes child paths`() {
        assertTrue(BusPaths.isProtectedLensPath(BusPaths.LENS_LINK_OFFER))
        assertTrue(BusPaths.isProtectedLensPath("${BusPaths.LENS_LINK_OFFER}/future"))
        assertTrue(BusPaths.isProtectedLensPath("${BusPaths.LENS_FROZEN_OCR_RESULT}/future"))
        assertTrue(BusPaths.isProtectedLensPath(BusPaths.GLASSES_WIFI_REQUEST))
        assertFalse(BusPaths.isProtectedLensPath(BusPaths.LENS_TRANSLATE_REQUEST))
    }

    @Test
    fun `frozen OCR result round trips with block and line structure`() {
        val result = LensFrozenOcrResult(
            freezeId = 42L,
            stage = LensWireContract.FROZEN_STAGE_FAST,
            script = LensWireContract.RECOGNIZER_MODE_JAPANESE,
            blocks = listOf(
                LensFrozenOcrBlock(
                    listOf(
                        LensFrozenOcrLine("first", intArrayOf(1, 2, 30, 40)),
                        LensFrozenOcrLine("second", intArrayOf(3, 44, 35, 70)),
                    ),
                ),
            ),
        )

        val json = LensWireContract.frozenOcrResultToJson(result)
        val decoded = LensWireContract.parseFrozenOcrResult(json)

        assertNotNull(decoded)
        assertEquals(result.freezeId, decoded!!.freezeId)
        assertEquals(result.stage, decoded.stage)
        assertEquals(result.script, decoded.script)
        assertEquals("second", decoded.blocks.single().lines[1].text)
        assertArrayEquals(intArrayOf(3, 44, 35, 70), decoded.blocks.single().lines[1].box)
        assertTrue(json.toString().toByteArray().size < LensWireContract.MAX_FROZEN_OCR_RESULT_BYTES)
    }

    @Test
    fun `serializer caps lines and characters`() {
        val blocks = listOf(
            LensFrozenOcrBlock(
                List(LensWireContract.MAX_FROZEN_OCR_TOTAL_LINES + 20) {
                    LensFrozenOcrLine("x".repeat(500), intArrayOf(0, it, 10, it + 1))
                },
            ),
        )
        val json = LensWireContract.frozenOcrResultToJson(
            LensFrozenOcrResult(
                1L,
                LensWireContract.FROZEN_STAGE_HD,
                LensWireContract.RECOGNIZER_MODE_LATIN,
                blocks,
            ),
        )
        val lines = json.getJSONArray("blocks").getJSONObject(0).getJSONArray("lines")
        assertEquals(LensWireContract.MAX_FROZEN_OCR_LINES_PER_BLOCK, lines.length())
        assertEquals(LensWireContract.MAX_FROZEN_OCR_LINE_CHARS, lines.getJSONObject(0).getString("text").length)
    }
}
