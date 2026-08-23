package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceOrderingCoordinatorTest {
    @Test
    fun `late base applies with newer stashed media anchor on top`() {
        val coordinator = SurfaceOrderingCoordinator<String>()
        assertApplyBase(coordinator.onBase(media(seq = 1, key = "track-a")))

        assertSame(
            SurfaceOrderDecision.StashAnchor,
            coordinator.onAnchor(media(seq = 3, key = "track-b"), "anchor-b-3"),
        )
        val decision = assertApplyBase(coordinator.onBase(media(seq = 2, key = "track-b")))

        assertEquals(3L, decision.pendingAnchor?.order?.seq)
        assertEquals("anchor-b-3", decision.pendingAnchor?.value)
    }

    @Test
    fun `in-order artwork base still applies after matching media anchor`() {
        val coordinator = SurfaceOrderingCoordinator<String>()
        assertApplyBase(coordinator.onBase(media(seq = 1, key = "track-a")))
        assertSame(
            SurfaceOrderDecision.ApplyAnchor,
            coordinator.onAnchor(media(seq = 2, key = "track-a"), "anchor-a-2"),
        )

        val decision = assertApplyBase(coordinator.onBase(media(seq = 3, key = "track-a")))

        assertNull(decision.pendingAnchor)
        assertNull(decision.appliedAnchorSeqToPreserve)
    }

    @Test
    fun `late same-track artwork base preserves newer applied anchor`() {
        val coordinator = SurfaceOrderingCoordinator<String>()
        assertApplyBase(coordinator.onBase(media(seq = 1, key = "track-a")))
        assertSame(
            SurfaceOrderDecision.ApplyAnchor,
            coordinator.onAnchor(media(seq = 3, key = "track-a"), "anchor-a-3"),
        )

        val decision = assertApplyBase(coordinator.onBase(media(seq = 2, key = "track-a")))

        assertNull(decision.pendingAnchor)
        assertEquals(3L, decision.appliedAnchorSeqToPreserve)
    }

    @Test
    fun `genuinely stale base is dropped against base watermark`() {
        val coordinator = SurfaceOrderingCoordinator<String>()
        assertApplyBase(coordinator.onBase(media(seq = 5, key = "track-a")))

        val decision = coordinator.onBase(media(seq = 2, key = "track-a"))

        assertDrop(decision, SurfaceOrderDropReason.STALE_BASE, latestBaseSeq = 5, latestSeq = 5)
    }

    @Test
    fun `hide clears pending anchor and rejects a late base`() {
        val coordinator = SurfaceOrderingCoordinator<String>()
        assertApplyBase(coordinator.onBase(media(seq = 5, key = "track-a")))
        assertSame(
            SurfaceOrderDecision.StashAnchor,
            coordinator.onAnchor(media(seq = 7, key = "track-b"), "anchor-b-7"),
        )

        assertSame(SurfaceOrderDecision.ApplyHide, coordinator.onHide(SURFACE_ID, seq = 6))
        assertDrop(
            coordinator.onBase(media(seq = 4, key = "track-b")),
            SurfaceOrderDropReason.STALE_BASE,
            latestBaseSeq = 6,
            latestSeq = 7,
        )
        assertDrop(
            coordinator.onAnchor(media(seq = 4, key = "track-a"), "late-anchor-a-4"),
            SurfaceOrderDropReason.STALE_ANCHOR,
            latestBaseSeq = 6,
            latestSeq = 7,
        )
        assertNull(
            assertApplyBase(coordinator.onBase(media(seq = 8, key = "track-b"))).pendingAnchor,
        )
    }

    @Test
    fun `old content anchor arriving after new base is dropped`() {
        val coordinator = SurfaceOrderingCoordinator<String>()
        assertApplyBase(coordinator.onBase(media(seq = 1, key = "track-a")))
        assertApplyBase(coordinator.onBase(media(seq = 3, key = "track-b")))

        assertDrop(
            coordinator.onAnchor(media(seq = 2, key = "track-a"), "late-anchor-a-2"),
            SurfaceOrderDropReason.STALE_ANCHOR,
            latestBaseSeq = 3,
            latestSeq = 3,
        )
    }

    @Test
    fun `timed lines use the same stash and late-base apply path`() {
        val coordinator = SurfaceOrderingCoordinator<String>()
        assertApplyBase(coordinator.onBase(timedLines(seq = 10, key = "lyrics-a")))

        assertSame(
            SurfaceOrderDecision.StashAnchor,
            coordinator.onAnchor(timedLines(seq = 12, key = "lyrics-b"), "lyrics-anchor-b-12"),
        )
        val decision = assertApplyBase(
            coordinator.onBase(timedLines(seq = 11, key = "lyrics-b")),
        )

        assertEquals("lyrics-anchor-b-12", decision.pendingAnchor?.value)
    }

    @Test
    fun `latest unmatched anchor replaces older pending anchor`() {
        val coordinator = SurfaceOrderingCoordinator<String>()

        assertSame(
            SurfaceOrderDecision.StashAnchor,
            coordinator.onAnchor(media(seq = 20, key = "track-a"), "anchor-20"),
        )
        assertSame(
            SurfaceOrderDecision.StashAnchor,
            coordinator.onAnchor(media(seq = 21, key = "track-a"), "anchor-21"),
        )

        val decision = assertApplyBase(coordinator.onBase(media(seq = 19, key = "track-a")))
        assertEquals("anchor-21", decision.pendingAnchor?.value)
    }

    @Test
    fun `older epoch is dropped even when seq is newer`() {
        val coordinator = SurfaceOrderingCoordinator<String>()
        assertApplyBase(coordinator.onBase(card(seq = 1, epoch = 1, surfaceId = "assistant:main")))

        assertDrop(
            coordinator.onBase(card(seq = 99, epoch = 0, surfaceId = "lyrics:main")),
            SurfaceOrderDropReason.STALE_EPOCH,
            latestBaseSeq = Long.MIN_VALUE,
            latestSeq = Long.MIN_VALUE,
            liveEpoch = 1,
        )
        assertDrop(
            coordinator.onAnchor(card(seq = 100, epoch = 0, surfaceId = "lyrics:main"), "late"),
            SurfaceOrderDropReason.STALE_EPOCH,
            latestBaseSeq = Long.MIN_VALUE,
            latestSeq = Long.MIN_VALUE,
            liveEpoch = 1,
        )
        assertTrue(
            coordinator.isCurrentBase(card(seq = 1, epoch = 1, surfaceId = "assistant:main")),
        )
    }

    @Test
    fun `newer epoch applies even when seq is older than the previous occupant`() {
        val coordinator = SurfaceOrderingCoordinator<String>()
        val previous = card(seq = 50, epoch = 1, surfaceId = "assistant:main")
        assertApplyBase(coordinator.onBase(previous))

        val next = card(seq = 2, epoch = 2, surfaceId = "lyrics:main")
        assertApplyBase(coordinator.onBase(next))

        assertTrue(coordinator.isCurrentBase(next))
        assertTrue(!coordinator.isCurrentBase(previous))
        assertDrop(
            coordinator.onBase(card(seq = 80, epoch = 1, surfaceId = "assistant:main")),
            SurfaceOrderDropReason.STALE_EPOCH,
            latestBaseSeq = 50,
            latestSeq = 50,
            liveEpoch = 2,
        )
    }

    @Test
    fun `equal epoch still drops a stale sequence`() {
        val coordinator = SurfaceOrderingCoordinator<String>()
        assertApplyBase(coordinator.onBase(card(seq = 5, epoch = 3)))

        assertDrop(
            coordinator.onBase(card(seq = 2, epoch = 3)),
            SurfaceOrderDropReason.STALE_BASE,
            latestBaseSeq = 5,
            latestSeq = 5,
            liveEpoch = 3,
        )
    }

    private fun media(seq: Long, key: String, epoch: Long = 0L) = SurfaceOrder(
        surfaceId = SURFACE_ID,
        seq = seq,
        kind = "media",
        contentKey = key,
        epoch = epoch,
    )

    private fun timedLines(seq: Long, key: String) = SurfaceOrder(
        surfaceId = SURFACE_ID,
        seq = seq,
        kind = "timed-lines",
        contentKey = key,
    )

    private fun card(
        seq: Long,
        epoch: Long,
        surfaceId: String = SURFACE_ID,
    ) = SurfaceOrder(
        surfaceId = surfaceId,
        seq = seq,
        kind = "card",
        contentKey = "card",
        epoch = epoch,
    )

    @Suppress("UNCHECKED_CAST")
    private fun assertApplyBase(
        decision: SurfaceOrderDecision<String>,
    ): SurfaceOrderDecision.ApplyBase<String> {
        assertTrue(decision is SurfaceOrderDecision.ApplyBase)
        return decision as SurfaceOrderDecision.ApplyBase<String>
    }

    private fun assertDrop(
        decision: SurfaceOrderDecision<String>,
        reason: SurfaceOrderDropReason,
        latestBaseSeq: Long,
        latestSeq: Long,
        liveEpoch: Long = 0L,
    ) {
        assertTrue(decision is SurfaceOrderDecision.Drop)
        decision as SurfaceOrderDecision.Drop
        assertEquals(reason, decision.reason)
        assertEquals(latestBaseSeq, decision.latestBaseSeq)
        assertEquals(latestSeq, decision.latestSeq)
        assertEquals(liveEpoch, decision.liveEpoch)
    }

    private companion object {
        const val SURFACE_ID = "media:media"
    }
}
