package com.anezium.rokidbus.media

import com.anezium.rokidbus.client.plugin.NexusActionType
import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageActionKind
import com.anezium.rokidbus.client.plugin.NexusPageResultStatus
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.media.session.FakeHub
import com.anezium.rokidbus.media.session.MediaDeckCommand
import com.anezium.rokidbus.media.session.MediaDeckObservation
import com.anezium.rokidbus.media.session.MediaDeckState
import com.anezium.rokidbus.media.session.MediaDeckStateMachine
import com.anezium.rokidbus.media.session.MediaPlaybackPhase
import com.anezium.rokidbus.media.session.SESSION_B
import com.anezium.rokidbus.media.session.observed
import com.anezium.rokidbus.media.session.track
import com.anezium.rokidbus.shared.MediaPlaybackState
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDeckPagesTest {
    private fun stateOf(vararg observations: MediaDeckObservation): MediaDeckState =
        MediaDeckStateMachine().apply { observations.forEach { observe(it) } }.state

    private fun content(state: MediaDeckState, extended: Boolean): NexusPage.Content =
        MediaDeckPages.page(MediaDeckPages.MAIN, 7, state, extended) as NexusPage.Content

    @Test
    fun `the launcher page is a media body whose controls are the typed explicit commands`() {
        val page = content(stateOf(observed()), extended = true)
        val body = page.body as PageBody.Media

        assertEquals(7L, page.revision)
        assertTrue(page.live)
        assertEquals("Teardrop", body.title)
        assertEquals("Massive Attack", body.artist)
        assertEquals("Mezzanine", body.album)
        assertEquals("Player", body.source)
        assertEquals(MediaPlaybackState.PLAYING, body.state)
        assertEquals(listOf("previous", "pause", "next"), body.controls)
        assertEquals("pause", body.defaultControl)
        assertEquals(body.controls, page.actions.map { it.id })
        assertEquals(
            listOf(NexusActionType.MediaPrevious, NexusActionType.MediaPause, NexusActionType.MediaNext),
            page.actions.map { it.typed },
        )
        assertTrue(page.actions.all { it.kind == NexusPageActionKind.PLUGIN })
    }

    @Test
    fun `a paused session offers Play on the page`() {
        val body = content(stateOf(observed(phase = MediaPlaybackPhase.PAUSED)), extended = true).body as PageBody.Media

        assertEquals(MediaPlaybackState.PAUSED, body.state)
        assertEquals(listOf("previous", "play", "next"), body.controls)
        assertEquals("play", body.defaultControl)
    }

    @Test
    fun `without a session the page says why and offers no control`() {
        val none = content(stateOf(MediaDeckObservation.NoSession), extended = true)
        assertEquals(MediaPlaybackState.NONE, (none.body as PageBody.Media).state)
        assertTrue((none.body as PageBody.Media).controls.isEmpty())
        assertTrue(none.actions.isEmpty())

        val revoked = content(stateOf(observed(), MediaDeckObservation.AccessRequired), extended = true)
        val body = revoked.body as PageBody.Media
        assertEquals(MediaPlaybackState.UNAVAILABLE, body.state)
        assertEquals("Media access was turned off", body.title)
        assertTrue(revoked.actions.isEmpty())
    }

    @Test
    fun `older launchers get the base summary with the controls and the full app as rows`() {
        val page = content(stateOf(observed()), extended = false)
        val body = page.body as PageBody.Summary

        assertEquals("Teardrop", body.primary)
        assertEquals("Massive Attack", body.secondary)
        assertEquals(listOf("previous", "pause", "next", MediaDeckPages.OPEN_APP), body.items.map { it.id })
        body.items.forEach { row -> assertEquals(PageTarget.Action(row.id), row.target) }
        assertEquals(NexusPageActionKind.IMMERSION, page.actions.single { it.id == MediaDeckPages.OPEN_APP }.kind)
        assertEquals(4, page.actions.size)
    }

    @Test
    fun `another page id is unavailable`() {
        assertEquals(NexusPage.Unavailable, MediaDeckPages.page("media:other", 1, stateOf(observed()), extended = true))
    }

    private data class Request(val pageId: String, val floor: Long?)

    private val fake = FakeHub()
    private val published = mutableListOf<NexusPage>()
    private val tasks = mutableListOf<() -> Unit>()

    private fun provider() = MediaDeckPageProvider<Request>(
        hub = fake.hub,
        pageIdOf = Request::pageId,
        floorOf = Request::floor,
        publish = { _, page ->
            published += page
            NexusSdkResult.SENT
        },
        extended = { true },
        now = { 0L },
        schedule = { _, task ->
            tasks += task
            ({ tasks.remove(task); Unit })
        },
    )

    @Test
    fun `a page action runs on the session the page showed, at that revision`() {
        fake.monitor.live = observed()
        val provider = provider()
        val page = provider.onRequest(Request(MediaDeckPages.MAIN, floor = 11)) as NexusPage.Content
        assertEquals(12L, page.revision)

        val result = provider.onAction(MediaDeckPages.MAIN, page.revision, "pause", NexusActionType.MediaPause)
        assertEquals(NexusPageResultStatus.DONE, result.status)
        assertEquals(listOf(observed() to MediaDeckCommand.PAUSE), fake.monitor.sent)
    }

    @Test
    fun `a page action on a revision that is gone or on another session is stale`() {
        fake.monitor.live = observed()
        val provider = provider()
        val page = provider.onRequest(Request(MediaDeckPages.MAIN, floor = null)) as NexusPage.Content
        provider.onVisibility(MediaDeckPages.MAIN, isVisible = true, leaseMs = 120_000L)

        assertEquals(NexusPageResultStatus.STALE, provider.onAction(MediaDeckPages.MAIN, 99, "pause", null).status)

        fake.monitor.emit(observed(identity = SESSION_B))
        val next = published.last() as NexusPage.Content
        assertTrue(next.revision > page.revision)
        assertEquals(NexusPageResultStatus.STALE, provider.onAction(MediaDeckPages.MAIN, page.revision, "next", null).status)
        assertEquals(NexusPageResultStatus.DONE, provider.onAction(MediaDeckPages.MAIN, next.revision, "next", null).status)
        assertEquals(listOf(observed(identity = SESSION_B) to MediaDeckCommand.NEXT), fake.monitor.sent)
    }

    @Test
    fun `without a session or access a page action is rejected`() {
        fake.monitor.live = MediaDeckObservation.AccessRequired
        val provider = provider()
        val page = provider.onRequest(Request(MediaDeckPages.MAIN, floor = null)) as NexusPage.Content

        val result = provider.onAction(MediaDeckPages.MAIN, page.revision, "play", NexusActionType.MediaPlay)
        assertEquals(NexusPageResultStatus.REJECTED, result.status)
        assertTrue(fake.monitor.sent.isEmpty())
    }

    @Test
    fun `the page watches only while it is shown`() {
        fake.monitor.live = observed()
        val provider = provider()
        provider.onRequest(Request(MediaDeckPages.MAIN, floor = null))
        provider.onVisibility(MediaDeckPages.MAIN, isVisible = true, leaseMs = 120_000L)
        assertTrue(provider.isWatching)
        assertTrue(fake.monitor.started)

        provider.onVisibility(MediaDeckPages.MAIN, isVisible = false, leaseMs = null)
        assertFalse(provider.isWatching)
        assertFalse(fake.monitor.started)

        provider.onClosed(MediaDeckPages.MAIN)
        assertNull(published.lastOrNull())
    }
}
