package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.ForegroundAmbientMode
import com.anezium.rokidbus.shared.PinSurfacePosition

/**
 * The glasses-owned context that determines how loudly an activity is presented. [NEXUS_LAUNCHER]
 * is the legacy launcher or an open Nexus session; [IDLE_HOME] the glasses' home (or nothing seen
 * yet); [NATIVE_APP] a native app in front, where islands are capped at text (plan 028a §8.2).
 */
internal enum class ActivityPresentationContext {
    ACTIVE_SURFACE,
    NEXUS_LAUNCHER,
    IDLE_HOME,
    NATIVE_APP,
    CAMERA_OVERLAY,
}

/** A renderer instruction. PULSE is the chip presentation with its refresh animation. */
internal enum class ActivityPresentation {
    CHIP,
    PANEL,
    FLARE,
    PULSE,
    HIDDEN,
}

/** The platform-owned idle collapse state; plugins have no wire field for it. */
internal enum class ActivityCollapseState {
    RUNNING,
    ELAPSED,
    ALWAYS_EXPANDED,
}

/** The presentation chosen for one start or update, and whether its flare is urgent. */
internal data class ActivityEventPresentation(
    val presentation: ActivityPresentation,
    val urgent: Boolean = false,
)

/** At most one urgent flare per activity per this interval; later ones are ordinary. */
internal const val ACTIVITY_URGENT_INTERVAL_MS = 60_000L

/**
 * An urgent tone has its own, rarer budget, separate from the ten-second flare
 * interval: "get off at the next stop" must not be swallowed because the
 * previous maneuver flared a few seconds earlier.
 */
internal fun urgentFlareAdmitted(
    urgent: Boolean,
    lastUrgentAtMs: Long?,
    nowMs: Long,
): Boolean = urgent &&
    (lastUrgentAtMs == null || nowMs - lastUrgentAtMs >= ACTIVITY_URGENT_INTERVAL_MS)

/**
 * Pure activity presentation selection.
 *
 * The plugin controls only [significant]. Context, flare admission, and the
 * collapse timer are platform state and therefore cannot be supplied over the
 * wire.
 */
internal fun selectActivityPresentation(
    context: ActivityPresentationContext,
    significant: Boolean,
    flareBudgetAvailable: Boolean,
    collapseState: ActivityCollapseState,
): ActivityPresentation = when {
    context == ActivityPresentationContext.CAMERA_OVERLAY ->
        ActivityPresentation.HIDDEN
    significant && flareBudgetAvailable ->
        ActivityPresentation.FLARE
    significant ->
        ActivityPresentation.PULSE
    context == ActivityPresentationContext.ACTIVE_SURFACE ||
        context == ActivityPresentationContext.NEXUS_LAUNCHER ->
        ActivityPresentation.PULSE
    collapseState == ActivityCollapseState.ELAPSED ->
        ActivityPresentation.CHIP
    // Over a native app an island never expands, whatever its collapse: capped at its text form.
    context == ActivityPresentationContext.NATIVE_APP ->
        ActivityPresentation.CHIP
    else ->
        ActivityPresentation.PANEL
}

/**
 * The foreground's per-app mode over a presentation (plan 028a §8.2): residents keep it, signals
 * keep only the flare of a significant update, nothing hides every island. Islands stay passive
 * in every mode: this only decides what is drawn.
 */
internal fun applyForegroundMode(
    presentation: ActivityPresentation,
    mode: ForegroundAmbientMode,
): ActivityPresentation = when (mode) {
    ForegroundAmbientMode.RESIDENTS -> presentation
    ForegroundAmbientMode.SIGNALS ->
        if (presentation == ActivityPresentation.FLARE) presentation else ActivityPresentation.HIDDEN
    ForegroundAmbientMode.NOTHING -> ActivityPresentation.HIDDEN
}

/**
 * The size an island with an explicit ambient preference is drawn at (plan 028a §8.1): the
 * preference itself, capped at text over a native app. Null keeps today's automatic chip/panel.
 */
internal fun ambientIslandSize(
    preferred: AmbientSize?,
    context: ActivityPresentationContext,
): AmbientSize? = when {
    preferred == null -> null
    context == ActivityPresentationContext.NATIVE_APP -> AmbientSize.TEXT
    else -> preferred
}

internal data class ActivityPrimaryCandidate(
    val activityId: String,
    val startedOrder: Long,
    val lastSignificantOrder: Long?,
)

/** Session identity captured when a delayed ring tap begins. */
internal data class ActivityInputTarget(
    val activityId: String,
    val startedOrder: Long,
    val actionId: String?,
)

/**
 * A delayed confirmation remains owned only while the same activity session
 * is still primary on the idle input layer.
 */
internal fun canResolveActivityTap(
    captured: ActivityInputTarget?,
    current: ActivityInputTarget?,
    idleLayerStillOwned: Boolean,
): Boolean =
    idleLayerStillOwned &&
        captured != null &&
        current != null &&
        captured.activityId == current.activityId &&
        captured.startedOrder == current.startedOrder

/**
 * Selects the singular expanded activity.
 *
 * The latest significant moment wins: a significant update, or the start of a
 * new session, which the controller records the same way. Corners never move;
 * only which island expands does. Without any, the first started stays primary.
 */
internal fun selectPrimaryActivity(
    candidates: Collection<ActivityPrimaryCandidate>,
): String? {
    val significant = candidates
        .filter { it.lastSignificantOrder != null }
        .maxWithOrNull(
            compareBy<ActivityPrimaryCandidate> { it.lastSignificantOrder }
                .thenBy { it.startedOrder }
                .thenBy { it.activityId },
        )
    return significant?.activityId ?: candidates.minWithOrNull(
        compareBy<ActivityPrimaryCandidate> { it.startedOrder }
            .thenBy { it.activityId },
    )?.activityId
}

/**
 * Allocates activity corners without moving a valid resident.
 *
 * The pin's declared corner is reserved first. Activities whose previous
 * corner remains available keep it; only a collision or a newly started
 * activity consumes the next free corner. Entries that cannot fit are omitted,
 * leaving capacity and eviction policy to canonical phone state.
 */
internal fun allocateActivityCorners(
    activityIdsInOrder: List<String>,
    existing: Map<String, PinSurfacePosition>,
    pinCorner: PinSurfacePosition?,
): Map<String, PinSurfacePosition> {
    val activityIds = activityIdsInOrder.distinct()
    val result = linkedMapOf<String, PinSurfacePosition>()
    val occupied = mutableSetOf<PinSurfacePosition>()
    pinCorner?.let(occupied::add)

    // Reserve every still-valid resident before placing collisions/newcomers.
    // Otherwise an earlier activity displaced by the pin could steal a later
    // resident's valid corner and make two panels jump for one collision.
    activityIds.forEach { activityId ->
        existing[activityId]
            ?.takeIf { it !in occupied }
            ?.let { retained ->
                result[activityId] = retained
                occupied += retained
            }
    }
    activityIds.filterNot(result::containsKey).forEach { activityId ->
        val allocated = CORNER_ORDER.firstOrNull { it !in occupied }
            ?: return@forEach
        result[activityId] = allocated
        occupied += allocated
    }
    return result
}

private val CORNER_ORDER = listOf(
    PinSurfacePosition.TOP_LEFT,
    PinSurfacePosition.TOP_RIGHT,
    PinSurfacePosition.BOTTOM_LEFT,
    PinSurfacePosition.BOTTOM_RIGHT,
)
