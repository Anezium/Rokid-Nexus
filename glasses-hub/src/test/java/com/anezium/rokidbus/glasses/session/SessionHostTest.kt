// Adapted from the Rokid-Nexus fork by alvarosw (https://github.com/alvarosw/Rokid-Nexus), Apache-2.0.
package com.anezium.rokidbus.glasses.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHostTest {
    private class FakeWindow(var accepts: Boolean = true) : SessionWindow {
        var adds = 0
        var removes = 0
        val shown = mutableListOf<HostScreen>()

        override fun add(): Boolean {
            adds++
            return accepts
        }

        override fun remove() {
            removes++
        }

        override fun show(screen: HostScreen) {
            shown += screen
        }
    }

    private val window = FakeWindow()
    private var aborts = 0
    private val logs = mutableListOf<String>()
    private val host = SessionHost(window, abort = { aborts++ }, log = { logs += it })

    @Test
    fun `the window is added once and removed once per session`() {
        host.attach()
        host.attach()
        host.render(SessionState.Opening(1_000, root()), null)
        host.render(root(), null)
        host.detach()
        host.detach()

        assertEquals(1, window.adds)
        assertEquals(1, window.removes)
        assertEquals(2, window.shown.size)
        assertFalse(host.isAttached)
    }

    @Test
    fun `a second attach after detach adds a fresh window`() {
        host.attach()
        host.detach()
        host.attach()

        assertEquals(2, window.adds)
        assertTrue(host.isAttached)
    }

    @Test
    fun `a refused window aborts the session once and is never retried`() {
        window.accepts = false

        host.attach()
        host.render(root(), null)
        host.detach()

        assertEquals(1, aborts)
        assertEquals(1, window.adds)
        assertEquals(0, window.removes)
        assertTrue(window.shown.isEmpty())
        assertEquals(1, logs.size)
    }

    @Test
    fun `nothing is drawn while detached`() {
        host.render(root(), null)
        assertTrue(window.shown.isEmpty())
    }

    @Test
    fun `the gate is a one-line hint`() {
        assertEquals(
            HostScreen(null, listOf(HostRow(SessionHost.GATE_HINT))),
            SessionHost.screenFor(SessionState.Opening(1_000, root()), null),
        )
    }

    @Test
    fun `the root is one row per stop with the selection marked and no activities row`() {
        val stops = RootStops.build(
            activities = listOf(
                ActivityStop("maps", "maps:route", "300 m", pinned = true),
                ActivityStop("transit", "transit:ride", "2 stops"),
                ActivityStop("timer", "timer:main", "4:00"),
            ),
            preview = NoticePreview("relay:thread", "Ana: hi"),
            notificationCount = 3,
        )
        val ended = RootStops.withEnded(stops, "transit")

        val screen = SessionHost.screenFor(SessionState.Root(ended, selected = 1), null)

        assertEquals(
            listOf(
                HostRow("300 m"), HostRow("2 stops · Ended", selected = true), HostRow("Ana: hi"),
                HostRow("Notifications · 3"), HostRow("Applications"),
            ),
            screen.rows,
        )
    }

    @Test
    fun `a frame shows its title and its state only`() {
        val loading = Frame("maps:route", FrameStatus.Loading(1_000, "r1", "open"))
        val unavailable = Frame("maps:route", FrameStatus.Unavailable(null, "PAGE_TIMEOUT"), selected = 1)

        assertEquals(
            HostScreen("maps:route", listOf(HostRow("Loading"))),
            SessionHost.screenFor(SessionState.InPage(listOf(loading), root()), null),
        )
        assertEquals(
            HostScreen(
                "maps:route",
                listOf(HostRow("Unavailable (PAGE_TIMEOUT)"), HostRow("Retry"), HostRow("Back", selected = true)),
            ),
            SessionHost.screenFor(SessionState.InPage(listOf(unavailable), root()), null),
        )
    }

    @Test
    fun `a pending open and a failed one are said on the status line over the page`() {
        val page = SessionState.InPage(listOf(Frame("maps:route", FrameStatus.Loading(1_000, "r1", "open"))), root())

        val launching = SessionHost.screenFor(SessionState.Launching("maps", 1, 11_000, page), null)
        val failed = SessionHost.screenFor(page, SessionStatus.OpenFailed("maps", OpenFailure.TIMEOUT))

        assertEquals("Opening maps…", launching.status)
        assertEquals(listOf(HostRow("Loading")), launching.rows)
        assertEquals("Could not open maps (timeout)", failed.status)
    }

    private fun root() = SessionState.Root(RootStops.build(emptyList(), null, 0))
}
