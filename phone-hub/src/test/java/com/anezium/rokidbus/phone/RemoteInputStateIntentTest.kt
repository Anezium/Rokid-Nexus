package com.anezium.rokidbus.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RemoteInputStateIntentTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `a requested keyboard reaches the screen only with an active field`() {
        val requested = RemoteInputTransportState(
            connected = true,
            fieldActive = true,
            sessionId = "relay-reply",
            keyboardRequested = true,
        )

        val parsed = RemoteInputPhoneContract.parseState(
            RemoteInputPhoneContract.stateIntent(context, requested),
        )!!
        assertTrue(parsed.keyboardRequested)
        assertTrue(RemoteInputViewState.from(parsed).keyboardRequested)

        val idle = RemoteInputPhoneContract.parseState(
            RemoteInputPhoneContract.stateIntent(
                context,
                requested.copy(fieldActive = false, sessionId = null),
            ),
        )!!
        assertFalse(idle.keyboardRequested)
        assertFalse(RemoteInputViewState.from(idle).keyboardRequested)
    }

    @Test
    fun `an ordinary field never asks for the keyboard`() {
        val plain = RemoteInputTransportState(connected = true, fieldActive = true, sessionId = "browser")

        val parsed = RemoteInputPhoneContract.parseState(
            RemoteInputPhoneContract.stateIntent(context, plain),
        )!!
        assertFalse(RemoteInputViewState.from(parsed).keyboardRequested)
    }
}
