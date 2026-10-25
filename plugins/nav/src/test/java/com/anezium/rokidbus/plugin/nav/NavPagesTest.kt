package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageActionKind
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageFact
import com.anezium.rokidbus.shared.PageTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavPagesTest {
    private val text = NavPageText(noAccess = "Allow access", off = "Switched off", idle = "Start navigating on the phone")

    private val walking = NavGuidance(
        source = NavSource.GOOGLE_MAPS,
        glyph = "turn_right",
        primary = "300 m",
        secondary = "Rue de Rivoli",
        eta = "12:41",
        stepKey = "step-1",
        instruction = "Turn right onto Rue de Rivoli",
    )

    private val transit = NavGuidance(
        source = NavSource.CITYMAPPER,
        glyph = "train",
        primary = "3 stops",
        badge = "RER B",
        track = NavTrack(count = 5, at = 2, target = 4, label = "Stops"),
        detail = listOf("Get off at Luxembourg"),
        stepKey = "ride",
    )

    private fun main(state: NavPageState): PageBody.Summary =
        ((NavPages.page(NavPages.MAIN, 1, state, text) as NexusPage.Content).body as PageBody.Summary)

    private fun live(guidance: NavGuidance?) = NavPageState(guidance, listenerConnected = true, enabled = true, updatedAt = "12:24")

    private fun facts(body: PageBody.Summary) = body.facts.associate { it.id to it.value }

    @Test
    fun `walking guidance shows the turn, distance, street, arrival, source and time`() {
        val body = main(live(walking))
        assertEquals("300 m", body.primary)
        assertEquals("Rue de Rivoli", body.secondary)
        assertEquals("Turn right onto Rue de Rivoli", body.text)
        assertEquals(mapOf("eta" to "12:41", "source" to "Google Maps", "updated" to "12:24"), facts(body))
        assertEquals(PageTarget.Page(NavPages.COMMANDS), body.items.single().target)
    }

    @Test
    fun `transit guidance shows stops, line and the instruction without inventing a street or ETA`() {
        val body = main(live(transit))
        assertEquals("3 stops", body.primary)
        assertNull(body.secondary)
        assertEquals("Get off at Luxembourg", body.text)
        assertEquals(PageFact("track", "Stops", "2 of 5"), body.facts.first { it.id == "track" })
        assertEquals("RER B", facts(body)["line"])
        assertFalse("eta" in facts(body))
    }

    @Test
    fun `values the app did not post are left out`() {
        val bare = NavGuidance(source = NavSource.ORGANIC_MAPS, glyph = "straight", primary = "1.2 km", stepKey = "s")
        val body = main(NavPageState(bare, true, true, updatedAt = null))
        assertNull(body.secondary)
        assertNull(body.text)
        assertEquals(mapOf("source" to "Organic Maps"), facts(body))
    }

    @Test
    fun `no route, no access, switched off and an arrival each say so honestly`() {
        val idle = main(live(null))
        assertEquals("No active route", idle.primary)
        assertEquals("Start navigating on the phone", idle.text)
        // Without the listener even a remembered route is not current.
        val noAccess = main(NavPageState(walking, listenerConnected = false, enabled = true, updatedAt = "12:24"))
        assertEquals("No notification access", noAccess.primary)
        assertEquals("Allow access", noAccess.text)
        assertTrue(noAccess.facts.isEmpty())
        assertEquals("Navigation is off", main(NavPageState(walking, true, enabled = false, updatedAt = null)).primary)
        assertEquals("Arrived", main(live(walking.copy(arrived = true))).primary)
    }

    @Test
    fun `commands holds only the labelled full app, no map or route controls`() {
        val page = NavPages.page(NavPages.COMMANDS, 3, live(walking), text) as NexusPage.Content
        val body = page.body as PageBody.Commands
        assertEquals(listOf("Open app"), body.items.map { it.label })
        assertEquals(NexusPageActionKind.IMMERSION, page.actions.single().kind)
        assertFalse(page.live)
        assertEquals(NexusPage.Unavailable, NavPages.page("nav:other", 1, live(walking), text))
    }

    @Test
    fun `the main page tells when it comes on screen and when it leaves, once each`() {
        val harness = Harness()
        // Not requested yet: a visibility for it is not this page's.
        harness.provider.onVisibility(NavPages.MAIN, true)
        assertTrue(harness.visibility.isEmpty())
        harness.provider.onRequest(Harness.Request(NavPages.MAIN))
        harness.provider.onVisibility(NavPages.COMMANDS, true)
        harness.provider.onVisibility(NavPages.MAIN, true)
        harness.provider.onVisibility(NavPages.MAIN, true)
        harness.provider.onVisibility(NavPages.MAIN, false)
        harness.provider.onVisibility(NavPages.MAIN, true)
        // Closed, or the link gone, while on screen: it has left.
        harness.provider.onClosed(NavPages.MAIN)
        harness.provider.forget()
        assertEquals(listOf(true, false, true, false), harness.visibility)
    }

    private class Harness {
        data class Request(val pageId: String, val floor: Long? = null)

        var guidance: NavGuidance? = null
        var clock = 0L
        val published = mutableListOf<Long>()
        var scheduled: Pair<Long, () -> Unit>? = null
        var accept = NexusSdkResult.SENT
        var retryAfterRefusalMs: Long? = null
        var journey: Long? = null
        val visibility = mutableListOf<Boolean>()
        val provider = NavPageProvider<Request>(
            pageIdOf = Request::pageId,
            floorOf = Request::floor,
            publish = { _, page ->
                if (accept == NexusSdkResult.SENT) published += (page as NexusPage.Content).revision
                accept
            },
            state = { NavPageState(guidance, true, true, "12:00") },
            text = { NavPageText("a", "b", "c") },
            now = { clock },
            schedule = { delay, task -> scheduled = delay to task },
            cancelScheduled = { scheduled = null },
            journey = { journey },
            visibilityChanged = { visibility += it },
            retryAfterRefusalMs = { retryAfterRefusalMs },
        )

        fun runScheduled() {
            val (delay, task) = scheduled ?: return
            scheduled = null
            clock += delay
            task()
        }
    }

    @Test
    fun `a held source-gone page retries once its rendered lease expires without another event`() {
        val harness = Harness()
        harness.guidance = walking
        harness.provider.onRequest(Harness.Request(NavPages.MAIN))
        harness.provider.onVisibility(NavPages.MAIN, true)
        harness.accept = NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        harness.retryAfterRefusalMs = 2_700L
        harness.guidance = null
        harness.provider.guidanceChanged()
        assertTrue(harness.published.isEmpty())
        assertEquals(2_700L, harness.scheduled?.first)
        harness.accept = NexusSdkResult.SENT
        harness.retryAfterRefusalMs = null
        harness.runScheduled()
        assertEquals(1, harness.published.size)
        assertNull(harness.scheduled)
    }

    @Test
    fun `closing a held page cancels its lease retry`() {
        val harness = Harness()
        harness.guidance = walking
        harness.provider.onRequest(Harness.Request(NavPages.MAIN))
        harness.provider.onVisibility(NavPages.MAIN, true)
        harness.accept = NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        harness.retryAfterRefusalMs = 2_700L
        harness.guidance = null
        harness.provider.guidanceChanged()
        harness.provider.onClosed(NavPages.MAIN)
        assertNull(harness.scheduled)
        assertTrue(harness.published.isEmpty())
    }

    @Test
    fun `an end confirmed on a page of an earlier journey is not the current journey's`() {
        val harness = Harness()
        harness.journey = 1L
        val first = harness.provider.onRequest(Harness.Request(NavPages.MAIN)) as NexusPage.Content
        assertTrue(harness.provider.showedCurrentJourney(first.revision))

        // The wearer starts another journey on the phone while the old confirmation is open.
        harness.journey = 2L
        assertFalse(harness.provider.showedCurrentJourney(first.revision))
        assertFalse(harness.provider.showedCurrentJourney(first.revision + 50))

        harness.journey = null
        val idle = harness.provider.onRequest(Harness.Request(NavPages.MAIN)) as NexusPage.Content
        assertFalse(harness.provider.showedCurrentJourney(idle.revision))
    }

    @Test
    fun `live guidance reaches only a visible page, twice a second at most, with rising revisions`() {
        val harness = Harness()
        val first = harness.provider.onRequest(Harness.Request(NavPages.MAIN)) as NexusPage.Content
        assertEquals(1L, first.revision)
        // Not visible yet: nothing is published.
        harness.guidance = walking
        harness.provider.guidanceChanged()
        assertTrue(harness.published.isEmpty())
        harness.provider.onVisibility(NavPages.MAIN, true)
        assertEquals(listOf(2L), harness.published)
        harness.clock += 100
        harness.guidance = walking.copy(primary = "200 m")
        harness.provider.guidanceChanged()
        harness.provider.guidanceChanged()
        assertEquals(listOf(2L), harness.published)
        assertEquals(400L, harness.scheduled?.first)
        harness.runScheduled()
        assertEquals(listOf(2L, 3L), harness.published)
        // Covered: nothing more, and the pending one is dropped.
        harness.clock += 100
        harness.guidance = walking.copy(primary = "100 m")
        harness.provider.guidanceChanged()
        harness.provider.onVisibility(NavPages.MAIN, false)
        assertNull(harness.scheduled)
        harness.clock += 1_000
        harness.provider.guidanceChanged()
        assertEquals(listOf(2L, 3L), harness.published)
        // Closed: nothing at all, even when shown again.
        harness.provider.onClosed(NavPages.MAIN)
        harness.provider.onVisibility(NavPages.MAIN, true)
        harness.provider.guidanceChanged()
        assertEquals(listOf(2L, 3L), harness.published)
    }

    @Test
    fun `a cold provider answers above the glasses' revision floor`() {
        val harness = Harness()
        // It cannot know the retained snapshot matches what it builds now, so it never reuses that revision.
        val page = harness.provider.onRequest(Harness.Request(NavPages.MAIN, floor = 41)) as NexusPage.Content
        assertEquals(42L, page.revision)
        harness.provider.onVisibility(NavPages.MAIN, true)
        harness.provider.guidanceChanged()
        assertTrue(harness.published.isEmpty())
        harness.guidance = walking
        harness.provider.guidanceChanged()
        assertEquals(listOf(43L), harness.published)
    }

    @Test
    fun `unchanged page content settles without another revision even across repeated guidance callbacks`() {
        val harness = Harness()
        harness.guidance = walking
        harness.provider.onRequest(Harness.Request(NavPages.MAIN))
        harness.provider.onVisibility(NavPages.MAIN, true)
        repeat(10) {
            harness.clock += 2_000
            harness.provider.guidanceChanged()
        }
        assertTrue(harness.published.isEmpty())
        assertNull(harness.scheduled)
        harness.guidance = walking.copy(primary = "250 m")
        harness.provider.guidanceChanged()
        assertEquals(listOf(2L), harness.published)
    }

    @Test
    fun `page traffic never touches the card or its open state`() {
        NavControl.cardOpen = false
        val harness = Harness()
        harness.guidance = walking
        harness.provider.onRequest(Harness.Request(NavPages.MAIN))
        harness.provider.onVisibility(NavPages.MAIN, true)
        harness.provider.guidanceChanged()
        harness.provider.onClosed(NavPages.MAIN)
        assertFalse(NavControl.cardOpen)
    }
}
