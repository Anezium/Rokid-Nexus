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
