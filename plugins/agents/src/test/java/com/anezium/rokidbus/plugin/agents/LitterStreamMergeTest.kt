package com.anezium.rokidbus.plugin.agents

import org.junit.Assert.*
import org.junit.Test

class LitterStreamMergeTest {
    @Test fun `snapshot and pre-hydration fragment preserve prefix without repeated overlap`() {
        assertEquals("Hello world", mergeLitterFragment("Hello ", "world"))
        assertEquals("Hello world", mergeLitterFragment("Hello wo", "world"))
        assertEquals("Hello world", mergeLitterFragment("Hello world", "world"))
        assertEquals("abababc", mergeLitterFragment("ababa", "ababc"))
        assertEquals("Hello", mergeLitterFragment("Hello", ""))
        assertEquals("Hello", mergeLitterFragment("", "Hello"))
    }

    @Test fun `large repeated prefixes are bounded and preserve the latest text`() {
        val text = "a".repeat(LitterProtocol.MAX_TEXT)
        assertEquals(text.drop(1) + "b", mergeLitterFragment(text, text.drop(1) + "b"))
    }
}
