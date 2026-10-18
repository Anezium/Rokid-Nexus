package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.RenderedMapContract
import org.json.JSONObject

/** The map view actually rendered by the glasses, under a short hub-issued lease. */
enum class NexusRenderedMapView { FOLLOW, OVERVIEW }

/** An acknowledged owner publication. A null [pageId] identifies activity geometry. */
data class NexusRenderedMap(val view: NexusRenderedMapView, val revision: Long, val pageId: String? = null)

internal class NexusRenderedMaps(private val clock: () -> Long) {
    private data class Lease(val token: String, val frame: NexusRenderedMap, val until: Long)
    private val leases = mutableMapOf<String, Lease>()

    @Synchronized
    fun receive(payload: JSONObject): Boolean {
        val kind = payload.optString("kind")
        if (kind != RenderedMapContract.AMBIENT && kind != RenderedMapContract.PAGE) return false
        val token = (payload.opt("token") as? String)?.takeIf { it.length in 1..64 && it.none(Char::isISOControl) } ?: return false
        val visible = payload.opt("visible") as? Boolean ?: return false
        if (!visible) {
            if (leases[kind]?.token == token) leases.remove(kind)
            return true
        }
        val view = when (payload.optString("view")) {
            RenderedMapContract.FOLLOW -> NexusRenderedMapView.FOLLOW
            RenderedMapContract.OVERVIEW -> NexusRenderedMapView.OVERVIEW
            else -> return false
        }
        if (kind == RenderedMapContract.AMBIENT && view != NexusRenderedMapView.FOLLOW) return false
        val revision = when (val value = payload.opt("revision")) {
            is Int -> value.toLong()
            is Long -> value
            else -> return false
        }.takeIf { it >= 0 } ?: return false
        val pageId = if (kind == RenderedMapContract.PAGE) {
            (payload.opt("pageId") as? String)?.takeIf { it.length in 1..160 && it.none(Char::isISOControl) } ?: return false
        } else null
        val duration = when (val value = payload.opt("leaseMs")) {
            is Int -> value.toLong()
            is Long -> value
            else -> return false
        }.takeIf { it in 1..RenderedMapContract.LEASE_MS } ?: return false
        leases[kind] = Lease(token, NexusRenderedMap(view, revision, pageId), clock() + duration)
        return true
    }

    @Synchronized
    fun frames(): Set<NexusRenderedMap> {
        leases.entries.removeAll { clock() >= it.value.until }
        return leases.values.mapTo(linkedSetOf()) { it.frame }
    }

    fun views(): Set<NexusRenderedMapView> = frames().mapTo(linkedSetOf()) { it.view }

    @Synchronized
    fun nextExpiryDelayMs(): Long? {
        val now = clock()
        leases.entries.removeAll { now >= it.value.until }
        return leases.values.minOfOrNull { it.until - now }
    }

    @Synchronized
    fun clear(kind: String? = null) {
        if (kind == null) leases.clear() else leases.remove(kind)
    }
}
