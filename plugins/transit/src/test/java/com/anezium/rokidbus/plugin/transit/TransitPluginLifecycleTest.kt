package com.anezium.rokidbus.plugin.transit

import android.view.KeyEvent
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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

    private class FakeRepository(var failDiscovery: Boolean = false) : TransitRepositorySource {
        var discoveryCalls = 0
        var departureCalls = 0
        val discoveredLocations = mutableListOf<TransitCoordinate>()
        val stops = listOf(
            TransitStop("one", "One", 1.001, 2.001),
            TransitStop("two", "Two", 1.002, 2.002),
            TransitStop("three", "Three", 1.003, 2.003),
        )

        override fun nearbyStops(location: TransitCoordinate): List<TransitStop> {
            discoveryCalls++
            discoveredLocations += location
            if (failDiscovery) error("offline")
            return stops
        }
        override fun departures(stopId: String): List<TransitDeparture> {
            departureCalls++
            return emptyList()
        }
        override fun searchStops(query: String): List<TransitStopMatch> = emptyList()
    }

    private class FakeLocation(
        var accessState: TransitLocationAccess,
        var coordinate: TransitCoordinate? = TransitCoordinate(1.0, 2.0),
    ) : TransitLocationSource {
        var calls = 0
        override fun access(): TransitLocationAccess = accessState
        override suspend fun currentLocation(): TransitCoordinate? {
            calls++
            return coordinate
        }
    }

    private class FakeFavorites(
        private val stops: List<TransitStop> = emptyList(),
    ) : TransitFavoritesSource {
        var mode = TransitMode.NEAR_ME
        override fun list(): List<TransitStop> = stops
        override fun add(stop: TransitStop) = Unit
        override fun remove(id: String) = Unit
        override fun lastMode(): TransitMode = mode
        override fun setLastMode(mode: TransitMode) { this.mode = mode }
    }

    private data class Fixture(
        val runtime: TransitRuntime,
        val host: FakeHost,
        val repository: FakeRepository,
        val location: FakeLocation,
    )

    private fun fixture(
        access: TransitLocationAccess = TransitLocationAccess.MISSING_PRECISE,
        repository: FakeRepository = FakeRepository(),
        location: FakeLocation = FakeLocation(access),
        favorites: FakeFavorites = FakeFavorites(),
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        refreshDelayMs: Long = 1_000L,
    ): Fixture {
        val host = FakeHost()
        return Fixture(
            TransitRuntime(
                host = host,
                dependencies = TransitDependencies(repository, location, favorites),
                refreshDispatcher = dispatcher,
                refreshDelayMs = refreshDelayMs,
                locationTimeoutMs = 15_000L,
            ),
            host,
            repository,
            location,
        )
    }

    private fun input(keyCode: Int) = NexusInputEvent("transit", keyCode, KeyEvent.ACTION_DOWN)

    @Test
    fun `open chooser moves once tap enters and back returns then hides`() = runTest {
        val fixture = fixture(dispatcher = StandardTestDispatcher(testScheduler))
        fixture.runtime.open()
        assertTrue(fixture.host.sent.single().show)
        assertTrue(fixture.host.sent.single().card!!.lines[0].text.startsWith(">"))

        fixture.runtime.input(input(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(fixture.host.sent.last().card!!.lines[1].text.startsWith(">"))

        fixture.runtime.input(input(KeyEvent.KEYCODE_ENTER))
        fixture.runtime.input(input(KeyEvent.KEYCODE_BACK))
        assertEquals("Transit", fixture.host.sent.last().card!!.title)
        assertTrue(fixture.host.sent.last().card!!.lines[1].text.startsWith(">"))

        fixture.runtime.input(input(KeyEvent.KEYCODE_BACK))
        assertTrue(fixture.host.sent.last().hidden)
        assertFalse(fixture.host.foreground)
    }

    @Test
    fun `missing precise location never starts foreground work`() = runTest {
        val fixture = fixture(
            access = TransitLocationAccess.MISSING_PRECISE,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        fixture.runtime.open()
        fixture.runtime.input(input(KeyEvent.KEYCODE_ENTER))
        assertFalse(fixture.host.foreground)
        assertEquals(0, fixture.location.calls)
        assertTrue(fixture.host.sent.last().card!!.lines.any { "precise location" in it.text })
    }

    @Test
    fun `missing background location never starts foreground work`() = runTest {
        val fixture = fixture(
            access = TransitLocationAccess.MISSING_BACKGROUND,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        fixture.runtime.open()
        fixture.runtime.input(input(KeyEvent.KEYCODE_ENTER))
        assertFalse(fixture.host.foreground)
        assertEquals(0, fixture.location.calls)
        assertTrue(fixture.host.sent.last().card!!.lines.any { "all the time" in it.text })
    }

    @Test
    fun `near me requests location once and refreshes fixed stops`() = runTest {
        val fixture = fixture(
            access = TransitLocationAccess.READY,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        fixture.runtime.open()
        fixture.runtime.input(input(KeyEvent.KEYCODE_ENTER))

        runCurrent()
        assertEquals(1, fixture.location.calls)
        assertEquals(1, fixture.repository.discoveryCalls)
        assertEquals(3, fixture.repository.departureCalls)
        assertFalse(fixture.host.foreground)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, fixture.location.calls)
        assertEquals(1, fixture.repository.discoveryCalls)
        assertEquals(6, fixture.repository.departureCalls)
        assertEquals(listOf(TransitCoordinate(1.0, 2.0)), fixture.repository.discoveredLocations)
        fixture.runtime.close()
    }

    @Test
    fun `reopening near me requests a new one shot location`() = runTest {
        val fixture = fixture(
            access = TransitLocationAccess.READY,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        fixture.runtime.open()
        fixture.runtime.input(input(KeyEvent.KEYCODE_ENTER))
        runCurrent()
        assertEquals(1, fixture.location.calls)

        fixture.runtime.input(input(KeyEvent.KEYCODE_BACK))
        fixture.runtime.input(input(KeyEvent.KEYCODE_ENTER))
        runCurrent()
        assertEquals(2, fixture.location.calls)
        fixture.runtime.close()
    }

    @Test
    fun `null location does not retry until near me reopens`() = runTest {
        val location = FakeLocation(TransitLocationAccess.READY, coordinate = null)
        val fixture = fixture(
            access = TransitLocationAccess.READY,
            location = location,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        fixture.runtime.open()
        fixture.runtime.input(input(KeyEvent.KEYCODE_ENTER))
        runCurrent()
        assertEquals(1, location.calls)
        assertFalse(fixture.host.foreground)
        assertTrue(fixture.host.sent.last().card!!.lines.any { "Location unavailable" in it.text })

        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(1, location.calls)
        fixture.runtime.input(input(KeyEvent.KEYCODE_BACK))
        fixture.runtime.input(input(KeyEvent.KEYCODE_ENTER))
        runCurrent()
        assertEquals(2, location.calls)
        fixture.runtime.close()
    }

    @Test
    fun `close before queued acquisition cancels all location work`() = runTest {
        val fixture = fixture(
            access = TransitLocationAccess.READY,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        fixture.runtime.open()
        fixture.runtime.input(input(KeyEvent.KEYCODE_ENTER))
        assertTrue(fixture.host.foreground)

        fixture.runtime.close()
        runCurrent()
        assertEquals(0, fixture.location.calls)
        assertFalse(fixture.host.foreground)
        assertTrue(fixture.host.sent.last().hidden)
    }

    @Test
    fun `favorites refresh never requests location`() = runTest {
        val favorite = TransitStop("saved", "Saved", 1.0, 2.0)
        val favorites = FakeFavorites(listOf(favorite)).apply { mode = TransitMode.FAVORITES }
        val fixture = fixture(
            access = TransitLocationAccess.READY,
            favorites = favorites,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        fixture.runtime.open()
        fixture.runtime.input(input(KeyEvent.KEYCODE_ENTER))
        runCurrent()
        assertEquals(0, fixture.location.calls)
        assertEquals(1, fixture.repository.departureCalls)
        fixture.runtime.close()
    }

    @Test
    fun `discovery failure uses same coordinate on next refresh`() = runTest {
        val repository = FakeRepository(failDiscovery = true)
        val fixture = fixture(
            access = TransitLocationAccess.READY,
            repository = repository,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        fixture.runtime.open()
        fixture.runtime.input(input(KeyEvent.KEYCODE_ENTER))

        runCurrent()
        assertEquals(1, fixture.location.calls)
        assertEquals(1, repository.discoveryCalls)
        repository.failDiscovery = false
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, fixture.location.calls)
        assertEquals(2, repository.discoveryCalls)
        assertEquals(
            listOf(TransitCoordinate(1.0, 2.0), TransitCoordinate(1.0, 2.0)),
            repository.discoveredLocations,
        )
        fixture.runtime.close()
    }
}
