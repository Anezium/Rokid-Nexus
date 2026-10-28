package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageTemplateContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Another app's long place name, from its accepted notification to the two places it is read:
 * the activity line keeps the contract's 28 characters, cut at a word, and the route page's
 * street field keeps the app's whole name within its own 120. A name past 120 is bounded there
 * too; guidance without a retained name falls back to the bounded line. The activity carries
 * the whole name beside its bounded line for the glasses to scroll.
 */
class NavLongNamePageTest {
    private val text = NavPageText(noAccess = "Allow access", off = "Switched off", idle = "Start navigating")
    private val name = "Gare de Fontainebleau, Essonne-Sud"
    private val longer = "Gare de Fontainebleau, Essonne-Sud, Île-de-France, terminus ligne R direction Montereau, via Melun, Bois-le-Roi et Fontainebleau-Avon, quai 2"

    private fun page(guidance: NavGuidance): PageBody.Route {
        val state = NavPageState(guidance, listenerConnected = true, enabled = true, updatedAt = "12:24", extended = true)
        return (NavPages.page(NavPages.MAIN, 1, state, text) as NexusPage.Content).body as PageBody.Route
    }

    private fun assertBoundedLineAndWholeName(guidance: NavGuidance, whole: String) {
        assertEquals(NavText.fit(whole, ActivitySurfaceContract.MAX_SECONDARY_CHARS), guidance.secondary)
        assertTrue(guidance.secondary!!.length <= ActivitySurfaceContract.MAX_SECONDARY_CHARS)
        assertEquals(whole, guidance.street)
        val street = page(guidance).maneuver!!.street
        assertEquals(NavText.fit(whole, PageTemplateContract.MAX_STREET_CHARS), street)
        assertTrue(street!!.length <= PageTemplateContract.MAX_STREET_CHARS)
    }

    @Test
    fun `Maps me keeps the whole name for the page and the bounded line for the activity`() {
        val guidance = MapsMeParser.parse(NavNotification(
            packageName = "com.mapswithme.maps.pro", channelId = "ActiveNavigationChannel", category = "navigation",
            ongoing = true, title = "100 m", text = name,
        ))!!
        assertEquals("Gare de Fontainebleau…", guidance.secondary)
        assertBoundedLineAndWholeName(guidance, name)
        assertEquals(name, page(guidance).maneuver!!.street)
    }

    @Test
    fun `a name past the page's own bound is cut there too, at a word`() {
        val guidance = MapsMeParser.parse(NavNotification(
            packageName = "com.mapswithme.maps.pro", channelId = "ActiveNavigationChannel", category = "navigation",
            ongoing = true, title = "100 m", text = longer,
        ))!!
        assertBoundedLineAndWholeName(guidance, longer)
        assertTrue(page(guidance).maneuver!!.street!!.endsWith("…"))
    }

    @Test
    fun `Google Maps turn by turn keeps the street it names, and its instruction without one`() {
        val base = NavNotification(
            packageName = NavSource.GOOGLE_MAPS.packageName, category = "navigation", ongoing = true,
            title = "80 m · Prendre à droite sur $name", subText = "Arrivée à 22:50", shortCriticalText = "80 m",
            progress = 27, progressMax = 25965, actions = listOf("Quitter la navigation"),
            nowBarPrimary = "80 m", nowBarSecondary = name,
        )
        val named = GoogleMapsParser.parse(base)!!
        assertBoundedLineAndWholeName(named, name)

        val instruction = "Prendre à droite sur $name"
        val fromInstruction = GoogleMapsParser.parse(base.copy(nowBarSecondary = null))!!
        assertEquals(NavText.fit(NavText.street(instruction) ?: instruction, ActivitySurfaceContract.MAX_SECONDARY_CHARS), fromInstruction.secondary)
        assertEquals(NavText.street(instruction) ?: instruction, fromInstruction.street)
        assertEquals(NavText.fit(fromInstruction.street!!, PageTemplateContract.MAX_STREET_CHARS), page(fromInstruction).maneuver!!.street)
    }

    @Test
    fun `Organic Maps and Yandex keep the place they name`() {
        val organic = OrganicMapsParser.parse(NavNotification(
            packageName = "app.organicmaps", channelId = "NAVIGATION", category = "navigation", ongoing = true, title = "250 m", text = name,
        ))!!
        assertBoundedLineAndWholeName(organic, name)
        val yandex = YandexMapsParser.parse(NavNotification(
            packageName = "ru.yandex.yandexmaps", channelId = "foreground_notification", category = "navigation", ongoing = true,
            title = "150 m", text = name, viewTexts = mapOf("titleView" to "150 m", "descriptionView" to name),
        ))!!
        assertBoundedLineAndWholeName(yandex, name)
    }

    @Test
    fun `OsmAnd keeps the street of its step`() {
        val guidance = OsmAndParser.parse(NavNotification(
            packageName = "net.osmand", channelId = "osmand_background_service", category = "navigation", ongoing = true,
            title = "80 m • Turn right and go", bigText = "Turn right and go $name 250 m\n1.2 km • 15 min • 18:23",
        ))!!
        assertEquals(name, guidance.street)
        assertBoundedLineAndWholeName(guidance, name)
    }

    @Test
    fun `guidance that kept no name falls back to its bounded line on the page`() {
        val guidance = NavGuidance(
            source = NavSource.GOOGLE_MAPS, glyph = "turn_right", primary = "300 m",
            secondary = NavText.fit(name, ActivitySurfaceContract.MAX_SECONDARY_CHARS), eta = "12:41", stepKey = "step-1",
        )
        assertNull(guidance.street)
        assertEquals("Gare de Fontainebleau…", page(guidance).maneuver!!.street)
    }
}
