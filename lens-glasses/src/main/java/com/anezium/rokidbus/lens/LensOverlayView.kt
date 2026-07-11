package com.anezium.rokidbus.lens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class LensOverlayBlock(
    val source: String,
    val normalized: String,
    val bounds: Rect,
    val lineBounds: List<Rect>,
    val translation: String?,
    val sourceLang: String?,
    val stableId: Long? = null,
    val gapBelow: Float = 0f,
    val columnIndex: Int = -1,
)

data class LensHudState(
    val mode: String = "LATIN",
    val targetLang: String = "fr",
    val cacheEntries: Int = 0,
    val busLabel: String = "DATA LINK OFFLINE",
    val ocrHz: Double = 0.0,
    val status: String = "STARTING",
    val frozen: Boolean = false,
)

data class LensOverlayLayoutStats(
    val suppressedPanels: Int = 0,
    val truncatedPanels: Int = 0,
)

class LensOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN
        typeface = Typeface.MONOSPACE
        textSize = 12f * resources.displayMetrics.scaledDensity
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN
        typeface = Typeface.DEFAULT_BOLD
    }
    private val scratchTextPaint = TextPaint(textPaint)
    private val modeFlashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 48f * resources.displayMetrics.scaledDensity
    }
    private val bitmapMatrix = Matrix()
    private val fittedLayoutCache = object : LinkedHashMap<LayoutCacheKey, LayoutFit>(
        FITTED_LAYOUT_CACHE_SIZE,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LayoutCacheKey, LayoutFit>?): Boolean =
            size > FITTED_LAYOUT_CACHE_SIZE
    }
    private val fixedLayoutCache = object : LinkedHashMap<FixedLayoutCacheKey, StaticLayout>(
        FIXED_LAYOUT_CACHE_SIZE,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FixedLayoutCacheKey, StaticLayout>?): Boolean =
            size > FIXED_LAYOUT_CACHE_SIZE
    }

    private var frozenBackground: Bitmap? = null
    private var transformMatrix: Matrix? = null
    private var blocks: List<LensOverlayBlock> = emptyList()
    private var hudState = LensHudState()
    private var modeFlashLabel: String? = null
    private var modeFlashUntilMs: Long = 0L
    private val pendingLayoutStatsListeners = ArrayDeque<(LensOverlayLayoutStats) -> Unit>()
    private var lastCompletedLayoutStats = LensOverlayLayoutStats()

    fun setFrozenBackground(bitmap: Bitmap?, recyclePrevious: Boolean = true) {
        val previous = frozenBackground
        if (recyclePrevious && previous != null && previous !== bitmap && !previous.isRecycled) {
            frozenBackground = null
            previous.recycle()
        }
        frozenBackground = bitmap
        invalidate()
    }

    fun replaceFrozenBackground(expected: Bitmap, replacement: Bitmap): Boolean {
        if (frozenBackground !== expected) return false
        frozenBackground = null
        if (!expected.isRecycled) expected.recycle()
        frozenBackground = replacement
        invalidate()
        return true
    }

    fun isFrozenBackground(bitmap: Bitmap): Boolean =
        frozenBackground === bitmap

    fun clearLayoutCache() {
        fittedLayoutCache.clear()
        fixedLayoutCache.clear()
    }

    fun updateOcrResult(
        transformMatrix: Matrix?,
        blocks: List<LensOverlayBlock>,
        hudState: LensHudState,
        onLayoutStats: ((LensOverlayLayoutStats) -> Unit)? = null,
    ) {
        this.transformMatrix = transformMatrix?.let(::Matrix)
        this.blocks = blocks
        this.hudState = hudState
        if (hudState.frozen || onLayoutStats != null) {
            dispatchPendingLayoutStats(lastCompletedLayoutStats)
        }
        if (onLayoutStats != null) pendingLayoutStatsListeners.addLast(onLayoutStats)
        invalidate()
    }

    fun updateHud(hudState: LensHudState) {
        this.hudState = hudState
        invalidate()
    }

    fun showModeFlash(label: String) {
        modeFlashLabel = label
        modeFlashUntilMs = SystemClock.elapsedRealtime() + MODE_FLASH_MS
        invalidate()
        postInvalidateDelayed(MODE_FLASH_MS)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        frozenBackground?.let { bitmap ->
            bitmapMatrix.setFillCenter(bitmap.width, bitmap.height, width, height)
            canvas.drawBitmap(bitmap, bitmapMatrix, null)
        }
        val matrix = transformMatrix
        val useFrozenLayout = hudState.frozen || frozenBackground != null
        val translatedBlocks = mutableListOf<TranslatedBlock>()
        val translatedSourceRects = linkedMapOf<Long, RectF>()
        val sourceOutlineRects = mutableListOf<RectF>()
        val mappedBlocks = if (matrix == null) {
            emptyList()
        } else {
            blocks.mapIndexed { index, block ->
                val sourceRect = RectF(block.bounds).also { matrix.mapRect(it) }
                val allowedRect = RectF(
                    block.bounds.left.toFloat(),
                    block.bounds.top.toFloat(),
                    block.bounds.right.toFloat(),
                    block.bounds.bottom.toFloat() + block.gapBelow.coerceAtLeast(0f),
                ).also { matrix.mapRect(it) }
                MappedOverlayBlock(
                    block = block,
                    sourceRect = sourceRect,
                    allowedRect = allowedRect,
                    medianSourceLineHeight = medianMappedLineHeight(
                        lineBounds = block.lineBounds,
                        matrix = matrix,
                        fallbackHeight = sourceRect.height(),
                    ),
                    stableId = block.stableId ?: index.toLong(),
                )
            }
        }
        val edgeMarginPx = PARAGRAPH_PANEL_SCREEN_EDGE_MARGIN_DP * resources.displayMetrics.density
        val hudObstacleRect = hudBounds()
        val frozenPanelInputs = if (useFrozenLayout) {
            mappedBlocks.map { mappedBlock ->
                ParagraphPanelSpaceInput(
                    sourceRect = mappedBlock.sourceRect.toParagraphPanelRect(),
                    horizontalClearancePx = mappedBlock.medianSourceLineHeight *
                        PARAGRAPH_PANEL_HORIZONTAL_CLEARANCE_LINE_HEIGHTS,
                    verticalClearancePx = mappedBlock.medianSourceLineHeight *
                        PARAGRAPH_PANEL_GAP_CLEARANCE_LINE_HEIGHTS,
                    columnIndex = mappedBlock.block.columnIndex,
                )
            }
        } else {
            emptyList()
        }
        val frozenObstacleInputs = if (useFrozenLayout) {
            frozenPanelInputs + ParagraphPanelSpaceInput(
                sourceRect = hudObstacleRect.toParagraphPanelRect(),
                horizontalClearancePx = 0f,
                verticalClearancePx = 0f,
            )
        } else {
            emptyList()
        }
        val frozenSpaceBudgets = if (useFrozenLayout) {
            computeParagraphPanelSpaceBudgets(
                panels = frozenObstacleInputs,
                viewportRect = ParagraphPanelRect(0f, 0f, width.toFloat(), height.toFloat()),
                edgeMarginPx = edgeMarginPx,
            ).take(frozenPanelInputs.size)
        } else {
            emptyList()
        }
        val liveHorizontalBudgets = if (useFrozenLayout) {
            emptyList()
        } else {
            computeParagraphPanelHorizontalBudgets(
                panels = mappedBlocks.map { mappedBlock ->
                    ParagraphPanelHorizontalBudgetInput(
                        sourceRect = mappedBlock.sourceRect.toParagraphPanelRect(),
                        allowedRect = mappedBlock.allowedRect.toParagraphPanelRect(),
                        clearancePx = mappedBlock.medianSourceLineHeight *
                            PARAGRAPH_PANEL_HORIZONTAL_CLEARANCE_LINE_HEIGHTS,
                    )
                },
                viewWidthPx = width.toFloat(),
                edgeMarginPx = edgeMarginPx,
            )
        }
        mappedBlocks.forEachIndexed { index, mappedBlock ->
            val block = mappedBlock.block
            val sourceRect = mappedBlock.sourceRect
            val allowedRect = RectF(
                mappedBlock.allowedRect,
            )
            val translation = block.translation?.takeIf { it.isNotBlank() }
            if (translation == null) {
                sourceOutlineRects += RectF(sourceRect)
            } else {
                translatedSourceRects[mappedBlock.stableId] = RectF(sourceRect)
                if (sourceRect.width() >= 4f && sourceRect.height() >= 4f) {
                    val minimumTextSizePx = minimumTranslationTextSizePx(useFrozenLayout)
                    val frozenBudget = frozenSpaceBudgets.getOrNull(index)
                    val liveHorizontalBudget = liveHorizontalBudgets.getOrNull(index)
                    val growthCandidates = if (frozenBudget != null) {
                        generateParagraphPanelGrowthCandidates(
                            panelIndex = index,
                            panels = frozenObstacleInputs,
                            budget = frozenBudget,
                            viewportRect = ParagraphPanelRect(0f, 0f, width.toFloat(), height.toFloat()),
                            edgeMarginPx = edgeMarginPx,
                        )
                    } else {
                        val growLeftPx = liveHorizontalBudget?.growLeftPx ?: 0f
                        val growRightPx = liveHorizontalBudget?.growRightPx ?: 0f
                        val growDownPx = maximumBottom(allowedRect, sourceRect) - sourceRect.bottom
                        listOf(
                            ParagraphPanelGrowthCandidate(growLeftPx, growRightPx, 0f, growDownPx),
                            ParagraphPanelGrowthCandidate(growLeftPx, growRightPx, 0f, 0f),
                            ParagraphPanelGrowthCandidate(0f, 0f, 0f, growDownPx),
                            ParagraphPanelGrowthCandidate(0f, 0f, 0f, 0f),
                        )
                    }
                    prepareTranslatedParagraphPanel(
                        rect = sourceRect,
                        sourceRect = sourceRect,
                        text = translation,
                        stableId = mappedBlock.stableId,
                        startingTextSizePx = max(
                            minimumTextSizePx,
                            mappedBlock.medianSourceLineHeight * PARAGRAPH_PANEL_START_LINE_HEIGHT_RATIO,
                        ),
                        minimumTextSizePx = minimumTextSizePx,
                        growthCandidates = growthCandidates,
                        blockedSourceRects = mappedBlocks.mapIndexedNotNull { otherIndex, other ->
                            RectF(other.sourceRect).takeIf { otherIndex != index }
                        },
                        hudObstacleRect = hudObstacleRect,
                    )?.let { translatedBlocks += it }
                }
            }
        }
        // Required safety net: malformed OCR collisions may adjust panels while retaining anchors.
        val collisionResolved = resolveTranslatedBlockCollisions(translatedBlocks)
            .mapNotNull(::refitTranslatedBlockToRect)
        val sourceSafeBlocks = collisionResolved.filter { candidate ->
            // Coarse FAST-1080 OCR yields source boxes that already overlap each other; a
            // hard no-intersect veto made those panels unrenderable (field 2026-07-11,
            // suppressedPanels=2-5 per freeze). Pre-existing overlap is tolerated — growth
            // just must not make any of it worse.
            val avoidsOtherSources = mappedBlocks.none { other ->
                other.stableId != candidate.stableId &&
                    !doesNotIncreaseObstacleOverlap(candidate.rect, other.sourceRect, candidate.sourceRect)
            }
            val staysInViewport = candidate.rect.left >= 0f && candidate.rect.top >= 0f &&
                candidate.rect.right <= width.toFloat() && candidate.rect.bottom <= height.toFloat()
            avoidsOtherSources && staysInViewport &&
                doesNotIncreaseObstacleOverlap(candidate.rect, hudObstacleRect, candidate.sourceRect)
        }
        val laidOutBlocks = suppressResidualLiveOverlaps(sourceSafeBlocks)
        val retainedIds = laidOutBlocks.mapTo(mutableSetOf()) { it.stableId }
        translatedSourceRects.forEach { (stableId, sourceRect) ->
            if (stableId !in retainedIds) sourceOutlineRects += RectF(sourceRect)
        }
        // In live mode the HUD goes UNDER the panels: top-of-scene paragraphs are legitimately
        // anchored inside the HUD band (their sources sit there), and hiding a translation the
        // user waited for is worse than covering a status line. Frozen keeps HUD-on-top since
        // its panels never share the band (budgets treat the HUD as an obstacle).
        if (!useFrozenLayout) drawHud(canvas)
        laidOutBlocks
            .sortedByDescending { it.rect.width() * it.rect.height() }
            .forEach { block -> canvas.drawRect(block.rect, fillPaint) }
        laidOutBlocks.forEach { block -> drawTranslationText(canvas, block) }
        sourceOutlineRects.forEach { sourceRect -> canvas.drawRect(sourceRect, outlinePaint) }
        val layoutStats = LensOverlayLayoutStats(
            suppressedPanels = (translatedSourceRects.size - laidOutBlocks.size).coerceAtLeast(0),
            truncatedPanels = laidOutBlocks.count { it.truncated },
        )
        lastCompletedLayoutStats = layoutStats
        dispatchPendingLayoutStats(layoutStats)
        if (useFrozenLayout) drawHud(canvas)
        drawModeFlash(canvas)
    }

    private fun dispatchPendingLayoutStats(layoutStats: LensOverlayLayoutStats) {
        while (pendingLayoutStatsListeners.isNotEmpty()) {
            val listener = pendingLayoutStatsListeners.removeFirst()
            runCatching {
                listener(layoutStats)
            }
        }
    }

    private fun prepareTranslatedParagraphPanel(
        rect: RectF,
        sourceRect: RectF,
        text: String,
        stableId: Long,
        startingTextSizePx: Float,
        minimumTextSizePx: Float,
        growthCandidates: List<ParagraphPanelGrowthCandidate>,
        blockedSourceRects: List<RectF>,
        hudObstacleRect: RectF,
    ): TranslatedBlock? {
        val density = resources.displayMetrics.density
        val pad = PARAGRAPH_PANEL_PADDING_DP * density
        fun prepareCandidate(
            candidateGrowLeftPx: Float,
            candidateGrowRightPx: Float,
            candidateGrowUpPx: Float,
            candidateGrowDownPx: Float,
        ): TranslatedBlock? {
            val fit = fittedLayout(
                text = text,
                sourceWidthPx = rect.width(),
                sourceHeightPx = rect.height(),
                gapAbovePx = candidateGrowUpPx,
                gapBelowPx = candidateGrowDownPx,
                growLeftPx = candidateGrowLeftPx,
                growRightPx = candidateGrowRightPx,
                startingTextSizePx = startingTextSizePx,
                minimumTextSizePx = minimumTextSizePx,
                pad = pad,
            ) ?: return null
            val selectedRect = RectF(
                rect.left + fit.panelRect.left,
                rect.top + fit.panelRect.top,
                rect.left + fit.panelRect.right,
                rect.top + fit.panelRect.bottom,
            )
            if (blockedSourceRects.any { RectF.intersects(selectedRect, it) }) return null
            if (!doesNotIncreaseObstacleOverlap(selectedRect, hudObstacleRect, sourceRect)) return null
            return TranslatedBlock(
                stableId = stableId,
                rect = selectedRect,
                sourceRect = RectF(sourceRect),
                text = text,
                layout = fit.layout,
                pad = pad,
                textSizePx = fit.textSizePx,
                minimumTextSizePx = minimumTextSizePx,
                truncated = fit.truncated,
            )
        }

        return selectParagraphPanelGrowthFit(
            candidates = growthCandidates,
            prepareCandidate = { candidate ->
                prepareCandidate(
                    candidateGrowLeftPx = candidate.growLeftPx,
                    candidateGrowRightPx = candidate.growRightPx,
                    candidateGrowUpPx = candidate.growUpPx,
                    candidateGrowDownPx = candidate.growDownPx,
                )
            },
            isTruncated = { it.truncated },
        )
    }

    private fun resolveTranslatedBlockCollisions(blocks: List<TranslatedBlock>): List<TranslatedBlock> {
        if (blocks.size < 2 || height <= 0) return blocks
        val resolved = blocks
            .sortedWith(compareBy<TranslatedBlock> { it.stableId }.thenBy { it.sourceRect.top }.thenBy { it.sourceRect.left })
            .map { it.withAnchoredRect(it.rect) }
            .toMutableList()
        if (resolved.hasNoOverlaps()) return resolved
        repeat(COLLISION_MAX_ITERATIONS) {
            var changed = false
            for (leftIndex in 0 until resolved.lastIndex) {
                for (rightIndex in leftIndex + 1 until resolved.size) {
                    if (!RectF.intersects(resolved[leftIndex].rect, resolved[rightIndex].rect)) continue
                    val adjusted = resolveAnchoredOverlap(resolved[leftIndex], resolved[rightIndex])
                    if (adjusted.first != resolved[leftIndex] || adjusted.second != resolved[rightIndex]) {
                        resolved[leftIndex] = adjusted.first
                        resolved[rightIndex] = adjusted.second
                        changed = true
                    }
                }
            }
            if (!changed || resolved.hasNoOverlaps()) return resolved
        }
        return resolved
    }

    private fun resolveAnchoredOverlap(
        first: TranslatedBlock,
        second: TranslatedBlock,
    ): Pair<TranslatedBlock, TranslatedBlock> {
        separateAnchoredPair(first, second)?.let { return it }
        var a = first
        var b = second
        var shrank = true
        while (RectF.intersects(a.rect, b.rect) && shrank) {
            shrank = false
            val shrinkFirst = a.rect.height() >= b.rect.height()
            if (shrinkFirst) {
                val nextA = shrinkTranslatedBlock(a)
                if (nextA != a) {
                    a = nextA
                    shrank = true
                    continue
                }
            }
            val nextB = shrinkTranslatedBlock(b)
            if (nextB != b) {
                b = nextB
                shrank = true
                continue
            }
            if (!shrinkFirst) {
                val nextA = shrinkTranslatedBlock(a)
                if (nextA != a) {
                    a = nextA
                    shrank = true
                }
            }
        }
        if (!RectF.intersects(a.rect, b.rect)) return a to b

        return abutAnchoredPair(a, b)
    }

    private fun shrinkTranslatedBlock(block: TranslatedBlock): TranslatedBlock {
        val density = resources.displayMetrics.scaledDensity
        val nextSize = max(block.minimumTextSizePx, block.textSizePx - density)
        if (nextSize >= block.textSizePx) return block

        val widthPx = contentWidth(block.rect, block.pad)
        val heightPx = contentHeight(block.rect, block.pad)
        val fullLayout = makeLayout(block.text, widthPx, nextSize)
        val truncated = !fullLayout.fitsCompleteMeasuredLines(heightPx)
        val layout = if (!truncated) {
            fullLayout
        } else {
            makeTruncatedLayout(block.text, widthPx, heightPx, nextSize) ?: return block
        }
        val rect = shrinkRectHeightAroundAnchor(
            rect = block.rect,
            targetHeight = if (!truncated) {
                min(block.rect.height(), fullLayout.height + block.pad * 2f)
            } else {
                block.rect.height()
            },
            anchorY = block.sourceRect.centerY(),
        )
        return block.copy(
            rect = rect,
            layout = layout,
            textSizePx = nextSize,
            truncated = truncated,
        )
    }

    private fun refitTranslatedBlockToRect(block: TranslatedBlock): TranslatedBlock? {
        if (block.rect.width() < 1f || block.rect.height() < 1f) return null
        if (block.rect.width() - block.pad * 2f < 1f) return null
        val widthPx = contentWidth(block.rect, block.pad)
        val heightPx = contentHeight(block.rect, block.pad)
        if (block.layout.width == widthPx && block.layout.fitsCompleteMeasuredLines(heightPx)) return block

        val fit = fittedLayout(
            text = block.text,
            sourceWidthPx = block.rect.width(),
            sourceHeightPx = block.rect.height(),
            gapAbovePx = 0f,
            gapBelowPx = 0f,
            growLeftPx = 0f,
            growRightPx = 0f,
            startingTextSizePx = block.textSizePx,
            minimumTextSizePx = block.minimumTextSizePx,
            pad = block.pad,
        ) ?: return null
        if (!fit.layout.fitsCompleteMeasuredLines(heightPx)) return null
        return block.copy(
            layout = fit.layout,
            textSizePx = fit.textSizePx,
            truncated = fit.truncated,
        )
    }

    private fun suppressResidualLiveOverlaps(blocks: List<TranslatedBlock>): List<TranslatedBlock> {
        if (blocks.size < 2) return blocks
        val selectedIds = selectNonOverlappingLiveBlockIds(
            blocks.map { block ->
                LivePlacedBlock(
                    stableId = block.stableId,
                    bounds = LiveLayoutRect(
                        left = block.rect.left,
                        top = block.rect.top,
                        right = block.rect.right,
                        bottom = block.rect.bottom,
                    ),
                )
            },
        ).toHashSet()
        return blocks.filter { it.stableId in selectedIds }
    }

    private fun separateAnchoredPair(
        first: TranslatedBlock,
        second: TranslatedBlock,
    ): Pair<TranslatedBlock, TranslatedBlock>? {
        val moves = listOfNotNull(
            separationMove(first, second, Axis.X),
            separationMove(first, second, Axis.Y),
        )
        val best = moves.minByOrNull { it.totalDistance } ?: return null
        return first.withAnchoredOffset(best.firstDx, best.firstDy) to
            second.withAnchoredOffset(best.secondDx, best.secondDy)
    }

    private fun separationMove(
        first: TranslatedBlock,
        second: TranslatedBlock,
        axis: Axis,
    ): SeparationMove? {
        val overlap = when (axis) {
            Axis.X -> min(first.rect.right, second.rect.right) - max(first.rect.left, second.rect.left)
            Axis.Y -> min(first.rect.bottom, second.rect.bottom) - max(first.rect.top, second.rect.top)
        }
        if (overlap <= 0f) return null
        val needed = overlap + COLLISION_ABUT_EPSILON
        val firstBeforeSecond = when (axis) {
            Axis.X -> first.sourceRect.centerX() <= second.sourceRect.centerX()
            Axis.Y -> first.sourceRect.centerY() <= second.sourceRect.centerY()
        }
        val firstLimit = first.anchorOffsetLimit(axis, negative = firstBeforeSecond)
        val secondLimit = second.anchorOffsetLimit(axis, negative = !firstBeforeSecond)
        val split = splitDisplacement(needed, firstLimit, secondLimit) ?: return null
        val firstSign = if (firstBeforeSecond) -1f else 1f
        val secondSign = -firstSign
        return when (axis) {
            Axis.X -> SeparationMove(
                firstDx = firstSign * split.first,
                firstDy = 0f,
                secondDx = secondSign * split.second,
                secondDy = 0f,
                totalDistance = needed,
            )
            Axis.Y -> SeparationMove(
                firstDx = 0f,
                firstDy = firstSign * split.first,
                secondDx = 0f,
                secondDy = secondSign * split.second,
                totalDistance = needed,
            )
        }
    }

    private fun splitDisplacement(
        needed: Float,
        firstLimit: Float,
        secondLimit: Float,
    ): Pair<Float, Float>? {
        if (firstLimit + secondLimit + COLLISION_ABUT_EPSILON < needed) return null
        var first = min(firstLimit, needed / 2f)
        var second = min(secondLimit, needed - first)
        val remainder = needed - first - second
        if (remainder > 0f) {
            val firstExtra = min(firstLimit - first, remainder)
            first += firstExtra
            second += min(secondLimit - second, remainder - firstExtra)
        }
        return if (first + second + COLLISION_ABUT_EPSILON >= needed) first to second else null
    }

    private fun abutAnchoredPair(
        first: TranslatedBlock,
        second: TranslatedBlock,
    ): Pair<TranslatedBlock, TranslatedBlock> {
        val verticalGap = kotlin.math.abs(first.sourceRect.centerY() - second.sourceRect.centerY())
        val horizontalGap = kotlin.math.abs(first.sourceRect.centerX() - second.sourceRect.centerX())
        return if (verticalGap >= horizontalGap && verticalGap > 0f) {
            abutVertically(first, second)
        } else if (horizontalGap > 0f) {
            abutHorizontally(first, second)
        } else {
            first to second
        }
    }

    private fun abutVertically(
        first: TranslatedBlock,
        second: TranslatedBlock,
    ): Pair<TranslatedBlock, TranslatedBlock> {
        val firstAbove = first.sourceRect.centerY() <= second.sourceRect.centerY()
        val upper = if (firstAbove) first else second
        val lower = if (firstAbove) second else first
        val boundary = (upper.sourceRect.centerY() + lower.sourceRect.centerY()) / 2f
        val upperRect = RectF(upper.rect).apply {
            bottom = min(bottom, boundary)
        }
        val lowerRect = RectF(lower.rect).apply {
            top = max(top, boundary)
        }
        val adjustedUpper = upper.withAnchoredRect(upperRect)
        val adjustedLower = lower.withAnchoredRect(lowerRect)
        return if (firstAbove) adjustedUpper to adjustedLower else adjustedLower to adjustedUpper
    }

    private fun abutHorizontally(
        first: TranslatedBlock,
        second: TranslatedBlock,
    ): Pair<TranslatedBlock, TranslatedBlock> {
        val firstLeft = first.sourceRect.centerX() <= second.sourceRect.centerX()
        val leftBlock = if (firstLeft) first else second
        val rightBlock = if (firstLeft) second else first
        val boundary = (leftBlock.sourceRect.centerX() + rightBlock.sourceRect.centerX()) / 2f
        val leftRect = RectF(leftBlock.rect).apply {
            right = min(right, boundary)
        }
        val rightRect = RectF(rightBlock.rect).apply {
            left = max(left, boundary)
        }
        val adjustedLeft = leftBlock.withAnchoredRect(leftRect)
        val adjustedRight = rightBlock.withAnchoredRect(rightRect)
        return if (firstLeft) adjustedLeft to adjustedRight else adjustedRight to adjustedLeft
    }

    private fun TranslatedBlock.withAnchoredOffset(dx: Float, dy: Float): TranslatedBlock {
        val moved = RectF(rect).apply { offset(dx, dy) }
        return withAnchoredRect(moved)
    }

    private fun TranslatedBlock.withAnchoredRect(candidate: RectF): TranslatedBlock {
        val anchored = RectF(candidate)
        ensureContainsSourceCenter(anchored, sourceRect)
        return copy(rect = anchored)
    }

    private fun TranslatedBlock.anchorOffsetLimit(axis: Axis, negative: Boolean): Float {
        val anchorX = sourceRect.centerX()
        val anchorY = sourceRect.centerY()
        val limit = when (axis) {
            Axis.X -> if (negative) rect.right - anchorX else anchorX - rect.left
            Axis.Y -> if (negative) rect.bottom - anchorY else anchorY - rect.top
        }
        return max(0f, limit)
    }

    private fun List<TranslatedBlock>.hasNoOverlaps(): Boolean {
        for (leftIndex in 0 until lastIndex) {
            for (rightIndex in leftIndex + 1 until size) {
                if (RectF.intersects(this[leftIndex].rect, this[rightIndex].rect)) return false
            }
        }
        return true
    }

    private fun ensureContainsSourceCenter(rect: RectF, sourceRect: RectF) {
        val anchorX = sourceRect.centerX()
        val anchorY = sourceRect.centerY()
        if (anchorX < rect.left) rect.offset(anchorX - rect.left, 0f)
        if (anchorX > rect.right) rect.offset(anchorX - rect.right, 0f)
        if (anchorY < rect.top) rect.offset(0f, anchorY - rect.top)
        if (anchorY > rect.bottom) rect.offset(0f, anchorY - rect.bottom)
    }

    private fun shrinkRectHeightAroundAnchor(rect: RectF, targetHeight: Float, anchorY: Float): RectF {
        val height = max(1f, targetHeight)
        if (height >= rect.height()) return RectF(rect)

        val anchoredY = anchorY.coerceIn(rect.top, rect.bottom)
        val currentAbove = max(0f, anchoredY - rect.top)
        val currentBelow = max(0f, rect.bottom - anchoredY)
        var above = min(currentAbove, height / 2f)
        var below = min(currentBelow, height - above)
        val remaining = height - above - below
        if (remaining > 0f) {
            val extraAbove = min(currentAbove - above, remaining)
            above += extraAbove
            below += min(currentBelow - below, remaining - extraAbove)
        }
        return RectF(rect.left, anchoredY - above, rect.right, anchoredY + below)
    }

    private fun drawTranslationText(canvas: Canvas, block: TranslatedBlock) {
        if (block.rect.width() < 4f || block.rect.height() < 4f) return

        canvas.save()
        canvas.clipRect(block.rect)
        canvas.translate(block.rect.left + block.pad, block.rect.top + block.pad)
        block.layout.draw(canvas)
        canvas.restore()
    }

    private fun fittedLayout(
        text: String,
        sourceWidthPx: Float,
        sourceHeightPx: Float,
        gapAbovePx: Float,
        gapBelowPx: Float,
        growLeftPx: Float,
        growRightPx: Float,
        startingTextSizePx: Float,
        minimumTextSizePx: Float,
        pad: Float,
    ): LayoutFit? {
        val safeGrowLeftPx = growLeftPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val safeGrowRightPx = growRightPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val safeGapAbovePx = gapAbovePx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val cacheKey = LayoutCacheKey(
            text = text,
            sourceWidthPxBits = sourceWidthPx.toBits(),
            sourceHeightPxBits = sourceHeightPx.toBits(),
            gapAbovePxBits = safeGapAbovePx.toBits(),
            gapBelowPxBits = gapBelowPx.toBits(),
            growLeftPxBits = safeGrowLeftPx.toBits(),
            growRightPxBits = safeGrowRightPx.toBits(),
            startingTextSizePxBits = startingTextSizePx.toBits(),
            minimumTextSizePxBits = minimumTextSizePx.toBits(),
            padPxBits = pad.toBits(),
        )
        fittedLayoutCache[cacheKey]?.let { return it }

        val decision = decideParagraphPanelFit(
            sourceRect = ParagraphPanelRect(
                left = 0f,
                top = 0f,
                right = sourceWidthPx,
                bottom = sourceHeightPx,
            ),
            gapBelowPx = gapBelowPx,
            growLeftPx = safeGrowLeftPx,
            growRightPx = safeGrowRightPx,
            growUpPx = safeGapAbovePx,
            textLength = text.length,
            startingTextSizePx = startingTextSizePx,
            minimumTextSizePx = minimumTextSizePx,
            horizontalPaddingPx = pad,
            verticalPaddingPx = pad,
            textSizeStepPx = resources.displayMetrics.scaledDensity,
            measureTextHeightPx = { request ->
                makeScratchLayout(text, request.contentWidthPx, request.textSizePx).height.toFloat()
            },
        )
        val rawContentWidthPx = decision.panelRect.width - pad * 2f
        val contentHeightPx = decision.panelRect.height - pad * 2f
        if (rawContentWidthPx < 1f || contentHeightPx <= 0f) return null
        val selectedWidthPx = rawContentWidthPx.toInt().coerceAtLeast(1)
        val layout = if (decision.truncated) {
            makeTruncatedLayout(
                text = text,
                widthPx = selectedWidthPx,
                heightPx = contentHeightPx,
                textSizePx = decision.textSizePx,
            ) ?: return null
        } else {
            makeLayout(text, selectedWidthPx, decision.textSizePx)
        }
        if (!layout.fitsCompleteMeasuredLines(contentHeightPx)) return null
        return LayoutFit(
            layout = layout,
            textSizePx = decision.textSizePx,
            panelRect = decision.panelRect,
            truncated = decision.truncated,
        ).also { fittedLayoutCache[cacheKey] = it }
    }

    private fun makeScratchLayout(text: String, widthPx: Int, textSizePx: Float): StaticLayout {
        scratchTextPaint.textSize = textSizePx
        return buildLayout(text, widthPx, scratchTextPaint, maxLines = Int.MAX_VALUE, ellipsize = false)
    }

    private fun makeLayout(text: String, widthPx: Int, textSizePx: Float): StaticLayout {
        return makeLayout(text, widthPx, textSizePx, maxLines = Int.MAX_VALUE, ellipsize = false)
    }

    private fun makeTruncatedLayout(
        text: String,
        widthPx: Int,
        heightPx: Float,
        textSizePx: Float,
    ): StaticLayout? {
        val fullLayout = makeLayout(text, widthPx, textSizePx)
        var completeLines = 0
        while (completeLines < fullLayout.lineCount &&
            fullLayout.getLineBottom(completeLines) <= heightPx + COMPLETE_LINE_EPSILON_PX
        ) {
            completeLines += 1
        }
        if (completeLines == 0) return null
        if (completeLines == fullLayout.lineCount) return fullLayout
        return makeLayout(text, widthPx, textSizePx, maxLines = completeLines, ellipsize = true)
    }

    private fun makeLayout(
        text: String,
        widthPx: Int,
        textSizePx: Float,
        maxLines: Int,
        ellipsize: Boolean,
    ): StaticLayout {
        val cacheKey = FixedLayoutCacheKey(text, widthPx, textSizePx.toBits(), maxLines, ellipsize)
        fixedLayoutCache[cacheKey]?.let { return it }
        val paint = TextPaint(textPaint).apply {
            textSize = textSizePx
        }
        return buildLayout(text, widthPx, paint, maxLines, ellipsize).also { fixedLayoutCache[cacheKey] = it }
    }

    private fun buildLayout(
        text: String,
        widthPx: Int,
        paint: TextPaint,
        maxLines: Int,
        ellipsize: Boolean,
    ): StaticLayout {
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
        if (maxLines != Int.MAX_VALUE) builder.setMaxLines(maxLines)
        if (ellipsize) builder.setEllipsize(TextUtils.TruncateAt.END)
        return builder.build()
    }

    private fun contentWidth(rect: RectF, pad: Float): Int =
        max(1, (rect.width() - pad * 2f).toInt())

    private fun contentHeight(rect: RectF, pad: Float): Float =
        rect.height() - pad * 2f

    private fun maximumBottom(allowedRect: RectF, sourceRect: RectF): Float =
        max(sourceRect.bottom, min(allowedRect.bottom, height.toFloat()))

    private fun doesNotIncreaseObstacleOverlap(
        candidateRect: RectF,
        obstacleRect: RectF,
        sourceRect: RectF,
    ): Boolean {
        val candidateIntersection = intersection(candidateRect, obstacleRect) ?: return true
        val allowedIntersection = intersection(sourceRect, obstacleRect) ?: return false
        return candidateIntersection.left + COMPLETE_LINE_EPSILON_PX >= allowedIntersection.left &&
            candidateIntersection.top + COMPLETE_LINE_EPSILON_PX >= allowedIntersection.top &&
            candidateIntersection.right <= allowedIntersection.right + COMPLETE_LINE_EPSILON_PX &&
            candidateIntersection.bottom <= allowedIntersection.bottom + COMPLETE_LINE_EPSILON_PX
    }

    private fun intersection(first: RectF, second: RectF): RectF? {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.right, second.right)
        val bottom = min(first.bottom, second.bottom)
        return RectF(left, top, right, bottom).takeIf { it.width() > 0f && it.height() > 0f }
    }

    private fun StaticLayout.fitsCompleteMeasuredLines(contentHeightPx: Float): Boolean {
        if (lineCount <= 0) return false
        val firstLineHeightPx = (getLineBottom(0) - getLineTop(0)).toFloat()
        return panelFitsMeasuredLayout(
            panelHeightPx = contentHeightPx,
            paddingPx = 0f,
            layoutHeightPx = height.toFloat(),
            firstLineHeightPx = firstLineHeightPx,
        )
    }

    private fun RectF.toParagraphPanelRect(): ParagraphPanelRect =
        ParagraphPanelRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
        )

    private fun minimumTranslationTextSizePx(useFrozenLayout: Boolean): Float {
        val minimumSp = if (useFrozenLayout) FROZEN_MIN_READABLE_TEXT_SP else LIVE_MIN_READABLE_TEXT_SP
        return minimumSp * resources.displayMetrics.scaledDensity
    }

    private fun medianMappedLineHeight(
        lineBounds: List<Rect>,
        matrix: Matrix,
        fallbackHeight: Float,
    ): Float {
        val heights = lineBounds.mapNotNull { bounds ->
            val mapped = RectF(bounds)
            matrix.mapRect(mapped)
            mapped.height().takeIf { it > 0f }
        }.sorted()
        if (heights.isEmpty()) return fallbackHeight.coerceAtLeast(1f)
        val middle = heights.size / 2
        return if (heights.size % 2 == 0) {
            (heights[middle - 1] + heights[middle]) / 2f
        } else {
            heights[middle]
        }
    }

    private fun Matrix.setFillCenter(sourceWidth: Int, sourceHeight: Int, viewWidth: Int, viewHeight: Int) {
        val safeSourceWidth = max(1, sourceWidth)
        val safeSourceHeight = max(1, sourceHeight)
        val safeViewWidth = max(1, viewWidth)
        val safeViewHeight = max(1, viewHeight)
        val scale = max(
            safeViewWidth.toFloat() / safeSourceWidth.toFloat(),
            safeViewHeight.toFloat() / safeSourceHeight.toFloat(),
        )
        val dx = (safeViewWidth - safeSourceWidth * scale) * 0.5f
        val dy = (safeViewHeight - safeSourceHeight * scale) * 0.5f
        reset()
        postScale(scale, scale)
        postTranslate(dx, dy)
    }

    private fun drawHud(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val pad = 6f * density
        val lineHeight = hudPaint.textSize + 3f * density
        val lines = hudLines()

        val maxWidth = lines.maxOf { hudPaint.measureText(it) }
        val right = min(width.toFloat(), maxWidth + pad * 2f)
        val bottom = min(height.toFloat(), pad * 2f + lineHeight * lines.size)
        canvas.drawRect(0f, 0f, right, bottom, fillPaint)

        var y = pad + hudPaint.textSize
        lines.forEach { line ->
            canvas.drawText(line, pad, y, hudPaint)
            y += lineHeight
        }
    }

    private fun hudBounds(): RectF {
        val density = resources.displayMetrics.density
        val pad = 6f * density
        val lineHeight = hudPaint.textSize + 3f * density
        val lines = hudLines()
        return RectF(
            0f,
            0f,
            min(width.toFloat(), lines.maxOf { hudPaint.measureText(it) } + pad * 2f),
            min(height.toFloat(), pad * 2f + lineHeight * lines.size),
        )
    }

    private fun hudLines(): List<String> = buildList {
        if (hudState.frozen) add("FROZEN")
        add("MODE ${hudState.mode}  TGT ${hudState.targetLang.uppercase(Locale.US)}")
        add("CACHE ${hudState.cacheEntries}  ${hudState.busLabel}")
        add(String.format(Locale.US, "OCR %.1f Hz", hudState.ocrHz))
        if (!hudState.frozen || hudState.status != "FROZEN") add(hudState.status)
    }

    private fun drawModeFlash(canvas: Canvas) {
        val label = modeFlashLabel ?: return
        val nowMs = SystemClock.elapsedRealtime()
        val remainingMs = modeFlashUntilMs - nowMs
        if (remainingMs <= 0L) {
            modeFlashLabel = null
            return
        }
        val baseline = height / 2f - (modeFlashPaint.descent() + modeFlashPaint.ascent()) / 2f
        canvas.drawText(label, width / 2f, baseline, modeFlashPaint)
        postInvalidateDelayed(remainingMs)
    }

    private data class TranslatedBlock(
        val stableId: Long,
        val rect: RectF,
        val sourceRect: RectF,
        val text: String,
        val layout: StaticLayout,
        val pad: Float,
        val textSizePx: Float,
        val minimumTextSizePx: Float,
        val truncated: Boolean,
    )

    private data class MappedOverlayBlock(
        val block: LensOverlayBlock,
        val sourceRect: RectF,
        val allowedRect: RectF,
        val medianSourceLineHeight: Float,
        val stableId: Long,
    )

    private data class SeparationMove(
        val firstDx: Float,
        val firstDy: Float,
        val secondDx: Float,
        val secondDy: Float,
        val totalDistance: Float,
    )

    private data class LayoutFit(
        val layout: StaticLayout,
        val textSizePx: Float,
        val panelRect: ParagraphPanelRect,
        val truncated: Boolean,
    )

    private data class LayoutCacheKey(
        val text: String,
        val sourceWidthPxBits: Int,
        val sourceHeightPxBits: Int,
        val gapAbovePxBits: Int,
        val gapBelowPxBits: Int,
        val growLeftPxBits: Int,
        val growRightPxBits: Int,
        val startingTextSizePxBits: Int,
        val minimumTextSizePxBits: Int,
        val padPxBits: Int,
    )

    private data class FixedLayoutCacheKey(
        val text: String,
        val widthPx: Int,
        val textSizePxBits: Int,
        val maxLines: Int,
        val ellipsize: Boolean,
    )

    private enum class Axis {
        X,
        Y,
    }

    companion object {
        private val GREEN = Color.rgb(0, 255, 102)
        private const val PARAGRAPH_PANEL_PADDING_DP = 1f
        private const val COLLISION_MAX_ITERATIONS = 6
        private const val COMPLETE_LINE_EPSILON_PX = 0.5f
        private const val COLLISION_ABUT_EPSILON = 0.01f
        private const val MODE_FLASH_MS = 1_500L
        private const val FITTED_LAYOUT_CACHE_SIZE = 64
        private const val FIXED_LAYOUT_CACHE_SIZE = 64
    }
}
