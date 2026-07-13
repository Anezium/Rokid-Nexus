package com.anezium.rokidbus.glasses

import kotlin.math.max
import kotlin.math.min

internal data class AdaptivePanelGeometry(
    val stableId: String,
    val sourceRect: ParagraphPanelRect,
    val panelRect: ParagraphPanelRect,
)

internal data class AdaptivePanelCandidateQuality(
    val complete: Boolean,
    val textSizePx: Float,
    val retainedTextFraction: Float,
    val panelAreaPx: Float = Float.POSITIVE_INFINITY,
)

internal data class AdaptiveCollisionResolution(
    val panelRect: ParagraphPanelRect?,
    val changed: Boolean,
    val suppressedByStableId: String? = null,
)

/**
 * Resolves opaque panels in deterministic compact-first order and returns results aligned with
 * [panels]. Smaller complete panels reserve their local gaps before large paragraphs consume the
 * surrounding free space. Source overlap is deliberately not an exception here: every retained
 * panel is separated from every earlier retained panel. Text-aware callers can reject or score
 * each trim through [evaluateFit].
 */
internal fun resolveAdaptivePanelCollisions(
    panels: List<AdaptivePanelGeometry>,
    clearancePx: Float,
    evaluateFit: (panelIndex: Int, candidateRect: ParagraphPanelRect) -> AdaptivePanelCandidateQuality? =
        { _, _ -> AdaptivePanelCandidateQuality(true, 0f, 1f) },
): List<AdaptiveCollisionResolution> {
    val clearance = clearancePx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val accepted = mutableListOf<AcceptedPanel>()
    val resolutions = MutableList(panels.size) { AdaptiveCollisionResolution(null, changed = true) }
    val ordered = panels.indices.sortedWith { firstIndex, secondIndex ->
        compareStablePanelPriority(panels[firstIndex], panels[secondIndex])
    }

    ordered.forEach { panelIndex ->
        val panel = panels[panelIndex]
        val original = panel.panelRect.normalizedRect()
        val duplicate = panel.stableId.takeIf(String::isNotBlank)?.let { stableId ->
            accepted.firstOrNull { it.stableId == stableId }
        }
        if (duplicate != null) {
            resolutions[panelIndex] = AdaptiveCollisionResolution(
                panelRect = null,
                changed = true,
                suppressedByStableId = duplicate.stableId,
            )
            return@forEach
        }

        val candidates = collisionAlternatives(original, accepted, clearance)
        val selected = candidates.mapNotNull { candidate ->
            evaluateFit(panelIndex, candidate)?.let { quality ->
                ScoredCollisionCandidate(candidate, quality.sanitized())
            }
        }.maxWithOrNull(collisionCandidateComparator)

        if (selected == null) {
            val blocker = accepted.firstOrNull { original.overlaps(it.rect.expanded(clearance)) }
            resolutions[panelIndex] = AdaptiveCollisionResolution(
                panelRect = null,
                changed = true,
                suppressedByStableId = blocker?.stableId,
            )
        } else {
            accepted += AcceptedPanel(panel.stableId, selected.rect)
            resolutions[panelIndex] = AdaptiveCollisionResolution(
                panelRect = selected.rect,
                changed = selected.rect != original,
            )
        }
    }
    return resolutions
}

internal fun doesNotIncreasePreexistingSourceOverlap(
    sourceRect: ParagraphPanelRect,
    panelRect: ParagraphPanelRect,
    obstacle: AdaptivePanelGeometry,
    clearancePx: Float = 0f,
): Boolean {
    val clearance = clearancePx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val candidate = panelRect.normalizedRect().intersection(obstacle.panelRect.normalizedRect().expanded(clearance))
        ?: return true
    val allowed = sourceRect.normalizedRect().intersection(obstacle.sourceRect.normalizedRect().expanded(clearance))
        ?: return false
    return candidate.left + EPSILON >= allowed.left &&
        candidate.top + EPSILON >= allowed.top &&
        candidate.right <= allowed.right + EPSILON &&
        candidate.bottom <= allowed.bottom + EPSILON
}

private fun collisionAlternatives(
    original: ParagraphPanelRect,
    accepted: List<AcceptedPanel>,
    clearancePx: Float,
): List<ParagraphPanelRect> {
    var candidates = listOf(original)
    accepted.forEach { obstacle ->
        val blocked = obstacle.rect.expanded(clearancePx)
        candidates = candidates.flatMap { candidate ->
            if (!candidate.overlaps(blocked)) listOf(candidate) else separatingTrims(candidate, blocked)
        }.filter { candidate ->
            candidate.width > EPSILON && candidate.height > EPSILON &&
                accepted.none { earlier -> candidate.overlaps(earlier.rect.expanded(clearancePx)) }
        }.distinct().sortedWith(rectGeometryComparator).take(COLLISION_ALTERNATIVE_LIMIT)
    }
    return candidates
}

/** Returns both horizontal and both vertical separation choices; text scoring decides among them. */
private fun separatingTrims(
    panelRect: ParagraphPanelRect,
    blockedRect: ParagraphPanelRect,
): List<ParagraphPanelRect> = buildList {
    panelRect.copy(right = min(panelRect.right, blockedRect.left)).takeIf { it.width > EPSILON }?.let(::add)
    panelRect.copy(left = max(panelRect.left, blockedRect.right)).takeIf { it.width > EPSILON }?.let(::add)
    panelRect.copy(bottom = min(panelRect.bottom, blockedRect.top)).takeIf { it.height > EPSILON }?.let(::add)
    panelRect.copy(top = max(panelRect.top, blockedRect.bottom)).takeIf { it.height > EPSILON }?.let(::add)
}

private fun compareStablePanelPriority(
    first: AdaptivePanelGeometry,
    second: AdaptivePanelGeometry,
): Int = stablePanelPriorityComparator.compare(first, second)

private val stablePanelPriorityComparator =
    compareBy<AdaptivePanelGeometry> { it.panelRect.normalizedRect().area() }
        .thenBy { it.sourceRect.normalizedRect().area() }
        .thenBy { it.stableId }
        .thenBy { it.sourceRect.normalizedRect().top }
        .thenBy { it.sourceRect.normalizedRect().left }
        .thenBy { it.sourceRect.normalizedRect().bottom }
        .thenBy { it.sourceRect.normalizedRect().right }
        .thenBy { it.panelRect.normalizedRect().top }
        .thenBy { it.panelRect.normalizedRect().left }
        .thenBy { it.panelRect.normalizedRect().bottom }
        .thenBy { it.panelRect.normalizedRect().right }

private val collisionCandidateComparator =
    compareBy<ScoredCollisionCandidate> { it.quality.complete }
        .thenBy { it.quality.retainedTextFraction }
        .thenBy { it.quality.textSizePx }
        .thenByDescending { it.quality.panelAreaPx }
        .thenBy { it.rect.area() }
        .thenBy { it.rect.width }
        .thenBy { it.rect.height }
        .thenByDescending { it.rect.left }
        .thenByDescending { it.rect.top }

private val rectGeometryComparator =
    compareByDescending<ParagraphPanelRect> { it.area() }
        .thenByDescending { it.width }
        .thenByDescending { it.height }
        .thenBy { it.left }
        .thenBy { it.top }

private fun AdaptivePanelCandidateQuality.sanitized() = copy(
    textSizePx = textSizePx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f,
    retainedTextFraction = retainedTextFraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
    panelAreaPx = panelAreaPx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: Float.POSITIVE_INFINITY,
)

private fun ParagraphPanelRect.normalizedRect() = ParagraphPanelRect(
    min(left, right), min(top, bottom), max(left, right), max(top, bottom),
)

private fun ParagraphPanelRect.expanded(amount: Float) = ParagraphPanelRect(
    left - amount, top - amount, right + amount, bottom + amount,
)

private fun ParagraphPanelRect.intersection(other: ParagraphPanelRect): ParagraphPanelRect? {
    val result = ParagraphPanelRect(
        max(left, other.left), max(top, other.top), min(right, other.right), min(bottom, other.bottom),
    )
    return result.takeIf { it.width > 0f && it.height > 0f }
}

private fun ParagraphPanelRect.overlaps(other: ParagraphPanelRect): Boolean =
    min(right, other.right) - max(left, other.left) > EPSILON &&
        min(bottom, other.bottom) - max(top, other.top) > EPSILON

private fun ParagraphPanelRect.area(): Float = max(0f, width) * max(0f, height)

private data class AcceptedPanel(val stableId: String, val rect: ParagraphPanelRect)
private data class ScoredCollisionCandidate(
    val rect: ParagraphPanelRect,
    val quality: AdaptivePanelCandidateQuality,
)

private const val COLLISION_ALTERNATIVE_LIMIT = 64
private const val EPSILON = 0.01f
