package com.anezium.rokidbus.plugin.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMapsParserTest {
    /** Shaped on a real walking capture (Maps 26.38, One UI, French); the street is swapped. */
    private val walking = NavNotification(
        packageName = NavSource.GOOGLE_MAPS.packageName,
        category = "navigation",
        ongoing = true,
        title = "80 m · Prendre à droite sur Rue de Rivoli",
        subText = "Arrivée à 22:50",
        shortCriticalText = "80 m",
        progress = 27,
        progressMax = 25965,
        actions = listOf("Quitter la navigation"),
        nowBarPrimary = "80 m",
        nowBarSecondary = "Rue de Rivoli",
    )

    @Test
    fun `a walking step reads as distance, arrow, street and arrival time`() {
        val guidance = GoogleMapsParser.parse(walking)!!

        assertEquals("turn-right", guidance.glyph)
        assertEquals("80 m", guidance.primary)
        assertEquals("Rue de Rivoli", guidance.secondary)
        assertEquals("22:50", guidance.eta)
        assertEquals(listOf("Prendre à droite"), guidance.detail)
        assertNull(guidance.progressPercent)
        assertFalse(guidance.imminent)
        assertFalse(guidance.arrived)
    }

    @Test
    fun `the street comes from the instruction when the phone has no Now Bar copy`() {
        val guidance = GoogleMapsParser.parse(walking.copy(nowBarPrimary = null, nowBarSecondary = null))!!

        assertEquals("Rue de Rivoli", guidance.secondary)
    }

    @Test
    fun `English instructions and twelve-hour arrival times read the same way`() {
        val guidance = GoogleMapsParser.parse(
            walking.copy(
                title = "0.2 mi · Turn left onto Market St",
                subText = "10:05 pm arrival",
                shortCriticalText = "0.2 mi",
                nowBarSecondary = null,
            ),
        )!!

        assertEquals("turn-left", guidance.glyph)
        assertEquals("0.2 mi", guidance.primary)
        assertEquals("Market St", guidance.secondary)
        assertEquals("10:05 PM", guidance.eta)
    }

    @Test
    fun `slight, sharp, u-turn and roundabout win over the plain turn they contain`() {
        fun glyphOf(instruction: String) =
            GoogleMapsParser.parse(walking.copy(title = "50 m · $instruction", nowBarSecondary = null))!!.glyph

        assertEquals("turn-slight-right", glyphOf("Tournez légèrement à droite sur Rue X"))
        assertEquals("turn-slight-left", glyphOf("Keep left at the fork"))
        assertEquals("turn-sharp-left", glyphOf("Tournez fortement à gauche"))
        assertEquals("u-turn", glyphOf("Faites demi-tour"))
        assertEquals("roundabout", glyphOf("Au rond-point, prenez la 2e sortie"))
        assertEquals("straight", glyphOf("Continuez sur Bd Saint-Michel"))
    }

    @Test
    fun `an instruction it cannot place gets the neutral route mark, not a guessed arrow`() {
        val guidance = GoogleMapsParser.parse(walking.copy(title = "300 m · Empruntez l'escalier"))!!

        assertEquals(NavText.ROUTE_GLYPH, guidance.glyph)
    }

    @Test
    fun `a close turn is imminent, a close straight line is not`() {
        assertTrue(GoogleMapsParser.parse(walking.copy(title = "30 m · Prendre à droite sur Rue de Rivoli"))!!.imminent)
        assertFalse(GoogleMapsParser.parse(walking.copy(title = "30 m · Continuez sur Rue de Rivoli"))!!.imminent)
        assertTrue(GoogleMapsParser.parse(walking.copy(title = "100 ft · Turn right onto Main St"))!!.imminent)
    }

    @Test
    fun `arrival without a distance shows the plugin's arrived label`() {
        val guidance = GoogleMapsParser.parse(
            walking.copy(title = "Vous êtes arrivé", shortCriticalText = null, nowBarSecondary = null),
            NavLabels(arrived = "Arrivé"),
        )!!

        assertEquals("arrive", guidance.glyph)
        assertEquals("Arrivé", guidance.primary)
        assertTrue(guidance.arrived)
    }

    @Test
    fun `the opening head-toward step has no distance, so Maps' own verb leads`() {
        val guidance = GoogleMapsParser.parse(
            walking.copy(
                title = "Aller vers Rue de Rivoli/Rue du Louvre",
                shortCriticalText = "",
                nowBarPrimary = null,
                nowBarSecondary = null,
            ),
        )!!

        assertEquals("straight", guidance.glyph)
        assertEquals("Aller", guidance.primary)
        assertEquals("Rue de Rivoli/Rue du Louvre", guidance.secondary)
        assertEquals(emptyList<String>(), guidance.detail)
        assertFalse(guidance.imminent)
    }

    @Test
    fun `long streets and instructions are cut to the activity limits`() {
        val guidance = GoogleMapsParser.parse(
            walking.copy(
                title = "1,2 km · Prendre à droite sur Avenue du Maréchal de Lattre de Tassigny",
                nowBarSecondary = null,
            ),
        )!!

        assertEquals("1,2 km", guidance.primary)
        assertTrue(guidance.secondary!!.length <= 28)
        assertTrue(guidance.secondary!!.endsWith("…"))
        assertTrue(guidance.detail.single().length <= 32)
    }

    /** Shaped on a real transit capture (walking leg); the stop is swapped. */
    private val transitWalk = NavNotification(
        packageName = NavSource.GOOGLE_MAPS.packageName,
        category = "navigation",
        ongoing = true,
        title = "Marchez 3 min (250 m)",
        text = "Châtelet · Départ à 17:36",
        subText = "Arrivée à 18:51",
        shortCriticalText = "3 min",
        progress = 0,
        progressMax = 100,
        actions = listOf("Arrêter le trajet"),
    )

    @Test
    fun `a transit walking leg shows the minutes, the stop, the departure and the distance`() {
        val guidance = GoogleMapsParser.parse(transitWalk)!!

        assertEquals("walk", guidance.glyph)
        assertEquals("3 min", guidance.primary)
        assertEquals("Châtelet", guidance.secondary)
        assertEquals(listOf("Départ à 17:36", "250 m"), guidance.detail)
        assertEquals("18:51", guidance.eta)
        assertNull(guidance.badge)
        assertNull(guidance.progressPercent)
    }

    @Test
    fun `a transit leg stays one step while its minutes tick down`() {
        val first = GoogleMapsParser.parse(transitWalk)!!
        val later = GoogleMapsParser.parse(
            transitWalk.copy(title = "Marchez 2 min (150 m)", shortCriticalText = "2 min"),
        )!!

        assertEquals(first.stepKey, later.stepKey)
        assertEquals("2 min", later.primary)
    }

    @Test
    fun `a ride counts its stops, names its vehicle and line, and warns on the last stop`() {
        // The ride wording is assumed from the walking leg's shape, not captured.
        val ride = GoogleMapsParser.parse(
            transitWalk.copy(
                title = "Descendez dans 3 arrêts",
                text = "Luxembourg · Bus 38",
                shortCriticalText = null,
            ),
        )!!

        assertEquals("bus", ride.glyph)
        assertEquals("38", ride.badge)
        assertEquals("3 arrêts", ride.primary)
        assertEquals("Luxembourg", ride.secondary)
        assertFalse(ride.imminent)
        assertTrue(
            GoogleMapsParser.parse(
                transitWalk.copy(title = "Descendez au prochain arrêt", text = "Luxembourg · RER B"),
            )!!.imminent,
        )
    }

    @Test
    fun `a transit step it does not know keeps Maps' words under the route mark`() {
        val guidance = GoogleMapsParser.parse(
            transitWalk.copy(title = "Correspondance", text = "Gare du Nord", shortCriticalText = "6 min"),
        )!!

        assertEquals(NavText.ROUTE_GLYPH, guidance.glyph)
        assertEquals("6 min", guidance.primary)
        assertEquals("Gare du Nord", guidance.secondary)
        assertEquals(listOf("Correspondance"), guidance.detail)
    }

    @Test
    fun `anything that is not live Maps guidance is refused`() {
        assertNull(GoogleMapsParser.parse(walking.copy(category = "status")))
        assertNull(GoogleMapsParser.parse(walking.copy(ongoing = false)))
        assertNull(GoogleMapsParser.parse(walking.copy(title = null)))
        assertNull(GoogleMapsParser.parse(walking.copy(packageName = "com.example.fake")))
        // No distance and not an arrival: nothing trustworthy to put first.
        assertNull(GoogleMapsParser.parse(walking.copy(title = "Recherche du GPS…", shortCriticalText = null)))
        // Transit guidance posts empty before Maps fills it in.
        assertNull(GoogleMapsParser.parse(transitWalk.copy(title = "", text = "")))
    }
}
