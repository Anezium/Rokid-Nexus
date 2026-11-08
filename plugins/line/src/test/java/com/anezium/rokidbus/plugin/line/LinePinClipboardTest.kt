package com.anezium.rokidbus.plugin.line

import org.junit.Assert.assertEquals
import org.junit.Test

/** The clipboard cleanup decision: clear our PIN, leave others' text alone, never give up on an unreadable clipboard. */
class LinePinClipboardTest {
    @Test fun ourPinStillThereIsCleared() {
        assertEquals(PinClipboardStep.CLEAR, PinClipboard.step("123456", "123456"))
    }

    @Test fun someoneElsesTextDropsOwnershipWithoutClearing() {
        assertEquals(PinClipboardStep.DROP, PinClipboard.step("123456", "a shopping list"))
        assertEquals(PinClipboardStep.DROP, PinClipboard.step("123456", ""))
    }

    @Test fun anUnreadableClipboardKeepsTheDutyForLater() {
        // Android returns null both for an empty clipboard and for one the app may not read
        // (no window focus, during a rotation, or while LINE is in front): keep, retry later.
        assertEquals(PinClipboardStep.KEEP, PinClipboard.step("123456", null))
    }
}
