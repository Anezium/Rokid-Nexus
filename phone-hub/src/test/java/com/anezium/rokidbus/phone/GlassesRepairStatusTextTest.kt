package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.GlassesRepairContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesRepairStatusTextTest {
    private val unknown = GlassesRepairStatusText.message("quantum_healed")

    @Test
    fun `every verdict the glasses can send has its own text`() {
        val texts = GlassesRepairContract.RESULTS.map(GlassesRepairStatusText::message)
        texts.forEach { assertTrue(it != unknown) }
        assertEquals(texts.size, texts.toSet().size)
        assertEquals(unknown, GlassesRepairStatusText.message(null))
    }

    @Test
    fun `failure texts point at an entry that exists`() {
        listOf(
            GlassesRepairContract.RESULT_ARM_FAILED,
            GlassesRepairContract.RESULT_PAIRING_REQUIRED,
        ).forEach { result ->
            val text = GlassesRepairStatusText.message(result)
            assertTrue(text, text.contains("Re-pair glasses"))
            assertFalse(text, text.contains("redo the glasses setup"))
        }
    }

    @Test
    fun `a re-pair answered by glasses that ignored the mode says to update them`() {
        // What glasses from before the pair mode send back: a plain repair's verdict, no mode.
        listOf(
            GlassesRepairContract.RESULT_ALREADY_HEALTHY,
            GlassesRepairContract.RESULT_REPAIRED,
            GlassesRepairContract.RESULT_ARM_FAILED,
        ).forEach { result ->
            val legacyReply = GlassesRepairContract.replyToJson(result)
            assertEquals(GlassesRepairStatusText.PAIR_UNSUPPORTED, GlassesRepairStatusText.pairMessage(legacyReply))
            assertFalse(GlassesRepairStatusText.pairIsGood(legacyReply))
        }
        assertTrue(GlassesRepairStatusText.PAIR_UNSUPPORTED.contains("Update Nexus on the glasses"))
    }

    @Test
    fun `a re-pair the glasses ran reads as its verdict`() {
        val pending = GlassesRepairContract.replyToJson(
            GlassesRepairContract.RESULT_PAIRING_PENDING,
            GlassesRepairContract.MODE_PAIR,
        )
        // Leaving the Re-pair screen ends the pairing, so the owner is told to stay on it.
        assertEquals(
            GlassesRepairStatusText.PAIR_PENDING_STAY,
            GlassesRepairStatusText.pairMessage(pending),
        )
        assertTrue(GlassesRepairStatusText.pairIsGood(pending))

        val busy = GlassesRepairContract.replyToJson(
            GlassesRepairContract.RESULT_BUSY,
            GlassesRepairContract.MODE_PAIR,
        )
        assertEquals(
            GlassesRepairStatusText.message(GlassesRepairContract.RESULT_BUSY),
            GlassesRepairStatusText.pairMessage(busy),
        )
        assertFalse(GlassesRepairStatusText.pairIsGood(busy))
        assertEquals(GlassesRepairStatusText.message(null), GlassesRepairStatusText.pairMessage(null))
    }

    @Test
    fun `only a running helper or a pairing underway reads as good`() {
        assertTrue(GlassesRepairStatusText.isGood(GlassesRepairContract.RESULT_REPAIRED))
        assertTrue(GlassesRepairStatusText.isGood(GlassesRepairContract.RESULT_ALREADY_HEALTHY))
        assertTrue(GlassesRepairStatusText.isGood(GlassesRepairContract.RESULT_PAIRING_PENDING))
        listOf(
            GlassesRepairContract.RESULT_PARTIAL,
            GlassesRepairContract.RESULT_PAIRING_REQUIRED,
            GlassesRepairContract.RESULT_ARM_FAILED,
            GlassesRepairContract.RESULT_WIFI_UNAVAILABLE,
            GlassesRepairContract.RESULT_BUSY,
            null,
        ).forEach { assertFalse(GlassesRepairStatusText.isGood(it)) }
    }
}
