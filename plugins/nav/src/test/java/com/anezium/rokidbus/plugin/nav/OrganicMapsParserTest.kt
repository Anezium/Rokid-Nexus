package com.anezium.rokidbus.plugin.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Fields from Organic Maps NavigationService, with public Paris street names. */
class OrganicMapsParserTest {
    private val step = NavNotification(
        packageName = "app.organicmaps",
        channelId = "NAVIGATION",
        category = "navigation",
        ongoing = true,
        title = "250 m",
        text = "Rue de Rivoli",
    )

    @Test
    fun `captured English walk and French drive retain only the exposed fields`() {
        val walking = OrganicMapsParser.parse(step.copy(
            packageName = "app.organicmaps.web", title = "69\u00a0m", text = "",
        ))!!
        assertEquals("69 m", walking.primary)
        assertNull(walking.secondary)
        val driving = OrganicMapsParser.parse(step.copy(
            packageName = "app.organicmaps.web", title = "380\u00a0m", text = "Quai de l'Hôtel de Ville",
        ))!!
        assertEquals("380 m", driving.primary)
        assertEquals("Quai de l'Hôtel de Ville", driving.secondary)
        assertEquals("route", driving.glyph)
        assertNull(driving.eta)
        // During GPS playback, zero metres on Rue de Rivoli was followed by
        // another leg. It was not an arrival notification.
        val zero = OrganicMapsParser.parse(step.copy(title = "0\u00a0m"))!!
        assertFalse(zero.arrived)
        assertFalse(zero.imminent)
    }

    @Test
    fun `English and French metric distances keep the street and neutral glyph`() {
        for (distance in listOf("250 m", "1.2 km", "1,2\u00a0km", "30\u202fm")) {
            val guidance = OrganicMapsParser.parse(step.copy(title = distance))!!
            assertEquals(NavText.spaces(distance), guidance.primary)
            assertEquals("Rue de Rivoli", guidance.secondary)
            assertEquals("route", guidance.glyph)
            assertNull(guidance.eta)
            assertFalse(guidance.imminent)
            assertFalse(guidance.arrived)
        }
    }

    @Test
    fun `a street name cannot supply a maneuver or arrival`() {
        for (street in listOf("Turn Left Street", "Destination Avenue", "Continue Lane")) {
            val guidance = OrganicMapsParser.parse(step.copy(title = "0 m", text = street))!!
            assertEquals("route", guidance.glyph)
            assertFalse(guidance.arrived)
        }
        assertNull(OrganicMapsParser.parse(step.copy(text = ""))!!.secondary)
    }

    @Test
    fun `the GitHub distribution shares the source and switch`() {
        assertEquals(NavSource.ORGANIC_MAPS, NavSource.of("app.organicmaps.web"))
        assertEquals(NavSource.ORGANIC_MAPS, OrganicMapsParser.parse(step.copy(packageName = "app.organicmaps.web"))!!.source)
        assertFalse(NavSwitches(organicMaps = false).allows(NavSource.ORGANIC_MAPS))
        assertFalse(NavSwitches(enabled = false).allows(NavSource.ORGANIC_MAPS))
        assertNull(NavSource.of("app.organicmaps.fake"))
    }

    @Test
    fun `distance countdown is quiet and a changed street is a new step`() {
        val first = OrganicMapsParser.parse(step)!!
        assertEquals(first.stepKey, OrganicMapsParser.parse(step.copy(title = "30 m"))!!.stepKey)
        assertEquals("ORGANIC_MAPS|Quai de Montebello", OrganicMapsParser.parse(step.copy(text = "Quai de Montebello"))!!.stepKey)
    }

    @Test
    fun `startup downloads and unrelated packages are refused`() {
        assertNull(OrganicMapsParser.parse(step.copy(title = null)))
        assertNull(OrganicMapsParser.parse(step.copy(title = "Downloading Paris")))
        assertNull(OrganicMapsParser.parse(step.copy(channelId = "downloader")))
        assertNull(OrganicMapsParser.parse(step.copy(channelId = null)))
        assertNull(OrganicMapsParser.parse(step.copy(category = "progress")))
        assertNull(OrganicMapsParser.parse(step.copy(ongoing = false)))
        assertNull(OrganicMapsParser.parse(step.copy(packageName = "com.mapswithme.maps.pro")))
    }
}
