package com.anezium.rokidbus.plugin.meta

import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.shared.PageBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A service ready before the hub showed the page: the shown page gets the ready content, once. */
class StalePagesTest {
    private val alice = MetaChat("chat-1", "Alice", group = false, supported = true)

    private fun instagramSub(state: MetaPagesState, revision: Long): String? =
        ((MetaPages.page(MetaPages.MAIN, revision, state, interactive = true) as NexusPage.Content).body as PageBody.SelectableList)
            .items.single { it.id == MessagingService.INSTAGRAM.id }.sub

    @Test fun stateBecameReadyBeforeThePageWasShownSoTheShownPageGetsTheReadyContent() {
        val state = MetaPagesState()
        val stale = StalePages()
        val visible = HashSet<String>()
        // The request is answered while Instagram connects.
        state.set(MessagingService.INSTAGRAM, ServiceState.Connecting)
        stale.answered(MetaPages.MAIN)
        assertEquals(MetaPages.SUB_CONNECTING, instagramSub(state, state.nextRevision(null)))
        // The engine was already connected: ready in milliseconds, before the hub showed the page.
        state.set(MessagingService.INSTAGRAM, ServiceState.Ready(listOf(alice)))
        if (MetaPages.MAIN !in visible) stale.skipped(MetaPages.MAIN)
        // Visibility arrives: the page owes one publish, which carries the ready content.
        visible += MetaPages.MAIN
        assertTrue(stale.shown(MetaPages.MAIN))
        assertEquals("1 conversation", instagramSub(state, state.nextRevision(null)))
        assertFalse("published once", stale.shown(MetaPages.MAIN))
    }

    @Test fun aFreshAnswerOrAClosedPageOwesNothing() {
        val stale = StalePages()
        stale.skipped(MetaPages.MAIN)
        stale.answered(MetaPages.MAIN)
        assertFalse(stale.shown(MetaPages.MAIN))
        stale.skipped(MetaPages.MAIN)
        stale.forget(MetaPages.MAIN)
        assertFalse(stale.shown(MetaPages.MAIN))
        stale.skipped(MetaPages.servicePageId(MessagingService.INSTAGRAM))
        stale.clear()
        assertFalse(stale.shown(MetaPages.servicePageId(MessagingService.INSTAGRAM)))
    }
}
