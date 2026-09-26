package com.anezium.rokidbus.plugin.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The steps below follow a real GO trip (Citymapper 11.59, French), with the
 * stop names swapped for central Paris ones.
 */
class CitymapperParserTest {
    private fun step(
        title: String?,
        subtitle: String? = null,
        prediction: String? = null,
        eta: String? = "Arrivée : 18:23 (83 min)",
    ) = NavNotification(
        packageName = NavSource.CITYMAPPER.packageName,
        channelId = "trip-progress",
        ongoing = true,
        actions = listOf("Terminer", "Préc.", "Suivant"),
        viewTexts = buildMap {
            title?.let { put("notification_title", it) }
            subtitle?.let { put("notification_subtitle", it) }
            prediction?.let { put("notification_prediction", it) }
            eta?.let { put("notification_eta", it) }
        },
    )

    @Test
    fun `walking to a stop shows the walk, the minutes and the stop`() {
        val guidance = CitymapperParser().parse(step("Marcher vers l'arrêt de bus", "Châtelet", "(à 4 min)"))!!

        assertEquals("walk", guidance.glyph)
        assertEquals("4 min", guidance.primary)
        assertEquals("Châtelet", guidance.secondary)
        assertEquals(listOf("Marcher vers l'arrêt de bus"), guidance.detail)
        assertEquals("18:23", guidance.eta)
    }

    @Test
    fun `waiting shows the line as a badge and the next departure first`() {
        val guidance = CitymapperParser().parse(step("Attendre 38 ou 85", "4, 11, 11 min"))!!

        assertEquals("bus", guidance.glyph)
        assertEquals("38", guidance.badge)
        assertEquals("4 min", guidance.primary)
        assertEquals("Attendre 38 ou 85", guidance.secondary)
        assertEquals(listOf("4, 11, 11 min"), guidance.detail)
        assertFalse(guidance.imminent)
    }

    @Test
    fun `a departing bus is imminent and reads now`() {
        val guidance = CitymapperParser().parse(step("Attendre 38 ou 85", "0, 11, 11 min"), NavLabels(now = "Maintenant"))!!

        assertEquals("Maintenant", guidance.primary)
        assertTrue(guidance.imminent)
    }

    @Test
    fun `a ride keeps the waited line and counts its stops down on a track`() {
        val parser = CitymapperParser()
        parser.parse(step("Attendre 38 ou 85", "4, 11, 11 min"))

        val boarding = parser.parse(step("3 arrêts jusqu'à", "Luxembourg"))!!
        assertEquals("bus", boarding.glyph)
        assertEquals("38", boarding.badge)
        assertEquals("3 arrêts", boarding.primary)
        assertEquals("Luxembourg", boarding.secondary)
        assertEquals(NavTrack(count = 4, at = 0, target = 3, label = "Luxembourg"), boarding.track)
        assertFalse(boarding.imminent)

        val later = parser.parse(step("1 arrêt jusqu'à", "Luxembourg"))!!
        assertEquals(NavTrack(count = 4, at = 2, target = 3, label = "Luxembourg"), later.track)
        assertEquals(boarding.stepKey, later.stepKey)
        assertTrue(later.imminent)
    }

    @Test
    fun `a train departure reads time, direction, platform and punctuality`() {
        val guidance = CitymapperParser().parse(step("17:34 ZECO Melun (à l'heure) ", "voie 2"))!!

        assertEquals("train", guidance.glyph)
        assertEquals("17:34", guidance.primary)
        assertEquals("Melun", guidance.secondary)
        assertEquals(listOf("voie 2 · à l'heure"), guidance.detail)
    }

    @Test
    fun `a ride after a train departure is a train ride`() {
        val parser = CitymapperParser()
        parser.parse(step("17:34 ZECO Melun (à l'heure) ", "voie 2"))

        val ride = parser.parse(step("6 arrêts jusqu'à", "Gare du Nord"))!!

        assertEquals("train", ride.glyph)
        assertNull(ride.badge)
        assertEquals(NavTrack(count = 7, at = 0, target = 6, label = "Gare du Nord"), ride.track)
    }

    @Test
    fun `the next-stop warning is imminent`() {
        val guidance = CitymapperParser().parse(step("Descendre au prochain arrêt", "Luxembourg"))!!

        assertTrue(guidance.imminent)
        assertEquals(NavTrack(count = 2, at = 0, target = 1, label = "Luxembourg"), guidance.track)
    }

    @Test
    fun `a step it does not know keeps Citymapper's words and the time left`() {
        val guidance = CitymapperParser().parse(step("Prenez la sortie 3"))!!

        assertEquals(NavText.ROUTE_GLYPH, guidance.glyph)
        assertEquals("83 min", guidance.primary)
        assertEquals("Prenez la sortie 3", guidance.secondary)
    }

    @Test
    fun `other Citymapper notifications and empty layouts are refused`() {
        val parser = CitymapperParser()

        assertNull(parser.parse(step("Marcher vers l'arrêt de bus", "Châtelet").copy(channelId = "promotions")))
        assertNull(parser.parse(step("Marcher vers l'arrêt de bus", "Châtelet").copy(ongoing = false)))
        assertNull(parser.parse(step(title = null)))
        // Unknown wording and no time anywhere: nothing trustworthy to lead with.
        assertNull(parser.parse(step("Prenez la sortie 3", eta = null)))
    }
}
