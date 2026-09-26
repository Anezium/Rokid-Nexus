package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputImeProvisionerTest {
    private val nexus = "com.anezium.rokidbus.glasses/.NexusRemoteInputMethodService"

    @Test
    fun `adds Nexus without removing enabled keyboards`() {
        assertEquals(
            "com.example/.Ime:$nexus",
            RemoteInputImeProvisioner.enabledMethodsWithNexus("com.example/.Ime", nexus),
        )
    }

    @Test
    fun `does not duplicate Nexus`() {
        assertEquals(
            "com.example/.Ime:$nexus",
            RemoteInputImeProvisioner.enabledMethodsWithNexus("com.example/.Ime:$nexus", nexus),
        )
    }

    @Test
    fun `selects Nexus only when no keyboard is selected`() {
        assertTrue(RemoteInputImeProvisioner.shouldSelectNexus(null, nexus))
        assertTrue(RemoteInputImeProvisioner.shouldSelectNexus("", nexus))
        assertTrue(RemoteInputImeProvisioner.shouldSelectNexus(nexus, nexus))
        assertFalse(RemoteInputImeProvisioner.shouldSelectNexus("com.example/.Ime", nexus))
    }

    @Test
    fun `recognises Nexus in both setting forms`() {
        val qualified = "com.anezium.rokidbus.glasses/com.anezium.rokidbus.glasses.NexusRemoteInputMethodService"

        assertTrue(RemoteInputImeProvisioner.isNexus(nexus, nexus))
        assertTrue(RemoteInputImeProvisioner.isNexus(qualified, nexus))
        assertFalse(RemoteInputImeProvisioner.isNexus(ROKID, nexus))
        assertFalse(RemoteInputImeProvisioner.isNexus(null, nexus))
        assertFalse(RemoteInputImeProvisioner.isNexus("", nexus))
    }

    @Test
    fun `names the selected keyboard's package`() {
        assertEquals("com.rokid.os.sprite.assistserver", RemoteInputImeProvisioner.methodPackage(ROKID))
        assertNull(RemoteInputImeProvisioner.methodPackage(null))
        assertNull(RemoteInputImeProvisioner.methodPackage("garbage"))
    }

    private companion object {
        const val ROKID = "com.rokid.os.sprite.assistserver/.RemoteInputMethodService"
    }
}
