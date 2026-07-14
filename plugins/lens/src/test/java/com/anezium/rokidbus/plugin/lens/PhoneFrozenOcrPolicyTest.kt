package com.anezium.rokidbus.plugin.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneFrozenOcrPolicyTest {
    @Test
    fun `live Japanese script leads the sweep before Latin fallback`() {
        assertEquals(
            listOf(
                PhoneOcrScript.JAPANESE,
                PhoneOcrScript.LATIN,
                PhoneOcrScript.CHINESE,
                PhoneOcrScript.KOREAN,
                PhoneOcrScript.DEVANAGARI,
            ),
            phoneFrozenOcrSweepOrder(listOf(PhoneOcrScript.JAPANESE)),
        )
    }

    @Test
    fun `empty target plan preserves the complete default sweep`() {
        assertEquals(PhoneOcrScript.entries, phoneFrozenOcrSweepOrder(emptyList()))
    }

    @Test
    fun `target plan order is retained without duplicate fallback passes`() {
        assertEquals(
            listOf(
                PhoneOcrScript.KOREAN,
                PhoneOcrScript.JAPANESE,
                PhoneOcrScript.LATIN,
                PhoneOcrScript.CHINESE,
                PhoneOcrScript.DEVANAGARI,
            ),
            phoneFrozenOcrSweepOrder(
                listOf(PhoneOcrScript.KOREAN, PhoneOcrScript.JAPANESE, PhoneOcrScript.KOREAN),
            ),
        )
    }

    @Test
    fun `Japanese accepts Han and kana`() {
        val text = "日本語の文章です"
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
        val text = "नमस्ते दुनिया"
        val stats = phoneOcrTextStats(text, PhoneOcrScript.DEVANAGARI)
        assertTrue(stats.nonSpaceCharacters >= 8)
        assertTrue(stats.matchingRatio >= 0.30)
    }
}
