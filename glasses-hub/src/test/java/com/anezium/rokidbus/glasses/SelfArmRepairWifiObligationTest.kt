package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmRepairWifiObligationTest {
    private val obligation = SelfArmRepairWifiObligation()

    @Test
    fun `nothing is owed until a repair takes it`() {
        assertEquals(SelfArmRepairWifiObligation.Release(owed = false, droppedBecause = null), obligation.release())
    }

    @Test
    fun `the repair that took it owes it at its end, and only once`() {
        obligation.take()
        obligation.onRadioState(enabled = true)

        assertTrue(obligation.release().owed)
        // Nothing carries over to a later repair or to anyone else.
        assertFalse(obligation.release().owed)
    }

    @Test
    fun `another owner taking the radio drops the claim and says why`() {
        obligation.take()
        obligation.onRadioState(enabled = true)

        assertTrue(obligation.revoke("native_app:com.example.maps"))
        assertFalse(obligation.revoke("camera_request"))

        val release = obligation.release()
        assertFalse(release.owed)
        assertEquals("native_app:com.example.maps", release.droppedBecause)
    }

    @Test
    fun `the radio going off after the repair turned it on drops the claim`() {
        obligation.take()
        obligation.onRadioState(enabled = true)

        assertTrue(obligation.onRadioState(enabled = false))
        // Whoever turns it back on, it is not the repair's to turn off.
        obligation.onRadioState(enabled = true)
        assertEquals("radio_turned_off_elsewhere", obligation.release().droppedBecause)
    }

    @Test
    fun `the radio still off before the repair's enable lands keeps the claim`() {
        obligation.take()
        // The state the watch reports the moment it registers, while the radio is still down.
        assertFalse(obligation.onRadioState(enabled = false))

        assertTrue(obligation.release().owed)
    }

    @Test
    fun `a new repair starts from a clean claim`() {
        obligation.take()
        obligation.revoke("camera_request")
        obligation.release()

        obligation.take()
        val release = obligation.release()
        assertTrue(release.owed)
        assertNull(release.droppedBecause)
    }
}
