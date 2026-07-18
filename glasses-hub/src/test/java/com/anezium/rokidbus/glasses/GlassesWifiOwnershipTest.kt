package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesWifiOwnershipTest {
    @Test
    fun acquireWhenWifiIsOffEnablesAndTakesOwnership() {
        val requested = mutableListOf<Boolean>()
        val result = GlassesWifiOwnership().handleRequest(
            enabled = true,
            wifiCurrentlyEnabled = false,
        ) {
            requested += it
            true
        }

        assertEquals(listOf(true), requested)
        assertTrue(result.applied)
        assertTrue(result.hubOwned)
    }

    @Test
    fun acquireWhenWifiIsAlreadyOnDoesNothingAndDoesNotOwnIt() {
        val requested = mutableListOf<Boolean>()
        val result = GlassesWifiOwnership().handleRequest(
            enabled = true,
            wifiCurrentlyEnabled = true,
        ) {
            requested += it
            true
        }

        assertTrue(requested.isEmpty())
        assertFalse(result.applied)
        assertFalse(result.hubOwned)
    }

    @Test
    fun releaseWhenOwnedDisablesAndClearsOwnership() {
        val ownership = GlassesWifiOwnership()
        ownership.handleRequest(enabled = true, wifiCurrentlyEnabled = false) { true }
        val requested = mutableListOf<Boolean>()

        val result = ownership.handleRequest(
            enabled = false,
            wifiCurrentlyEnabled = true,
        ) {
            requested += it
            true
        }

        assertEquals(listOf(false), requested)
        assertTrue(result.applied)
        assertFalse(result.hubOwned)
    }

    @Test
    fun releaseWhenNotOwnedDoesNothing() {
        val requested = mutableListOf<Boolean>()
        val result = GlassesWifiOwnership().handleRequest(
            enabled = false,
            wifiCurrentlyEnabled = true,
        ) {
            requested += it
            true
        }

        assertTrue(requested.isEmpty())
        assertFalse(result.applied)
        assertFalse(result.hubOwned)
    }

    @Test
    fun confirmedAsynchronousEnableTakesOwnership() {
        val ownership = GlassesWifiOwnership()

        ownership.markEnabledByHub()

        assertTrue(ownership.isHubOwned())
    }
}
