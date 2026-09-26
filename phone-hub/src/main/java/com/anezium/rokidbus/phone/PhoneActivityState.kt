package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.ActivityCloseReason
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.ActivitySurfacePatchResult
import com.anezium.rokidbus.shared.ActivitySurfaceValidationResult
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

internal data class CanonicalPhoneActivity(
    val ownerPluginId: String,
    val content: ActivitySurfaceContent,
    /** Full-state payload used for a start or reconnect resend. Never carries `significant`. */
    val payload: JSONObject,
    /** Fixed when the session starts. Updates cannot extend it. */
    val maxDurationDeadlineMs: Long?,
    /** Monotonic order of this session's start, including same-owner restarts. */
    val startedOrder: Long,
    /** Monotonic local order used for deterministic capacity eviction. */
    val lastUpdatedOrder: Long,
    /** Last significant update in this session, used only to protect the current primary. */
    val lastSignificantOrder: Long?,
)

internal sealed interface PhoneActivityStartResult {
    data class Accepted(
        val activity: CanonicalPhoneActivity,
        val payload: JSONObject,
        /** Present only when a third distinct owner displaced a resident. */
        val replaced: PhoneActivityClearResult.Cleared?,
    ) : PhoneActivityStartResult

    data class Rejected(val code: String) : PhoneActivityStartResult
}

internal sealed interface PhoneActivityUpdateResult {
    data class Accepted(
        val activity: CanonicalPhoneActivity,
        /** Full normalized update, including transient `significant` when true. */
        val payload: JSONObject,
        val significant: Boolean,
    ) : PhoneActivityUpdateResult

    data class Rejected(val code: String) : PhoneActivityUpdateResult

    /** No session for this owner. Logged, never exposed as a protocol error. */
    data object Ignored : PhoneActivityUpdateResult
}

internal sealed interface PhoneActivityClearResult {
    data class Cleared(
        val ownerPluginId: String,
        val reason: ActivityCloseReason,
        val payload: JSONObject,
    ) : PhoneActivityClearResult

    data object Ignored : PhoneActivityClearResult
}

/**
 * Canonical phone-side activity state.
 *
 * Activities are keyed by owner, survive a glasses-link interruption, and end when their
 * owning plugin connection disappears. Transport, scheduling, and callback delivery remain
 * in [BusHubService].
 */
internal class PhoneActivityState(
    private val nowMs: () -> Long,
    initialSequence: Long = System.currentTimeMillis(),
) {
    private val sequence = AtomicLong(initialSequence)
    private val residents = linkedMapOf<String, CanonicalPhoneActivity>()
    private val recentUpdatesByPlugin = mutableMapOf<String, ArrayDeque<Long>>()

    @Synchronized
    fun start(ownerPluginId: String, payload: JSONObject): PhoneActivityStartResult {
        if (!hasExpectedIdentity(ownerPluginId, payload)) {
            return PhoneActivityStartResult.Rejected(ActivitySurfaceContract.ERROR_INVALID_ACTIVITY)
        }
        val validation = ActivitySurfaceContract.validateStart(payload)
        if (validation !is ActivitySurfaceValidationResult.Valid) {
            return PhoneActivityStartResult.Rejected(ActivitySurfaceContract.ERROR_INVALID_ACTIVITY)
        }

        val now = nowMs()

        val replacingOwnSession = ownerPluginId in residents
        val replaced = if (
            !replacingOwnSession &&
            residents.size >= ActivitySurfaceContract.MAX_ACTIVE_ACTIVITIES
        ) {
            clearActive(selectEvictionOwner(), ActivityCloseReason.REPLACED)
        } else {
            null
        }

        val content = validation.content
        val order = sequence.incrementAndGet()
        val normalized = normalizedStart(ownerPluginId, content, order)
        val activity = CanonicalPhoneActivity(
            ownerPluginId = ownerPluginId,
            content = content,
            payload = normalized,
            maxDurationDeadlineMs = content.maxDurationMs?.let { now + it },
            startedOrder = order,
            lastUpdatedOrder = order,
            // Significance belongs to an individual session and never survives a restart.
            lastSignificantOrder = null,
        )
        residents[ownerPluginId] = activity
        return PhoneActivityStartResult.Accepted(
            activity = activity,
            payload = JSONObject(normalized.toString()),
            replaced = replaced,
        )
    }

    @Synchronized
    fun update(ownerPluginId: String, payload: JSONObject): PhoneActivityUpdateResult {
        val current = residents[ownerPluginId] ?: return PhoneActivityUpdateResult.Ignored
        if (!hasExpectedIdentity(ownerPluginId, payload)) {
            return PhoneActivityUpdateResult.Rejected(ActivitySurfaceContract.ERROR_INVALID_ACTIVITY)
        }
        val validation = ActivitySurfaceContract.validateUpdate(payload)
        if (validation !is ActivitySurfacePatchResult.Valid) {
            return PhoneActivityUpdateResult.Rejected(ActivitySurfaceContract.ERROR_INVALID_ACTIVITY)
        }

        val patched = validation.patch.applyTo(current.content)
        val now = nowMs()
        if (!admit(ownerPluginId, now)) {
            return PhoneActivityUpdateResult.Rejected(ActivitySurfaceContract.ERROR_ACTIVITY_RATE_LIMITED)
        }

        val order = sequence.incrementAndGet()
        val canonicalPayload = normalizedStart(ownerPluginId, patched, order)
        val forwarded = withIdentity(
            ownerPluginId,
            ActivitySurfaceContract.toUpdatePayload(
                activityId(ownerPluginId),
                patched,
                validation.patch.significant,
                validation.patch.urgent,
            ),
            order,
        )
        val activity = current.copy(
            content = patched,
            payload = canonicalPayload,
            // The original absolute deadline is deliberately retained.
            lastUpdatedOrder = order,
            lastSignificantOrder = if (validation.patch.significant) {
                order
            } else {
                current.lastSignificantOrder
            },
        )
        residents[ownerPluginId] = activity
        return PhoneActivityUpdateResult.Accepted(
            activity = activity,
            payload = forwarded,
            significant = validation.patch.significant,
        )
    }

    @Synchronized
    fun end(ownerPluginId: String): PhoneActivityClearResult =
        if (ownerPluginId in residents) {
            clearActive(ownerPluginId, ActivityCloseReason.OWNER)
        } else {
            PhoneActivityClearResult.Ignored
        }

    @Synchronized
    fun ownerDisconnected(ownerPluginId: String): PhoneActivityClearResult =
        if (ownerPluginId in residents) {
            clearActive(ownerPluginId, ActivityCloseReason.DISCONNECT)
        } else {
            PhoneActivityClearResult.Ignored
        }

    @Synchronized
    fun ownerLostAccess(ownerPluginId: String): PhoneActivityClearResult =
        ownerDisconnected(ownerPluginId)

    @Synchronized
    fun disconnectAll(): List<PhoneActivityClearResult.Cleared> =
        residents.keys.toList().map { ownerPluginId ->
            clearActive(ownerPluginId, ActivityCloseReason.DISCONNECT)
        }

    @Synchronized
    fun closedByGlasses(
        surfaceId: String,
        reason: ActivityCloseReason,
    ): PhoneActivityClearResult {
        val owner = ownerForActivity(surfaceId) ?: return PhoneActivityClearResult.Ignored
        return clearActive(owner, reason)
    }

    /** Clears every duration-limited activity currently due. */
    @Synchronized
    fun expireIfDue(): List<PhoneActivityClearResult.Cleared> {
        val now = nowMs()
        val dueOwners = residents.values
            .filter { activity ->
                activity.maxDurationDeadlineMs?.let { now >= it } == true
            }
            .map { it.ownerPluginId }
        return dueOwners.map { owner ->
            clearActive(owner, ActivityCloseReason.MAX_DURATION)
        }
    }

    @Synchronized
    fun nextExpiryDeadlineMs(): Long? =
        residents.values.mapNotNull { it.maxDurationDeadlineMs }.minOrNull()

    @Synchronized
    fun ownerPluginIds(): Set<String> = residents.keys.toSet()

    @Synchronized
    fun primaryOwnerPluginId(): String? {
        val significant = residents.values
            .filter { it.lastSignificantOrder != null }
            .maxWithOrNull(
                compareBy<CanonicalPhoneActivity> { it.lastSignificantOrder }
                    .thenBy { it.startedOrder }
                    .thenBy { it.ownerPluginId },
            )
        return significant?.ownerPluginId ?: residents.values
            .minWithOrNull(
                compareBy<CanonicalPhoneActivity> { it.startedOrder }
                    .thenBy { it.ownerPluginId },
            )
            ?.ownerPluginId
    }

    @Synchronized
    fun ownerForActivity(surfaceId: String): String? {
        val owner = surfaceId
            .takeIf { it.endsWith(":${ActivitySurfaceContract.LOCAL_SURFACE_ID}") }
            ?.substringBeforeLast(':')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return owner.takeIf {
            residents[it]?.payload?.optString("surfaceId") == surfaceId
        }
    }

    /**
     * Resolves only an action still present in the canonical activity. This prevents a delayed
     * glasses event from firing an action that a newer update already removed.
     */
    @Synchronized
    fun ownerForAction(surfaceId: String, actionId: String): String? {
        if (actionId.isBlank()) return null
        val owner = ownerForActivity(surfaceId) ?: return null
        val actions = residents[owner]?.payload?.optJSONArray("actions") ?: return null
        for (index in 0 until actions.length()) {
            if (actions.optJSONObject(index)?.optString("id") == actionId) return owner
        }
        return null
    }

    /**
     * Rebuilds all live starts for a newly announced glasses hub.
     *
     * `significant` is absent so reconnect cannot replay a flare. A remaining maximum duration
     * is bounded like the shared start contract while the phone retains the exact fixed deadline.
     */
    @Synchronized
    fun payloadsForResend(): List<JSONObject> {
        val now = nowMs()
        val primary = primaryOwnerPluginId()
        val resendOrder = residents.values.sortedWith(
            compareBy<CanonicalPhoneActivity> {
                if (it.ownerPluginId == primary) 0 else 1
            }.thenBy { it.startedOrder }
                .thenBy { it.ownerPluginId },
        )
        return resendOrder.map { activity ->
            val ownerPluginId = activity.ownerPluginId
            val resendSequence = sequence.incrementAndGet()
            val remainingDuration = activity.maxDurationDeadlineMs?.let { deadline ->
                (deadline - now)
                    .coerceAtLeast(ActivitySurfaceContract.MIN_MAX_DURATION_MS)
                    .coerceAtMost(ActivitySurfaceContract.MAX_MAX_DURATION_MS)
            }
            val resendContent = activity.content.copy(maxDurationMs = remainingDuration)
            // Advance the canonical wire watermark too, but do not alter activity recency or
            // the original content/deadline. A reconnect is transport bookkeeping, not an update.
            residents[ownerPluginId] = activity.copy(
                payload = normalizedStart(
                    ownerPluginId,
                    activity.content,
                    resendSequence,
                ),
            )
            withIdentity(
                ownerPluginId,
                ActivitySurfaceContract.toPayload(
                    activityId(ownerPluginId),
                    resendContent,
                ),
                resendSequence,
            )
        }
    }

    /**
     * Hub-owned global clear sentinel sent before reconnect resends.
     *
     * Activities are multi-slot, so an owner-specific end cannot remove ghosts left by a
     * previous phone process. Its owner is deliberately outside the plugin-id grammar, so an
     * ordinary plugin end can never be mistaken for this clear-all marker.
     */
    @Synchronized
    fun emptySlotAssertPayload(): JSONObject =
        ActivitySurfaceContract.emptySlotAssertPayload(sequence.incrementAndGet())

    private fun hasExpectedIdentity(ownerPluginId: String, payload: JSONObject): Boolean =
        payload.optString("surfaceId") == activityId(ownerPluginId) &&
            payload.optString("localSurfaceId") == ActivitySurfaceContract.LOCAL_SURFACE_ID &&
            payload.optString("ownerPluginId") == ownerPluginId

    private fun normalizedStart(
        ownerPluginId: String,
        content: ActivitySurfaceContent,
        order: Long,
    ): JSONObject = withIdentity(
        ownerPluginId,
        ActivitySurfaceContract.toPayload(activityId(ownerPluginId), content),
        order,
    )

    private fun withIdentity(
        ownerPluginId: String,
        payload: JSONObject,
        order: Long,
    ): JSONObject = payload
        .put("localSurfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
        .put("ownerPluginId", ownerPluginId)
        .put("seq", order)

    private fun activityId(ownerPluginId: String): String =
        "$ownerPluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}"

    private fun selectEvictionOwner(): String {
        check(residents.isNotEmpty())
        val primary = primaryOwnerPluginId()
        val nonPrimary = residents.values.filter { it.ownerPluginId != primary }
        return (nonPrimary.ifEmpty { residents.values.toList() })
            .minBy { it.lastUpdatedOrder }
            .ownerPluginId
    }

    /** The only method that removes canonical state. */
    private fun clearActive(
        ownerPluginId: String,
        reason: ActivityCloseReason,
    ): PhoneActivityClearResult.Cleared {
        checkNotNull(residents.remove(ownerPluginId))
        return PhoneActivityClearResult.Cleared(
            ownerPluginId = ownerPluginId,
            reason = reason,
            payload = JSONObject()
                .put("surfaceId", activityId(ownerPluginId))
                .put("localSurfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
                .put("ownerPluginId", ownerPluginId)
                .put("seq", sequence.incrementAndGet()),
        )
    }

    /** Sliding one-second window for accepted updates, exactly as the wire contract states. */
    private fun admit(ownerPluginId: String, now: Long): Boolean {
        val window = recentUpdatesByPlugin.getOrPut(ownerPluginId) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() >= RATE_WINDOW_MS) {
            window.removeFirst()
        }
        if (window.size >= ActivitySurfaceContract.MAX_UPDATES_PER_SECOND) return false
        window.addLast(now)
        return true
    }

    private companion object {
        const val RATE_WINDOW_MS = 1_000L
    }
}
