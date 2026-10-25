package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.ActivityCloseReason
import com.anezium.rokidbus.shared.ActivityCommand
import com.anezium.rokidbus.shared.ActivityCommandMode
import com.anezium.rokidbus.shared.ActivityCommandResult
import com.anezium.rokidbus.shared.ActivityHubStamps
import com.anezium.rokidbus.shared.ActivityPersistence
import com.anezium.rokidbus.shared.ActivityState
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.ActivitySurfacePatchResult
import com.anezium.rokidbus.shared.ActivitySurfaceValidationResult
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.QuickActionFamily
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

internal data class CanonicalPhoneActivity(
    val ownerPluginId: String,
    val content: ActivitySurfaceContent,
    /**
     * Full-state payload used for a start or reconnect resend. Never carries `significant`, and
     * never a hub stamp a plugin supplied; the phone's own stamps are written at send time.
     */
    val payload: JSONObject,
    /** Fixed when the session starts. Updates cannot extend it; an offline entry has none. */
    val maxDurationDeadlineMs: Long?,
    /** Monotonic order of this session's start, including same-owner restarts. */
    val startedOrder: Long,
    /** Monotonic local order used for deterministic capacity eviction. */
    val lastUpdatedOrder: Long,
    /** Last significant update in this session, used only to protect the current primary. */
    val lastSignificantOrder: Long?,
    /** Wall-clock time the content was last confirmed live. */
    val knownAtWallMs: Long? = null,
) {
    val offline: Boolean get() = content.isOffline

    /** The revision the glasses were last sent for this entry. */
    val seq: Long get() = payload.optLong("seq")
}

/**
 * The user's choices that decide how an owner's entry is admitted and stamped. Resolved by owner
 * id because canonical state is keyed that way; the hub maps the id to the grant key.
 */
internal interface PhoneActivityOwnerPolicy {
    fun isEnabled(ownerPluginId: String): Boolean = true
    fun isPinned(ownerPluginId: String): Boolean = false

    /** The quick-action families the user approved for the owner and it still declares. */
    fun quickFamilies(ownerPluginId: String): Set<QuickActionFamily> = emptySet()

    companion object {
        val DEFAULT: PhoneActivityOwnerPolicy = object : PhoneActivityOwnerPolicy {}
    }
}

internal sealed interface PhoneActivityStartResult {
    data class Accepted(
        val activity: CanonicalPhoneActivity,
        /** What goes to the glasses: stamped with the bundle, stripped of it without. */
        val payload: JSONObject,
        /** Present only when a new owner displaced a resident at capacity. */
        val replaced: PhoneActivityClearResult.Cleared?,
        /** The start revived this owner's offline entry. */
        val revived: Boolean = false,
    ) : PhoneActivityStartResult

    /** [ended] is the owner's own entry a refusal removed, when the user disabled its activity. */
    data class Rejected(
        val code: String,
        val ended: PhoneActivityClearResult.Cleared? = null,
    ) : PhoneActivityStartResult
}

internal sealed interface PhoneActivityUpdateResult {
    data class Accepted(
        val activity: CanonicalPhoneActivity,
        /** Full normalized update, including transient `significant` when true. */
        val payload: JSONObject,
        val significant: Boolean,
    ) : PhoneActivityUpdateResult

    data class Rejected(val code: String) : PhoneActivityUpdateResult

    /** No live session for this owner. Logged, never exposed as a protocol error. */
    data object Ignored : PhoneActivityUpdateResult
}

internal sealed interface PhoneActivityClearResult {
    data class Cleared(
        val ownerPluginId: String,
        val reason: ActivityCloseReason,
        val payload: JSONObject,
        /** The entry was an offline snapshot: its owner is gone and hears nothing. */
        val offline: Boolean = false,
    ) : PhoneActivityClearResult

    /** The owner left, and its entry stays as an inert offline snapshot. */
    data class WentOffline(
        val ownerPluginId: String,
        /** The offline start for the glasses, with a new `seq`. */
        val payload: JSONObject,
    ) : PhoneActivityClearResult

    data object Ignored : PhoneActivityClearResult
}

/** The phone's verdict on one launcher-bundle `/activity/action` from the glasses. */
internal sealed interface PhoneActivityCommandResult {
    /** [payload] goes to [ownerPluginId] only: `{activityId, id, invocationId, seq, typed?, pluginId}`. */
    data class Accepted(val ownerPluginId: String, val invocationId: String, val payload: JSONObject) :
        PhoneActivityCommandResult

    /** Logged by reason; the glasses show the command unconfirmed and nothing is replayed. */
    data class Rejected(val reason: String) : PhoneActivityCommandResult
}

/** What a change of the user's activity choices does to an owner's entry. */
internal sealed interface PhoneActivityPreferenceOutcome {
    data class Ended(val cleared: PhoneActivityClearResult.Cleared) : PhoneActivityPreferenceOutcome

    /**
     * The entry stays; [payload] carries its new stamps and a new `seq`: a full update for a live
     * entry, a start for an offline one ([asStart]), which only ever arrives whole.
     */
    data class Restamped(
        val ownerPluginId: String,
        val payload: JSONObject,
        val asStart: Boolean = false,
    ) : PhoneActivityPreferenceOutcome
    data object Unchanged : PhoneActivityPreferenceOutcome
}

/**
 * Canonical phone-side activity state: one collection entry per owner.
 *
 * Entries survive a glasses-link interruption. Without the launcher bundle they end when their
 * owning plugin connection disappears and the collection holds two, exactly as activity v1 did.
 * With it the collection holds eight, an ongoing or pinned entry stays as an inert offline
 * snapshot when its owner leaves, every entry carries the phone's stamps, and commands are bound
 * to the visible `seq`. Transport, scheduling, persistence and callback delivery remain in
 * [PhoneActivityRouter] and [BusHubService].
 */
internal class PhoneActivityState(
    private val nowMs: () -> Long,
    initialSequence: Long = System.currentTimeMillis(),
    private val wallMs: () -> Long = System::currentTimeMillis,
    /**
     * The launcher bundle version negotiated with the glasses, kept across link loss like
     * activity support; null before any glasses announced since the hub started.
     */
    private val launcherVersion: () -> Int? = { 0 },
    private val policy: PhoneActivityOwnerPolicy = PhoneActivityOwnerPolicy.DEFAULT,
) {
    private data class PendingCommand(
        val invocationId: String,
        val deadlineMs: Long,
        /** The family a quick command ran under; null for a confirmed one. */
        val quickFamily: QuickActionFamily?,
    )

    private val sequence = AtomicLong(initialSequence)
    private val residents = linkedMapOf<String, CanonicalPhoneActivity>()
    private val recentUpdatesByPlugin = mutableMapOf<String, ArrayDeque<Long>>()
    private val pendingCommands = mutableMapOf<String, PendingCommand>()

    /** Invocation ids already used, so a command can never be replayed under an old id. */
    private val usedInvocations = linkedSetOf<String>()

    /** Bumped whenever the set or content of offline entries changes, for persistence. */
    private var offlineRevision = 0L

    /** Whether the glasses are known to run the bundle; only then are its fields sent. */
    @Synchronized
    fun bundleNegotiated(): Boolean = LauncherInteractiveContract.isActive(launcherVersion() ?: 0)

    /**
     * Whether the bundle's collection rules apply: known to be negotiated, or not known yet.
     * Before the glasses announce, the phone keeps what it holds and trims on the announce.
     */
    @Synchronized
    fun bundleRetained(): Boolean = launcherVersion()?.let(LauncherInteractiveContract::isActive) ?: true

    @Synchronized
    fun capacity(): Int =
        if (bundleRetained()) ActivitySurfaceContract.MAX_COLLECTION_ACTIVITIES else ActivitySurfaceContract.MAX_ACTIVE_ACTIVITIES

    @Synchronized
    fun start(ownerPluginId: String, payload: JSONObject): PhoneActivityStartResult {
        if (!hasExpectedIdentity(ownerPluginId, payload)) {
            return PhoneActivityStartResult.Rejected(ActivitySurfaceContract.ERROR_INVALID_ACTIVITY)
        }
        // A plugin's own stamps are never read: the validator ignores them, and the canonical
        // payload is re-serialized from the content, so none survives into what is forwarded.
        val validation = ActivitySurfaceContract.validateStart(payload)
        if (validation !is ActivitySurfaceValidationResult.Valid) {
            return PhoneActivityStartResult.Rejected(ActivitySurfaceContract.ERROR_INVALID_ACTIVITY)
        }
        val content = validation.content
        if (!ActivitySurfaceContract.destinationAllowed(content, ownerPluginId)) {
            return PhoneActivityStartResult.Rejected(ActivitySurfaceContract.ERROR_INVALID_ACTIVITY)
        }
        if (!policy.isEnabled(ownerPluginId)) {
            val ended = residents[ownerPluginId]?.let { clearActive(ownerPluginId, ActivityCloseReason.REPLACED) }
            return PhoneActivityStartResult.Rejected(ActivitySurfaceContract.ERROR_ACTIVITY_DISABLED, ended)
        }

        val now = nowMs()
        val existing = residents[ownerPluginId]
        val replaced = if (existing == null && residents.size >= capacity()) {
            clearActive(selectEvictionOwner(), ActivityCloseReason.REPLACED)
        } else {
            null
        }
        if (existing?.offline == true) offlineRevision++

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
            knownAtWallMs = wallMs(),
        )
        residents[ownerPluginId] = activity
        return PhoneActivityStartResult.Accepted(
            activity = activity,
            payload = wireForm(activity, normalized),
            replaced = replaced,
            revived = existing?.offline == true,
        )
    }

    @Synchronized
    fun update(ownerPluginId: String, payload: JSONObject): PhoneActivityUpdateResult {
        val current = residents[ownerPluginId]?.takeUnless { it.offline } ?: return PhoneActivityUpdateResult.Ignored
        if (!hasExpectedIdentity(ownerPluginId, payload)) {
            return PhoneActivityUpdateResult.Rejected(ActivitySurfaceContract.ERROR_INVALID_ACTIVITY)
        }
        val validation = ActivitySurfaceContract.validateUpdate(payload)
        if (validation !is ActivitySurfacePatchResult.Valid) {
            return PhoneActivityUpdateResult.Rejected(ActivitySurfaceContract.ERROR_INVALID_ACTIVITY)
        }

        val patched = validation.patch.applyTo(current.content)
        // The launcher rules hold on what the update produces, not only on the update itself.
        if (
            ActivitySurfaceContract.interactiveProblem(patched) != null ||
            !ActivitySurfaceContract.destinationAllowed(patched, ownerPluginId)
        ) {
            return PhoneActivityUpdateResult.Rejected(ActivitySurfaceContract.ERROR_INVALID_ACTIVITY)
        }
        val now = nowMs()
        if (!admit(ownerPluginId, now)) {
            return PhoneActivityUpdateResult.Rejected(ActivitySurfaceContract.ERROR_ACTIVITY_RATE_LIMITED)
        }

        val order = sequence.incrementAndGet()
        val canonicalPayload = normalizedStart(ownerPluginId, patched, order)
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
            knownAtWallMs = wallMs(),
        )
        residents[ownerPluginId] = activity
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
        return PhoneActivityUpdateResult.Accepted(
            activity = activity,
            payload = wireForm(activity, forwarded),
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

    /**
     * The owner's last registration left. With the bundle an ongoing or pinned live entry turns
     * into an offline snapshot; anything else ends as in v1.
     */
    @Synchronized
    fun ownerDisconnected(ownerPluginId: String): PhoneActivityClearResult {
        val current = residents[ownerPluginId] ?: return PhoneActivityClearResult.Ignored
        if (current.offline) return PhoneActivityClearResult.Ignored
        return if (retainsOffline(current)) {
            val offline = goOffline(current)
            PhoneActivityClearResult.WentOffline(ownerPluginId, wireForm(offline, offline.payload))
        } else {
            clearActive(ownerPluginId, ActivityCloseReason.DISCONNECT)
        }
    }

    /** Revoke, disable, uninstall or a signer change: the entry goes, offline or not. */
    @Synchronized
    fun ownerLostAccess(ownerPluginId: String): PhoneActivityClearResult =
        if (ownerPluginId in residents) {
            clearActive(ownerPluginId, ActivityCloseReason.DISCONNECT)
        } else {
            PhoneActivityClearResult.Ignored
        }

    /** The hub stops: every entry leaves memory; live owners hear `disconnect`. */
    @Synchronized
    fun disconnectAll(): List<PhoneActivityClearResult.Cleared> =
        residents.keys.toList().map { ownerPluginId ->
            clearActive(ownerPluginId, ActivityCloseReason.DISCONNECT)
        }

    /**
     * The hub stops, which every owner sees as its connection going: like [ownerDisconnected]
     * for each live entry at once. Returns the owners whose live entry ended and those whose
     * entry stayed as an offline snapshot; both lost a live activity.
     */
    @Synchronized
    fun hubStopped(): Pair<List<PhoneActivityClearResult.Cleared>, List<String>> {
        val cleared = mutableListOf<PhoneActivityClearResult.Cleared>()
        val offline = mutableListOf<String>()
        residents.values.filter { !it.offline }.forEach { activity ->
            if (retainsOffline(activity)) {
                goOffline(activity)
                offline += activity.ownerPluginId
            } else {
                cleared += clearActive(activity.ownerPluginId, ActivityCloseReason.DISCONNECT)
            }
        }
        return cleared to offline
    }

    @Synchronized
    fun closedByGlasses(
        surfaceId: String,
        reason: ActivityCloseReason,
    ): PhoneActivityClearResult {
        val owner = ownerForActivity(surfaceId) ?: return PhoneActivityClearResult.Ignored
        return clearActive(owner, reason)
    }

    /** Expires pending commands and clears every duration-limited activity currently due. */
    @Synchronized
    fun expireIfDue(): List<PhoneActivityClearResult.Cleared> {
        val now = nowMs()
        pendingCommands.entries.removeAll { now >= it.value.deadlineMs }
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
        (residents.values.mapNotNull { it.maxDurationDeadlineMs } + pendingCommands.values.map { it.deadlineMs }).minOrNull()

    @Synchronized
    fun pendingCommandOwners(): Set<String> = pendingCommands.keys.toSet()

    @Synchronized
    fun ownerPluginIds(): Set<String> = residents.keys.toSet()

    @Synchronized
    fun isLive(ownerPluginId: String): Boolean = residents[ownerPluginId]?.offline == false

    @Synchronized
    fun isOffline(ownerPluginId: String): Boolean = residents[ownerPluginId]?.offline == true

    /** The live entry the corners favour: latest significant, else oldest started. Never offline. */
    @Synchronized
    fun primaryOwnerPluginId(): String? {
        val live = residents.values.filter { !it.offline }
        val significant = live
            .filter { it.lastSignificantOrder != null }
            .maxWithOrNull(
                compareBy<CanonicalPhoneActivity> { it.lastSignificantOrder }
                    .thenBy { it.startedOrder }
                    .thenBy { it.ownerPluginId },
            )
        return significant?.ownerPluginId ?: live
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

    @Synchronized
    fun ownerForRenderedMap(surfaceId: String, seq: Long): String? {
        val owner = ownerForActivity(surfaceId) ?: return null
        val activity = residents[owner]?.takeUnless { it.offline } ?: return null
        return owner.takeIf { activity.seq == seq }
    }

    @Synchronized
    fun activitySequence(ownerPluginId: String): Long? = residents[ownerPluginId]?.seq

    /**
     * The v1 resolution: only an action still present in the live canonical activity. This
     * prevents a delayed glasses event from firing an action that a newer update removed.
     */
    @Synchronized
    fun ownerForAction(surfaceId: String, actionId: String): String? {
        if (actionId.isBlank()) return null
        val owner = ownerForActivity(surfaceId) ?: return null
        val activity = residents[owner]?.takeUnless { it.offline } ?: return null
        return owner.takeIf { activity.content.actions.any { it.id == actionId } }
    }

    /**
     * Admits one launcher-bundle command: the owner live with exactly one registration, the entry
     * live at the `seq` the wearer saw, the action still declared, a quick command only for a
     * quick-eligible type whose family the user approves now, a fresh invocation id, and no other
     * command of this owner pending. The delivery carries the phone's own `typed`, never the
     * glasses'.
     */
    @Synchronized
    fun beginCommand(command: ActivityCommand, ownerRegistrations: (String) -> Int): PhoneActivityCommandResult {
        val owner = ownerForActivity(command.activityId) ?: return commandRejected("not_current")
        val activity = residents.getValue(owner)
        if (activity.offline) return commandRejected("offline")
        if (command.seq != activity.seq) return commandRejected("stale")
        val action = activity.content.actions.firstOrNull { it.id == command.actionId } ?: return commandRejected("undeclared")
        val mode = command.mode ?: return commandRejected("no_mode")
        if (!ActivitySurfaceContract.modeAllowedFor(mode, action)) return commandRejected("mode")
        val quickFamily = if (mode == ActivityCommandMode.QUICK) {
            val family = action.typed?.family ?: return commandRejected("mode")
            if (family !in policy.quickFamilies(owner)) return commandRejected("not_approved")
            family
        } else {
            null
        }
        if (command.invocationId in usedInvocations) return commandRejected("reused")
        val now = nowMs()
        pendingCommands[owner]?.let { pending ->
            if (now < pending.deadlineMs) return commandRejected("busy")
        }
        if (ownerRegistrations(owner) != 1) return commandRejected("registration")
        usedInvocations += command.invocationId
        while (usedInvocations.size > MAX_REMEMBERED_INVOCATIONS) usedInvocations.remove(usedInvocations.first())
        pendingCommands[owner] = PendingCommand(
            invocationId = command.invocationId,
            deadlineMs = now + ActivitySurfaceContract.COMMAND_DEADLINE_MS,
            quickFamily = quickFamily,
        )
        val delivery = ActivitySurfaceContract.commandPayload(
            ActivityCommand(
                activityId = activity.payload.optString("surfaceId"),
                actionId = action.id,
                invocationId = command.invocationId,
                seq = activity.seq,
                typed = action.typed,
            ),
        ).put("pluginId", owner)
        return PhoneActivityCommandResult.Accepted(owner, command.invocationId, delivery)
    }

    /** The command never reached its owner; it is released and stays unconfirmed. */
    @Synchronized
    fun commandUndelivered(ownerPluginId: String, invocationId: String) {
        if (pendingCommands[ownerPluginId]?.invocationId == invocationId) pendingCommands.remove(ownerPluginId)
    }

    /**
     * The owner's result for its pending command, as the glasses get it, or null when it is late,
     * unknown, for another activity, or already answered.
     */
    @Synchronized
    fun completeCommand(ownerPluginId: String, result: ActivityCommandResult): JSONObject? {
        val pending = pendingCommands[ownerPluginId] ?: return null
        if (pending.invocationId != result.invocationId) return null
        pendingCommands.remove(ownerPluginId)
        if (nowMs() >= pending.deadlineMs) return null
        if (result.activityId != activityId(ownerPluginId) || ownerPluginId !in residents) return null
        return ActivitySurfaceContract.resultPayload(result).put("ownerPluginId", ownerPluginId)
    }

    /**
     * The user withdrew a quick family: a pending quick command of [ownerPluginId] under a family
     * no longer approved is rejected at once, so its result is never forwarded. True when one was.
     */
    @Synchronized
    fun rejectUnapprovedQuickCommands(ownerPluginId: String): Boolean {
        val pending = pendingCommands[ownerPluginId] ?: return false
        val family = pending.quickFamily ?: return false
        if (family in policy.quickFamilies(ownerPluginId)) return false
        pendingCommands.remove(ownerPluginId)
        return true
    }

    /** The glasses link was replaced or lost: no command they held can be answered any more. */
    @Synchronized
    fun dropPendingCommands() {
        pendingCommands.clear()
    }

    /**
     * Trims to [capacity] after the glasses re-announced. Without the bundle nothing stays
     * offline (activity v1 ends on disconnect), then the eviction order applies until the
     * collection fits. Evicted live owners must hear `replaced` before the resend.
     */
    @Synchronized
    fun trimToCapacity(): List<PhoneActivityClearResult.Cleared> {
        val cleared = mutableListOf<PhoneActivityClearResult.Cleared>()
        if (!bundleRetained()) {
            residents.values.filter { it.offline }.map { it.ownerPluginId }.forEach { owner ->
                cleared += clearActive(owner, ActivityCloseReason.REPLACED)
            }
        }
        while (residents.size > capacity()) {
            cleared += clearActive(selectEvictionOwner(), ActivityCloseReason.REPLACED)
        }
        return cleared
    }

    /**
     * Re-reads the user's choices for [ownerPluginId]: disabled ends the entry; an unpinned
     * offline entry that is not ongoing goes; anything else is restamped with a new `seq`.
     */
    @Synchronized
    fun preferencesChanged(ownerPluginId: String): PhoneActivityPreferenceOutcome {
        val current = residents[ownerPluginId] ?: return PhoneActivityPreferenceOutcome.Unchanged
        if (!policy.isEnabled(ownerPluginId)) {
            return PhoneActivityPreferenceOutcome.Ended(clearActive(ownerPluginId, ActivityCloseReason.REPLACED))
        }
        if (
            current.offline &&
            current.content.persistence != ActivityPersistence.ONGOING &&
            !policy.isPinned(ownerPluginId)
        ) {
            return PhoneActivityPreferenceOutcome.Ended(clearActive(ownerPluginId, ActivityCloseReason.REPLACED))
        }
        return restamp(current)
    }

    /** The owner's quick approvals changed: its entry is restamped when it has typed actions. */
    @Synchronized
    fun quickPolicyChanged(ownerPluginId: String): PhoneActivityPreferenceOutcome {
        val current = residents[ownerPluginId] ?: return PhoneActivityPreferenceOutcome.Unchanged
        if (current.offline || current.content.actions.none { it.typed != null }) return PhoneActivityPreferenceOutcome.Unchanged
        return restamp(current)
    }

    /**
     * Restores one persisted offline snapshot after a hub restart. The caller already checked
     * that the grant is approved with `surfaces`; this checks the snapshot itself.
     */
    @Synchronized
    fun restoreOffline(ownerPluginId: String, payload: JSONObject): Boolean {
        if (ownerPluginId in residents || residents.size >= ActivitySurfaceContract.MAX_OFFLINE_SNAPSHOTS) return false
        if (!hasExpectedIdentity(ownerPluginId, payload)) return false
        val content = (ActivitySurfaceContract.validateStart(payload, hubStamps = true) as? ActivitySurfaceValidationResult.Valid)
            ?.content ?: return false
        if (!content.isOffline || content.actions.isNotEmpty()) return false
        if (!ActivitySurfaceContract.destinationAllowed(content, ownerPluginId)) return false
        // A stamp the phone writes again at send time is not part of what it restores.
        val restored = content.copy(userPinned = false, maxDurationMs = null)
        val order = sequence.incrementAndGet()
        residents[ownerPluginId] = CanonicalPhoneActivity(
            ownerPluginId = ownerPluginId,
            content = restored,
            payload = normalizedStart(ownerPluginId, restored, order),
            maxDurationDeadlineMs = null,
            startedOrder = order,
            lastUpdatedOrder = order,
            lastSignificantOrder = null,
            knownAtWallMs = restored.knownAtWallMs,
        )
        offlineRevision++
        return true
    }

    /** The offline entries as they are persisted, with the revision they were read at. */
    @Synchronized
    fun offlineSnapshots(): Pair<Long, List<Pair<String, JSONObject>>> =
        offlineRevision to residents.values.filter { it.offline }.map { it.ownerPluginId to JSONObject(it.payload.toString()) }

    @Synchronized
    fun offlineRevision(): Long = offlineRevision

    /**
     * Rebuilds all entries for a newly announced glasses hub.
     *
     * `significant` is absent so reconnect cannot replay a flare. A remaining maximum duration
     * is bounded like the shared start contract while the phone retains the exact fixed deadline.
     * Live entries come first, the primary leading; offline ones follow.
     */
    @Synchronized
    fun payloadsForResend(): List<JSONObject> {
        val now = nowMs()
        val primary = primaryOwnerPluginId()
        val resendOrder = residents.values.sortedWith(
            compareBy<CanonicalPhoneActivity> { if (it.offline) 1 else 0 }
                .thenBy { if (it.ownerPluginId == primary) 0 else 1 }
                .thenBy { it.startedOrder }
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
            val advanced = activity.copy(
                payload = normalizedStart(
                    ownerPluginId,
                    activity.content,
                    resendSequence,
                ),
            )
            residents[ownerPluginId] = advanced
            wireForm(
                advanced,
                withIdentity(
                    ownerPluginId,
                    ActivitySurfaceContract.toPayload(
                        activityId(ownerPluginId),
                        resendContent,
                    ),
                    resendSequence,
                ),
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

    private fun retainsOffline(activity: CanonicalPhoneActivity): Boolean =
        bundleRetained() &&
            (activity.content.persistence == ActivityPersistence.ONGOING || policy.isPinned(activity.ownerPluginId))

    /** Turns a live entry into its offline snapshot in place, with a new `seq`. */
    private fun goOffline(current: CanonicalPhoneActivity): CanonicalPhoneActivity {
        val order = sequence.incrementAndGet()
        val knownAt = wallMs()
        val content = offlineContent(current, knownAt)
        val offline = current.copy(
            content = content,
            payload = normalizedStart(current.ownerPluginId, content, order),
            maxDurationDeadlineMs = null,
            knownAtWallMs = knownAt,
        )
        residents[current.ownerPluginId] = offline
        pendingCommands.remove(current.ownerPluginId)
        recentUpdatesByPlugin.remove(current.ownerPluginId)
        offlineRevision++
        return offline
    }

    /**
     * What an offline entry says: the content and destination it last had, no actions, no
     * activation, and no duration, since nothing is live to keep counting it down.
     */
    private fun offlineContent(current: CanonicalPhoneActivity, knownAt: Long): ActivitySurfaceContent =
        ActivitySurfaceContract.offlineSnapshot(current.content.copy(maxDurationMs = null, userPinned = false), knownAt)

    private fun restamp(current: CanonicalPhoneActivity): PhoneActivityPreferenceOutcome {
        val order = sequence.incrementAndGet()
        val advanced = current.copy(payload = normalizedStart(current.ownerPluginId, current.content, order))
        residents[current.ownerPluginId] = advanced
        if (advanced.offline) {
            return PhoneActivityPreferenceOutcome.Restamped(
                current.ownerPluginId,
                wireForm(advanced, JSONObject(advanced.payload.toString())),
                asStart = true,
            )
        }
        val update = withIdentity(
            current.ownerPluginId,
            ActivitySurfaceContract.toUpdatePayload(activityId(current.ownerPluginId), current.content, significant = false),
            order,
        )
        return PhoneActivityPreferenceOutcome.Restamped(current.ownerPluginId, wireForm(advanced, update))
    }

    /** The glasses' copy: the phone's stamps with the bundle, a plain v1 activity without it. */
    private fun wireForm(activity: CanonicalPhoneActivity, payload: JSONObject): JSONObject {
        if (!bundleNegotiated()) return ActivitySurfaceContract.stripLauncherFields(payload)
        val owner = activity.ownerPluginId
        val stamps = ActivityHubStamps(
            state = if (activity.offline) ActivityState.OFFLINE else ActivityState.LIVE,
            userPinned = policy.isPinned(owner),
            knownAtWallMs = activity.knownAtWallMs,
        )
        return ActivitySurfaceContract.withHubStamps(
            payload,
            stamps,
            ActivitySurfaceContract.quickActionIds(activity.content, policy.quickFamilies(owner)),
        )
    }

    private fun commandRejected(reason: String) = PhoneActivityCommandResult.Rejected(reason)

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

    /**
     * The entry a new owner displaces at capacity, first match wins: an offline entry (oldest
     * `knownAtWallMs`), then an unpinned live non-primary entry (least recently updated), then
     * the unpinned primary, then a pinned entry (least recently updated).
     */
    private fun selectEvictionOwner(): String {
        check(residents.isNotEmpty())
        residents.values.filter { it.offline }
            .minWithOrNull(compareBy<CanonicalPhoneActivity> { it.knownAtWallMs ?: Long.MIN_VALUE }.thenBy { it.ownerPluginId })
            ?.let { return it.ownerPluginId }
        val primary = primaryOwnerPluginId()
        val live = residents.values.filter { !it.offline }
        val unpinned = live.filter { !policy.isPinned(it.ownerPluginId) }
        val leastRecent = compareBy<CanonicalPhoneActivity> { it.lastUpdatedOrder }.thenBy { it.ownerPluginId }
        return (
            unpinned.filter { it.ownerPluginId != primary }.minWithOrNull(leastRecent)
                ?: unpinned.minWithOrNull(leastRecent)
                ?: live.minWith(leastRecent)
            ).ownerPluginId
    }

    /** The only method that removes canonical state. */
    private fun clearActive(
        ownerPluginId: String,
        reason: ActivityCloseReason,
    ): PhoneActivityClearResult.Cleared {
        val removed = checkNotNull(residents.remove(ownerPluginId))
        pendingCommands.remove(ownerPluginId)
        if (removed.offline) offlineRevision++
        return PhoneActivityClearResult.Cleared(
            ownerPluginId = ownerPluginId,
            reason = reason,
            payload = JSONObject()
                .put("surfaceId", activityId(ownerPluginId))
                .put("localSurfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
                .put("ownerPluginId", ownerPluginId)
                .put("seq", sequence.incrementAndGet()),
            offline = removed.offline,
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
        const val MAX_REMEMBERED_INVOCATIONS = 256
    }
}
