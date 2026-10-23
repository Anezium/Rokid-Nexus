package com.anezium.rokidbus.plugin.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAdmissionTest {
    @Test
    fun `a reply action needs a pending intent and a free form input`() {
        assertFalse(
            NotificationAdmission.isRepliableAction(
                action(hasActionIntent = true, allowsFreeForm = false),
            ),
        )
        assertFalse(
            NotificationAdmission.isRepliableAction(
                action(hasActionIntent = false, allowsFreeForm = true),
            ),
        )
        assertTrue(
            NotificationAdmission.isRepliableAction(
                action(hasActionIntent = true, allowsFreeForm = true),
            ),
        )
    }

    @Test
    fun `the first qualifying reply action wins`() {
        val actions = listOf(
            action(hasActionIntent = true, allowsFreeForm = false),
            action(hasActionIntent = true, allowsFreeForm = true),
            action(hasActionIntent = true, allowsFreeForm = true),
        )

        assertEquals(1, NotificationAdmission.firstRepliableActionIndex(actions))
        assertNull(
            NotificationAdmission.firstRepliableActionIndex(
                listOf(action(hasActionIntent = false, allowsFreeForm = true)),
            ),
        )
    }

    @Test
    fun `the default admits repliable notifications without requiring prior discovery`() {
        val policy = RelayAppPolicy.fromStored(null)
        assertTrue(policy.admits(repliable = true))
        assertFalse(policy.admits(repliable = false))
    }

    @Test
    fun `all notifications is explicit and suppresses ongoing and group summaries`() {
        assertTrue(RelayAppPolicy.ALL.admits(repliable = false))
        assertFalse(RelayAppPolicy.ALL.admits(repliable = false, ongoing = true))
        assertFalse(RelayAppPolicy.ALL.admits(repliable = false, groupSummary = true))
        assertTrue(RelayAppPolicy.ALL.admits(repliable = true, ongoing = true))
    }

    @Test
    fun `off blocks both kinds and invalid stored policies never enable read only`() {
        assertFalse(RelayAppPolicy.OFF.admits(repliable = true))
        assertFalse(RelayAppPolicy.OFF.admits(repliable = false))
        assertFalse(RelayAppPolicy.fromStored("unknown").admits(repliable = false))
    }

    private fun action(hasActionIntent: Boolean, allowsFreeForm: Boolean) =
        NotificationActionShape(
            hasActionIntent = hasActionIntent,
            remoteInputs = listOf(RemoteInputShape(allowsFreeForm = allowsFreeForm)),
        )
}
