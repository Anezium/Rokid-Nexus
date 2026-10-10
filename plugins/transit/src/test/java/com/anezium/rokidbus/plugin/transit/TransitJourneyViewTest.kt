package com.anezium.rokidbus.plugin.transit

import android.view.KeyEvent
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TransitJourneyViewTest {
    private class Host(var journey: JourneyState?) : TransitRuntimeHost {
        val cards = mutableListOf<TransitCardContent>()
        var hidden = false
        var stopped = false
        override fun sendCard(card: TransitCardContent, show: Boolean) {
            cards += card
        }
        override fun hideSurface() {
            hidden = true
        }
        override fun post(action: () -> Unit) = action()
        override fun log(message: String) = Unit
        override fun setNearMeForeground(active: Boolean) = true
        override fun journey() = journey
        override fun stopJourney() {
            stopped = true
            journey = null
        }
    }

    private object NoRepository : TransitRepositorySource {
        override fun nearbyStops(location: TransitCoordinate) = emptyList<TransitStop>()
        override fun departures(stopId: String) = emptyList<TransitDeparture>()
        override fun searchStops(query: String) = emptyList<TransitStopMatch>()
    }

    private object NoLocation : TransitLocationSource {
        override fun access() = TransitLocationAccess.MISSING_PRECISE
        override suspend fun currentLocation(): TransitCoordinate? = null
    }

    private object NoFavorites : TransitFavoritesSource {
        override fun list() = emptyList<TransitStop>()
        override fun add(stop: TransitStop) = Unit
        override fun remove(id: String) = Unit
        override fun lastMode() = TransitMode.FAVORITES
        override fun setLastMode(mode: TransitMode) = Unit
    }

    private val journey = TransitJourneyGuide.initial("j1", sampleItinerary(), "Home", TransitCoordinate(48.8262, 2.3503), 1, T0)
        .copy(legIndex = 1, phase = JourneyPhase.RIDE, rideStopIndex = 1)
    private val host = Host(journey)
    private val runtime = TransitRuntime(
        host = host,
        dependencies = TransitDependencies(NoRepository, NoLocation, NoFavorites),
        zone = { ZoneId.of("Europe/Paris") },
    )

    private fun press(keyCode: Int) = runtime.input(NexusInputEvent("transit", keyCode, KeyEvent.ACTION_DOWN))

    @Test
    fun `opening Transit during a journey shows the current leg of the whole itinerary first`() {
        runtime.open()

        val card = host.cards.last()
        assertEquals("Journey 2/5 · now", card.title)
        assertEquals("38", card.lines.first().badge)
        assertEquals(listOf("10:05"), card.lines[1].trail)
        assertEquals(listOf("10:11"), card.lines[2].trail)
        assertTrue(card.lines.any { it.text == "You are aboard" })
    }

    @Test
    fun `legs page forward and backward, one per page`() {
        runtime.open()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Journey 3/5", host.cards.last().title)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Journey 1/5", host.cards.last().title)
        assertTrue(host.cards.last().lines.any { it.text == "Done" })
    }

    @Test
    fun `back leaves the view without ending the journey`() {
        runtime.open()
        press(KeyEvent.KEYCODE_BACK)

        assertTrue(host.hidden)
        assertFalse(host.stopped)
    }

    @Test
    fun `guidance ends only from the explicit menu action, with Near Me and favorites beside it`() {
        runtime.open()
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("> Near Me", "  Favorites", "  Stop guidance"), host.cards.last().lines.map { it.text })

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFalse(host.stopped)
        press(KeyEvent.KEYCODE_ENTER)

        assertTrue(host.stopped)
        assertEquals("Transit", host.cards.last().title)
    }

    @Test
    fun `without a journey Transit opens on its chooser as before`() {
        host.journey = null
        runtime.open()
        assertEquals("Transit", host.cards.last().title)
    }
}
