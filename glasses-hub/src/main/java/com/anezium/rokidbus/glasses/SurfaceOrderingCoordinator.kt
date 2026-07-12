package com.anezium.rokidbus.glasses

data class SurfaceOrder(
    val surfaceId: String,
    val seq: Long,
    val kind: String,
    val contentKey: String,
)

data class PendingSurfaceAnchor<out T>(
    val order: SurfaceOrder,
    val value: T,
)

enum class SurfaceOrderDropReason {
    STALE_BASE,
    STALE_ANCHOR,
    STALE_HIDE,
}

sealed interface SurfaceOrderDecision<out T> {
    data class ApplyBase<T>(
        val pendingAnchor: PendingSurfaceAnchor<T>?,
        val appliedAnchorSeqToPreserve: Long? = null,
    ) : SurfaceOrderDecision<T>

    data object ApplyAnchor : SurfaceOrderDecision<Nothing>
    data object StashAnchor : SurfaceOrderDecision<Nothing>
    data object ApplyHide : SurfaceOrderDecision<Nothing>

    data class Drop(
        val reason: SurfaceOrderDropReason,
        val latestBaseSeq: Long,
        val latestSeq: Long,
    ) : SurfaceOrderDecision<Nothing>
}

/**
 * Per-surface ordering for messages delivered over transports that do not share a queue.
 *
 * Base updates are ordered only against other bases. Anchor-only updates additionally use the
 * all-message watermark, and one unmatched anchor is retained until its base arrives.
 */
class SurfaceOrderingCoordinator<T> {
    private data class SurfaceState<T>(
        var latestBaseSeq: Long = Long.MIN_VALUE,
        var latestSeq: Long = Long.MIN_VALUE,
        var activeKind: String? = null,
        var activeContentKey: String? = null,
        var latestAppliedAnchorSeq: Long = Long.MIN_VALUE,
        var pendingAnchor: PendingSurfaceAnchor<T>? = null,
    )

    private val states = mutableMapOf<String, SurfaceState<T>>()

    @Synchronized
    fun onBase(order: SurfaceOrder): SurfaceOrderDecision<T> {
        val state = states.getOrPut(order.surfaceId) { SurfaceState() }
        if (order.seq <= state.latestBaseSeq) {
            return SurfaceOrderDecision.Drop(
                reason = SurfaceOrderDropReason.STALE_BASE,
                latestBaseSeq = state.latestBaseSeq,
                latestSeq = state.latestSeq,
            )
        }

        val sameActiveIdentity = state.activeKind == order.kind &&
            state.activeContentKey == order.contentKey
        val appliedAnchorSeqToPreserve = state.latestAppliedAnchorSeq
            .takeIf { sameActiveIdentity && it > order.seq }

        state.latestBaseSeq = order.seq
        state.latestSeq = maxOf(state.latestSeq, order.seq)
        state.activeKind = order.kind
        state.activeContentKey = order.contentKey

        val pending = state.pendingAnchor
        state.pendingAnchor = null
        val matchingPending = pending?.takeIf {
            it.order.seq > order.seq && identitiesMatch(order, it.order)
        }
        state.latestAppliedAnchorSeq = matchingPending?.order?.seq
            ?: appliedAnchorSeqToPreserve
            ?: Long.MIN_VALUE
        return SurfaceOrderDecision.ApplyBase(
            pendingAnchor = matchingPending,
            appliedAnchorSeqToPreserve = appliedAnchorSeqToPreserve
                .takeIf { matchingPending == null },
        )
    }

    @Synchronized
    fun onAnchor(order: SurfaceOrder, value: T): SurfaceOrderDecision<T> {
        val state = states.getOrPut(order.surfaceId) { SurfaceState() }
        if (order.seq <= state.latestSeq) {
            return SurfaceOrderDecision.Drop(
                reason = SurfaceOrderDropReason.STALE_ANCHOR,
                latestBaseSeq = state.latestBaseSeq,
                latestSeq = state.latestSeq,
            )
        }

        state.latestSeq = order.seq
        val matchesActiveBase = state.activeKind == order.kind &&
            (order.contentKey.isBlank() || state.activeContentKey == order.contentKey)
        return if (matchesActiveBase) {
            state.latestAppliedAnchorSeq = order.seq
            SurfaceOrderDecision.ApplyAnchor
        } else {
            state.pendingAnchor = PendingSurfaceAnchor(order, value)
            SurfaceOrderDecision.StashAnchor
        }
    }

    @Synchronized
    fun onHide(surfaceId: String, seq: Long): SurfaceOrderDecision<T> {
        val state = states.getOrPut(surfaceId) { SurfaceState() }
        if (seq <= state.latestBaseSeq) {
            return SurfaceOrderDecision.Drop(
                reason = SurfaceOrderDropReason.STALE_HIDE,
                latestBaseSeq = state.latestBaseSeq,
                latestSeq = state.latestSeq,
            )
        }

        state.latestBaseSeq = seq
        state.latestSeq = maxOf(state.latestSeq, seq)
        state.activeKind = null
        state.activeContentKey = null
        state.latestAppliedAnchorSeq = Long.MIN_VALUE
        state.pendingAnchor = null
        return SurfaceOrderDecision.ApplyHide
    }

    /** The surface is no longer displayed, but its replay watermarks remain authoritative. */
    @Synchronized
    fun deactivate(surfaceId: String) {
        states[surfaceId]?.let { state ->
            state.activeKind = null
            state.activeContentKey = null
            state.latestAppliedAnchorSeq = Long.MIN_VALUE
        }
    }

    @Synchronized
    fun isCurrentBase(order: SurfaceOrder): Boolean {
        val state = states[order.surfaceId] ?: return false
        return state.latestBaseSeq == order.seq &&
            state.activeKind == order.kind &&
            state.activeContentKey == order.contentKey
    }

    private fun identitiesMatch(base: SurfaceOrder, anchor: SurfaceOrder): Boolean =
        base.kind == anchor.kind &&
            (anchor.contentKey.isBlank() || base.contentKey == anchor.contentKey)
}
