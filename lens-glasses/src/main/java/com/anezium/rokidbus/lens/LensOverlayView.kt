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
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class LensOverlayBlock(
    val source: String,
    val normalized: String,
    val bounds: Rect,
    val translation: String?,
    val sourceLang: String?,
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

    private var frozenBackground: Bitmap? = null
    private var transformMatrix: Matrix? = null
    private var blocks: List<LensOverlayBlock> = emptyList()
    private var hudState = LensHudState()
    private var modeFlashLabel: String? = null
    private var modeFlashUntilMs: Long = 0L

    fun setFrozenBackground(bitmap: Bitmap?, recyclePrevious: Boolean = true) {
        val previous = frozenBackground
        frozenBackground = bitmap
        invalidate()
        if (recyclePrevious && previous != null && previous !== bitmap && !previous.isRecycled) {
            previous.recycle()
        }
    }

    fun replaceFrozenBackground(expected: Bitmap, replacement: Bitmap): Boolean {
        if (frozenBackground !== expected) return false
        frozenBackground = replacement
        invalidate()
        if (!expected.isRecycled) expected.recycle()
        return true
    }

    fun isFrozenBackground(bitmap: Bitmap): Boolean =
        frozenBackground === bitmap

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
        blocks.forEach { block ->
            val mapped = RectF(block.bounds)
            matrix?.mapRect(mapped) ?: return@forEach
            val sourceRect = RectF(mapped)
            val translation = block.translation?.takeIf { it.isNotBlank() }
            if (translation == null) {
                canvas.drawRect(mapped, outlinePaint)
            } else {
                if (!useFrozenLayout) {
                    mapped.inset(0f, mapped.height() * TRANSLATED_VERTICAL_INSET_FRACTION)
                }
                if (mapped.width() >= 4f && mapped.height() >= 4f) {
                    prepareTranslatedBlock(
                        rect = mapped,
                        sourceRect = sourceRect,
                        text = translation,
                        allowHorizontalExpansion = useFrozenLayout,
                    )?.let { translatedBlocks += it }
                }
            }
        }
        val laidOutBlocks = if (useFrozenLayout) {
            resolveTranslatedBlockCollisions(translatedBlocks)
        } else {
            translatedBlocks
        }
        laidOutBlocks
            .sortedByDescending { it.rect.width() * it.rect.height() }
            .forEach { block -> canvas.drawRect(block.rect, fillPaint) }
        laidOutBlocks.forEach { block -> drawTranslationText(canvas, block) }
        drawHud(canvas)
        drawModeFlash(canvas)
    }

    private fun prepareTranslatedBlock(
        rect: RectF,
        sourceRect: RectF,
        text: String,
        allowHorizontalExpansion: Boolean,
    ): TranslatedBlock? {
        val density = resources.displayMetrics.density
        val pad = 3f * density
        val verticalFit = prepareWithVerticalExpansion(sourceRect, rect, text, pad)
        if (!allowHorizontalExpansion || verticalFit.comfortable || width <= 0) {
            return verticalFit.block
        }

        val widenedRect = expandRectHorizontally(sourceRect, verticalFit.block.rect)
        if (widenedRect.width() <= verticalFit.block.rect.width() + 0.5f) {
            return verticalFit.block
        }
        return prepareWithVerticalExpansion(sourceRect, widenedRect, text, pad).block
    }

    private fun prepareWithVerticalExpansion(
        sourceRect: RectF,
        rect: RectF,
        text: String,
        pad: Float,
    ): PreparedBlock {
        val fitted = fittedLayout(text, contentWidth(rect, pad), contentHeight(rect, pad))
        if (fitted.fits) {
            return PreparedBlock(
                block = TranslatedBlock(RectF(rect), RectF(sourceRect), text, fitted.layout, pad, fitted.textSizePx),
                comfortable = fitted.textSizePx >= comfortableTextSizePx(),
            )
        }

        val expandedHeight = min(
            rect.height() * MAX_TRANSLATED_RECT_EXPANSION,
            max(rect.height(), fitted.layout.height + pad * 2f),
        )
        val expandedRect = expandRectVertically(sourceRect, rect, expandedHeight)
        val expandedFit = fittedLayout(text, contentWidth(expandedRect, pad), contentHeight(expandedRect, pad))
        return PreparedBlock(
            block = TranslatedBlock(
                expandedRect,
                RectF(sourceRect),
                text,
                expandedFit.layout,
                pad,
                expandedFit.textSizePx,
            ),
            comfortable = expandedFit.fits && expandedFit.textSizePx >= comfortableTextSizePx(),
        )
    }

    private fun expandRectVertically(sourceRect: RectF, rect: RectF, targetHeight: Float): RectF {
        val expanded = RectF(rect)
        val extraHeight = max(0f, targetHeight - rect.height())
        expanded.bottom += extraHeight
        if (expanded.bottom > height) {
            val overflow = expanded.bottom - height
            expanded.bottom = height.toFloat()
            expanded.top = max(0f, expanded.top - overflow)
        }
        ensureContainsSourceCenter(expanded, sourceRect)
        return expanded
    }

    private fun expandRectHorizontally(sourceRect: RectF, rect: RectF): RectF {
        val density = resources.displayMetrics.density
        val margin = HORIZONTAL_EXPANSION_MARGIN_DP * density
        val maxWidth = max(1f, width.toFloat() - margin * 2f)
        val targetWidth = maxWidth
        val centerX = rect.centerX()
        val expanded = RectF(rect)
        expanded.left = centerX - targetWidth / 2f
        expanded.right = centerX + targetWidth / 2f
        if (expanded.left < margin) {
            val shift = margin - expanded.left
            expanded.left += shift
            expanded.right += shift
        }
        val rightLimit = width.toFloat() - margin
        if (expanded.right > rightLimit) {
            val shift = expanded.right - rightLimit
            expanded.left -= shift
            expanded.right -= shift
        }
        expanded.left = max(margin, expanded.left)
        expanded.right = min(rightLimit, expanded.right)
        ensureContainsSourceCenter(expanded, sourceRect)
        return expanded
    }

    private fun resolveTranslatedBlockCollisions(blocks: List<TranslatedBlock>): List<TranslatedBlock> {
        if (blocks.size < 2 || height <= 0) return blocks
        val resolved = blocks.map { it.withAnchoredRect(it.rect) }.toMutableList()
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
        val minSize = MIN_TRANSLATION_TEXT_SP * density
        val nextSize = max(minSize, block.textSizePx - density)
        if (nextSize >= block.textSizePx) return block

        val layout = makeLayout(block.text, contentWidth(block.rect, block.pad), nextSize)
        val rect = shrinkRectHeightAroundAnchor(
            rect = block.rect,
            targetHeight = min(block.rect.height(), layout.height + block.pad * 2f),
            anchorY = block.sourceRect.centerY(),
        )
        return block.copy(rect = rect, layout = layout, textSizePx = nextSize)
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

    private fun fittedLayout(text: String, widthPx: Int, heightPx: Float): LayoutFit {
        val density = resources.displayMetrics.scaledDensity
        val maxSize = min(24f * density, max(9f * density, heightPx * 0.55f))
        val cacheKey = LayoutCacheKey(
            text = text,
            widthPx = widthPx,
            heightPxBits = heightPx.toBits(),
            baseTextSizePxBits = maxSize.toBits(),
        )
        fittedLayoutCache[cacheKey]?.let { return it }

        val minSize = MIN_TRANSLATION_TEXT_SP * density
        var size = maxSize
        var best = makeScratchLayout(text, widthPx, size)
        while (size > minSize && best.height > heightPx) {
            size = max(minSize, size - 1f * density)
            best = makeScratchLayout(text, widthPx, size)
        }
        return LayoutFit(
            layout = makeLayout(text, widthPx, size),
            fits = best.height <= heightPx,
            textSizePx = size,
        ).also { fittedLayoutCache[cacheKey] = it }
    }

    private fun makeScratchLayout(text: String, widthPx: Int, textSizePx: Float): StaticLayout {
        scratchTextPaint.textSize = textSizePx
        return buildLayout(text, widthPx, scratchTextPaint)
    }

    private fun makeLayout(text: String, widthPx: Int, textSizePx: Float): StaticLayout {
        val paint = TextPaint(textPaint).apply {
            textSize = textSizePx
        }
        return buildLayout(text, widthPx, paint)
    }

    private fun buildLayout(text: String, widthPx: Int, paint: TextPaint): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
    }

    private fun contentWidth(rect: RectF, pad: Float): Int =
        max(1, (rect.width() - pad * 2f).toInt())

    private fun contentHeight(rect: RectF, pad: Float): Float =
        max(1f, rect.height() - pad * 2f)

    private fun comfortableTextSizePx(): Float =
        COMFORTABLE_TRANSLATION_TEXT_SP * resources.displayMetrics.scaledDensity

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
        val rect: RectF,
        val sourceRect: RectF,
        val text: String,
        val layout: StaticLayout,
        val pad: Float,
        val textSizePx: Float,
    )

    private data class PreparedBlock(
        val block: TranslatedBlock,
        val comfortable: Boolean,
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
        val fits: Boolean,
        val textSizePx: Float,
    )

    private data class LayoutCacheKey(
        val text: String,
        val widthPx: Int,
        val heightPxBits: Int,
        val baseTextSizePxBits: Int,
    )

    private enum class Axis {
        X,
        Y,
    }

    companion object {
        private val GREEN = Color.rgb(0, 255, 102)
        private const val TRANSLATED_VERTICAL_INSET_FRACTION = 0.08f
        private const val COMFORTABLE_TRANSLATION_TEXT_SP = 10f
        private const val MIN_TRANSLATION_TEXT_SP = 7f
        private const val MAX_TRANSLATED_RECT_EXPANSION = 3f
        private const val HORIZONTAL_EXPANSION_MARGIN_DP = 4f
        private const val COLLISION_MAX_ITERATIONS = 6
        private const val COLLISION_ABUT_EPSILON = 0.01f
        private const val MODE_FLASH_MS = 1_500L
        private const val FITTED_LAYOUT_CACHE_SIZE = 256
    }
}
