package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantInputModeTest {
    @Test
    fun `a missing or unknown stored value reads as voice only`() {
        assertEquals(AssistantInputMode.VOICE_ONLY, AssistantInputMode.fromWire(null))
        assertEquals(AssistantInputMode.VOICE_ONLY, AssistantInputMode.fromWire(""))
        assertEquals(AssistantInputMode.VOICE_ONLY, AssistantInputMode.fromWire("keyboard"))
        AssistantInputMode.entries.forEach { mode ->
            assertEquals(mode, AssistantInputMode.fromWire(mode.wireValue))
        }
    }

    @Test
    fun `an ask during a capture switches to typing only once Type first is chosen`() {
        AssistantInputMode.entries.forEach { mode ->
            assertEquals(AssistantAskAction.START, askAction(captureActive = false, mode = mode))
        }
        // The capture predates the switch: the next ask stops it and opens the field.
        assertEquals(
            AssistantAskAction.STOP_AND_TYPE,
            askAction(captureActive = true, mode = AssistantInputMode.TYPE_FIRST),
        )
        assertEquals(
            AssistantAskAction.KEEP_LISTENING,
            askAction(captureActive = true, mode = AssistantInputMode.VOICE_ONLY),
        )
        assertEquals(
            AssistantAskAction.KEEP_LISTENING,
            askAction(captureActive = true, mode = AssistantInputMode.VOICE_AND_TYPE),
        )
    }

    @Test
    fun `glasses that cannot take a typed field keep every choice on voice`() {
        AssistantInputMode.entries.forEach { mode ->
            assertEquals(AssistantInputMode.VOICE_ONLY, effectiveInputMode(mode, canType = false))
            assertEquals(mode, effectiveInputMode(mode, canType = true))
        }
    }
}
