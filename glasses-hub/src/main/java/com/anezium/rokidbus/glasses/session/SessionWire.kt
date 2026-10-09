package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageTemplateContract
import org.json.JSONObject

/**
 * What the glasses put on the page routes for each effect, and what they let in. Local `nexus:`
 * pages never leave the hub, and a call that names no frame is never sent.
 */
internal object SessionWire {
    fun isLocal(pageId: String): Boolean = PageTemplateContract.isLocalPageId(pageId)

    /** The request with what is left of its absolute budget, never above eight seconds. */
    fun requestPayload(effect: SessionEffect.RequestPage, nowMs: Long): JSONObject {
        val payload = effect.request.toPayload()
        val remaining = (effect.deadlineMs ?: (nowMs + PageSurfaceContract.PAGE_TIMEOUT_MS)) - nowMs
        payload.put(PageSurfaceContract.FIELD_REMAINING_TIMEOUT_MS, remaining.coerceIn(0, PageSurfaceContract.PAGE_TIMEOUT_MS))
        effect.minimumRevision?.let { payload.put(PageSurfaceContract.FIELD_MINIMUM_REVISION, it) }
        return payload
    }

    /** Null for a local page or a call without frame identity. */
    fun visibilityPayload(effect: SessionEffect.SendVisibility, nowMs: Long): JSONObject? {
        val frame = effect.frame ?: return null
        if (isLocal(effect.pageId)) return null
        val payload = JSONObject()
            .put("pageId", effect.pageId)
            .put("visible", effect.visible)
            .put("sessionGeneration", frame.sessionGeneration)
            .put("frameIndex", frame.frameIndex)
        val lease = effect.leaseUntilMs
        if (effect.visible && lease != null) {
            payload.put("leaseUntilMs", lease)
            payload.put(PageSurfaceContract.FIELD_LEASE_DURATION_MS, (lease - nowMs).coerceIn(1, PageSurfaceContract.LEASE_MS))
        }
        return payload
    }

    /** Null for a local page or a close that names no frame, as for a refused seventh one. */
    fun closedPayload(effect: SessionEffect.SendClosed): JSONObject? {
        val frame = effect.frame ?: return null
        if (isLocal(effect.pageId)) return null
        return JSONObject()
            .put("pageId", effect.pageId)
            .put("reason", effect.reason)
            .put("sessionGeneration", frame.sessionGeneration)
            .put("frameIndex", frame.frameIndex)
    }

    /**
     * A phone answer enters the session only when the phone stamped it for the owner of the
     * page it names, and never for a page the glasses author themselves.
     */
    fun acceptsResponse(payload: JSONObject): Boolean {
        val owner = payload.opt(PageSurfaceContract.FIELD_PLUGIN_ID) as? String ?: return false
        val pageId = payload.opt("pageId") as? String ?: return false
        return !isLocal(pageId) && PageTemplateContract.isProviderPageId(pageId, owner)
    }

    /** A result names no page; it must come from the owner of the page that holds the invocation. */
    fun acceptsResult(payload: JSONObject, topPageId: String?): Boolean {
        val owner = payload.opt(PageSurfaceContract.FIELD_PLUGIN_ID) as? String ?: return false
        topPageId ?: return false
        return !isLocal(topPageId) && PageTemplateContract.isProviderPageId(topPageId, owner)
    }
}

/** One live activity as the glasses' activity tier holds it. */
internal data class ActivitySource(
    val surfaceId: String,
    val ownerPluginId: String,
    val primary: Boolean,
    val text: String,
    val detail: String?,
    val measure: String?,
)

/**
 * Turns the activity tier's snapshots into session events: a newcomer starts, a change of value
 * updates, a disappearance ends. Each card carries its owner's catalog name and the wall time of
 * its latest content.
 */
internal class ActivityFeed(
    private val ownerLabel: (String) -> String?,
    private val pageIdFor: (String) -> String,
    private val wallClock: () -> Long,
) {
    private val known = linkedMapOf<String, ActivityStop>()

    fun update(sources: List<ActivitySource>): List<SessionEvent> {
        val events = mutableListOf<SessionEvent>()
        val seen = mutableSetOf<String>()
        sources.forEach { source ->
            seen += source.surfaceId
            val previous = known[source.surfaceId]
            val contentChanged = previous == null || previous.text != source.text ||
                previous.detail != source.detail || previous.measure != source.measure
            val stop = ActivityStop(
                id = source.surfaceId,
                pageId = pageIdFor(source.ownerPluginId),
                text = source.text,
                pinned = source.primary,
                ownerPluginId = source.ownerPluginId,
                ownerLabel = ownerLabel(source.ownerPluginId) ?: source.ownerPluginId,
                detail = source.detail,
                measure = source.measure,
                updatedAtWallMs = if (contentChanged) wallClock() else previous?.updatedAtWallMs,
            )
            if (stop == previous) return@forEach
            known[source.surfaceId] = stop
            events += if (previous == null) SessionEvent.ActivityStarted(stop) else SessionEvent.ActivityUpdated(stop)
        }
        known.keys.filter { it !in seen }.toList().forEach { id ->
            known.remove(id)
            events += SessionEvent.ActivityEnded(id)
        }
        return events
    }

    /** The latest known activities, for the hub's own activity pages. */
    fun current(): List<ActivityStop> = known.values.toList()
}
