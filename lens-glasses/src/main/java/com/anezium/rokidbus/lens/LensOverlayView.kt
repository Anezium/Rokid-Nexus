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
    ) {
        this.transformMatrix = transformMatrix?.let(::Matrix)
        this.blocks = blocks
        this.hudState = hudState
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
        val horizontalBudgets = computeParagraphPanelHorizontalBudgets(
            panels = mappedBlocks.map { mappedBlock ->
                ParagraphPanelHorizontalBudgetInput(
                    sourceRect = mappedBlock.sourceRect.toParagraphPanelRect(),
                    allowedRect = mappedBlock.allowedRect.toParagraphPanelRect(),
                    clearancePx = mappedBlock.medianSourceLineHeight *
                        PARAGRAPH_PANEL_HORIZONTAL_CLEARANCE_LINE_HEIGHTS,
                )
            },
            viewWidthPx = width.toFloat(),
            edgeMarginPx = PARAGRAPH_PANEL_SCREEN_EDGE_MARGIN_DP * resources.displayMetrics.density,
        )
        mappedBlocks.forEachIndexed { index, mappedBlock ->
            val block = mappedBlock.block
            val sourceRect = mappedBlock.sourceRect
            val allowedRect = RectF(
                mappedBlock.allowedRect,
            )
            val translation = block.translation?.takeIf { it.isNotBlank() }
            if (translation == null) {
                canvas.drawRect(sourceRect, outlinePaint)
            } else {
                if (sourceRect.width() >= 4f && sourceRect.height() >= 4f) {
                    val minimumTextSizePx = minimumTranslationTextSizePx(useFrozenLayout)
                    val horizontalBudget = horizontalBudgets[index]
                    prepareTranslatedParagraphPanel(
                        rect = sourceRect,
                        sourceRect = sourceRect,
                        allowedRect = allowedRect,
                        text = translation,
                        stableId = mappedBlock.stableId,
                        startingTextSizePx = max(
                            minimumTextSizePx,
                            mappedBlock.medianSourceLineHeight * PARAGRAPH_PANEL_START_LINE_HEIGHT_RATIO,
                        ),
                        minimumTextSizePx = minimumTextSizePx,
                        growLeftPx = horizontalBudget.growLeftPx,
                        growRightPx = horizontalBudget.growRightPx,
                    )?.let { translatedBlocks += it }
                }
            }
        }
        // Required safety net: malformed OCR collisions may adjust panels while retaining anchors.
        val collisionResolved = resolveTranslatedBlockCollisions(translatedBlocks)
            .map(::refitTranslatedBlockToRect)
        val laidOutBlocks = if (useFrozenLayout) {
            collisionResolved
        } else {
            suppressResidualLiveOverlaps(collisionResolved)
        }
        laidOutBlocks
            .sortedByDescending { it.rect.width() * it.rect.height() }
            .forEach { block -> canvas.drawRect(block.rect, fillPaint) }
        laidOutBlocks.forEach { block -> drawTranslationText(canvas, block) }
        drawHud(canvas)
        drawModeFlash(canvas)
    }

    private fun prepareTranslatedParagraphPanel(
        rect: RectF,
        sourceRect: RectF,
        allowedRect: RectF,
        text: String,
        stableId: Long,
        startingTextSizePx: Float,
        minimumTextSizePx: Float,
        growLeftPx: Float,
        growRightPx: Float,
    ): TranslatedBlock? {
        val density = resources.displayMetrics.density
        val pad = PARAGRAPH_PANEL_PADDING_DP * density
        val maximumBottom = max(
            rect.bottom,
            min(allowedRect.bottom, this@LensOverlayView.height.toFloat()),
        )
        val fit = fittedLayout(
            text = text,
            sourceWidthPx = rect.width(),
            sourceHeightPx = rect.height(),
            gapBelowPx = maximumBottom - rect.bottom,
            growLeftPx = growLeftPx,
            growRightPx = growRightPx,
            startingTextSizePx = startingTextSizePx,
            minimumTextSizePx = minimumTextSizePx,
            pad = pad,
        )
        val selectedRect = RectF(
            rect.left + fit.panelRect.left,
            rect.top,
            rect.left + fit.panelRect.right,
            min(maximumBottom, rect.top + fit.panelRect.height),
        )
        return TranslatedBlock(
            stableId = stableId,
            rect = RectF(selectedRect),
            sourceRect = RectF(sourceRect),
            text = text,
            layout = fit.layout,
            pad = pad,
            textSizePx = fit.textSizePx,
            minimumTextSizePx = minimumTextSizePx,
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

        separateAnchoredPair(a, b)?.let { return it }
        return abutAnchoredPair(a, b)
    }

    private fun shrinkTranslatedBlock(block: TranslatedBlock): TranslatedBlock {
        val density = resources.displayMetrics.scaledDensity
        val nextSize = max(block.minimumTextSizePx, block.textSizePx - density)
        if (nextSize >= block.textSizePx) return block

        val widthPx = contentWidth(block.rect, block.pad)
        val heightPx = contentHeight(block.rect, block.pad)
        val fullLayout = makeLayout(block.text, widthPx, nextSize)
        val layout = if (fullLayout.height <= heightPx) {
            fullLayout
        } else {
            makeTruncatedLayout(block.text, widthPx, heightPx, nextSize)
        }
        val rect = shrinkRectHeightAroundAnchor(
            rect = block.rect,
            targetHeight = if (fullLayout.height <= heightPx) {
                min(block.rect.height(), fullLayout.height + block.pad * 2f)
            } else {
                block.rect.height()
            },
            anchorY = block.sourceRect.centerY(),
        )
        return block.copy(rect = rect, layout = layout, textSizePx = nextSize)
    }

    private fun refitTranslatedBlockToRect(block: TranslatedBlock): TranslatedBlock {
        if (block.rect.width() < 1f || block.rect.height() < 1f) return block
        val widthPx = contentWidth(block.rect, block.pad)
        val heightPx = contentHeight(block.rect, block.pad)
        if (block.layout.width == widthPx && block.layout.height <= heightPx + 0.5f) return block

        val fit = fittedLayout(
            text = block.text,
            sourceWidthPx = block.rect.width(),
            sourceHeightPx = block.rect.height(),
            gapBelowPx = 0f,
            growLeftPx = 0f,
            growRightPx = 0f,
            startingTextSizePx = block.textSizePx,
            minimumTextSizePx = block.minimumTextSizePx,
            pad = block.pad,
        )
        return block.copy(layout = fit.layout, textSizePx = fit.textSizePx)
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
        gapBelowPx: Float,
        growLeftPx: Float,
        growRightPx: Float,
        startingTextSizePx: Float,
        minimumTextSizePx: Float,
        pad: Float,
    ): LayoutFit {
        val safeGrowLeftPx = growLeftPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val safeGrowRightPx = growRightPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val cacheKey = LayoutCacheKey(
            text = text,
            sourceWidthPxBits = sourceWidthPx.toBits(),
            sourceHeightPxBits = sourceHeightPx.toBits(),
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
        val selectedWidthPx = max(1, (decision.panelRect.width - pad * 2f).toInt())
        val contentHeightPx = max(1f, decision.panelRect.height - pad * 2f)
        val layout = if (decision.truncated) {
            makeTruncatedLayout(
                text = text,
                widthPx = selectedWidthPx,
                heightPx = contentHeightPx,
                textSizePx = decision.textSizePx,
            )
        } else {
            makeLayout(text, selectedWidthPx, decision.textSizePx)
        }
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
    ): StaticLayout {
        val paint = TextPaint(textPaint).apply { this.textSize = textSizePx }
        val lineHeight = max(1f, paint.fontMetrics.descent - paint.fontMetrics.ascent)
        val maxLines = max(1, (heightPx / lineHeight).toInt())
        return makeLayout(text, widthPx, textSizePx, maxLines = maxLines, ellipsize = true)
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
        max(1f, rect.height() - pad * 2f)

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
        val lines = mutableListOf<String>()
        if (hudState.frozen) lines += "FROZEN"
        lines += "MODE ${hudState.mode}  TGT ${hudState.targetLang.uppercase(Locale.US)}"
        lines += "CACHE ${hudState.cacheEntries}  ${hudState.busLabel}"
        lines += String.format(Locale.US, "OCR %.1f Hz", hudState.ocrHz)
        if (!hudState.frozen || hudState.status != "FROZEN") lines += hudState.status

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
        private const val COLLISION_ABUT_EPSILON = 0.01f
        private const val MODE_FLASH_MS = 1_500L
        private const val FITTED_LAYOUT_CACHE_SIZE = 256
        private const val FIXED_LAYOUT_CACHE_SIZE = 256
    }
}
