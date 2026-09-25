package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.PinSurfacePosition

/** The glasses-owned context that determines how loudly an activity is presented. */
internal enum class ActivityPresentationContext {
    ACTIVE_SURFACE,
    NEXUS_LAUNCHER,
    IDLE_OR_NATIVE_HOME,
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
    else ->
        ActivityPresentation.PANEL
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
 * A significant update always wins. Before any activity has one, the first
 * still-live activity remains primary so merely starting a second process does
 * not make the HUD jump corners.
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

/** Symmetric child-view translations for the two halves of a flare morph. */
internal data class ActivityFlareTranslation(
    val nodeToBandX: Float,
    val nodeToBandY: Float,
    val bandToNodeX: Float,
    val bandToNodeY: Float,
)

internal fun activityFlareTranslation(
    nodeCenterX: Float,
    nodeCenterY: Float,
    bandCenterX: Float,
    bandCenterY: Float,
): ActivityFlareTranslation {
    val x = bandCenterX - nodeCenterX
    val y = bandCenterY - nodeCenterY
    return ActivityFlareTranslation(
        nodeToBandX = x,
        nodeToBandY = y,
        bandToNodeX = -x,
        bandToNodeY = -y,
    )
}

private val CORNER_ORDER = listOf(
    PinSurfacePosition.TOP_LEFT,
    PinSurfacePosition.TOP_RIGHT,
    PinSurfacePosition.BOTTOM_LEFT,
    PinSurfacePosition.BOTTOM_RIGHT,
)
