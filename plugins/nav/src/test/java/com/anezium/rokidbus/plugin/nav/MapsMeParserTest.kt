package com.anezium.rokidbus.plugin.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** maps.me 17.12.72038 Google release, API 36.1 emulator, Paris driving route. */
class MapsMeParserTest {
    private val driving = NavNotification(
        packageName = "com.mapswithme.maps.pro",
        channelId = "ActiveNavigationChannel",
        category = "navigation",
        ongoing = true,
        title = "430 m",
        text = "Rue de Lobau",
    )

    @Test
    fun `captured background driving reads distance and street but no bitmap or ETA`() {
        val result = MapsMeParser.parse(driving)!!
        assertEquals("430 m", result.primary)
        assertEquals("Rue de Lobau", result.secondary)
        assertEquals("route", result.glyph)
        assertNull(result.eta)
        assertFalse(result.imminent)
        assertFalse(result.arrived)
    }

    @Test
    fun `French capture uses the same metric fields and a quiet distance update`() {
        val first = MapsMeParser.parse(driving)!!
        val french = MapsMeParser.parse(driving.copy(title = "150 m"))!!
        assertEquals("150 m", french.primary)
        assertEquals("Rue de Lobau", french.secondary)
        assertEquals(first.stepKey, french.stepKey)
        assertEquals("route", french.glyph)
        assertFalse(french.imminent)
    }

    @Test
    fun `street names and short distances cannot invent turns or arrival`() {
        val result = MapsMeParser.parse(driving.copy(title = "0 m", text = "Turn Right Street"))!!
        assertEquals("route", result.glyph)
        assertEquals("Turn Right Street", result.secondary)
        assertFalse(result.imminent)
        assertFalse(result.arrived)
        assertNull(MapsMeParser.parse(driving.copy(text = ""))!!.secondary)
    }

    @Test
    fun `downloads rerouting other packages and disabled sources are refused`() {
        assertNull(MapsMeParser.parse(driving.copy(channelId = "downloading_notification_channel_id", title = "Maps downloading")))
        assertNull(MapsMeParser.parse(driving.copy(title = "Rebuilding the route...")))
        assertNull(MapsMeParser.parse(driving.copy(title = null)))
        assertNull(MapsMeParser.parse(driving.copy(ongoing = false)))
        assertNull(MapsMeParser.parse(driving.copy(category = null)))
        assertNull(MapsMeParser.parse(driving.copy(packageName = "app.organicmaps")))
        assertNull(MapsMeParser.parse(driving.copy(channelId = "NAVIGATION")))
        assertTrue(NavSwitches().allows(NavSource.MAPS_ME))
        assertFalse(NavSwitches(mapsMe = false).allows(NavSource.MAPS_ME))
        assertFalse(NavSwitches(enabled = false).allows(NavSource.MAPS_ME))
        assertNull(NavSource.of("com.mapswithme.maps"))
    }
}
