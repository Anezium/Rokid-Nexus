package com.anezium.rokidbus.shared

import org.json.JSONObject

/** Hub-owned authority for one rendered map, never accepted from a plugin. */
data class RenderedMapContext(
    val kind: String,
    val id: String,
    val revision: Long,
    val seq: Long = 0,
    val sessionGeneration: Long = 0,
    val frameIndex: Int = 0,
    val requestId: String = "",
) {
    fun toPayload(): JSONObject = JSONObject().put("kind", kind).put("id", id).put("revision", revision)
        .put("seq", seq).put("sessionGeneration", sessionGeneration).put("frameIndex", frameIndex).put("requestId", requestId)
}

/** V1 couples rendered-view demand with preservation and rendering of RouteGeometry.attribution. */
object RenderedMapContract {
    const val PATH = "/map/rendered"
    const val AMBIENT = "ambient"
    const val PAGE = "page"
    const val FOLLOW = "follow"
    const val OVERVIEW = "overview"
    const val LEASE_MS = 5_000L
    const val RENEW_MS = 2_000L

    fun supported(features: Int): Boolean = features and BusCapabilityBits.RENDERED_MAP != 0

    fun negotiated(phoneFeatures: Int, glassesFeatures: Int): Boolean = supported(phoneFeatures) && supported(glassesFeatures)

    fun context(payload: JSONObject): RenderedMapContext? {
        fun number(key: String): Long? = when (val value = payload.opt(key)) {
            is Int -> value.toLong().takeIf { it >= 0 }
            is Long -> value.takeIf { it >= 0 }
            else -> null
        }
        val kind = payload.opt("kind") as? String ?: return null
        val id = (payload.opt("id") as? String)?.takeIf { it.length in 1..160 && it.none(Char::isISOControl) } ?: return null
        val revision = number("revision") ?: return null
        return when (kind) {
            AMBIENT -> RenderedMapContext(kind, id, revision, seq = number("seq") ?: return null)
            PAGE -> RenderedMapContext(kind, id, revision,
                sessionGeneration = number("sessionGeneration") ?: return null,
                frameIndex = (number("frameIndex") ?: return null).takeIf { it in 1L until PageSurfaceContract.MAX_FRAMES.toLong() }?.toInt() ?: return null,
                requestId = (payload.opt("requestId") as? String)?.takeIf { it.length in 1..128 } ?: return null)
            else -> null
        }
    }

    fun validEvent(payload: JSONObject): Boolean = context(payload) != null &&
        payload.opt("visible") is Boolean && payload.opt("view") in setOf(FOLLOW, OVERVIEW) &&
        (payload.opt("token") as? String)?.let { it.length in 1..64 && it.none(Char::isISOControl) } == true

    /** Return only this owner's publication identity, never another owner's rendering context. */
    fun delivery(payload: JSONObject, owner: String): JSONObject = JSONObject()
        .put("pluginId", owner).put("kind", payload.getString("kind"))
        .put("token", payload.getString("token")).put("view", payload.getString("view"))
        .put("visible", payload.getBoolean("visible")).put("leaseMs", LEASE_MS)
        .put("revision", payload.getLong("revision")).apply {
            if (payload.getString("kind") == PAGE) put("pageId", payload.getString("id"))
        }
}
