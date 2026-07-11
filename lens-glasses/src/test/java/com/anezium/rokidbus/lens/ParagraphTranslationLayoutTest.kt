package com.anezium.rokidbus.lens

import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphTranslationLayoutTest {
    @Test
    fun sideBySideColumnsStopAtNeighborMinusClearance() {
        val panels = listOf(
            budgetInput(
                sourceRect = ParagraphPanelRect(20f, 10f, 120f, 40f),
                allowedBottom = 60f,
                clearancePx = 7f,
            ),
            budgetInput(
                sourceRect = ParagraphPanelRect(200f, 15f, 300f, 50f),
                allowedBottom = 70f,
                clearancePx = 7f,
            ),
        )

        val budgets = computeParagraphPanelHorizontalBudgets(
            panels = panels,
            viewWidthPx = 480f,
            edgeMarginPx = 4f,
        )

        assertEquals(73f, budgets[0].growRightPx, 0f)
    }

    @Test
    fun downwardAllowanceBandsBlockGrowthBeforeSourceBandsOverlap() {
        val panels = listOf(
            budgetInput(
                sourceRect = ParagraphPanelRect(20f, 10f, 120f, 30f),
                allowedBottom = 70f,
                clearancePx = 7f,
            ),
            budgetInput(
                sourceRect = ParagraphPanelRect(200f, 50f, 300f, 80f),
                allowedBottom = 90f,
                clearancePx = 7f,
            ),
        )

        val budgets = computeParagraphPanelHorizontalBudgets(
            panels = panels,
            viewWidthPx = 480f,
            edgeMarginPx = 4f,
        )

        assertEquals(73f, budgets[0].growRightPx, 0f)
    }

    @Test
    fun singlePanelReachesBothScreenEdgesMinusMargin() {
        val budgets = computeParagraphPanelHorizontalBudgets(
            panels = listOf(
                budgetInput(
                    sourceRect = ParagraphPanelRect(100f, 20f, 200f, 60f),
                    allowedBottom = 80f,
                    clearancePx = 8f,
                ),
            ),
            viewWidthPx = 480f,
            edgeMarginPx = 4f,
        )

        assertEquals(96f, budgets.single().growLeftPx, 0f)
        assertEquals(276f, budgets.single().growRightPx, 0f)
    }

    @Test
    fun horizontalBudgetsDoNotDependOnInputOrder() {
        val panels = listOf(
            budgetInput(ParagraphPanelRect(20f, 10f, 100f, 40f), allowedBottom = 70f, clearancePx = 5f),
            budgetInput(ParagraphPanelRect(160f, 20f, 240f, 50f), allowedBottom = 80f, clearancePx = 8f),
            budgetInput(ParagraphPanelRect(300f, 90f, 380f, 120f), allowedBottom = 140f, clearancePx = 6f),
        )
        val permuted = listOf(panels[2], panels[0], panels[1])

        assertEquals(
            budgetsBySourceRect(panels),
            budgetsBySourceRect(permuted),
        )
    }

    @Test
    fun fortyPercentLongerTranslationUsesFreeRightHalfAtStartingTextSize() {
        val sourceRect = ParagraphPanelRect(4f, 0f, 240f, 60f)
        val horizontalBudget = computeParagraphPanelHorizontalBudgets(
            panels = listOf(
                budgetInput(
                    sourceRect = sourceRect,
                    allowedBottom = 81f,
                    clearancePx = 6f,
                ),
            ),
            viewWidthPx = 480f,
            edgeMarginPx = 4f,
        ).single()
        val sourceCharacters = 90

        val decision = decideParagraphPanelFit(
            sourceRect = sourceRect,
            gapBelowPx = 21f,
            growLeftPx = horizontalBudget.growLeftPx,
            growRightPx = horizontalBudget.growRightPx,
            textLength = ceil(sourceCharacters * 1.40f).toInt(),
            startingTextSizePx = 17f,
            minimumTextSizePx = 7f,
            horizontalPaddingPx = 1f,
            verticalPaddingPx = 1f,
            textSizeStepPx = 1f,
            measureTextHeightPx = ::estimateWrappedTextHeight,
        )

        assertFalse(decision.truncated)
        assertEquals(17f, decision.textSizePx, 0f)
        assertTrue(decision.panelRect.width > sourceRect.width)
        assertTrue(decision.panelRect.left <= sourceRect.left)
        assertTrue(decision.panelRect.right >= sourceRect.right)
        assertTrue(decision.panelRect.left >= 4f)
        assertTrue(decision.panelRect.right <= 476f)
    }

    @Test
    fun thirtyPercentLongerTranslationFitsThreeLinePanelWithoutFloorOrTruncation() {
        val sourceCharacters = 90
        val translatedCharacters = ceil(sourceCharacters * 1.30f).toInt()
        val request = fitRequest(
            sourceRect = ParagraphPanelRect(0f, 0f, 300f, 60f),
            gapBelowPx = 21f,
            textLength = translatedCharacters,
            startingTextSizePx = 17f,
            minimumTextSizePx = 7f,
        )

        val decision = decideParagraphPanelFit(request)
        val repeated = decideParagraphPanelFit(request)

        assertEquals(decision, repeated)
        assertFalse(decision.truncated)
        assertTrue(decision.textSizePx > 7f)
        assertTrue(decision.panelRect.bottom > 60f)
        assertTrue(decision.panelRect.bottom <= 81f)
    }

    @Test
    fun fullWidthAndBoundedHeightAreExhaustedBeforeTruncation() {
        val decision = decideParagraphPanelFit(
            sourceRect = ParagraphPanelRect(10f, 20f, 110f, 40f),
            gapBelowPx = 1_000f,
            growLeftPx = 10f,
            growRightPx = 20f,
            textLength = 1_000,
            startingTextSizePx = 17f,
            minimumTextSizePx = 10f,
            horizontalPaddingPx = 1f,
            verticalPaddingPx = 1f,
            textSizeStepPx = 1f,
            measureTextHeightPx = { 1_000f },
        )

        assertTrue(decision.truncated)
        assertEquals(10f, decision.textSizePx, 0f)
        assertEquals(0f, decision.panelRect.left, 0f)
        assertEquals(130f, decision.panelRect.right, 0f)
        assertEquals(47f, decision.panelRect.bottom, 0.01f)
    }

    @Test
    fun zeroHorizontalBudgetsPreservePreviousFitDecision() {
        val decision = decideParagraphPanelFit(
            sourceRect = ParagraphPanelRect(0f, 0f, 100f, 20f),
            gapBelowPx = 7f,
            growLeftPx = 0f,
            growRightPx = 0f,
            textLength = 40,
            startingTextSizePx = 12f,
            minimumTextSizePx = 10f,
            horizontalPaddingPx = 1f,
            verticalPaddingPx = 1f,
            textSizeStepPx = 1f,
            measureTextHeightPx = { measurement -> if (measurement.textSizePx > 10f) 40f else 24f },
        )

        assertFalse(decision.truncated)
        assertEquals(
            ParagraphPanelFitDecision(
                textSizePx = 10f,
                panelRect = ParagraphPanelRect(0f, 0f, 100f, 26f),
                truncated = false,
            ),
            decision,
        )
    }

    @Test
    fun horizontalGrowthIsAllocatedRightFirst() {
        val decision = decideParagraphPanelFit(
            sourceRect = ParagraphPanelRect(100f, 0f, 200f, 20f),
            gapBelowPx = 0f,
            growLeftPx = 80f,
            growRightPx = 40f,
            textLength = 40,
            startingTextSizePx = 12f,
            minimumTextSizePx = 10f,
            horizontalPaddingPx = 0f,
            verticalPaddingPx = 0f,
            textSizeStepPx = 1f,
            measureTextHeightPx = { measurement ->
                if (measurement.contentWidthPx >= 160) 20f else 30f
            },
        )

        assertFalse(decision.truncated)
        assertEquals(12f, decision.textSizePx, 0f)
        assertEquals(80f, decision.panelRect.left, 0f)
        assertEquals(240f, decision.panelRect.right, 0f)
    }

    @Test
    fun nonFiniteHorizontalBudgetsAreTreatedAsZero() {
        fun decide(growLeftPx: Float, growRightPx: Float): ParagraphPanelFitDecision =
            decideParagraphPanelFit(
                sourceRect = ParagraphPanelRect(0f, 0f, 100f, 20f),
                gapBelowPx = 0f,
                growLeftPx = growLeftPx,
                growRightPx = growRightPx,
                textLength = 40,
                startingTextSizePx = 12f,
                minimumTextSizePx = 10f,
                horizontalPaddingPx = 1f,
                verticalPaddingPx = 1f,
                textSizeStepPx = 1f,
                measureTextHeightPx = { 18f },
            )

        assertEquals(decide(0f, 0f), decide(Float.NaN, Float.POSITIVE_INFINITY))
    }

    private data class FitRequest(
        val sourceRect: ParagraphPanelRect,
        val gapBelowPx: Float,
        val textLength: Int,
        val startingTextSizePx: Float,
        val minimumTextSizePx: Float,
    )

    private fun fitRequest(
        sourceRect: ParagraphPanelRect,
        gapBelowPx: Float,
        textLength: Int,
        startingTextSizePx: Float,
        minimumTextSizePx: Float,
    ): FitRequest = FitRequest(
        sourceRect = sourceRect,
        gapBelowPx = gapBelowPx,
        textLength = textLength,
        startingTextSizePx = startingTextSizePx,
        minimumTextSizePx = minimumTextSizePx,
    )

    private fun decideParagraphPanelFit(request: FitRequest): ParagraphPanelFitDecision =
        decideParagraphPanelFit(
            sourceRect = request.sourceRect,
            gapBelowPx = request.gapBelowPx,
            growLeftPx = 0f,
            growRightPx = 0f,
            textLength = request.textLength,
            startingTextSizePx = request.startingTextSizePx,
            minimumTextSizePx = request.minimumTextSizePx,
            horizontalPaddingPx = 1f,
            verticalPaddingPx = 1f,
            textSizeStepPx = 1f,
            measureTextHeightPx = ::estimateWrappedTextHeight,
        )

    private fun budgetInput(
        sourceRect: ParagraphPanelRect,
        allowedBottom: Float,
        clearancePx: Float,
    ): ParagraphPanelHorizontalBudgetInput = ParagraphPanelHorizontalBudgetInput(
        sourceRect = sourceRect,
        allowedRect = sourceRect.copy(bottom = allowedBottom),
        clearancePx = clearancePx,
    )

    private fun budgetsBySourceRect(
        panels: List<ParagraphPanelHorizontalBudgetInput>,
    ): Map<ParagraphPanelRect, ParagraphPanelHorizontalBudget> =
        panels.zip(
            computeParagraphPanelHorizontalBudgets(
                panels = panels,
                viewWidthPx = 480f,
                edgeMarginPx = 4f,
            ),
        ).associate { (input, budget) -> input.sourceRect to budget }

    private fun estimateWrappedTextHeight(request: ParagraphTextMeasureRequest): Float {
        val averageCharacterWidth = request.textSizePx * 0.52f
        val charactersPerLine = floor(request.contentWidthPx / averageCharacterWidth)
            .toInt()
            .coerceAtLeast(1)
        val lineCount = ceil(request.textLength.toFloat() / charactersPerLine.toFloat())
            .toInt()
            .coerceAtLeast(1)
        return lineCount * request.textSizePx * 1.10f
    }
}
