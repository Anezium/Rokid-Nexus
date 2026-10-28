package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.ActivityEntry
import com.anezium.rokidbus.shared.ActivityAction
import com.anezium.rokidbus.shared.ActivityActivation
import com.anezium.rokidbus.shared.ActivityCommandResult
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.PageDestination
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

    /**
     * An owner's `/activity/result` as the phone forwarded it: well formed, and, when the phone
     * stamped an owner, for that owner's own activity. The reducer still matches it to a pending
     * command by invocation; anything else is dropped there.
     */
    fun activityResult(payload: JSONObject): ActivityCommandResult? {
        val result = ActivitySurfaceContract.parseResult(payload) ?: return null
        val owner = payload.opt("ownerPluginId") as? String
        if (owner != null && result.activityId != "$owner:${ActivitySurfaceContract.LOCAL_SURFACE_ID}") return null
        return result
    }

    /** A result names no page; it must come from the owner of the page that holds the invocation. */
    fun acceptsResult(payload: JSONObject, topPageId: String?): Boolean {
        val owner = payload.opt(PageSurfaceContract.FIELD_PLUGIN_ID) as? String ?: return false
        topPageId ?: return false
        return !isLocal(topPageId) && PageTemplateContract.isProviderPageId(topPageId, owner)
    }
}

/**
 * One collection entry as the glasses' activity tier holds it. The launcher-bundle fields are
 * empty unless the pair negotiated it: [activation] is the effective one, [commands] the plugin's
 * actions with the phone's quick stamps, [seq] the revision a command binds to.
 */
internal data class ActivitySource(
    val surfaceId: String,
    val ownerPluginId: String,
    val primary: Boolean,
    val text: String,
    val detail: String?,
    val measure: String?,
    val userPinned: Boolean = false,
    val offline: Boolean = false,
    val knownAtWallMs: Long? = null,
    val seq: Long = 0,
    val destination: PageDestination? = null,
    val activation: ActivityActivation? = null,
    val commands: List<ActivityAction> = emptyList(),
    val defaultActionId: String? = null,
    val startedOrder: Long = 0,
    val significantOrder: Long? = null,
    /** Held while the phone link is down; see [ActivityStop.stale]. */
    val stale: Boolean = false,
    /** Its launcher fields were validated before a link loss and nothing was announced since; see [ActivityStop.held]. */
    val held: Boolean = false,
)

/**
 * [entry] as the session reads it. The launcher-bundle fields are read only over a pair that
 * negotiated it ([interactive]); a v1 pair gets v1 cards, its 12-character primary included.
 * Through a link loss ([held]) the card keeps what the bundle validated, to read and to open
 * inert, but never a command: until the phone announces again, nothing on it can be sent.
 */
internal fun activitySourceOf(entry: ActivityEntry, interactive: Boolean, held: Boolean): ActivitySource {
    val content = entry.content
    val base = ActivitySource(
        surfaceId = entry.surfaceId,
        ownerPluginId = entry.ownerPluginId,
        primary = entry.primary,
        text = content.primary,
        detail = content.secondary,
        measure = content.eta ?: content.measure,
        startedOrder = entry.startedOrder,
        significantOrder = entry.lastSignificantOrder,
        // The link is the hub's own fact: a v1 card shows its last-known state too.
        stale = entry.stale,
        knownAtWallMs = entry.knownAtWallMs.takeIf { entry.stale },
    )
    return when {
        interactive -> base.copy(
            text = content.headline ?: content.primary,
            detail = content.wholeSecondary ?: content.secondary,
            userPinned = content.userPinned,
            offline = content.isOffline,
            knownAtWallMs = content.knownAtWallMs ?: base.knownAtWallMs,
            seq = entry.seq,
            destination = content.destination,
            activation = content.effectiveActivation,
            commands = if (content.isOffline) emptyList() else content.actions,
            defaultActionId = content.effectiveDefaultActionId,
        )
        held -> base.copy(
            text = content.headline ?: content.primary,
            detail = content.wholeSecondary ?: content.secondary,
            userPinned = content.userPinned,
            offline = content.isOffline,
            knownAtWallMs = content.knownAtWallMs ?: base.knownAtWallMs,
            destination = content.destination,
            activation = content.effectiveActivation,
            held = true,
        )
        else -> base
    }
}

/**
 * Turns the activity tier's snapshots into session events: a newcomer starts, a change of value
 * updates, a disappearance ends. Each card carries its owner's catalog name, icon and whether its
 * full app can be opened, and the wall time of its latest content.
 */
internal class ActivityFeed(
    private val ownerLabel: (String) -> String?,
    private val pageIdFor: (String) -> String,
    private val wallClock: () -> Long,
    /** The owner's full app is in the Applications catalog, and is not a native app. */
    private val launchable: (String) -> Boolean = { false },
    private val iconOf: (String) -> IconRef? = { null },
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
                userPinned = source.userPinned,
                offline = source.offline,
                knownAtWallMs = source.knownAtWallMs,
                stale = source.stale,
                seq = source.seq,
                destination = source.destination,
                activation = source.activation,
                commands = source.commands,
                defaultActionId = source.defaultActionId,
                launchable = launchable(source.ownerPluginId),
                icon = iconOf(source.ownerPluginId),
                startedOrder = source.startedOrder,
                significantOrder = source.significantOrder,
                held = source.held,
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
