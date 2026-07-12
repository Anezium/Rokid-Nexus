package com.anezium.rokidbus.plugin.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneFrozenOcrPolicyTest {
    @Test
    fun `Japanese accepts Han and kana`() {
        val text = "æ—¥æœ¬èªžã®æ–‡ç« ã§ã™"
        val stats = phoneOcrTextStats(text, PhoneOcrScript.JAPANESE)
        assertEquals(8, stats.nonSpaceCharacters)
        assertTrue(isStrongPhoneOcrCandidate(text, PhoneOcrScript.JAPANESE))
    }

    @Test
    fun `Korean rejects long Latin false positive`() {
        val text = "recognized latin text"
        assertFalse(isStrongPhoneOcrCandidate(text, PhoneOcrScript.KOREAN))
    }

    @Test
    fun `Devanagari counts combining code points as script characters`() {
        val text = "à¤¨à¤®à¤¸à¥à¤¤à¥‡ à¤¦à¥à¤¨à¤¿à¤¯à¤¾"
        val stats = phoneOcrTextStats(text, PhoneOcrScript.DEVANAGARI)
        assertTrue(stats.nonSpaceCharacters >= 8)
        assertTrue(stats.matchingRatio >= 0.30)
    }
}

