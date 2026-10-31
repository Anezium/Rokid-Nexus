package com.anezium.rokidbus.shared

import org.json.JSONObject

/** Counts and local monotonic ages only. No identity, token, map content or wire fields are copied. */
class MapFeedbackDiagnostics(private val clock: () -> Long, private val emit: (String) -> Unit) {
    enum class Gate {
        PHONE_CAPABILITY, PHONE_AMBIENT, PHONE_PAGE, PHONE_PAGE_DELIVERY,
        SDK_DELIVERY, SDK_LEASE, NAV_CORRELATION, NAV_PUBLICATION, GLASSES_DRAW, GLASSES_SEND, GLASSES_ISLAND,
    }
    enum class Verdict { PASS, DROP, HOLD, CLEAR }
    enum class Reason {
        CURRENT, CAPABILITY, DATA_PLANE, MALFORMED, KIND_VIEW, IDENTITY, SEQUENCE, REGISTRATION,
        REVISION, FRAME, REQUEST, PAGE_LEASE, NOT_ACTIONABLE, DELIVERY_FAILED, DUPLICATE, CLOSED,
        HIDDEN, TOKEN_MISMATCH, EXPIRED, RESET, REMEMBERED, UNKNOWN_PUBLICATION, NO_LEASE, AWAITING_ACK,
        NO_CONTEXT, DETACHED, COVERED, NOT_VISIBLE, COLLAPSED, TEXT, STALE,
    }
    enum class Kind { AMBIENT, PAGE, UNKNOWN }
    enum class View { FOLLOW, OVERVIEW, UNKNOWN }

    data class Sample(
        val kind: Kind = Kind.UNKNOWN,
        val view: View = View.UNKNOWN,
        val revision: Long = -1,
        val currentRevision: Long = -1,
        val seq: Long = -1,
        val currentSeq: Long = -1,
        val ageMs: Long = -1,
        val leaseRemainingMs: Long = -1,
        val visible: Boolean? = null,
        val collapsed: Boolean? = null,
        val frameCount: Int = -1,
        val keyCount: Int = -1,
    ) {
        companion object {
            fun from(payload: JSONObject) = Sample(
                kind = when (payload.optString("kind")) {
                    RenderedMapContract.AMBIENT -> Kind.AMBIENT
                    RenderedMapContract.PAGE -> Kind.PAGE
                    else -> Kind.UNKNOWN
                },
                view = when (payload.optString("view")) {
                    RenderedMapContract.FOLLOW -> View.FOLLOW
                    RenderedMapContract.OVERVIEW -> View.OVERVIEW
                    else -> View.UNKNOWN
                },
                revision = payload.optLong("revision", -1),
                seq = payload.optLong("seq", -1),
                visible = payload.opt("visible") as? Boolean,
            )
        }
    }

    private data class Key(val gate: Gate, val verdict: Verdict, val reason: Reason, val kind: Kind, val view: View)
    private data class Counter(var count: Long = 0, var suppressed: Long = 0, var lastAtMs: Long? = null)
    // Keys contain only fixed enums. Each distinct verdict is reported immediately, then at most
    // once per five seconds; an alternating pass/drop cannot flood logs or hide the first rejection.
    private val counters = mutableMapOf<Key, Counter>()

    @Synchronized
    fun record(gate: Gate, verdict: Verdict, reason: Reason, sample: Sample = Sample()) {
        val now = clock()
        val counter = counters.getOrPut(Key(gate, verdict, reason, sample.kind, sample.view)) { Counter() }
        counter.count++
        if (counter.lastAtMs?.let { now - it < REPORT_INTERVAL_MS } == true) {
            counter.suppressed++
            return
        }
        counter.lastAtMs = now
        emit("NexusMapDiag gate=$gate verdict=$verdict reason=$reason kind=${sample.kind} view=${sample.view}" +
            " revision=${sample.revision} currentRevision=${sample.currentRevision} seq=${sample.seq} currentSeq=${sample.currentSeq}" +
            " ageMs=${sample.ageMs} leaseRemainingMs=${sample.leaseRemainingMs}" +
            " visible=${sample.visible} collapsed=${sample.collapsed} frames=${sample.frameCount} keys=${sample.keyCount}" +
            " count=${counter.count} suppressed=${counter.suppressed}")
        counter.suppressed = 0
    }

    private companion object {
        const val REPORT_INTERVAL_MS = 5_000L
    }
}
