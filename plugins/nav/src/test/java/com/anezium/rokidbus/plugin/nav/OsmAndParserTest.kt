package com.anezium.rokidbus.plugin.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-derived cases, plus explicitly marked OsmAnd 5.4.4 emulator captures. */
class OsmAndParserTest {
    private val step = NavNotification(
        packageName = "net.osmand",
        channelId = "osmand_background_service",
        category = "navigation",
        ongoing = true,
        title = "80 m • Turn right and go",
        bigText = "Turn right and go Rue de Rivoli 250 m\n1.2 km • 15 min • 18:23",
    )

    @Test
    fun `captured driving countdown keeps one step and warns once at fifteen metres`() {
        // API 36.1 emulator, Notre-Dame to Hotel de Ville, F-Droid x86 build.
        val planner = NavActivityPlanner()
        val far = OsmAndParser.parse(step.copy(
            packageName = "net.osmand.plus",
            title = "300 m • Turn right and go",
            bigText = "Turn right and go Quai de l'Hôtel de Ville 100 m\n900 m • 7 min • 4:12 PM • 0 km/h",
        ))!!
        val close = OsmAndParser.parse(step.copy(
            packageName = "net.osmand.plus",
            title = "15 m • Turn right and go",
            bigText = "Turn right and go Quai de l'Hôtel de Ville 100 m\n600 m • 5 min • 4:10 PM • 0 km/h",
        ))!!
        assertEquals("Quai de l'Hôtel de Ville", far.secondary)
        assertEquals("4:12 PM", far.eta)
        assertEquals("15 m • Turn right and go · Quai de l'Hôtel de Ville", close.instruction)
        assertEquals(far.stepKey, close.stepKey)
        assertTrue(planner.plan(far) is NavPlan.Start)
        val update = planner.plan(close) as NavPlan.Update
        assertTrue(update.significant)
        assertTrue(update.urgent)
        assertEquals(NavPlan.Unchanged, planner.plan(close))
        val straight = OsmAndParser.parse(step.copy(
            title = "80 m • Head",
            bigText = "Head Rue d'Arcole 250 m\n900 m • 7 min • 4:12 PM • 0 km/h",
        ))!!
        assertEquals("straight", straight.glyph)
        assertEquals("Rue d'Arcole", straight.secondary)
        val french = OsmAndParser.parse(step.copy(
            title = "300 m • Tournez à droite",
            bigText = "Tournez à droite Quai de l'Hôtel de Ville 100 m\n900 m • 7 min • 16:14 • 0 km/h",
        ))!!
        assertEquals("turn-right", french.glyph)
        assertEquals(far.secondary, french.secondary)
        assertEquals("16:14", french.eta)
    }

    @Test
    fun `captured empty off-route maneuver is rejected rather than publishing a false step`() {
        // Captured between real maneuvers; trailing whitespace is significant
        // to this regression. Trimming leaves a title that is not a distance.
        assertNull(OsmAndParser.parse(step.copy(
            title = "0 m • ", bigText = "1.0 km • 8 min • 4:14 PM • 0 km/h",
        )))
        assertNull(OsmAndParser.parse(step.copy(
            title = "0 m • ", bigText = "900 m • 7 min • 16:13 • 0 km/h",
        )))
    }

    @Test
    fun `mixed-language captures do not label an old maneuver sentence as a street`() {
        val left = OsmAndParser.parse(step.copy(
            title = "150 m • Tournez à gauche",
            bigText = "Turn left and go Quai de Gesvres 300 m\n900 m • 8 min • 16:13 • 0 km/h",
        ))!!
        assertEquals("turn-left", left.glyph)
        assertEquals("150 m", left.primary)
        assertEquals("16:13", left.eta)
        assertEquals("Tournez à gauche", left.secondary)
        assertEquals("150 m • Tournez à gauche", left.instruction)
        val slight = OsmAndParser.parse(step.copy(
            title = "200 m • Tournez légèrement vers la gauche et continuez",
            bigText = "Turn slightly left and go Rue de Lobau 20 m\n700 m • 6 min • 16:11 • 0 km/h",
        ))!!
        assertEquals("turn-slight-left", slight.glyph)
        assertFalse(slight.imminent)
        assertFalse(slight.instruction!!.contains("20 m"))
        assertFalse(slight.instruction.contains("Turn slightly"))
    }

    @Test
    fun `read the next turn distance street and ETA rather than following leg or total distance`() {
        val guidance = OsmAndParser.parse(step)!!
        assertEquals("80 m", guidance.primary)
        assertEquals("turn-right", guidance.glyph)
        assertEquals("Rue de Rivoli", guidance.secondary)
        assertEquals("18:23", guidance.eta)
        assertFalse(guidance.imminent)
        assertFalse(guidance.arrived)
        assertNull(guidance.progressPercent)
        val later = OsmAndParser.parse(step.copy(title = "30 m • Turn right and go"))!!
        assertTrue(later.imminent)
        assertEquals(guidance.stepKey, later.stepKey)
    }

    @Test
    fun `French big text handles no-break spaces intermediate waypoint and speed`() {
        val guidance = OsmAndParser.parse(step.copy(
            packageName = "net.osmand.plus",
            title = "30\u00a0m • Tournez à gauche",
            bigText = "Tournez à gauche Rue de Rivoli 250\u202fm\n500 m • Louvre\n1,2 km • 15 min • 18:23 • 4 km/h",
        ))!!
        assertEquals("Rue de Rivoli", guidance.secondary)
        assertEquals("18:23", guidance.eta)
        assertEquals("turn-left", guidance.glyph)
        assertTrue(guidance.imminent)
    }

    @Test
    fun `source phrases distinguish slight sharp and straight in English French and Korean`() {
        val phrases = mapOf(
            "Turn slightly right and go" to "turn-slight-right",
            "Turn sharply left and go" to "turn-sharp-left",
            "Tournez légèrement vers la droite et continuez" to "turn-slight-right",
            "Prenez le virage serré à gauche et continuez" to "turn-sharp-left",
            "약한 우회전 후 직진" to "turn-slight-right",
            "급한 좌회전 후 직진" to "turn-sharp-left",
            "Head" to "straight",
            "Avancez" to "straight",
            "전방" to "straight",
        )
        for ((phrase, glyph) in phrases) {
            val guidance = OsmAndParser.parse(step.copy(title = "30 m • $phrase", bigText = "1.2 km • 15 min • 18:23"))!!
            assertEquals(phrase, glyph, guidance.glyph)
            if (glyph == "straight") assertFalse(guidance.imminent)
        }
    }

    @Test
    fun `unknown maneuvers and zero distance cannot manufacture an arrow or arrival`() {
        for (title in listOf("0 m", "50 m • Prenez la sortie 3", "50 m • Take 3 exit and go", "50 m • Unknown")) {
            val guidance = OsmAndParser.parse(step.copy(title = title, bigText = "1.2 km • 15 min • 18:23"))!!
            assertEquals("route", guidance.glyph)
            assertFalse(guidance.arrived)
            assertFalse(guidance.imminent)
        }
    }

    @Test
    fun `missing description and twelve-hour ETA are allowed without treating duration as a clock`() {
        val guidance = OsmAndParser.parse(step.copy(bigText = "1.2 km • 1 h 15 min • 6:23 PM"))!!
        assertEquals("6:23 PM", guidance.eta)
        assertEquals("Turn right and go", guidance.secondary)
        assertNull(OsmAndParser.parse(step.copy(bigText = "1.2 km • 1:15 • unknown"))!!.eta)
    }

    @Test
    fun `both editions use one switch and other packages remain unknown`() {
        assertEquals(NavSource.OSMAND, NavSource.of("net.osmand.plus"))
        assertTrue(NavSwitches().allows(NavSource.OSMAND))
        assertFalse(NavSwitches(osmand = false).allows(NavSource.OSMAND))
        assertFalse(NavSwitches(enabled = false).allows(NavSource.OSMAND))
        assertNull(NavSource.of("net.osmand.other"))
    }

    @Test
    fun `paused recalculating recording downloads and malformed notifications are refused`() {
        assertNull(OsmAndParser.parse(step.copy(ongoing = false)))
        assertNull(OsmAndParser.parse(step.copy(title = "Navigation", bigText = "Paused")))
        assertNull(OsmAndParser.parse(step.copy(title = "Navigation", bigText = "Route calculation...")))
        assertNull(OsmAndParser.parse(step.copy(category = "status")))
        assertNull(OsmAndParser.parse(step.copy(channelId = "downloads")))
        assertNull(OsmAndParser.parse(step.copy(channelId = null)))
        assertNull(OsmAndParser.parse(step.copy(packageName = "com.example.other")))
        assertNull(OsmAndParser.parse(step.copy(bigText = "")))
        assertNull(OsmAndParser.parse(step.copy(bigText = "Downloading map")))
    }
}
