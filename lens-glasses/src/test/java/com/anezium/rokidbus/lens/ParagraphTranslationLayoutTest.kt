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
    fun fullWidthAndAvailableHeightAreExhaustedBeforeTruncation() {
        val decision = decideParagraphPanelFit(
            sourceRect = ParagraphPanelRect(10f, 20f, 110f, 40f),
            gapBelowPx = 27f,
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
        assertEquals(67f, decision.panelRect.bottom, 0.01f)
        assertEquals(1_002f, decision.requiredHeightPx, 0f)
        assertEquals(47f, decision.availableHeightPx, 0f)
        assertEquals(955f, decision.overflowHeightPx, 0f)
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
                requiredHeightPx = 26f,
                availableHeightPx = 27f,
                overflowHeightPx = 0f,
            ),
            decision,
        )
    }

    @Test
    fun globalViewportSpaceLetsFullTextGrowBeyondOldHeightFactor() {
        val sourceRect = ParagraphPanelRect(20f, 20f, 120f, 60f)
        val budget = computeParagraphPanelSpaceBudgets(
            panels = listOf(spaceInput(sourceRect)),
            viewportRect = ParagraphPanelRect(0f, 0f, 480f, 640f),
            edgeMarginPx = 4f,
        ).single()

        val decision = decideParagraphPanelFit(
            sourceRect = sourceRect,
            gapBelowPx = budget.growDownPx,
            growLeftPx = 0f,
            growRightPx = 0f,
            growUpPx = budget.growUpPx,
            textLength = 200,
            startingTextSizePx = 17f,
            minimumTextSizePx = 7f,
            horizontalPaddingPx = 1f,
            verticalPaddingPx = 1f,
            textSizeStepPx = 1f,
            measureTextHeightPx = { 80f },
        )

        assertFalse(decision.truncated)
        assertEquals(17f, decision.textSizePx, 0f)
        assertEquals(82f, decision.requiredHeightPx, 0f)
        assertEquals(632f, decision.availableHeightPx, 0f)
        assertEquals(0f, decision.overflowHeightPx, 0f)
        assertEquals(102f, decision.panelRect.bottom, 0f)
        assertTrue(decision.panelRect.height > sourceRect.height * PARAGRAPH_PANEL_MAX_HEIGHT_FACTOR)
    }

    @Test
    fun verticallyDisjointBandLetsFullTextUseNeighboringHorizontalSpace() {
        val sourceRect = ParagraphPanelRect(20f, 20f, 120f, 60f)
        val inputs = listOf(
            spaceInput(sourceRect),
            spaceInput(ParagraphPanelRect(200f, 100f, 300f, 140f)),
        )
        val budget = computeParagraphPanelSpaceBudgets(
            panels = inputs,
            viewportRect = ParagraphPanelRect(0f, 0f, 480f, 640f),
            edgeMarginPx = 4f,
        ).first()

        val decision = decideParagraphPanelFit(
            sourceRect = sourceRect,
            gapBelowPx = 0f,
            growLeftPx = budget.growLeftPx,
            growRightPx = budget.growRightPx,
            textLength = 120,
            startingTextSizePx = 17f,
            minimumTextSizePx = 7f,
            horizontalPaddingPx = 1f,
            verticalPaddingPx = 1f,
            textSizeStepPx = 1f,
            measureTextHeightPx = { request -> if (request.contentWidthPx >= 300) 38f else 80f },
        )

        assertEquals(356f, budget.growRightPx, 0f)
        assertFalse(decision.truncated)
        assertEquals(17f, decision.textSizePx, 0f)
        assertTrue(decision.panelRect.width >= 300f)
        assertEquals(0f, decision.overflowHeightPx, 0f)
    }

    @Test
    fun fitUsesSpaceAboveAfterDownwardSpaceIsConsumed() {
        val sourceRect = ParagraphPanelRect(20f, 100f, 220f, 120f)
        val budgets = computeParagraphPanelSpaceBudgets(
            panels = listOf(
                spaceInput(sourceRect, verticalClearancePx = 2f),
                spaceInput(
                    ParagraphPanelRect(20f, 130f, 220f, 150f),
                    verticalClearancePx = 2f,
                ),
            ),
            viewportRect = ParagraphPanelRect(0f, 0f, 240f, 160f),
            edgeMarginPx = 0f,
        )
        val budget = budgets.first()

        val decision = decideParagraphPanelFit(
            sourceRect = sourceRect,
            gapBelowPx = budget.growDownPx,
            growLeftPx = 0f,
            growRightPx = 0f,
            growUpPx = budget.growUpPx,
            textLength = 100,
            startingTextSizePx = 12f,
            minimumTextSizePx = 7f,
            horizontalPaddingPx = 1f,
            verticalPaddingPx = 1f,
            textSizeStepPx = 1f,
            measureTextHeightPx = { 50f },
        )

        assertEquals(4f, budget.growDownPx, 0f)
        assertEquals(100f, budget.growUpPx, 0f)
        assertFalse(decision.truncated)
        assertEquals(72f, decision.panelRect.top, 0f)
        assertEquals(124f, decision.panelRect.bottom, 0f)
    }

    @Test
    fun globalBudgetsPartitionSharedGapWithAdaptiveClearance() {
        val inputs = listOf(
            spaceInput(ParagraphPanelRect(0f, 20f, 100f, 60f), horizontalClearancePx = 10f),
            spaceInput(ParagraphPanelRect(200f, 20f, 300f, 60f), horizontalClearancePx = 10f),
        )

        val budgets = computeParagraphPanelSpaceBudgets(
            panels = inputs,
            viewportRect = ParagraphPanelRect(0f, 0f, 400f, 200f),
            edgeMarginPx = 0f,
        )

        assertEquals(45f, budgets[0].growRightPx, 0f)
        assertEquals(45f, budgets[1].growLeftPx, 0f)
        val firstRight = inputs[0].sourceRect.right + budgets[0].growRightPx
        val secondLeft = inputs[1].sourceRect.left - budgets[1].growLeftPx
        assertEquals(10f, secondLeft - firstRight, 0f)
    }

    @Test
    fun genuinelyExhaustedViewportTruncatesAtFloorAndReportsOverflow() {
        val decision = decideParagraphPanelFit(
            sourceRect = ParagraphPanelRect(0f, 0f, 100f, 20f),
            gapBelowPx = 10f,
            growLeftPx = 10f,
            growRightPx = 10f,
            growUpPx = 5f,
            textLength = 1_000,
            startingTextSizePx = 14f,
            minimumTextSizePx = 7f,
            horizontalPaddingPx = 1f,
            verticalPaddingPx = 1f,
            textSizeStepPx = 1f,
            measureTextHeightPx = { 100f },
        )

        assertTrue(decision.truncated)
        assertEquals(7f, decision.textSizePx, 0f)
        assertEquals(102f, decision.requiredHeightPx, 0f)
        assertEquals(35f, decision.availableHeightPx, 0f)
        assertEquals(67f, decision.overflowHeightPx, 0f)
        assertEquals(-5f, decision.panelRect.top, 0f)
        assertEquals(30f, decision.panelRect.bottom, 0f)
    }

    @Test
    fun staggeredColumnsFindCompleteMixedGrowthAfterLegacyCandidatesFail() {
        val sourceRect = ParagraphPanelRect(40f, 80f, 140f, 120f)
        val panels = listOf(
            spaceInput(sourceRect, columnIndex = 0),
            spaceInput(ParagraphPanelRect(220f, 160f, 320f, 200f), columnIndex = 1),
        )
        val viewport = ParagraphPanelRect(0f, 0f, 480f, 320f)
        val budget = computeParagraphPanelSpaceBudgets(
            panels = panels,
            viewportRect = viewport,
            edgeMarginPx = 0f,
        ).first()
        val candidates = generateParagraphPanelGrowthCandidates(
            panelIndex = 0,
            panels = panels,
            budget = budget,
            viewportRect = viewport,
            edgeMarginPx = 0f,
        )
        val legacyCandidates = listOf(
            ParagraphPanelGrowthCandidate(
                budget.growLeftPx,
                budget.growRightPx,
                budget.growUpPx,
                budget.growDownPx,
            ),
            ParagraphPanelGrowthCandidate(budget.growLeftPx, budget.growRightPx, 0f, 0f),
            ParagraphPanelGrowthCandidate(0f, 0f, budget.growUpPx, budget.growDownPx),
            ParagraphPanelGrowthCandidate(0f, 0f, 0f, 0f),
        )

        assertFalse(candidates.contains(legacyCandidates.first()))
        assertTrue(decideStaggeredFixtureFit(sourceRect, legacyCandidates[1]).truncated)
        assertTrue(decideStaggeredFixtureFit(sourceRect, legacyCandidates[2]).truncated)
        assertTrue(decideStaggeredFixtureFit(sourceRect, legacyCandidates[3]).truncated)

        val selected = selectParagraphPanelGrowthFit(
            candidates = candidates,
            prepareCandidate = { candidate ->
                CandidateFit(candidate, decideStaggeredFixtureFit(sourceRect, candidate))
            },
            isTruncated = { it.fit.truncated },
        )

        requireNotNull(selected)
        assertFalse(selected.fit.truncated)
        assertFalse(legacyCandidates.contains(selected.candidate))
        assertTrue(selected.candidate.growLeftPx + selected.candidate.growRightPx > 0f)
        assertTrue(selected.candidate.growUpPx + selected.candidate.growDownPx > 0f)
    }

    @Test
    fun packedColumnStillReturnsTruncatedTerminalFit() {
        val sourceRect = ParagraphPanelRect(10f, 20f, 110f, 40f)
        val panels = listOf(
            spaceInput(sourceRect, columnIndex = 0),
            spaceInput(ParagraphPanelRect(10f, 44f, 110f, 64f), columnIndex = 0),
        )
        val viewport = ParagraphPanelRect(0f, 0f, 120f, 70f)
        val budget = computeParagraphPanelSpaceBudgets(
            panels = panels,
            viewportRect = viewport,
            edgeMarginPx = 0f,
        ).first()
        val candidates = generateParagraphPanelGrowthCandidates(
            panelIndex = 0,
            panels = panels,
            budget = budget,
            viewportRect = viewport,
            edgeMarginPx = 0f,
        )

        val selected = selectParagraphPanelGrowthFit(
            candidates = candidates,
            prepareCandidate = { candidate ->
                decideParagraphPanelFit(
                    sourceRect = sourceRect,
                    gapBelowPx = candidate.growDownPx,
                    growLeftPx = candidate.growLeftPx,
                    growRightPx = candidate.growRightPx,
                    growUpPx = candidate.growUpPx,
                    textLength = 1_000,
                    startingTextSizePx = 10f,
                    minimumTextSizePx = 10f,
                    horizontalPaddingPx = 1f,
                    verticalPaddingPx = 1f,
                    textSizeStepPx = 1f,
                    measureTextHeightPx = { 500f },
                )
            },
            isTruncated = { it.truncated },
        )

        requireNotNull(selected)
        assertTrue(selected.truncated)
    }

    @Test
    fun mixedGrowthSearchIsDeterministic() {
        val sourceRect = ParagraphPanelRect(40f, 80f, 140f, 120f)
        val panels = listOf(
            spaceInput(sourceRect, columnIndex = 0),
            spaceInput(ParagraphPanelRect(220f, 160f, 320f, 200f), columnIndex = 1),
        )
        val viewport = ParagraphPanelRect(0f, 0f, 480f, 320f)
        val budget = computeParagraphPanelSpaceBudgets(panels, viewport, edgeMarginPx = 0f).first()

        fun search(): CandidateFit? {
            val candidates = generateParagraphPanelGrowthCandidates(
                panelIndex = 0,
                panels = panels,
                budget = budget,
                viewportRect = viewport,
                edgeMarginPx = 0f,
            )
            assertTrue(candidates.size <= 20)
            return selectParagraphPanelGrowthFit(
                candidates = candidates,
                prepareCandidate = { candidate ->
                    CandidateFit(candidate, decideStaggeredFixtureFit(sourceRect, candidate))
                },
                isTruncated = { it.fit.truncated },
            )
        }

        assertEquals(search(), search())
    }

    @Test
    fun postCollisionPanelRequiresOneCompleteMeasuredLineIncludingPadding() {
        assertTrue(
            panelFitsMeasuredLayout(
                panelHeightPx = 14f,
                paddingPx = 2f,
                layoutHeightPx = 10f,
                firstLineHeightPx = 10f,
            ),
        )
        assertFalse(
            panelFitsMeasuredLayout(
                panelHeightPx = 13.5f,
                paddingPx = 2f,
                layoutHeightPx = 10f,
                firstLineHeightPx = 10f,
            ),
        )
        assertFalse(
            panelFitsMeasuredLayout(
                panelHeightPx = 14f,
                paddingPx = 2f,
                layoutHeightPx = 20f,
                firstLineHeightPx = 10f,
            ),
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

    private data class CandidateFit(
        val candidate: ParagraphPanelGrowthCandidate,
        val fit: ParagraphPanelFitDecision,
    )

    private fun decideStaggeredFixtureFit(
        sourceRect: ParagraphPanelRect,
        candidate: ParagraphPanelGrowthCandidate,
    ): ParagraphPanelFitDecision = decideParagraphPanelFit(
        sourceRect = sourceRect,
        gapBelowPx = candidate.growDownPx,
        growLeftPx = candidate.growLeftPx,
        growRightPx = candidate.growRightPx,
        growUpPx = candidate.growUpPx,
        textLength = 200,
        startingTextSizePx = 10f,
        minimumTextSizePx = 10f,
        horizontalPaddingPx = 1f,
        verticalPaddingPx = 1f,
        textSizeStepPx = 1f,
        measureTextHeightPx = { request -> if (request.contentWidthPx >= 130) 86f else 400f },
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

    private fun spaceInput(
        sourceRect: ParagraphPanelRect,
        horizontalClearancePx: Float = 7f,
        verticalClearancePx: Float = 2f,
        columnIndex: Int = -1,
    ): ParagraphPanelSpaceInput = ParagraphPanelSpaceInput(
        sourceRect = sourceRect,
        horizontalClearancePx = horizontalClearancePx,
        verticalClearancePx = verticalClearancePx,
        columnIndex = columnIndex,
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
