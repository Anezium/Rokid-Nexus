package com.anezium.rokidbus.plugin.lens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayEmissionPolicyTest {
    @Test
    fun `empty and normalized unchanged OCR output are suppressed`() {
        val policy = OverlayEmissionPolicy()
        assertFalse(policy.shouldEmit("  \n "))
        assertTrue(policy.shouldEmit("Exit   Platform 2"))
        assertFalse(policy.shouldEmit(" Exit Platform 2 "))
    }

    @Test
    fun `changed OCR emits and reset permits the same content in a new session`() {
        val policy = OverlayEmissionPolicy()
        assertTrue(policy.shouldEmit("Exit"))
        assertTrue(policy.shouldEmit("Platform"))
        assertFalse(policy.shouldEmit("Platform"))
        policy.reset()
        assertTrue(policy.shouldEmit("Platform"))
    }
}
