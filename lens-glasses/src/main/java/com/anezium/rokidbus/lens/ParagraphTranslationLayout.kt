package com.anezium.rokidbus.lens

import kotlin.math.max
import kotlin.math.min

internal const val PARAGRAPH_PANEL_START_LINE_HEIGHT_RATIO = 0.85f
// Legacy paragraph-segmentation hint; frozen viewport-global fitting no longer enforces this cap.
internal const val PARAGRAPH_PANEL_MAX_HEIGHT_FACTOR = 1.35f
internal const val PARAGRAPH_PANEL_GAP_CLEARANCE_LINE_HEIGHTS = 0.10f
internal const val PARAGRAPH_PANEL_HORIZONTAL_CLEARANCE_LINE_HEIGHTS = 0.35f
internal const val PARAGRAPH_PANEL_SCREEN_EDGE_MARGIN_DP = 4f
internal const val FROZEN_MIN_READABLE_TEXT_SP = 7f
internal const val LIVE_MIN_READABLE_TEXT_SP = 10f

internal data class ParagraphPanelRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal data class ParagraphTextMeasureRequest(
    val textLength: Int,
    val contentWidthPx: Int,
    val textSizePx: Float,
)

internal data class ParagraphPanelFitDecision(
    val textSizePx: Float,
    val panelRect: ParagraphPanelRect,
    val truncated: Boolean,
    val requiredHeightPx: Float,
    val availableHeightPx: Float,
    val overflowHeightPx: Float,
)

internal data class ParagraphPanelHorizontalBudgetInput(
    val sourceRect: ParagraphPanelRect,
    val allowedRect: ParagraphPanelRect,
    val clearancePx: Float,
)

internal data class ParagraphPanelHorizontalBudget(
    val growLeftPx: Float,
    val growRightPx: Float,
)

internal data class ParagraphPanelSpaceInput(
    val sourceRect: ParagraphPanelRect,
    val horizontalClearancePx: Float,
    val verticalClearancePx: Float,
)

internal data class ParagraphPanelSpaceBudget(
    val growLeftPx: Float,
    val growRightPx: Float,
    val growUpPx: Float,
    val growDownPx: Float,
)

/**
 * Partitions viewport free space from the complete source geometry, rather than paragraph columns.
 * Pairwise gaps are split deterministically and retain an adaptive clearance, so two panels may
 * consume their complete budgets without crossing each other. Results are index-aligned with
 * [panels] and depend only on source rectangles, never on an earlier panel's result.
 */
internal fun computeParagraphPanelSpaceBudgets(
    panels: List<ParagraphPanelSpaceInput>,
    viewportRect: ParagraphPanelRect,
    edgeMarginPx: Float,
): List<ParagraphPanelSpaceBudget> {
    require(
        listOf(viewportRect.left, viewportRect.top, viewportRect.right, viewportRect.bottom)
            .all { it.isFinite() },
    ) { "viewportRect coordinates must be finite" }
    require(viewportRect.width >= 0f && viewportRect.height >= 0f) {
        "viewportRect dimensions must not be negative"
    }

    val safeEdgeMargin = edgeMarginPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val horizontalMargin = min(safeEdgeMargin, viewportRect.width / 2f)
    val verticalMargin = min(safeEdgeMargin, viewportRect.height / 2f)
    val viewportLeft = viewportRect.left + horizontalMargin
    val viewportRight = viewportRect.right - horizontalMargin
    val viewportTop = viewportRect.top + verticalMargin
    val viewportBottom = viewportRect.bottom - verticalMargin

    return panels.mapIndexed { panelIndex, panel ->
        val source = panel.sourceRect.normalized()
        var leftLimit = viewportLeft
        var rightLimit = viewportRight
        var topLimit = viewportTop
        var bottomLimit = viewportBottom

        panels.forEachIndexed { otherIndex, otherPanel ->
            if (otherIndex == panelIndex) return@forEachIndexed
            val other = otherPanel.sourceRect.normalized()

            if (verticalBandsOverlap(source, other)) {
                val preferredClearance = max(
                    panel.horizontalClearancePx.safeClearance(),
                    otherPanel.horizontalClearancePx.safeClearance(),
                )
                if (other.left >= source.right) {
                    val gap = other.left - source.right
                    rightLimit = min(
                        rightLimit,
                        other.left - partitionedGapReservation(gap, preferredClearance),
                    )
                }
                if (other.right <= source.left) {
                    val gap = source.left - other.right
                    leftLimit = max(
                        leftLimit,
                        other.right + partitionedGapReservation(gap, preferredClearance),
                    )
                }
            }

            if (horizontalBandsOverlap(source, other)) {
                val preferredClearance = max(
                    panel.verticalClearancePx.safeClearance(),
                    otherPanel.verticalClearancePx.safeClearance(),
                )
                if (other.top >= source.bottom) {
                    val gap = other.top - source.bottom
                    bottomLimit = min(
                        bottomLimit,
                        other.top - partitionedGapReservation(gap, preferredClearance),
                    )
                }
                if (other.bottom <= source.top) {
                    val gap = source.top - other.bottom
                    topLimit = max(
                        topLimit,
                        other.bottom + partitionedGapReservation(gap, preferredClearance),
                    )
                }
            }
        }

        ParagraphPanelSpaceBudget(
            growLeftPx = max(0f, source.left - leftLimit),
            growRightPx = max(0f, rightLimit - source.right),
            growUpPx = max(0f, source.top - topLimit),
            growDownPx = max(0f, bottomLimit - source.bottom),
        )
    }
}

/**
 * Computes each paragraph's horizontal free space from the original mapped source geometry.
 * Results are index-aligned with [panels]; no result is fed back into a later calculation.
 */
internal fun computeParagraphPanelHorizontalBudgets(
    panels: List<ParagraphPanelHorizontalBudgetInput>,
    viewWidthPx: Float,
    edgeMarginPx: Float,
): List<ParagraphPanelHorizontalBudget> {
    val safeViewWidth = viewWidthPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeEdgeMargin = edgeMarginPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val leftScreenLimit = min(safeEdgeMargin, safeViewWidth)
    val rightScreenLimit = max(0f, safeViewWidth - safeEdgeMargin)

    return panels.mapIndexed { panelIndex, panel ->
        val sourceLeft = min(panel.sourceRect.left, panel.sourceRect.right)
        val sourceRight = max(panel.sourceRect.left, panel.sourceRect.right)
        val clearance = panel.clearancePx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        var leftLimit = leftScreenLimit
        var rightLimit = rightScreenLimit

        panels.forEachIndexed { otherIndex, other ->
            if (otherIndex == panelIndex || !verticalBandsOverlap(panel.allowedRect, other.allowedRect)) {
                return@forEachIndexed
            }
            val otherLeft = min(other.sourceRect.left, other.sourceRect.right)
            val otherRight = max(other.sourceRect.left, other.sourceRect.right)
            if (otherLeft >= sourceRight) {
                rightLimit = min(rightLimit, otherLeft - clearance)
            }
            if (otherRight <= sourceLeft) {
                leftLimit = max(leftLimit, otherRight + clearance)
            }
        }

        ParagraphPanelHorizontalBudget(
            growLeftPx = max(0f, sourceLeft - leftLimit),
            growRightPx = max(0f, rightLimit - sourceRight),
        )
    }
}

/**
 * Chooses the largest readable paragraph layout after exhausting horizontal and viewport-global
 * vertical growth. Extra height is placed below the source first, then above it, to keep the
 * visual anchor stable while still using free space in either direction.
 * Android text measurement is injected so this decision stays pure and unit-testable while the
 * runtime can use the real StaticLayout metrics and typeface.
 */
internal fun decideParagraphPanelFit(
    sourceRect: ParagraphPanelRect,
    gapBelowPx: Float,
    growLeftPx: Float,
    growRightPx: Float,
    growUpPx: Float = 0f,
    textLength: Int,
    startingTextSizePx: Float,
    minimumTextSizePx: Float,
    horizontalPaddingPx: Float,
    verticalPaddingPx: Float,
    textSizeStepPx: Float,
    measureTextHeightPx: (ParagraphTextMeasureRequest) -> Float,
): ParagraphPanelFitDecision {
    require(
        listOf(sourceRect.left, sourceRect.top, sourceRect.right, sourceRect.bottom).all { it.isFinite() },
    ) { "sourceRect coordinates must be finite" }
    require(sourceRect.width > 0f && sourceRect.height > 0f) { "sourceRect must have positive dimensions" }
    require(textLength >= 0) { "textLength must not be negative" }
    require(minimumTextSizePx.isFinite() && minimumTextSizePx > 0f) {
        "minimumTextSizePx must be finite and positive"
    }
    require(startingTextSizePx.isFinite() && startingTextSizePx > 0f) {
        "startingTextSizePx must be finite and positive"
    }
    require(horizontalPaddingPx.isFinite() && verticalPaddingPx.isFinite()) { "padding must be finite" }
    require(horizontalPaddingPx >= 0f && verticalPaddingPx >= 0f) { "padding must not be negative" }
    require(textSizeStepPx.isFinite() && textSizeStepPx > 0f) {
        "textSizeStepPx must be finite and positive"
    }

    val safeGrowDown = gapBelowPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeGrowUp = growUpPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeGrowLeft = growLeftPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeGrowRight = growRightPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val totalHorizontalGrowth = safeGrowLeft + safeGrowRight
    val maximumPanelHeight = sourceRect.height + safeGrowUp + safeGrowDown
    var textSize = max(startingTextSizePx, minimumTextSizePx)

    while (true) {
        val lastWidthStep = if (totalHorizontalGrowth == 0f) 0 else HORIZONTAL_GROWTH_STEP_COUNT
        for (widthStep in 0..lastWidthStep) {
            val requestedGrowth = if (widthStep == HORIZONTAL_GROWTH_STEP_COUNT) {
                totalHorizontalGrowth
            } else {
                totalHorizontalGrowth * widthStep.toFloat() / HORIZONTAL_GROWTH_STEP_COUNT.toFloat()
            }
            val candidateRect = sourceRect.withRightFirstHorizontalGrowth(
                requestedGrowthPx = requestedGrowth,
                maximumGrowLeftPx = safeGrowLeft,
                maximumGrowRightPx = safeGrowRight,
            )
            val contentWidth = max(1, (candidateRect.width - horizontalPaddingPx * 2f).toInt())
            val measuredHeight = measureTextHeightPx(
                ParagraphTextMeasureRequest(
                    textLength = textLength,
                    contentWidthPx = contentWidth,
                    textSizePx = textSize,
                ),
            ).takeIf { it.isFinite() && it >= 0f } ?: Float.POSITIVE_INFINITY
            val requiredPanelHeight = max(
                sourceRect.height,
                measuredHeight + verticalPaddingPx * 2f,
            )
            if (requiredPanelHeight <= maximumPanelHeight + FIT_EPSILON_PX) {
                return ParagraphPanelFitDecision(
                    textSizePx = textSize,
                    panelRect = candidateRect.withVerticalGrowth(
                        targetHeight = min(requiredPanelHeight, maximumPanelHeight),
                        maximumGrowUpPx = safeGrowUp,
                        maximumGrowDownPx = safeGrowDown,
                    ),
                    truncated = false,
                    requiredHeightPx = requiredPanelHeight,
                    availableHeightPx = maximumPanelHeight,
                    overflowHeightPx = 0f,
                )
            }
        }

        if (textSize <= minimumTextSizePx + FIT_EPSILON_PX) {
            val widestRect = sourceRect.withRightFirstHorizontalGrowth(
                requestedGrowthPx = totalHorizontalGrowth,
                maximumGrowLeftPx = safeGrowLeft,
                maximumGrowRightPx = safeGrowRight,
            )
            val contentWidth = max(1, (widestRect.width - horizontalPaddingPx * 2f).toInt())
            val measuredHeight = measureTextHeightPx(
                ParagraphTextMeasureRequest(
                    textLength = textLength,
                    contentWidthPx = contentWidth,
                    textSizePx = minimumTextSizePx,
                ),
            ).takeIf { it.isFinite() && it >= 0f } ?: Float.POSITIVE_INFINITY
            val requiredPanelHeight = max(
                sourceRect.height,
                measuredHeight + verticalPaddingPx * 2f,
            )
            val overflowHeight = max(0f, requiredPanelHeight - maximumPanelHeight)
            return ParagraphPanelFitDecision(
                textSizePx = minimumTextSizePx,
                panelRect = widestRect.withVerticalGrowth(
                    targetHeight = maximumPanelHeight,
                    maximumGrowUpPx = safeGrowUp,
                    maximumGrowDownPx = safeGrowDown,
                ),
                truncated = overflowHeight > FIT_EPSILON_PX,
                requiredHeightPx = requiredPanelHeight,
                availableHeightPx = maximumPanelHeight,
                overflowHeightPx = overflowHeight,
            )
        }
        textSize = max(minimumTextSizePx, textSize - textSizeStepPx)
    }
}

/** Returns false before rendering can force or clip even one measured layout line. */
internal fun panelFitsMeasuredLayout(
    panelHeightPx: Float,
    paddingPx: Float,
    layoutHeightPx: Float,
    firstLineHeightPx: Float,
): Boolean {
    if (!panelHeightPx.isFinite() || !paddingPx.isFinite() ||
        !layoutHeightPx.isFinite() || !firstLineHeightPx.isFinite()
    ) {
        return false
    }
    if (panelHeightPx <= 0f || paddingPx < 0f ||
        layoutHeightPx <= 0f || firstLineHeightPx <= 0f
    ) {
        return false
    }
    val availableContentHeight = panelHeightPx - paddingPx * 2f
    return availableContentHeight + FIT_EPSILON_PX >= firstLineHeightPx &&
        availableContentHeight + FIT_EPSILON_PX >= layoutHeightPx
}

private fun ParagraphPanelRect.withVerticalGrowth(
    targetHeight: Float,
    maximumGrowUpPx: Float,
    maximumGrowDownPx: Float,
): ParagraphPanelRect {
    val extraHeight = max(0f, targetHeight.coerceAtLeast(1f) - height)
    val growDown = min(maximumGrowDownPx, extraHeight)
    val growUp = min(maximumGrowUpPx, max(0f, extraHeight - growDown))
    return copy(
        top = top - growUp,
        bottom = bottom + growDown,
    )
}

private fun ParagraphPanelRect.withRightFirstHorizontalGrowth(
    requestedGrowthPx: Float,
    maximumGrowLeftPx: Float,
    maximumGrowRightPx: Float,
): ParagraphPanelRect {
    val growRight = min(maximumGrowRightPx, requestedGrowthPx)
    val growLeft = min(maximumGrowLeftPx, max(0f, requestedGrowthPx - growRight))
    return copy(
        left = left - growLeft,
        right = right + growRight,
    )
}

private fun verticalBandsOverlap(
    first: ParagraphPanelRect,
    second: ParagraphPanelRect,
): Boolean {
    val firstTop = min(first.top, first.bottom)
    val firstBottom = max(first.top, first.bottom)
    val secondTop = min(second.top, second.bottom)
    val secondBottom = max(second.top, second.bottom)
    return firstTop < secondBottom && secondTop < firstBottom
}

private fun horizontalBandsOverlap(
    first: ParagraphPanelRect,
    second: ParagraphPanelRect,
): Boolean {
    val firstLeft = min(first.left, first.right)
    val firstRight = max(first.left, first.right)
    val secondLeft = min(second.left, second.right)
    val secondRight = max(second.left, second.right)
    return firstLeft < secondRight && secondLeft < firstRight
}

private fun ParagraphPanelRect.normalized(): ParagraphPanelRect =
    ParagraphPanelRect(
        left = min(left, right),
        top = min(top, bottom),
        right = max(left, right),
        bottom = max(top, bottom),
    )

private fun Float.safeClearance(): Float =
    takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f

private fun partitionedGapReservation(gapPx: Float, preferredClearancePx: Float): Float {
    val safeGap = gapPx.coerceAtLeast(0f)
    val adaptiveClearance = min(preferredClearancePx, safeGap * ADAPTIVE_CLEARANCE_GAP_FRACTION)
    return (safeGap + adaptiveClearance) / 2f
}

private const val FIT_EPSILON_PX = 0.01f
private const val HORIZONTAL_GROWTH_STEP_COUNT = 4
private const val ADAPTIVE_CLEARANCE_GAP_FRACTION = 0.25f
