package com.anezium.rokidbus.plugin.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavSwitchesTest {
    @Test
    fun `both apps are followed by default`() {
        assertTrue(NavSwitches().allows(NavSource.GOOGLE_MAPS))
        assertTrue(NavSwitches().allows(NavSource.CITYMAPPER))
    }

    @Test
    fun `one app switched off leaves the other on`() {
        val switches = NavSwitches(googleMaps = false)

        assertFalse(switches.allows(NavSource.GOOGLE_MAPS))
        assertTrue(switches.allows(NavSource.CITYMAPPER))
    }

    @Test
    fun `the main switch off wins over both apps`() {
        val switches = NavSwitches(enabled = false, googleMaps = true, citymapper = true)

        assertFalse(switches.allows(NavSource.GOOGLE_MAPS))
        assertFalse(switches.allows(NavSource.CITYMAPPER))
    }
}
