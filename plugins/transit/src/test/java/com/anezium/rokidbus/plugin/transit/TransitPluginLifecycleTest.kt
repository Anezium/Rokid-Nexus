package com.anezium.rokidbus.plugin.transit

import android.view.KeyEvent
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitPluginLifecycleTest {
    private class FakeHost : TransitRuntimeHost {
        data class Sent(val card: TransitCardContent?, val show: Boolean, val hidden: Boolean)
        val sent = mutableListOf<Sent>()
        var foreground = false
        override fun sendCard(card: TransitCardContent, show: Boolean) {
            sent += Sent(card, show, hidden = false)
        }
        override fun hideSurface() {
            sent += Sent(null, show = false, hidden = true)
        }
        override fun post(action: () -> Unit) = action()
        override fun log(message: String) = Unit
        override fun setNearMeForeground(active: Boolean): Boolean {
            foreground = active
            return true
        }
    }

    private class FakeRepository(var fail: Boolean = false) : TransitRepositorySource {
        override fun nearbyStops(location: TransitCoordinate): List<TransitStop> {
            if (fail) error("offline")
            return emptyList()
        }
        override fun departures(stopId: String): List<TransitDeparture> = emptyList()
        override fun searchStops(query: String): List<TransitStopMatch> = emptyList()
    }

    private class FakeLocation(
        private val granted: Boolean,
    ) : TransitLocationSource {
        override fun hasLocationPermission(): Boolean = granted
        override suspend fun currentLocation(): TransitCoordinate? = TransitCoordinate(1.0, 2.0)
    }

    private class FakeFavorites : TransitFavoritesSource {
        var mode = TransitMode.NEAR_ME
        override fun list(): List<TransitStop> = emptyList()
        override fun add(stop: TransitStop) = Unit
        override fun remove(id: String) = Unit
        override fun lastMode(): TransitMode = mode
        override fun setLastMode(mode: TransitMode) { this.mode = mode }
    }

    private data class Fixture(val runtime: TransitRuntime, val host: FakeHost)

    private fun fixture(
        locationGranted: Boolean = false,
        repository: FakeRepository = FakeRepository(),
    ): Fixture {
        val host = FakeHost()
        return Fixture(
            TransitRuntime(
                host = host,
                dependencies = TransitDependencies(repository, FakeLocation(locationGranted), FakeFavorites()),
                refreshDispatcher = Dispatchers.Unconfined,
                refreshDelayMs = Long.MAX_VALUE,
            ),
            host,
        )
    }

    private fun input(keyCode: Int) = NexusInputEvent("transit", keyCode, KeyEvent.ACTION_DOWN)

    @Test
    fun `open chooser moves once tap enters and back returns then hides`() {
        val (runtime, host) = fixture()
        runtime.open()
        assertTrue(host.sent.single().show)
        assertTrue(host.sent.single().card!!.lines[0].text.startsWith(">"))

        runtime.input(input(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(host.sent.last().card!!.lines[1].text.startsWith(">"))

        runtime.input(input(KeyEvent.KEYCODE_ENTER))
        runtime.input(input(KeyEvent.KEYCODE_BACK))
        assertEquals("Transit", host.sent.last().card!!.title)
        assertTrue(host.sent.last().card!!.lines[1].text.startsWith(">"))

        runtime.input(input(KeyEvent.KEYCODE_BACK))
        assertTrue(host.sent.last().hidden)
        assertFalse(host.foreground)
    }

    @Test
    fun `close cancels refresh and failure card stays bounded`() {
        val repository = FakeRepository(fail = true)
        val (runtime, host) = fixture(locationGranted = true, repository = repository)
        runtime.open()
        runtime.input(input(KeyEvent.KEYCODE_ENTER))
        assertTrue(host.sent.last().card!!.lines.any { "Transit fetch failed." in it.text })
        runtime.close()
        val countAfterClose = host.sent.size
        assertTrue(host.sent.last().hidden)
        assertFalse(host.foreground)
        assertEquals(countAfterClose, host.sent.size)
    }

    @Test
    fun `missing location permission never starts foreground work`() {
        val (runtime, host) = fixture(locationGranted = false)
        runtime.open()
        runtime.input(input(KeyEvent.KEYCODE_ENTER))
        assertFalse(host.foreground)
        assertTrue(host.sent.last().card!!.lines.any { "Grant location" in it.text })
    }
}
