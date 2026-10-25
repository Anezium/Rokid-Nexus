package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.RenderedMapContract
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Gate
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Reason
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Verdict
import org.json.JSONObject

/** The map view actually rendered by the glasses, under a short hub-issued lease. */
enum class NexusRenderedMapView { FOLLOW, OVERVIEW }

/** An acknowledged owner publication. A null [pageId] identifies activity geometry. */
data class NexusRenderedMap(val view: NexusRenderedMapView, val revision: Long, val pageId: String? = null)

internal class NexusRenderedMaps(
    private val clock: () -> Long,
    private val diagnostics: MapFeedbackDiagnostics? = null,
) {
    private data class Lease(val token: String, val frame: NexusRenderedMap, val receivedAt: Long, val until: Long)
    private val leases = mutableMapOf<String, Lease>()

    @Synchronized
    fun receive(payload: JSONObject): Boolean {
        val kind = payload.optString("kind")
        if (kind != RenderedMapContract.AMBIENT && kind != RenderedMapContract.PAGE) return reject(payload)
        val token = (payload.opt("token") as? String)?.takeIf { it.length in 1..64 && it.none(Char::isISOControl) } ?: return reject(payload)
        val visible = payload.opt("visible") as? Boolean ?: return reject(payload)
        if (!visible) {
            val matched = leases[kind]?.token == token
            if (matched) leases.remove(kind)
            diagnostics?.record(Gate.SDK_LEASE, if (matched) Verdict.CLEAR else Verdict.DROP,
                if (matched) Reason.HIDDEN else Reason.TOKEN_MISMATCH, MapFeedbackDiagnostics.Sample.from(payload))
            return true
        }
        val view = when (payload.optString("view")) {
            RenderedMapContract.FOLLOW -> NexusRenderedMapView.FOLLOW
            RenderedMapContract.OVERVIEW -> NexusRenderedMapView.OVERVIEW
            else -> return reject(payload)
        }
        if (kind == RenderedMapContract.AMBIENT && view != NexusRenderedMapView.FOLLOW) return reject(payload)
        val revision = when (val value = payload.opt("revision")) {
            is Int -> value.toLong()
            is Long -> value
            else -> return reject(payload)
        }.takeIf { it >= 0 } ?: return reject(payload)
        val pageId = if (kind == RenderedMapContract.PAGE) {
            (payload.opt("pageId") as? String)?.takeIf { it.length in 1..160 && it.none(Char::isISOControl) } ?: return reject(payload)
        } else null
        val duration = when (val value = payload.opt("leaseMs")) {
            is Int -> value.toLong()
            is Long -> value
            else -> return reject(payload)
        }.takeIf { it in 1..RenderedMapContract.LEASE_MS } ?: return reject(payload)
        val now = clock()
        leases[kind] = Lease(token, NexusRenderedMap(view, revision, pageId), now, now + duration)
        diagnostics?.record(Gate.SDK_LEASE, Verdict.PASS, Reason.CURRENT,
            MapFeedbackDiagnostics.Sample.from(payload).copy(ageMs = 0, leaseRemainingMs = duration))
        return true
    }

    @Synchronized
    fun frames(): Set<NexusRenderedMap> {
        expire(clock())
        return leases.values.mapTo(linkedSetOf()) { it.frame }
    }

    fun views(): Set<NexusRenderedMapView> = frames().mapTo(linkedSetOf()) { it.view }

    @Synchronized
    fun nextExpiryDelayMs(): Long? {
        val now = clock()
        expire(now)
        return leases.values.minOfOrNull { it.until - now }
    }

    @Synchronized
    fun clear(kind: String? = null) {
        leases.filterKeys { kind == null || it == kind }.values.forEach { lease ->
            diagnostics?.record(Gate.SDK_LEASE, Verdict.CLEAR, Reason.RESET, sample(lease, clock()))
        }
        if (kind == null) leases.clear() else leases.remove(kind)
    }

    private fun reject(payload: JSONObject): Boolean {
        diagnostics?.record(Gate.SDK_LEASE, Verdict.DROP, Reason.MALFORMED, MapFeedbackDiagnostics.Sample.from(payload))
        return false
    }

    private fun expire(now: Long) {
        leases.entries.removeAll { (_, lease) ->
            (now >= lease.until).also { expired ->
                if (expired) diagnostics?.record(Gate.SDK_LEASE, Verdict.CLEAR, Reason.EXPIRED, sample(lease, now))
            }
        }
    }

    private fun sample(lease: Lease, now: Long) = MapFeedbackDiagnostics.Sample(
        kind = if (lease.frame.pageId == null) MapFeedbackDiagnostics.Kind.AMBIENT else MapFeedbackDiagnostics.Kind.PAGE,
        view = if (lease.frame.view == NexusRenderedMapView.FOLLOW) MapFeedbackDiagnostics.View.FOLLOW else MapFeedbackDiagnostics.View.OVERVIEW,
        revision = lease.frame.revision, ageMs = now - lease.receivedAt,
        leaseRemainingMs = (lease.until - now).coerceAtLeast(0),
    )
}
