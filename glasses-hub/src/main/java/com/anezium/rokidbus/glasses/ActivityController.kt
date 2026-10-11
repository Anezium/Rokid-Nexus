package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.shared.ActivityCloseReason
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.ActivitySurfacePatch
import com.anezium.rokidbus.shared.ActivitySurfacePatchResult
import com.anezium.rokidbus.shared.ActivitySurfaceValidationResult
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.ForegroundAmbientMode
import com.anezium.rokidbus.shared.PinSurfacePosition
import java.util.concurrent.CopyOnWriteArrayList

internal data class NexusActivitySurface(
    val surfaceId: String,
    val ownerPluginId: String,
    val seq: Long,
    val content: ActivitySurfaceContent,
    val corner: PinSurfacePosition,
    val startedOrder: Long,
    val lastUpdatedOrder: Long,
    val lastSignificantOrder: Long?,
    val collapseAtMs: Long,
    val maxDurationDeadlineMs: Long?,
    val selectedActionIndex: Int,
    val motionToken: Long,
)

internal data class ActivityRenderItem(
    val activity: NexusActivitySurface,
    val primary: Boolean,
    val presentation: ActivityPresentation,
    /** True only on the event item whose flare was admitted as urgent. */
    val urgent: Boolean = false,
    /** The owner's explicit ambient size as drawn here (capped over an app); null while automatic. */
    val size: AmbientSize? = null,
)

/**
 * One collection entry, with or without an island: the session lists them all. [primary] marks
 * the entry whose island is the expanded one.
 */
internal data class ActivityEntry(
    val surfaceId: String,
    val ownerPluginId: String,
    val seq: Long,
    val content: ActivitySurfaceContent,
    val primary: Boolean,
    val startedOrder: Long,
    val lastSignificantOrder: Long?,
)

/**
 * [items] are the corner islands: the primary and at most one other live entry. [collection] is
 * every entry the glasses hold, offline ones included, in start order.
 */
internal data class ActivityRenderState(
    val items: List<ActivityRenderItem> = emptyList(),
    val collection: List<ActivityEntry> = emptyList(),
) {
    val primary: ActivityRenderItem?
        get() = items.firstOrNull(ActivityRenderItem::primary)
}

internal sealed interface ActivityMutation {
    data class Applied(
        val surfaceId: String,
        val significant: Boolean,
        val replacedSurfaceId: String? = null,
        val urgent: Boolean = false,
    ) : ActivityMutation

    data class Removed(val surfaceId: String) : ActivityMutation
    data class Cleared(val surfaceIds: List<String>) : ActivityMutation
    data object DroppedStale : ActivityMutation
    data object Ignored : ActivityMutation
}

/**
 * Pure multi-slot activity state.
 *
 * Presentation context is deliberately supplied by the caller. This class
 * owns activity facts and clocks; it never asks Android what is in front of
 * the wearer and it never accepts a plugin-selected presentation.
 */
internal class ActivityStateMachine {
    private data class Resident(
        val surfaceId: String,
        val ownerPluginId: String,
        val seq: Long,
        val content: ActivitySurfaceContent,
        val startedOrder: Long,
        val lastUpdatedOrder: Long,
        val lastSignificantOrder: Long?,
        val lastFlareAtMs: Long?,
        val lastUrgentAtMs: Long?,
        val collapseAtMs: Long,
        val maxDurationDeadlineMs: Long?,
        val selectedActionIndex: Int,
        val motionToken: Long,
    )

    private val residents = linkedMapOf<String, Resident>()
    private val latestSeqBySurface = mutableMapOf<String, Long>()
    private var globalClearSeq = Long.MIN_VALUE
    private var order = 0L
    private var motionToken = 0L
    private var corners = emptyMap<String, PinSurfacePosition>()

    /**
     * How many entries the collection holds: [ActivitySurfaceContract.MAX_COLLECTION_ACTIVITIES]
     * over a pair that negotiated the launcher bundle, else the v1 two.
     */
    var capacity: Int = ActivitySurfaceContract.MAX_ACTIVE_ACTIVITIES
        private set

    fun surfaceIds(): Set<String> = residents.keys.toSet()

    /** The expanded island's entry: offline entries never hold one. */
    fun primarySurfaceId(): String? = selectPrimaryActivity(
        residents.values.filterNot { it.content.isOffline }.map {
            ActivityPrimaryCandidate(
                activityId = it.surfaceId,
                startedOrder = it.startedOrder,
                lastSignificantOrder = it.lastSignificantOrder,
            )
        },
    )

    /**
     * A capacity drop trims the collection in eviction order. The phone, which is authoritative,
     * trims and tells the evicted owners first; this only keeps the glasses within bounds.
     */
    fun setCapacity(next: Int): List<String> {
        capacity = next.coerceAtLeast(1)
        val evicted = mutableListOf<String>()
        while (residents.size > capacity) {
            val id = evictionCandidate()
            residents.remove(id)
            corners = corners - id
            evicted += id
        }
        return evicted
    }

    fun start(
        surfaceId: String,
        ownerPluginId: String,
        seq: Long,
        content: ActivitySurfaceContent,
        nowMs: Long,
    ): ActivityMutation {
        // Defence in depth: a destination is only ever one of the owner's own pages.
        if (!ActivitySurfaceContract.destinationAllowed(content, ownerPluginId)) return ActivityMutation.Ignored
        if (isStale(surfaceId, seq)) return ActivityMutation.DroppedStale
        latestSeqBySurface[surfaceId] = seq

        var replacedSurfaceId: String? = null
        if (surfaceId !in residents && residents.size >= capacity) {
            replacedSurfaceId = evictionCandidate()
            residents.remove(replacedSurfaceId)
            corners = corners - replacedSurfaceId
        }

        val previous = residents[surfaceId]
        val nextOrder = ++order
        val next = Resident(
            surfaceId = surfaceId,
            ownerPluginId = ownerPluginId,
            seq = seq,
            content = content,
            startedOrder = nextOrder,
            lastUpdatedOrder = nextOrder,
            lastSignificantOrder = null,
            lastFlareAtMs = null,
            lastUrgentAtMs = previous?.lastUrgentAtMs,
            collapseAtMs = nowMs + COLLAPSE_AFTER_MS,
            maxDurationDeadlineMs = content.maxDurationMs?.let { nowMs + it },
            selectedActionIndex = previous
                ?.selectedActionIndex
                ?.coerceIn(0, (content.actions.lastIndex).coerceAtLeast(0))
                ?: 0,
            motionToken = ++motionToken,
        )
        residents[surfaceId] = next
        return ActivityMutation.Applied(
            surfaceId = surfaceId,
            significant = false,
            replacedSurfaceId = replacedSurfaceId,
        )
    }

    fun update(
        surfaceId: String,
        seq: Long,
        patch: ActivitySurfacePatch,
        nowMs: Long,
    ): ActivityMutation {
        if (isStale(surfaceId, seq)) return ActivityMutation.DroppedStale
        latestSeqBySurface[surfaceId] = seq
        val current = residents[surfaceId] ?: return ActivityMutation.Ignored
        val content = patch.applyTo(current.content)
        // An offline entry comes back only through a fresh start, never an update.
        if (current.content.isOffline && !content.isOffline) return ActivityMutation.Ignored
        if (ActivitySurfaceContract.interactiveProblem(content) != null ||
            !ActivitySurfaceContract.destinationAllowed(content, current.ownerPluginId)
        ) {
            return ActivityMutation.Ignored
        }
        val selectedId = current.content.actions
            .getOrNull(current.selectedActionIndex)
            ?.id
        val selectedIndex = selectedId
            ?.let { id -> content.actions.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        val nextOrder = ++order
        residents[surfaceId] = current.copy(
            seq = seq,
            content = content,
            lastUpdatedOrder = nextOrder,
            lastSignificantOrder = if (patch.significant) {
                nextOrder
            } else {
                current.lastSignificantOrder
            },
            collapseAtMs = nowMs + COLLAPSE_AFTER_MS,
            selectedActionIndex = selectedIndex,
            motionToken = ++motionToken,
        )
        return ActivityMutation.Applied(
            surfaceId = surfaceId,
            significant = patch.significant,
            urgent = patch.urgent,
        )
    }

    fun end(surfaceId: String, seq: Long): ActivityMutation {
        if (isStale(surfaceId, seq)) return ActivityMutation.DroppedStale
        latestSeqBySurface[surfaceId] = seq
        val removed = residents.remove(surfaceId) ?: return ActivityMutation.Ignored
        corners = corners - removed.surfaceId
        return ActivityMutation.Removed(removed.surfaceId)
    }

    /**
     * A reserved hub-owned `/activity/end` is the multi-slot empty assertion.
     * Its sequence is also a watermark, so delayed starts from before reconnect
     * cannot recreate a ghost after the clear. A delayed assertion may arrive
     * after one of the fresh resend starts, so it removes only residents at or
     * below its watermark and preserves newer canonical state.
     */
    fun clearAll(seq: Long): ActivityMutation {
        if (seq <= globalClearSeq) return ActivityMutation.DroppedStale
        globalClearSeq = seq
        val cleared = residents.values
            .filter { resident -> resident.seq <= seq }
            .map(Resident::surfaceId)
        cleared.forEach(residents::remove)
        if (cleared.isNotEmpty()) corners = corners - cleared.toSet()
        return ActivityMutation.Cleared(cleared)
    }

    fun expire(nowMs: Long): List<String> {
        val expired = residents.values
            .filter { it.maxDurationDeadlineMs?.let { deadline -> nowMs >= deadline } == true }
            .map(Resident::surfaceId)
        expired.forEach(residents::remove)
        if (expired.isNotEmpty()) corners = corners - expired.toSet()
        return expired
    }

    fun nextDeadlineMs(nowMs: Long, alwaysExpanded: Boolean): Long? = buildList {
        residents.values.forEach { resident ->
            resident.maxDurationDeadlineMs?.let(::add)
            if (!alwaysExpanded && resident.collapseAtMs > nowMs) add(resident.collapseAtMs)
        }
    }.minOrNull()

    fun presentationForEvent(
        surfaceId: String,
        context: ActivityPresentationContext,
        significant: Boolean,
        nowMs: Long,
        alwaysExpanded: Boolean,
    ): ActivityPresentation = presentEvent(
        surfaceId = surfaceId,
        context = context,
        significant = significant,
        urgent = false,
        nowMs = nowMs,
        alwaysExpanded = alwaysExpanded,
    ).presentation

    fun presentEvent(
        surfaceId: String,
        context: ActivityPresentationContext,
        significant: Boolean,
        urgent: Boolean,
        nowMs: Long,
        alwaysExpanded: Boolean,
        sizeOf: (String) -> AmbientSize? = { null },
        mode: ForegroundAmbientMode = ForegroundAmbientMode.RESIDENTS,
    ): ActivityEventPresentation {
        val resident = residents[surfaceId]
            ?: return ActivityEventPresentation(ActivityPresentation.HIDDEN)
        if (context == ActivityPresentationContext.CAMERA_OVERLAY || mode == ForegroundAmbientMode.NOTHING) {
            return ActivityEventPresentation(ActivityPresentation.HIDDEN)
        }
        if (surfaceId != primarySurfaceId()) {
            return ActivityEventPresentation(applyForegroundMode(ActivityPresentation.PULSE, mode))
        }
        val flareAvailable = resident.lastFlareAtMs
            ?.let { nowMs - it >= FLARE_INTERVAL_MS }
            ?: true
        val urgentAdmitted = significant &&
            urgentFlareAdmitted(urgent, resident.lastUrgentAtMs, nowMs)
        val selected = applyForegroundMode(
            selectActivityPresentation(
                context = context,
                significant = significant,
                flareBudgetAvailable = flareAvailable || urgentAdmitted,
                collapseState = collapseState(resident, nowMs, alwaysExpanded || sizeOf(resident.ownerPluginId) != null),
            ),
            mode,
        )
        val urgentFlare = urgentAdmitted && selected == ActivityPresentation.FLARE
        if (selected == ActivityPresentation.FLARE) {
            residents[surfaceId] = resident.copy(
                lastFlareAtMs = nowMs,
                lastUrgentAtMs = if (urgentFlare) nowMs else resident.lastUrgentAtMs,
            )
        }
        return ActivityEventPresentation(selected, urgentFlare)
    }

    fun snapshot(
        nowMs: Long,
        context: ActivityPresentationContext,
        pinCorner: PinSurfacePosition?,
        alwaysExpanded: Boolean,
        eventSurfaceId: String? = null,
        eventPresentation: ActivityPresentation? = null,
        eventUrgent: Boolean = false,
        sizeOf: (String) -> AmbientSize? = { null },
        mode: ForegroundAmbientMode = ForegroundAmbientMode.RESIDENTS,
    ): ActivityRenderState {
        val ordered = residents.values.sortedBy(Resident::startedOrder)
        val primary = primarySurfaceId()
        val islands = islandIds(primary)
        corners = allocateActivityCorners(
            activityIdsInOrder = ordered.map(Resident::surfaceId).filter { it in islands },
            existing = corners.filterKeys { it in islands },
            pinCorner = pinCorner,
        )
        return ActivityRenderState(
            items = ordered.filter { it.surfaceId in islands }.mapNotNull { resident ->
                val corner = corners[resident.surfaceId] ?: return@mapNotNull null
                val preferred = sizeOf(resident.ownerPluginId)
                val presentation = when {
                    context == ActivityPresentationContext.CAMERA_OVERLAY ->
                        ActivityPresentation.HIDDEN
                    resident.surfaceId == eventSurfaceId && eventPresentation != null ->
                        eventPresentation
                    resident.surfaceId != primary ->
                        applyForegroundMode(ActivityPresentation.CHIP, mode)
                    else ->
                        applyForegroundMode(
                            selectActivityPresentation(
                                context = context,
                                significant = false,
                                flareBudgetAvailable = false,
                                // An explicit size replaces the ten-second collapse.
                                collapseState = collapseState(resident, nowMs, alwaysExpanded || preferred != null),
                            ),
                            mode,
                        )
                }
                ActivityRenderItem(
                    activity = NexusActivitySurface(
                        surfaceId = resident.surfaceId,
                        ownerPluginId = resident.ownerPluginId,
                        seq = resident.seq,
                        content = resident.content,
                        corner = corner,
                        startedOrder = resident.startedOrder,
                        lastUpdatedOrder = resident.lastUpdatedOrder,
                        lastSignificantOrder = resident.lastSignificantOrder,
                        collapseAtMs = resident.collapseAtMs,
                        maxDurationDeadlineMs = resident.maxDurationDeadlineMs,
                        // Islands take no input, so no action chip is ever shown as selected.
                        selectedActionIndex = NO_SELECTED_ACTION,
                        motionToken = resident.motionToken,
                    ),
                    primary = resident.surfaceId == primary,
                    presentation = presentation,
                    urgent = eventUrgent &&
                        resident.surfaceId == eventSurfaceId &&
                        presentation == ActivityPresentation.FLARE,
                    size = ambientIslandSize(preferred, context),
                )
            },
            collection = ordered.map {
                ActivityEntry(
                    surfaceId = it.surfaceId,
                    ownerPluginId = it.ownerPluginId,
                    seq = it.seq,
                    content = it.content,
                    primary = it.surfaceId == primary,
                    startedOrder = it.startedOrder,
                    lastSignificantOrder = it.lastSignificantOrder,
                )
            },
        )
    }

    /**
     * The entries that get a corner island: the primary and one other live entry, the wearer's
     * pin first, then the latest significant update, then the earliest start. Never an offline one.
     */
    private fun islandIds(primary: String?): Set<String> {
        val other = residents.values
            .filter { !it.content.isOffline && it.surfaceId != primary }
            .sortedWith(
                compareByDescending<Resident> { it.content.userPinned }
                    .thenByDescending { it.lastSignificantOrder ?: Long.MIN_VALUE }
                    .thenBy { it.startedOrder }
                    .thenBy { it.ownerPluginId },
            )
            .firstOrNull()
        return setOfNotNull(primary, other?.surfaceId)
    }

    fun moveSelection(surfaceId: String, delta: Int, nowMs: Long): Boolean {
        val current = residents[surfaceId] ?: return false
        val count = current.content.actions.size
        if (count == 0) return false
        val next = (current.selectedActionIndex + delta + count) % count
        residents[surfaceId] = current.copy(
            selectedActionIndex = next,
            collapseAtMs = nowMs + COLLAPSE_AFTER_MS,
            motionToken = ++motionToken,
        )
        return true
    }

    fun hasActions(surfaceId: String): Boolean =
        residents[surfaceId]?.content?.actions?.isNotEmpty() == true

    fun selectedAction(surfaceId: String) =
        residents[surfaceId]?.let { it.content.actions.getOrNull(it.selectedActionIndex) }

    fun ownerPluginId(surfaceId: String): String? = residents[surfaceId]?.ownerPluginId

    fun wakeDisplayRequested(surfaceId: String): Boolean =
        residents[surfaceId]?.content?.wakeDisplay == true

    private fun isStale(surfaceId: String, seq: Long): Boolean =
        seq <= globalClearSeq || seq <= (latestSeqBySurface[surfaceId] ?: Long.MIN_VALUE)

    /**
     * Who leaves when a newcomer finds the collection full (plan 028a §1.3), first match wins:
     * the oldest offline entry, then the least recently updated unpinned live entry that is not
     * the primary, then the unpinned primary, then the least recently updated pinned entry.
     */
    private fun evictionCandidate(): String {
        val primary = primarySurfaceId()
        val recency = compareBy<Resident> { it.lastUpdatedOrder }.thenBy { it.startedOrder }.thenBy { it.surfaceId }
        val all = residents.values.toList()
        all.filter { it.content.isOffline }
            .minWithOrNull(compareBy<Resident> { it.content.knownAtWallMs ?: Long.MIN_VALUE }.then(recency))
            ?.let { return it.surfaceId }
        val unpinned = all.filter { !it.content.userPinned }
        unpinned.filter { it.surfaceId != primary }.minWithOrNull(recency)?.let { return it.surfaceId }
        unpinned.firstOrNull { it.surfaceId == primary }?.let { return it.surfaceId }
        return all.minWith(recency).surfaceId
    }

    private fun collapseState(
        resident: Resident,
        nowMs: Long,
        alwaysExpanded: Boolean,
    ): ActivityCollapseState = when {
        alwaysExpanded -> ActivityCollapseState.ALWAYS_EXPANDED
        nowMs >= resident.collapseAtMs -> ActivityCollapseState.ELAPSED
        else -> ActivityCollapseState.RUNNING
    }

    companion object {
        const val COLLAPSE_AFTER_MS = 10_000L
        const val FLARE_INTERVAL_MS = 10_000L
        const val NO_SELECTED_ACTION = -1
    }
}

internal object ActivityPresentationSettings {
    private const val PREFS = "activity_presentation"
    private const val ALWAYS_EXPANDED = "always_expanded"
    private const val LAUNCHER_BACKEND = "launcher.backend"
    private const val PHONE_LAUNCHER_BACKEND = "launcher.backend.phone"

    fun alwaysExpanded(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ALWAYS_EXPANDED, false)

    fun setAlwaysExpanded(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ALWAYS_EXPANDED, enabled)
            .apply()
    }

    /**
     * The launcher backend lives in this store, the one the phone's settings already write,
     * rather than a preferences file of its own. Absent means the legacy launcher.
     */
    fun launcherBackend(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAUNCHER_BACKEND, null)

    fun setLauncherBackend(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LAUNCHER_BACKEND, value)
            .apply()
    }

    /**
     * The phone's explicit backend as last announced, kept apart from the glasses' own choice so
     * neither ever overwrites the other, and across a link loss so the launcher does not flip when
     * the phone goes away. A phone announcing no opinion clears it.
     */
    fun phoneLauncherBackend(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PHONE_LAUNCHER_BACKEND, null)

    fun setPhoneLauncherBackend(context: Context, value: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(PHONE_LAUNCHER_BACKEND, null) == value) return
        prefs.edit()
            .apply { if (value == null) remove(PHONE_LAUNCHER_BACKEND) else putString(PHONE_LAUNCHER_BACKEND, value) }
            .apply()
    }
}

internal object ActivityController {
    private val main = Handler(Looper.getMainLooper())
    private val state = ActivityStateMachine()
    private val listeners = CopyOnWriteArrayList<(ActivityRenderState) -> Unit>()
    private var deadlineTask: Runnable? = null
    private var context: Context? = null
    private var surfaceUnsubscribe: (() -> Unit)? = null
    private var pinUnsubscribe: (() -> Unit)? = null
    private var ambientUnsubscribe: (() -> Unit)? = null
    private var foregroundUnsubscribe: (() -> Unit)? = null
    private var cameraOverlayActive = false
    private var sessionOpen = false
    private var latestRender = ActivityRenderState()
    private var launcherVersion = 0

    fun onServiceConnected(context: Context) {
        runOnMain {
            this.context = context.applicationContext
            surfaceUnsubscribe?.invoke()
            pinUnsubscribe?.invoke()
            surfaceUnsubscribe = SurfaceController.observe { contextChanged() }
            pinUnsubscribe = PinController.observe { contextChanged() }
            ambientUnsubscribe?.invoke()
            ambientUnsubscribe = AmbientPreferences.observe { runOnMain(::contextChanged) }
            foregroundUnsubscribe?.invoke()
            foregroundUnsubscribe = AmbientForeground.observe { runOnMain(::contextChanged) }
            publish()
        }
    }

    fun onServiceDestroyed() {
        runOnMain {
            surfaceUnsubscribe?.invoke()
            surfaceUnsubscribe = null
            pinUnsubscribe?.invoke()
            pinUnsubscribe = null
            ambientUnsubscribe?.invoke()
            ambientUnsubscribe = null
            foregroundUnsubscribe?.invoke()
            foregroundUnsubscribe = null
            cancelDeadline()
            context = null
        }
    }

    fun observe(listener: (ActivityRenderState) -> Unit): () -> Unit {
        listeners += listener
        listener(latestRender)
        return { listeners.remove(listener) }
    }

    fun handleActivityEnvelope(context: Context, envelope: BusEnvelope): Boolean = when (envelope.path) {
        BusPaths.ACTIVITY_START -> {
            runOnMain { start(envelope) }
            true
        }
        BusPaths.ACTIVITY_UPDATE -> {
            runOnMain { update(context.applicationContext, envelope) }
            true
        }
        BusPaths.ACTIVITY_END -> {
            runOnMain { end(envelope) }
            true
        }
        else -> false
    }

    fun setCameraOverlayActive(active: Boolean) {
        runOnMain {
            if (cameraOverlayActive == active) return@runOnMain
            cameraOverlayActive = active
            contextChanged()
        }
    }

    fun onLauncherVisibilityChanged() {
        runOnMain(::contextChanged)
    }

    fun setAlwaysExpanded(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        ActivityPresentationSettings.setAlwaysExpanded(appContext, enabled)
        runOnMain {
            if (this.context != null) contextChanged()
        }
    }

    fun isPresenting(): Boolean = latestRender.primary != null

    /**
     * The pair's launcher-bundle version changed: the collection takes its capacity, and the
     * phone's stamps are read only while it is negotiated.
     */
    fun onLauncherNegotiated(version: Int) {
        runOnMain {
            if (version == launcherVersion) return@runOnMain
            launcherVersion = version
            val evicted = state.setCapacity(ActivitySurfaceContract.effectiveCollectionCapacity(version))
            if (evicted.isNotEmpty()) log("activity collection trimmed count=${evicted.size}")
            publish()
        }
    }

    private fun start(envelope: BusEnvelope) {
        val payload = envelope.payload
        val validation = ActivitySurfaceContract.validateStart(payload, hubStamps = LauncherInteractiveContract.isActive(launcherVersion))
        if (validation !is ActivitySurfaceValidationResult.Valid) {
            log("activity rejected code=${ActivitySurfaceContract.ERROR_INVALID_ACTIVITY}")
            return
        }
        val identity = identity(payload) ?: run {
            log("activity rejected code=${ActivitySurfaceContract.ERROR_INVALID_ACTIVITY}")
            return
        }
        val now = SystemClock.elapsedRealtime()
        when (
            val result = state.start(
                surfaceId = identity.first,
                ownerPluginId = identity.second,
                seq = payload.optLong("seq", Long.MIN_VALUE),
                content = validation.content,
                nowMs = now,
            )
        ) {
            is ActivityMutation.Applied -> {
                result.replacedSurfaceId?.let {
                    reportClosed(it, ActivityCloseReason.REPLACED)
                }
                publishEvent(result, now)
            }
            ActivityMutation.DroppedStale ->
                log("activity dropped stale id=${identity.first}")
            else -> Unit
        }
    }

    private fun update(context: Context, envelope: BusEnvelope) {
        val payload = envelope.payload
        val validation = ActivitySurfaceContract.validateUpdate(payload, hubStamps = LauncherInteractiveContract.isActive(launcherVersion))
        if (validation !is ActivitySurfacePatchResult.Valid) {
            log("activity update rejected code=${ActivitySurfaceContract.ERROR_INVALID_ACTIVITY}")
            return
        }
        val identity = identity(payload) ?: run {
            log("activity update rejected code=${ActivitySurfaceContract.ERROR_INVALID_ACTIVITY}")
            return
        }
        val now = SystemClock.elapsedRealtime()
        when (
            val result = state.update(
                surfaceId = identity.first,
                seq = payload.optLong("seq", Long.MIN_VALUE),
                patch = validation.patch,
                nowMs = now,
            )
        ) {
            is ActivityMutation.Applied -> {
                DisplayWakePolicy.requestWake(
                    context,
                    DisplayWakeKind.ACTIVITY,
                    requested = result.significant && state.wakeDisplayRequested(result.surfaceId),
                )
                publishEvent(result, now)
            }
            ActivityMutation.DroppedStale ->
                log("activity update dropped stale id=${identity.first}")
            else -> Unit
        }
    }

    private fun end(envelope: BusEnvelope) {
        val payload = envelope.payload
        val seq = payload.optLong("seq", Long.MIN_VALUE)
        if (ActivitySurfaceContract.isEmptySlotAssert(payload)) {
            when (state.clearAll(seq)) {
                is ActivityMutation.Cleared -> publish()
                ActivityMutation.DroppedStale -> log("activity empty assert dropped stale")
                else -> Unit
            }
            return
        }
        val identity = identity(payload) ?: return
        when (state.end(identity.first, seq)) {
            is ActivityMutation.Removed -> publish()
            ActivityMutation.DroppedStale ->
                log("activity end dropped stale id=${identity.first}")
            else -> Unit
        }
    }

    private fun publishEvent(result: ActivityMutation.Applied, nowMs: Long) {
        val event = state.presentEvent(
            surfaceId = result.surfaceId,
            context = presentationContext(),
            significant = result.significant,
            urgent = result.urgent,
            nowMs = nowMs,
            alwaysExpanded = alwaysExpanded(),
            sizeOf = AmbientPreferences::effectiveSize,
            mode = AmbientForeground.mode(),
        )
        if (result.significant && event.presentation == ActivityPresentation.PULSE) {
            log("activity flare throttled id=${result.surfaceId}")
        }
        if (result.urgent && !event.urgent) {
            log("activity urgent tone throttled id=${result.surfaceId}")
        }
        publish(
            eventSurfaceId = result.surfaceId,
            eventPresentation = event.presentation,
            eventUrgent = event.urgent,
            nowMs = nowMs,
        )
    }

    private fun publish(
        eventSurfaceId: String? = null,
        eventPresentation: ActivityPresentation? = null,
        eventUrgent: Boolean = false,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ) {
        latestRender = state.snapshot(
            nowMs = nowMs,
            context = presentationContext(),
            pinCorner = PinController.activePin()?.content?.position,
            alwaysExpanded = alwaysExpanded(),
            eventSurfaceId = eventSurfaceId,
            eventPresentation = eventPresentation,
            eventUrgent = eventUrgent,
            sizeOf = AmbientPreferences::effectiveSize,
            mode = AmbientForeground.mode(),
        )
        // A route lives as long as its activity: ended or offline, its geometry and heading go.
        RouteLive.retain(
            live = latestRender.collection.filterNot { it.content.isOffline }.mapTo(mutableSetOf()) { it.ownerPluginId },
            offline = latestRender.collection.filter { it.content.isOffline }.mapTo(mutableSetOf()) { it.ownerPluginId },
        )
        listeners.forEach { listener -> runCatching { listener(latestRender) } }
        scheduleDeadline(nowMs)
    }

    private fun contextChanged() {
        publish()
    }

    /**
     * The open Nexus session counts as the launcher, explicitly, whichever backend drew it; under
     * Nexus's own windows the foreground resolver tells the home from a native app.
     */
    private fun presentationContext(): ActivityPresentationContext = when {
        cameraOverlayActive -> ActivityPresentationContext.CAMERA_OVERLAY
        SurfaceController.activeSurface() != null -> ActivityPresentationContext.ACTIVE_SURFACE
        LauncherOverlayRenderer.isShown() || sessionOpen -> ActivityPresentationContext.NEXUS_LAUNCHER
        AmbientForeground.homeInFront -> ActivityPresentationContext.IDLE_HOME
        else -> ActivityPresentationContext.NATIVE_APP
    }

    /** The Nexus session opened or closed; told by the session host, on the main thread. */
    fun setSessionOpen(open: Boolean) {
        runOnMain {
            if (sessionOpen == open) return@runOnMain
            sessionOpen = open
            contextChanged()
        }
    }

    private fun alwaysExpanded(): Boolean =
        context?.let(ActivityPresentationSettings::alwaysExpanded) ?: false

    private fun scheduleDeadline(nowMs: Long) {
        cancelDeadline()
        val deadline = state.nextDeadlineMs(nowMs, alwaysExpanded()) ?: return
        val task = Runnable {
            deadlineTask = null
            val expired = state.expire(SystemClock.elapsedRealtime())
            expired.forEach { reportClosed(it, ActivityCloseReason.MAX_DURATION) }
            publish()
        }
        deadlineTask = task
        main.postDelayed(task, (deadline - nowMs).coerceAtLeast(0L))
    }

    private fun cancelDeadline() {
        deadlineTask?.let(main::removeCallbacks)
        deadlineTask = null
    }

    private fun reportClosed(surfaceId: String, reason: ActivityCloseReason) {
        GlassesHub.sendToPhone(
            BusPaths.ACTIVITY_CLOSED,
            ActivitySurfaceContract.closedPayload(surfaceId, reason),
        )
    }

    private fun identity(payload: org.json.JSONObject): Pair<String, String>? {
        val surfaceId = payload.optString("surfaceId")
        val ownerPluginId = payload.optString("ownerPluginId")
        if (surfaceId.isBlank() || ownerPluginId.isBlank()) return null
        if (surfaceId != "$ownerPluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}") return null
        return surfaceId to ownerPluginId
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
