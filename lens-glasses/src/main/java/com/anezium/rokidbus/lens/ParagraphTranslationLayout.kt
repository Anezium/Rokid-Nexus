package com.anezium.rokidbus.lens

import kotlin.math.max
import kotlin.math.min

internal const val PARAGRAPH_PANEL_START_LINE_HEIGHT_RATIO = 0.85f
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
 * Chooses the largest readable paragraph layout that fits after bounded downward growth.
 * Android text measurement is injected so this decision stays pure and unit-testable while the
 * runtime can use the real StaticLayout metrics and typeface.
 */
internal fun decideParagraphPanelFit(
    sourceRect: ParagraphPanelRect,
    gapBelowPx: Float,
    growLeftPx: Float,
    growRightPx: Float,
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

    val boundedGap = min(
        gapBelowPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f,
        sourceRect.height * (PARAGRAPH_PANEL_MAX_HEIGHT_FACTOR - 1f),
    )
    val safeGrowLeft = growLeftPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeGrowRight = growRightPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val totalHorizontalGrowth = safeGrowLeft + safeGrowRight
    val maximumPanelHeight = sourceRect.height + boundedGap
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
                    panelRect = candidateRect.withHeight(min(requiredPanelHeight, maximumPanelHeight)),
                    truncated = false,
                )
            }
        }

        if (textSize <= minimumTextSizePx + FIT_EPSILON_PX) {
            val widestRect = sourceRect.withRightFirstHorizontalGrowth(
                requestedGrowthPx = totalHorizontalGrowth,
                maximumGrowLeftPx = safeGrowLeft,
                maximumGrowRightPx = safeGrowRight,
            )
            return ParagraphPanelFitDecision(
                textSizePx = minimumTextSizePx,
                panelRect = widestRect.withHeight(maximumPanelHeight),
                truncated = true,
            )
        }
        textSize = max(minimumTextSizePx, textSize - textSizeStepPx)
    }
}

private fun ParagraphPanelRect.withHeight(height: Float): ParagraphPanelRect =
    copy(bottom = top + height.coerceAtLeast(1f))

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

private const val FIT_EPSILON_PX = 0.01f
private const val HORIZONTAL_GROWTH_STEP_COUNT = 4
