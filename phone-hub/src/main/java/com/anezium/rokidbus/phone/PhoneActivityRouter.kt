package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.ActivityCloseReason
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.ActivitySurfacePatchResult
import com.anezium.rokidbus.shared.ActivitySurfaceValidationResult
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.RenderedMapContract
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Gate
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Reason
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Verdict
import org.json.JSONObject

/**
 * What the activity tier needs from the hub for the launcher bundle, beyond [PhoneHudRouteSink].
 * The defaults describe a hub without the bundle's transport: nothing can go over the data plane.
 */
internal interface PhoneActivityLauncherHost {
    /** Live registrations of [pluginId]; a command needs exactly one. */
    fun registrationCount(pluginId: String): Int = 0

    /** A command-only importance hold, independent of page, skill and immersion bindings. */
    fun bindCommand(pluginId: String): Boolean = false
    fun unbindCommand(pluginId: String) = Unit

    /** Whether the SPP data plane is up, which route geometry requires. */
    fun dataPlaneUp(): Boolean = false

    /** Sends over the data plane only, never over CXR control and never queued. */
    fun sendDataPlane(envelope: BusEnvelope): String? = "NO_DATA_PLANE"

    /** Replaces the persisted offline snapshots with these `(owner, offline start)` pairs. */
    fun persistOffline(snapshots: List<Pair<String, JSONObject>>) = Unit

    companion object {
        val NONE: PhoneActivityLauncherHost = object : PhoneActivityLauncherHost {}
    }
}

/**
 * Activity-tier routing: local start/update/end, TTL expiry, revoke, and reconnect resend; with
 * the launcher bundle also commands and their results, route geometry and heading, offline
 * snapshots, the user's activity choices and quick-action approvals.
 *
 * Canonical state stays in [PhoneActivityState]. The wire lock, transport, and
 * timers are owned here so [BusHubService] can keep the Binder and the glasses link.
 */
internal class PhoneActivityRouter(
    private val state: PhoneActivityState,
    private val sink: PhoneHudRouteSink,
    private val nowMs: () -> Long,
    private val postExpiry: (delayMs: Long) -> Unit,
    private val cancelExpiry: () -> Unit,
    private val launcher: PhoneActivityLauncherHost = PhoneActivityLauncherHost.NONE,
) {
    /** Orders outbound mutations and sends, including reconnect batches; feedback never takes it. */
    private val wireLock = Any()

    /** Guarded by state, alongside the owner and sequence, never across a transport write. */
    private data class GeometryPublication(val revision: Long, val atMs: Long)
    private val geometryPublications = mutableMapOf<String, GeometryPublication>()
    private val mapDiagnostics = MapFeedbackDiagnostics(nowMs, sink::log)
    private val headingWindows = mutableMapOf<String, ArrayDeque<Long>>()
    private val commandBindings = mutableMapOf<String, String>()
    private var persistedOfflineRevision = state.offlineRevision()

    fun matches(path: String): Boolean =
        path == BusPaths.ACTIVITY_START ||
            path == BusPaths.ACTIVITY_UPDATE ||
            path == BusPaths.ACTIVITY_END ||
            path == BusPaths.ACTIVITY_RESULT ||
            path == BusPaths.ACTIVITY_GEOMETRY ||
            path == BusPaths.ACTIVITY_HEADING

    fun ownerPluginIds(): Set<String> = state.ownerPluginIds()

    fun invalidLocalReason(envelope: BusEnvelope): String? {
        val invalid = envelope.binary != null ||
            envelope.payload.optString("surfaceId") != ActivitySurfaceContract.LOCAL_SURFACE_ID ||
            when (envelope.path) {
                BusPaths.ACTIVITY_START ->
                    ActivitySurfaceContract.validateStart(envelope.payload) !is ActivitySurfaceValidationResult.Valid
                BusPaths.ACTIVITY_UPDATE ->
                    ActivitySurfaceContract.validateUpdate(envelope.payload) !is ActivitySurfacePatchResult.Valid
                BusPaths.ACTIVITY_RESULT -> ActivitySurfaceContract.parseResult(envelope.payload) == null
                BusPaths.ACTIVITY_GEOMETRY -> ActivitySurfaceContract.parseGeometry(envelope.payload) == null
                BusPaths.ACTIVITY_HEADING -> ActivitySurfaceContract.parseHeading(envelope.payload) == null
                else -> false
            }
        return if (invalid) ActivitySurfaceContract.ERROR_INVALID_ACTIVITY else null
    }

    fun handleLocal(envelope: BusEnvelope, sender: PhoneHudRouteSender): Unit = synchronized(wireLock) {
        val pluginId = sender.pluginId
        if (pluginId == null || envelope.binary != null) {
            reject(
                envelope,
                sender,
                ActivitySurfaceContract.ERROR_INVALID_ACTIVITY,
            )
            return
        }
        if (!sink.activitiesSupported()) {
            reject(
                envelope,
                sender,
                ActivitySurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE,
            )
            return
        }

        when (envelope.path) {
            BusPaths.ACTIVITY_START ->
                when (val result = state.start(pluginId, envelope.payload)) {
                    is PhoneActivityStartResult.Rejected -> {
                        result.ended?.let { ended ->
                            sink.log("activity ended owner=$pluginId reason=disabled")
                            afterCleared(ended, "disabled")
                        }
                        reject(envelope, sender, result.code)
                    }
                    is PhoneActivityStartResult.Accepted -> {
                        result.replaced?.let { replaced ->
                            sink.log("activity replaced owner=${replaced.ownerPluginId} by=$pluginId")
                            afterCleared(replaced, "replaced")
                        }
                        // A new session of this owner: whatever geometry it drew before is gone.
                        clearTransient(pluginId)
                        if (result.revived) sink.log("activity revived owner=$pluginId")
                        persistIfChanged()
                        scheduleExpiry()
                        forward(envelope, result.payload, sender)
                    }
                }
            BusPaths.ACTIVITY_UPDATE ->
                when (val result = state.update(pluginId, envelope.payload)) {
                    PhoneActivityUpdateResult.Ignored -> sink.recordLocalRoute(
                        envelope,
                        sender.uid,
                        pluginId,
                        PluginBusJournal.Verdict.OK,
                        "ACTIVITY_UPDATE_IGNORED_NO_SESSION",
                    )
                    is PhoneActivityUpdateResult.Rejected ->
                        reject(envelope, sender, result.code)
                    is PhoneActivityUpdateResult.Accepted -> {
                        scheduleExpiry()
                        forward(envelope, result.payload, sender)
                    }
                }
            BusPaths.ACTIVITY_END ->
                when (val result = state.end(pluginId)) {
                    is PhoneActivityClearResult.Cleared -> {
                        scheduleExpiry()
                        clearTransient(pluginId)
                        persistIfChanged()
                        val forwarded = envelope.copy(payload = result.payload)
                        sink.recordLocalRoute(
                            forwarded,
                            sender.uid,
                            pluginId,
                            PluginBusJournal.Verdict.OK,
                        )
                        if (sink.pinLinkUp()) {
                            sink.sendRemote(forwarded)?.let {
                                sink.deliverError(sender.replyBinder, envelope.id, it)
                            }
                        } else {
                            sink.log("activity end held owner=$pluginId reason=link_down")
                        }
                        deliverClosed(result.ownerPluginId, result.reason)
                    }
                    else -> sink.recordLocalRoute(
                        envelope,
                        sender.uid,
                        pluginId,
                        PluginBusJournal.Verdict.OK,
                        "ACTIVITY_END_IGNORED_NO_SESSION",
                    )
                }
            BusPaths.ACTIVITY_RESULT -> launcherOnly(envelope, sender) { handleResult(envelope, sender, pluginId) }
            BusPaths.ACTIVITY_GEOMETRY -> launcherOnly(envelope, sender) { handleGeometry(envelope, sender, pluginId) }
            BusPaths.ACTIVITY_HEADING -> launcherOnly(envelope, sender) { handleHeading(envelope, sender, pluginId) }
        }
    }

    /**
     * `/activity/action` from the glasses. With the bundle it must be a command bound to the
     * visible `seq`; without it the v1 `{activityId, id}` is delivered as it always was.
     */
    fun handleGlassesAction(envelope: BusEnvelope): Unit = synchronized(wireLock) {
        if (state.bundleNegotiated()) {
            handleGlassesCommand(envelope)
            return
        }
        val activityId = envelope.payload.optString("activityId")
        val actionId = envelope.payload.optString("id")
        val owner = state.ownerForAction(activityId, actionId)
        if (owner == null) {
            sink.log(
                "activity action ignored id=${activityId.take(80)} " +
                    "actionPresent=${actionId.isNotBlank()} reason=not_current",
            )
            return
        }
        val payload = ActivitySurfaceContract.actionPayload(activityId, actionId).put("pluginId", owner)
        if (!sink.deliverLocal(envelope.copy(payload = payload))) {
            sink.log("activity action undelivered owner=$owner; no live registration")
        }
    }

    fun handleGlassesClosed(envelope: BusEnvelope): Unit = synchronized(wireLock) {
        val activityId = envelope.payload.optString("activityId")
        val reason = ActivityCloseReason.fromWireValue(envelope.payload.optString("reason"))
        if (reason == null) {
            sink.log("activity close ignored id=${activityId.take(80)} reason=invalid")
            return
        }
        when (val result = state.closedByGlasses(activityId, reason)) {
            is PhoneActivityClearResult.Cleared -> {
                scheduleExpiry()
                clearTransient(result.ownerPluginId)
                persistIfChanged()
                sink.log("activity closed owner=${result.ownerPluginId} reason=${reason.wireValue}")
                if (!result.offline) deliverClosed(result.ownerPluginId, result.reason)
            }
            else ->
                sink.log(
                    "activity close ignored id=${activityId.take(80)} " +
                        "reason=${reason.wireValue}",
                )
        }
    }

    /** Feedback comes only from the authenticated peer, for a live owner and its current geometry. */
    fun handleRenderedMap(payload: JSONObject) {
        val context = RenderedMapContract.context(payload)
        val visible = payload.optBoolean("visible")
        val owner = context?.let { state.ownerForActivity(it.id) }
        val supported = sink.renderedMapsSupported()
        val dataPlaneUp = launcher.dataPlaneUp()
        val registrations = owner?.let(launcher::registrationCount) ?: 0
        val (reason, sample) = synchronized(state) {
            val geometry = geometryPublications[owner]
            val currentRevision = geometry?.revision ?: -1
            val currentSeq = owner?.let(state::activitySequence) ?: -1
            val sample = MapFeedbackDiagnostics.Sample.from(payload).copy(
                currentRevision = currentRevision, currentSeq = currentSeq,
                ageMs = geometry?.let { nowMs() - it.atMs } ?: -1,
            )
            val reason = when {
                !supported -> Reason.CAPABILITY
                !dataPlaneUp -> Reason.DATA_PLANE
                !RenderedMapContract.validEvent(payload) || context == null -> Reason.MALFORMED
                context.kind != RenderedMapContract.AMBIENT || payload.optString("view") != RenderedMapContract.FOLLOW -> Reason.KIND_VIEW
                owner == null || state.ownerForActivity(context.id) != owner -> Reason.IDENTITY
                visible && !state.isLive(owner) -> Reason.STALE
                visible && currentSeq != context.seq -> Reason.SEQUENCE
                registrations != 1 -> Reason.REGISTRATION
                visible && currentRevision != context.revision -> Reason.REVISION
                else -> null
            }
            reason to sample
        }
        if (reason != null) {
            mapDiagnostics.record(Gate.PHONE_AMBIENT, Verdict.DROP, reason, sample)
            return
        }
        val delivered = sink.deliverLocal(BusEnvelope(path = RenderedMapContract.PATH,
            payload = RenderedMapContract.delivery(payload, checkNotNull(owner))))
        mapDiagnostics.record(Gate.PHONE_AMBIENT, if (delivered) Verdict.PASS else Verdict.DROP,
            if (delivered) Reason.CURRENT else Reason.DELIVERY_FAILED, sample)
    }

    fun expireCanonical(): Unit = synchronized(wireLock) {
        state.expireIfDue().forEach { expired ->
            sink.log("activity expired owner=${expired.ownerPluginId}")
            clearTransient(expired.ownerPluginId)
            sendEndIfReachable(expired, "max_duration")
            deliverClosed(expired.ownerPluginId, expired.reason)
        }
        scheduleExpiry()
    }

    /**
     * The owner's last registration left. An ongoing or pinned entry stays as an offline
     * snapshot the glasses are told about; any other entry ends.
     */
    fun clearForDisconnectedOwner(pluginId: String, reason: String): Unit = synchronized(wireLock) {
        when (val result = state.ownerDisconnected(pluginId)) {
            is PhoneActivityClearResult.Cleared -> {
                scheduleExpiry()
                clearTransient(pluginId)
                persistIfChanged()
                sink.log("activity cleared owner=$pluginId reason=$reason")
                sendEndIfReachable(result, "disconnect")
            }
            is PhoneActivityClearResult.WentOffline -> {
                scheduleExpiry()
                clearTransient(pluginId)
                persistIfChanged()
                sink.log("activity offline owner=$pluginId reason=$reason")
                sendOffline(result)
            }
            PhoneActivityClearResult.Ignored -> Unit
        }
    }

    /** Revoke, disable, uninstall or a signer change: the entry goes, its snapshot with it. */
    fun clearForRevokedOwner(pluginId: String, reason: String): Unit = synchronized(wireLock) {
        val result = state.ownerLostAccess(pluginId)
        if (result !is PhoneActivityClearResult.Cleared) return
        scheduleExpiry()
        clearTransient(pluginId)
        persistIfChanged()
        sink.log("activity cleared owner=$pluginId reason=$reason")
        sendEndIfReachable(result, "access_lost")
    }

    fun clearAllForHubStop(): Unit = synchronized(wireLock) {
        val (cleared, offline) = state.hubStopped()
        reconcileCommandBindings()
        if (cleared.isEmpty() && offline.isEmpty()) return@synchronized
        cancelExpiry()
        (cleared.map { it.ownerPluginId } + offline).forEach { owner ->
            clearTransient(owner)
            deliverClosed(owner, ActivityCloseReason.DISCONNECT)
        }
        persistIfChanged()
        if (sink.pinLinkUp()) {
            val sentinel = state.emptySlotAssertPayload()
            sink.sendRemote(BusEnvelope(path = BusPaths.ACTIVITY_END, payload = sentinel))
        }
        sink.log("activity tier cleared reason=hub_stopped count=${cleared.size} offline=${offline.size}")
    }

    /**
     * After a glasses announce: commands the previous connection held are void, a capacity drop
     * trims the collection (evicted live owners hear `replaced` first), then the whole tier is
     * cleared and every entry resent with a new `seq`.
     */
    fun resendIfAvailable(): Unit = synchronized(wireLock) {
        if (!sink.activitiesSupported()) return
        state.dropPendingCommands()
        val trimmed = state.trimToCapacity()
        trimmed.forEach { evicted ->
            sink.log("activity trimmed owner=${evicted.ownerPluginId} capacity=${state.capacity()}")
            clearTransient(evicted.ownerPluginId)
            if (!evicted.offline) deliverClosed(evicted.ownerPluginId, evicted.reason)
        }
        if (trimmed.isNotEmpty()) persistIfChanged()
        expireCanonical()

        // A phone-hub restart may leave several owner IDs rendered on the glasses.
        // Clear the whole tier first, then mint newer sequences for every canonical resend.
        val emptyAssert = state.emptySlotAssertPayload()
        val clearError = sink.sendRemote(
            BusEnvelope(path = BusPaths.ACTIVITY_END, payload = emptyAssert),
        )
        if (clearError != null) {
            sink.log("activity empty assert failed code=$clearError")
            return
        }

        state.payloadsForResend().forEach { payload ->
            val error = sink.sendRemote(BusEnvelope(path = BusPaths.ACTIVITY_START, payload = payload))
            if (error == null) {
                sink.log(
                    "activity resent owner=${payload.optString("ownerPluginId")} " +
                        "seq=${payload.optLong("seq")}",
                )
            } else {
                sink.log(
                    "activity resend failed owner=${payload.optString("ownerPluginId")} code=$error",
                )
            }
        }
    }

    /** Restores the persisted offline snapshots at hub start, then persists what was kept. */
    fun restoreOffline(snapshots: List<Pair<String, JSONObject>>): Unit = synchronized(wireLock) {
        val restored = snapshots.count { (owner, payload) -> state.restoreOffline(owner, payload) }
        persistedOfflineRevision = -1L
        persistIfChanged()
        if (snapshots.isNotEmpty()) sink.log("activity snapshots restored=$restored of=${snapshots.size}")
    }

    /**
     * The user changed [pluginId]'s activity choices on the phone. Disabling ends its entry;
     * unpinning a non-ongoing offline entry removes it; anything else resends its stamps.
     */
    fun onPreferencesChanged(pluginId: String): Unit = synchronized(wireLock) {
        when (val outcome = state.preferencesChanged(pluginId)) {
            is PhoneActivityPreferenceOutcome.Ended -> {
                sink.log("activity ended owner=$pluginId reason=user_preference")
                afterCleared(outcome.cleared, "user_preference")
            }
            is PhoneActivityPreferenceOutcome.Restamped -> sendRestamp(outcome)
            PhoneActivityPreferenceOutcome.Unchanged -> Unit
        }
    }

    /**
     * The user changed [pluginId]'s quick-action approvals. A withdrawn family rejects its
     * pending quick command at once; the entry is restamped so `hubQuick` follows the policy.
     */
    fun onQuickPolicyChanged(pluginId: String): Unit = synchronized(wireLock) {
        if (state.rejectUnapprovedQuickCommands(pluginId)) {
            sink.log("activity command rejected owner=$pluginId reason=quick_policy_withdrawn")
            scheduleExpiry()
        }
        val outcome = state.quickPolicyChanged(pluginId)
        if (outcome is PhoneActivityPreferenceOutcome.Restamped) sendRestamp(outcome)
    }

    private fun handleGlassesCommand(envelope: BusEnvelope) {
        val command = ActivitySurfaceContract.parseCommand(envelope.payload, requireMode = true)
        if (command == null) {
            sink.log("activity command ignored reason=malformed")
            return
        }
        when (val result = state.beginCommand(command, launcher::registrationCount)) {
            is PhoneActivityCommandResult.Rejected ->
                sink.log("activity command ignored id=${command.activityId.take(80)} reason=${result.reason}")
            is PhoneActivityCommandResult.Accepted -> {
                val bound = launcher.bindCommand(result.ownerPluginId)
                if (bound || result.ownerPluginId in commandBindings) {
                    commandBindings[result.ownerPluginId] = result.invocationId
                }
                if (!bound) sink.log("activity command bind failed owner=${result.ownerPluginId}")
                val delivered = sink.deliverLocal(BusEnvelope(path = BusPaths.ACTIVITY_ACTION, payload = result.payload))
                if (!delivered) {
                    state.commandUndelivered(result.ownerPluginId, result.invocationId)
                    sink.log("activity command undelivered owner=${result.ownerPluginId} bound=$bound")
                }
                scheduleExpiry()
            }
        }
    }

    private fun handleResult(envelope: BusEnvelope, sender: PhoneHudRouteSender, pluginId: String) {
        val result = ActivitySurfaceContract.parseResult(envelope.payload)
            ?: return reject(envelope, sender, ActivitySurfaceContract.ERROR_INVALID_ACTIVITY)
        val forwarded = state.completeCommand(pluginId, result)
        scheduleExpiry()
        if (forwarded == null) {
            // Late, unknown or already answered: the glasses already show it unconfirmed.
            sink.recordLocalRoute(envelope, sender.uid, pluginId, PluginBusJournal.Verdict.OK, "ACTIVITY_RESULT_DROPPED")
            return
        }
        val outgoing = BusEnvelope(path = BusPaths.ACTIVITY_RESULT, id = envelope.id, payload = forwarded)
        sink.recordLocalRoute(outgoing, sender.uid, pluginId, PluginBusJournal.Verdict.OK)
        if (!sink.pinLinkUp()) {
            sink.log("activity result dropped owner=$pluginId reason=link_down")
            return
        }
        sink.sendRemote(outgoing)?.let { sink.log("activity result failed owner=$pluginId code=$it") }
    }

    private fun handleGeometry(envelope: BusEnvelope, sender: PhoneHudRouteSender, pluginId: String) {
        val update = ActivitySurfaceContract.parseGeometry(envelope.payload)
            ?: return reject(envelope, sender, ActivitySurfaceContract.ERROR_INVALID_ACTIVITY)
        val drop = when {
            !state.isLive(pluginId) -> "ACTIVITY_GEOMETRY_IGNORED_NO_SESSION"
            synchronized(state) { geometryPublications[pluginId]?.let { update.revision <= it.revision } == true } -> "ACTIVITY_GEOMETRY_STALE"
            // Route geometry rides the data plane only; with CXR control alone it is dropped,
            // never queued, so text guidance never waits on it.
            !launcher.dataPlaneUp() -> "ACTIVITY_GEOMETRY_NO_DATA_PLANE"
            else -> null
        }
        if (drop != null) {
            sink.recordLocalRoute(envelope, sender.uid, pluginId, PluginBusJournal.Verdict.OK, drop)
            return
        }
        val outgoing = BusEnvelope(
            path = BusPaths.ACTIVITY_GEOMETRY,
            id = envelope.id,
            payload = withOwner(pluginId, ActivitySurfaceContract.geometryPayload(update)),
        )
        sink.recordLocalRoute(outgoing, sender.uid, pluginId, PluginBusJournal.Verdict.OK)
        // The outbound lock preserves send order; the state monitor stays available to RX.
        val publication = GeometryPublication(update.revision, nowMs())
        val error = launcher.sendDataPlane(outgoing)
        if (error == null) {
            synchronized(state) { geometryPublications[pluginId] = publication }
        } else {
            sink.log("activity geometry dropped owner=$pluginId code=$error")
        }
    }

    private fun handleHeading(envelope: BusEnvelope, sender: PhoneHudRouteSender, pluginId: String) {
        val heading = ActivitySurfaceContract.parseHeading(envelope.payload)
            ?: return reject(envelope, sender, ActivitySurfaceContract.ERROR_INVALID_ACTIVITY)
        val drop = when {
            !state.isLive(pluginId) -> "ACTIVITY_HEADING_IGNORED_NO_SESSION"
            !admitHeading(pluginId) -> "ACTIVITY_HEADING_RATE_LIMITED"
            // Heading only turns the map that geometry draws, so it rides the same data plane
            // and never adds a stream of samples to the narrow control link.
            !launcher.dataPlaneUp() -> "ACTIVITY_HEADING_NO_DATA_PLANE"
            else -> null
        }
        if (drop != null) {
            sink.recordLocalRoute(envelope, sender.uid, pluginId, PluginBusJournal.Verdict.OK, drop)
            return
        }
        val outgoing = BusEnvelope(
            path = BusPaths.ACTIVITY_HEADING,
            id = envelope.id,
            payload = withOwner(pluginId, ActivitySurfaceContract.headingPayload(heading)),
        )
        sink.recordLocalRoute(outgoing, sender.uid, pluginId, PluginBusJournal.Verdict.OK)
        launcher.sendDataPlane(outgoing)?.let { sink.log("activity heading dropped owner=$pluginId code=$it") }
    }

    /** At most [ActivitySurfaceContract.MAX_HEADINGS_PER_SECOND] per owner in a sliding second. */
    private fun admitHeading(pluginId: String): Boolean {
        val now = nowMs()
        val window = headingWindows.getOrPut(pluginId) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() >= HEADING_WINDOW_MS) window.removeFirst()
        if (window.size >= ActivitySurfaceContract.MAX_HEADINGS_PER_SECOND) return false
        window.addLast(now)
        return true
    }

    /** The launcher bundle's plugin paths exist only between hubs that negotiated it. */
    private inline fun launcherOnly(envelope: BusEnvelope, sender: PhoneHudRouteSender, handle: () -> Unit) {
        if (!state.bundleNegotiated()) {
            reject(envelope, sender, ActivitySurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE)
            return
        }
        handle()
    }

    private fun withOwner(pluginId: String, payload: JSONObject): JSONObject = payload
        .put("surfaceId", "$pluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}")
        .put("localSurfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
        .put("ownerPluginId", pluginId)

    /** Geometry and heading belong to one live session; an end or an offline entry drops them. */
    private fun clearTransient(pluginId: String) {
        synchronized(state) { geometryPublications.remove(pluginId) }
        headingWindows.remove(pluginId)
    }

    /** An entry the hub removed: the glasses hear the end, a live owner hears why. */
    private fun afterCleared(cleared: PhoneActivityClearResult.Cleared, cause: String) {
        scheduleExpiry()
        clearTransient(cleared.ownerPluginId)
        persistIfChanged()
        sendEndIfReachable(cleared, cause)
        if (!cleared.offline) deliverClosed(cleared.ownerPluginId, cleared.reason)
    }

    private fun sendOffline(result: PhoneActivityClearResult.WentOffline) {
        // Glasses not yet known to run the bundle get the offline entry with the next announce.
        if (!state.bundleNegotiated() || !sink.pinLinkUp()) {
            sink.log("activity offline held owner=${result.ownerPluginId} reason=link_or_bundle")
            return
        }
        sink.sendRemote(BusEnvelope(path = BusPaths.ACTIVITY_START, payload = result.payload))?.let {
            sink.log("activity offline failed owner=${result.ownerPluginId} code=$it")
        }
    }

    /** New stamps only mean something to glasses that read them; v1 glasses keep what they have. */
    private fun sendRestamp(outcome: PhoneActivityPreferenceOutcome.Restamped) {
        if (!state.bundleNegotiated() || !sink.pinLinkUp()) return
        val path = if (outcome.asStart) BusPaths.ACTIVITY_START else BusPaths.ACTIVITY_UPDATE
        sink.sendRemote(BusEnvelope(path = path, payload = outcome.payload))?.let {
            sink.log("activity restamp failed owner=${outcome.ownerPluginId} code=$it")
        }
    }

    private fun persistIfChanged() {
        val (revision, snapshots) = state.offlineSnapshots()
        if (revision == persistedOfflineRevision) return
        persistedOfflineRevision = revision
        launcher.persistOffline(snapshots)
    }

    private fun forward(
        envelope: BusEnvelope,
        payload: JSONObject,
        sender: PhoneHudRouteSender,
    ) {
        val forwarded = envelope.copy(payload = payload)
        sink.recordLocalRoute(forwarded, sender.uid, sender.pluginId, PluginBusJournal.Verdict.OK)
        if (sink.pinLinkUp()) {
            sink.sendRemote(forwarded)?.let { sink.deliverError(sender.replyBinder, envelope.id, it) }
        } else {
            sink.log(
                "activity held owner=${payload.optString("ownerPluginId")} " +
                    "path=${envelope.path} reason=link_down",
            )
        }
    }

    private fun reject(
        envelope: BusEnvelope,
        sender: PhoneHudRouteSender,
        code: String,
    ) {
        sink.recordLocalRoute(
            envelope,
            sender.uid,
            sender.pluginId,
            PluginBusJournal.Verdict.REJECTED,
            code,
        )
        sink.deliverError(sender.replyBinder, envelope.id, code)
    }

    private fun deliverClosed(pluginId: String, reason: ActivityCloseReason) {
        val payload = ActivitySurfaceContract
            .closedPayload("$pluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}", reason)
            .put("pluginId", pluginId)
        if (!sink.deliverLocal(BusEnvelope(path = BusPaths.ACTIVITY_CLOSED, payload = payload))) {
            sink.log("activity close undelivered owner=$pluginId reason=${reason.wireValue}")
        }
    }

    private fun sendEndIfReachable(
        result: PhoneActivityClearResult.Cleared,
        cause: String,
    ) {
        if (!sink.pinLinkUp()) {
            sink.log("activity end held owner=${result.ownerPluginId} cause=$cause reason=link_down")
            return
        }
        val error = sink.sendRemote(BusEnvelope(path = BusPaths.ACTIVITY_END, payload = result.payload))
        if (error != null) {
            sink.log("activity end failed owner=${result.ownerPluginId} cause=$cause code=$error")
        }
    }

    fun scheduleExpiry(): Unit = synchronized(wireLock) {
        reconcileCommandBindings()
        cancelExpiry()
        val deadline = state.nextExpiryDeadlineMs() ?: return
        postExpiry((deadline - nowMs()).coerceAtLeast(0L))
    }

    fun onCommandBindingDied(pluginId: String): Unit = synchronized(wireLock) {
        commandBindings[pluginId]?.let { state.commandUndelivered(pluginId, it) }
        scheduleExpiry()
    }

    private fun reconcileCommandBindings() {
        val pending = state.pendingCommandOwners()
        commandBindings.keys.filter { it !in pending }.forEach { owner ->
            commandBindings.remove(owner)
            launcher.unbindCommand(owner)
        }
    }

    private companion object {
        const val HEADING_WINDOW_MS = 1_000L
    }
}
