package com.anezium.rokidbus.glasses

import org.junit.Assert.*
import org.junit.Test

class WidgetEligibilityTest {
    @Test fun eachAttentionOwnerTemporarilySuspendsWithoutChangingContent() {
        fun visible(block: Int) = WidgetEligibility.visible(true, block == 0, block == 1,
            block == 2, block == 3, block == 4, block == 5, block == 6)
        assertTrue(visible(-1))
        for (reason in 0..6) assertFalse("reason=$reason", visible(reason))
        assertTrue(visible(-1))
        assertFalse(WidgetEligibility.visible(false, false, false, false, false, false, false, false))
    }
}
