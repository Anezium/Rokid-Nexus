package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.anezium.rokidbus.shared.CameraOverlayItem
import kotlin.math.max
import kotlin.math.min

internal enum class CameraOverlayMode { LIVE, FROZEN }

/** Fixed-box legacy renderer plus an opt-in adaptive paragraph renderer. */
internal class CameraOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val scratchTextPaint = TextPaint(textPaint)
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN
        textSize = 12f * scaledDensity
        typeface = Typeface.MONOSPACE
    }
    private val hudBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 220
    }
    private val bitmapMatrix = Matrix()
    private val stabilizer = CameraOverlayStabilizer()
    private val fittedLayoutCache = lruMap<LayoutCacheKey, LayoutFit>()
    private val fixedLayoutCache = lruMap<FixedLayoutCacheKey, StaticLayout>()

    private var items: List<CameraOverlayItem> = emptyList()
    private var status = "STARTING"
    private var zoomLabel = "1.0x"
    private var frozenBackground: Bitmap? = null
    private var frozenSourceViewport: CameraSourceViewport? = null
    private var frozenDisplayScale = 1f
    private var frozenBitmapScale = 1f
    private var mode = CameraOverlayMode.LIVE
    private var lastStats: LayoutStats? = null

    fun updateOverlay(next: List<CameraOverlayItem>) {
        items = stabilizer.update(next)
        invalidate()
    }

    fun updateStatus(next: String, zoom: String = zoomLabel) {
        status = next
        zoomLabel = zoom
        invalidate()
    }

    fun setMode(next: CameraOverlayMode) {
        if (mode == next) return
        mode = next
        invalidate()
    }

    fun setFrozenBackground(
        bitmap: Bitmap?,
        sourceViewport: CameraSourceViewport? = null,
        recyclePrevious: Boolean = true,
    ) {
        val previous = frozenBackground
        frozenBackground = bitmap
        frozenSourceViewport = sourceViewport
        frozenDisplayScale = 1f
        frozenBitmapScale = 1f
        if (recyclePrevious && previous !== bitmap && previous?.isRecycled == false) previous.recycle()
        invalidate()
    }

    fun setFrozenDisplayScale(scale: Float) {
        frozenDisplayScale = scale.coerceAtLeast(1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        frozenBackground?.takeUnless(Bitmap::isRecycled)?.let { drawFrozenBackground(canvas, it) }
        val scaled = frozenBackground != null && frozenDisplayScale > 1f
        if (scaled) {
            canvas.save()
            canvas.scale(frozenDisplayScale, frozenDisplayScale, width / 2f, height / 2f)
        }
        if (items.none(CameraOverlayItem::isAdaptiveParagraph)) {
            items.forEach { drawLegacyItem(canvas, it) }
            if (scaled) canvas.restore()
            drawHud(canvas)
            return
        }

        items.filterNot(CameraOverlayItem::isAdaptiveParagraph).forEach { drawLegacyItem(canvas, it) }
        if (mode == CameraOverlayMode.LIVE) {
            if (scaled) canvas.restore()
            drawHud(canvas)
            if (scaled) {
                canvas.save()
                canvas.scale(frozenDisplayScale, frozenDisplayScale, width / 2f, height / 2f)
            }
        }
        val scene = prepareAdaptiveScene()
        scene.panels.sortedByDescending { it.rect.width() * it.rect.height() }
            .forEach { canvas.drawRect(it.rect, panelPaint) }
        scene.panels.forEach { drawPanelText(canvas, it) }
        outlinePaint.color = CYAN
        scene.sourceOutlines.forEach { canvas.drawRect(it, outlinePaint) }
        logStats(scene.stats)
        if (scaled) canvas.restore()
        if (mode == CameraOverlayMode.FROZEN) drawHud(canvas)
    }

    private fun prepareAdaptiveScene(): AdaptiveScene {
        if (width <= 0 || height <= 0) return AdaptiveScene()
        val mapped = items.mapIndexedNotNull { index, item ->
            item.takeIf(CameraOverlayItem::isAdaptiveParagraph)?.let {
                val source = itemRect(it)
                AdaptiveItem(index, stableOverlayId(it, source), it, source).takeIf { mappedItem ->
                    mappedItem.source.width() >= 4f && mappedItem.source.height() >= 4f
                }
            }
        }
        if (mapped.isEmpty()) return AdaptiveScene()
        val viewport = ParagraphPanelRect(0f, 0f, width.toFloat(), height.toFloat())
        val edgeMargin = PARAGRAPH_PANEL_SCREEN_EDGE_MARGIN_DP * density
        val hud = hudBounds().paragraphRect()
        val inputs = mapped.map { entry ->
            ParagraphPanelSpaceInput(
                entry.source.paragraphRect(),
                entry.lineHeight() * PARAGRAPH_PANEL_HORIZONTAL_CLEARANCE_LINE_HEIGHTS,
                entry.lineHeight() * PARAGRAPH_PANEL_GAP_CLEARANCE_LINE_HEIGHTS,
                entry.item.layout?.column ?: -1,
            )
        }
        val frozenInputs = if (mode == CameraOverlayMode.FROZEN) {
            inputs + ParagraphPanelSpaceInput(hud, 0f, 0f)
        } else {
            emptyList()
        }
        val frozenBudgets = if (mode == CameraOverlayMode.FROZEN) {
            computeParagraphPanelSpaceBudgets(frozenInputs, viewport, edgeMargin).take(inputs.size)
        } else {
            emptyList()
        }
        val liveBudgets = if (mode == CameraOverlayMode.LIVE) {
            computeParagraphPanelHorizontalBudgets(
                mapped.map { entry ->
                    val source = entry.source.paragraphRect()
                    ParagraphPanelHorizontalBudgetInput(
                        source,
                        source.copy(bottom = min(height.toFloat(), source.bottom + entry.growDown())),
                        entry.lineHeight() * PARAGRAPH_PANEL_HORIZONTAL_CLEARANCE_LINE_HEIGHTS,
                    )
                },
                width.toFloat(),
                edgeMargin,
            )
        } else {
            emptyList()
        }

        val prepared = mutableListOf<AdaptivePanel>()
        val diagnostics = mutableListOf<PanelDiagnostic>()
        mapped.forEachIndexed { mappedIndex, entry ->
            val candidates = if (mode == CameraOverlayMode.FROZEN) {
                generateParagraphPanelGrowthCandidates(
                    mappedIndex, frozenInputs, frozenBudgets[mappedIndex], viewport, edgeMargin,
                )
            } else {
                val budget = liveBudgets[mappedIndex]
                val down = entry.growDown()
                listOf(
                    ParagraphPanelGrowthCandidate(budget.growLeftPx, budget.growRightPx, 0f, down),
                    ParagraphPanelGrowthCandidate(budget.growLeftPx, budget.growRightPx, 0f, 0f),
                    ParagraphPanelGrowthCandidate(0f, 0f, 0f, down),
                    ParagraphPanelGrowthCandidate(0f, 0f, 0f, 0f),
                )
            }
            selectParagraphPanelGrowthFit(
                candidates,
                { candidate -> prepareCandidate(entry, candidate, mapped, hud) },
                { panel -> panel.candidateQuality() },
            )?.let(prepared::add) ?: diagnostics.add(
                PanelDiagnostic(entry.stableId, PanelDiagnosticReason.NO_LEGAL_CANDIDATE),
            )
        }

        val clearance = prepared.minOfOrNull(AdaptivePanel::lineHeight)?.times(
            PARAGRAPH_PANEL_GAP_CLEARANCE_LINE_HEIGHTS,
        ) ?: 0f
        val refitCache = mutableMapOf<PanelRefitKey, AdaptivePanel?>()
        val resolutions = resolveAdaptivePanelCollisions(
            prepared.map {
                AdaptivePanelGeometry(it.stableId, it.source.paragraphRect(), it.rect.paragraphRect())
            },
            clearance,
        ) { panelIndex, candidateRect ->
            val refitted = refitPanelCached(prepared[panelIndex], candidateRect.rectF(), refitCache)
            refitted?.collisionQuality()
        }
        val retained = mutableListOf<AdaptivePanel>()
        prepared.zip(resolutions).forEach { (panel, resolution) ->
            val refitted = resolution.panelRect?.rectF()?.let {
                refitPanelCached(panel, it, refitCache)
            }
            if (refitted == null) {
                diagnostics += PanelDiagnostic(
                    panel.stableId,
                    if (resolution.suppressedByStableId == panel.stableId) {
                        PanelDiagnosticReason.DUPLICATE_ID
                    } else {
                        PanelDiagnosticReason.COLLISION_SUPPRESSED
                    },
                    resolution.suppressedByStableId,
                )
            } else {
                retained += refitted
                if (refitted.truncated) {
                    diagnostics += PanelDiagnostic(
                        refitted.stableId,
                        PanelDiagnosticReason.TRUNCATED,
                        retainedTextPercent = (refitted.retainedTextFraction * 100f).toInt(),
                    )
                }
            }
        }
        return AdaptiveScene(
            retained,
            emptyList(),
            LayoutStats(
                prepared.size,
                (prepared.size - retained.size).coerceAtLeast(0),
                retained.count(AdaptivePanel::truncated),
                diagnostics.sortedWith(
                    compareBy<PanelDiagnostic> { it.stableId }
                        .thenBy { it.reason.name }
                        .thenBy { it.blockerStableId.orEmpty() },
                ),
            ),
        )
    }

    private fun prepareCandidate(
        entry: AdaptiveItem,
        growth: ParagraphPanelGrowthCandidate,
        allItems: List<AdaptiveItem>,
        hud: ParagraphPanelRect,
    ): AdaptivePanel? {
        val pad = density
        val minimum = minimumTextSize()
        val fit = fittedLayout(
            entry.item.text,
            entry.source.width(),
            entry.source.height(),
            growth.growUpPx,
            growth.growDownPx,
            growth.growLeftPx,
            growth.growRightPx,
            max(minimum, entry.lineHeight() * PARAGRAPH_PANEL_START_LINE_HEIGHT_RATIO),
            minimum,
            pad,
        ) ?: return null
        val rect = RectF(
            entry.source.left + fit.panelRect.left,
            entry.source.top + fit.panelRect.top,
            entry.source.left + fit.panelRect.right,
            entry.source.top + fit.panelRect.bottom,
        )
        val source = entry.source.paragraphRect()
        val candidate = rect.paragraphRect()
        if (allItems.any { other ->
                other.index != entry.index && !doesNotIncreasePreexistingSourceOverlap(
                    source,
                    candidate,
                    AdaptivePanelGeometry(other.stableId, other.source.paragraphRect(), other.source.paragraphRect()),
                    0f,
                )
            }
        ) return null
        if (mode == CameraOverlayMode.FROZEN && !doesNotIncreasePreexistingSourceOverlap(
                source, candidate, AdaptivePanelGeometry(HUD_STABLE_ID, hud, hud),
            )
        ) return null
        return AdaptivePanel(
            entry.index,
            entry.stableId,
            RectF(entry.source),
            rect,
            entry.item.text,
            roleColor(entry.item.role),
            fit.layout,
            pad,
            fit.textSize,
            minimum,
            entry.lineHeight(),
            fit.truncated,
            fit.retainedTextFraction,
        )
    }

    private fun fittedLayout(
        text: String,
        sourceWidth: Float,
        sourceHeight: Float,
        growUp: Float,
        growDown: Float,
        growLeft: Float,
        growRight: Float,
        startingSize: Float,
        minimumSize: Float,
        pad: Float,
    ): LayoutFit? {
        val key = LayoutCacheKey(
            text, sourceWidth.toBits(), sourceHeight.toBits(), growUp.toBits(), growDown.toBits(),
            growLeft.toBits(), growRight.toBits(), startingSize.toBits(), minimumSize.toBits(), pad.toBits(),
        )
        fittedLayoutCache[key]?.let { return it }
        val decision = decideParagraphPanelFit(
            ParagraphPanelRect(0f, 0f, sourceWidth, sourceHeight),
            growDown,
            growLeft,
            growRight,
            growUp,
            text.length,
            startingSize,
            minimumSize,
            pad,
            pad,
            scaledDensity,
        ) { request -> scratchLayout(text, request.contentWidthPx, request.textSizePx).height.toFloat() }
        val contentWidth = (decision.panelRect.width - pad * 2f).toInt().coerceAtLeast(1)
        val contentHeight = decision.panelRect.height - pad * 2f
        if (contentHeight <= 0f) return null
        val fullLayout = layout(text, contentWidth, decision.textSizePx)
        val layout = if (decision.truncated) {
            truncatedLayout(text, contentWidth, contentHeight, decision.textSizePx) ?: return null
        } else {
            fullLayout
        }
        if (!layout.fits(contentHeight)) return null
        val rendered = renderedTextMetrics(text, layout)
        return LayoutFit(
            layout,
            decision.textSizePx,
            decision.panelRect,
            rendered.truncated,
            rendered.retainedTextFraction,
        ).also {
            fittedLayoutCache[key] = it
        }
    }

    private fun refitPanelCached(
        panel: AdaptivePanel,
        rect: RectF,
        cache: MutableMap<PanelRefitKey, AdaptivePanel?>,
    ): AdaptivePanel? {
        val key = PanelRefitKey(
            panel.sourceIndex,
            rect.left.toBits(),
            rect.top.toBits(),
            rect.right.toBits(),
            rect.bottom.toBits(),
        )
        return cache.getOrPut(key) { refitPanel(panel, rect) }
    }

    private fun refitPanel(panel: AdaptivePanel, rect: RectF): AdaptivePanel? {
        val widthPx = max(1, (rect.width() - panel.pad * 2f).toInt())
        val heightPx = rect.height() - panel.pad * 2f
        if (heightPx <= 0f) return null
        var size = panel.textSize
        while (true) {
            val full = layout(panel.text, widthPx, size)
            if (full.fits(heightPx)) {
                return panel.copy(
                    rect = rect,
                    layout = full,
                    textSize = size,
                    truncated = false,
                    retainedTextFraction = 1f,
                )
            }
            if (size <= panel.minimumTextSize + COMPLETE_LINE_EPSILON) {
                val terminal = truncatedLayout(panel.text, widthPx, heightPx, panel.minimumTextSize) ?: return null
                val rendered = renderedTextMetrics(panel.text, terminal)
                return panel.copy(
                    rect = rect,
                    layout = terminal,
                    textSize = panel.minimumTextSize,
                    truncated = rendered.truncated,
                    retainedTextFraction = rendered.retainedTextFraction,
                )
            }
            size = max(panel.minimumTextSize, size - scaledDensity)
        }
    }

    private fun scratchLayout(text: String, widthPx: Int, size: Float): StaticLayout {
        scratchTextPaint.textSize = size
        return buildLayout(text, widthPx, scratchTextPaint, Int.MAX_VALUE, false)
    }

    private fun layout(
        text: String,
        widthPx: Int,
        size: Float,
        maxLines: Int = Int.MAX_VALUE,
        ellipsize: Boolean = false,
    ): StaticLayout {
        val key = FixedLayoutCacheKey(text, widthPx, size.toBits(), maxLines, ellipsize)
        fixedLayoutCache[key]?.let { return it }
        val paint = TextPaint(textPaint).apply { textSize = size }
        return buildLayout(text, widthPx, paint, maxLines, ellipsize).also { fixedLayoutCache[key] = it }
    }

    private fun truncatedLayout(text: String, widthPx: Int, heightPx: Float, size: Float): StaticLayout? {
        val full = layout(text, widthPx, size)
        var completeLines = 0
        while (completeLines < full.lineCount &&
            full.getLineBottom(completeLines) <= heightPx + COMPLETE_LINE_EPSILON
        ) completeLines += 1
        if (completeLines == 0) return null
        if (completeLines == full.lineCount) return full
        return layout(text, widthPx, size, completeLines, ellipsize = true)
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
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
        if (maxLines != Int.MAX_VALUE) builder.setMaxLines(maxLines)
        if (ellipsize) builder.setEllipsize(TextUtils.TruncateAt.END)
        return builder.build()
    }

    private fun StaticLayout.fits(heightPx: Float): Boolean = lineCount > 0 && panelFitsMeasuredLayout(
        heightPx,
        0f,
        height.toFloat(),
        (getLineBottom(0) - getLineTop(0)).toFloat(),
    )

    private fun renderedTextMetrics(text: String, layout: StaticLayout): ParagraphRenderedTextMetrics {
        if (layout.lineCount == 0) return ParagraphRenderedTextMetrics(text.isNotEmpty(), 0f)
        val lastLine = layout.lineCount - 1
        return paragraphRenderedTextMetrics(
            textLength = text.length,
            lastLineStart = layout.getLineStart(lastLine),
            lastLineEnd = layout.getLineEnd(lastLine),
            ellipsisStart = layout.getEllipsisStart(lastLine),
            ellipsisCount = layout.getEllipsisCount(lastLine),
        )
    }

    private fun AdaptivePanel.candidateQuality() = ParagraphPanelCandidateQuality(
        complete = !truncated,
        textSizePx = textSize,
        retainedTextFraction = retainedTextFraction,
        panelAreaPx = rect.width() * rect.height(),
    )

    private fun AdaptivePanel.collisionQuality() = AdaptivePanelCandidateQuality(
        complete = !truncated,
        textSizePx = textSize,
        retainedTextFraction = retainedTextFraction,
        panelAreaPx = rect.width() * rect.height(),
    )

    private fun drawPanelText(canvas: Canvas, panel: AdaptivePanel) {
        textPaint.color = panel.color
        canvas.save()
        canvas.clipRect(panel.rect)
        canvas.translate(panel.rect.left + panel.pad, panel.rect.top + panel.pad)
        panel.layout.draw(canvas)
        canvas.restore()
    }

    private fun drawFrozenBackground(canvas: Canvas, bitmap: Bitmap) {
        val source = frozenSourceViewport ?: CameraSourceViewport(
            0f,
            0f,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
        )
        val scale = min(width / source.width, height / source.height)
        frozenBitmapScale = scale
        bitmapMatrix.setRectToRect(
            RectF(source.left, source.top, source.right, source.bottom),
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            Matrix.ScaleToFit.CENTER,
        )
        canvas.save()
        if (frozenDisplayScale > 1f) canvas.scale(frozenDisplayScale, frozenDisplayScale, width / 2f, height / 2f)
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bitmap, bitmapMatrix, null)
        canvas.restore()
    }

    /** Exact legacy fixed-box path for missing or invalid paragraph metadata. */
    private fun drawLegacyItem(canvas: Canvas, item: CameraOverlayItem) {
        val rect = itemRect(item)
        if (rect.width() < 2f || rect.height() < 2f) return
        val color = roleColor(item.role)
        outlinePaint.color = color
        textPaint.color = color
        canvas.drawRect(rect, panelPaint)
        canvas.drawRect(rect, outlinePaint)
        val padding = 3f * density
        val textWidth = max(1, (rect.width() - padding * 2f).toInt())
        val textHeight = max(1f, rect.height() - padding * 2f)
        val fitted = fitLegacyText(item.text, textWidth, textHeight)
        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(rect.left + padding, rect.top + padding)
        fitted.draw(canvas)
        canvas.restore()
    }

    private fun fitLegacyText(text: String, widthPx: Int, heightPx: Float): StaticLayout {
        var size = min(28f * scaledDensity, heightPx * 0.6f).coerceAtLeast(MIN_LEGACY_TEXT_SP * scaledDensity)
        while (true) {
            textPaint.textSize = size
            val result = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .build()
            if (result.height <= heightPx || size <= MIN_LEGACY_TEXT_SP * scaledDensity) return result
            size = max(MIN_LEGACY_TEXT_SP * scaledDensity, size - scaledDensity)
        }
    }

    private fun drawHud(canvas: Canvas) {
        val hud = hudGeometry()
        canvas.drawRoundRect(hud.rect, 5f * density, 5f * density, hudBackgroundPaint)
        hud.lines.forEachIndexed { index, line ->
            canvas.drawText(line, hud.rect.left + hud.padding, hud.baselines[index], hudPaint)
        }
    }

    private fun hudBounds(): RectF = RectF(hudGeometry().rect)

    private fun hudGeometry(): HudGeometry {
        val lines = listOf("CAMERA", status.take(MAX_STATUS_CHARS), zoomLabel)
        val padding = 6f * density
        val lineHeight = hudPaint.textSize + 3f * density
        val right = width - 4f * density
        val rect = RectF(
            right - lines.maxOf(hudPaint::measureText) - padding * 2f,
            4f * density,
            right,
            4f * density + padding * 2f + lineHeight * lines.size,
        )
        var baseline = rect.top + padding + hudPaint.textSize
        val baselines = FloatArray(lines.size) { baseline.also { baseline += lineHeight } }
        return HudGeometry(lines, rect, padding, baselines)
    }

    private fun logStats(stats: LayoutStats) {
        if (stats == lastStats) return
        lastStats = stats
        Log.d(
            TAG,
            "adaptive mode=$mode panels=${stats.panels} suppressed=${stats.suppressed} " +
                "truncated=${stats.truncated} fittedCache=${fittedLayoutCache.size} " +
                "fixedCache=${fixedLayoutCache.size}",
        )
        stats.diagnostics.forEach { diagnostic ->
            val blocker = diagnostic.blockerStableId?.let { " blocker=$it" }.orEmpty()
            val retained = diagnostic.retainedTextPercent?.let { " retained=${it}%" }.orEmpty()
            Log.d(TAG, "adaptive id=${diagnostic.stableId} reason=${diagnostic.reason}$blocker$retained")
        }
    }

    private fun stableOverlayId(item: CameraOverlayItem, source: RectF): String =
        item.id?.takeIf(String::isNotBlank) ?: buildString {
            append("anonymous:")
            append(item.role.lowercase())
            append(':')
            append(source.left.toBits().toUInt().toString(16))
            append(':')
            append(source.top.toBits().toUInt().toString(16))
            append(':')
            append(source.right.toBits().toUInt().toString(16))
            append(':')
            append(source.bottom.toBits().toUInt().toString(16))
        }

    private fun itemRect(item: CameraOverlayItem): RectF {
        val bitmap = frozenBackground
        if (mode != CameraOverlayMode.FROZEN || bitmap == null || bitmap.isRecycled) {
            return RectF(
                item.box.left * width,
                item.box.top * height,
                item.box.right * width,
                item.box.bottom * height,
            )
        }
        val mapped = RectF(
            item.box.left * bitmap.width,
            item.box.top * bitmap.height,
            item.box.right * bitmap.width,
            item.box.bottom * bitmap.height,
        ).also(bitmapMatrix::mapRect)
        return RectF(
            max(0f, mapped.left),
            max(0f, mapped.top),
            min(width.toFloat(), mapped.right),
            min(height.toFloat(), mapped.bottom),
        )
    }

    private fun AdaptiveItem.lineHeight(): Float {
        val normalized = item.layout?.medianLineHeight ?: 0f
        val bitmap = frozenBackground
        return max(
            1f,
            if (mode == CameraOverlayMode.FROZEN && bitmap != null && !bitmap.isRecycled) {
                normalized * bitmap.height * frozenBitmapScale
            } else {
                normalized * height
            },
        )
    }

    private fun AdaptiveItem.growDown(): Float {
        val normalized = item.layout?.growDown ?: 0f
        val bitmap = frozenBackground
        return max(
            0f,
            if (mode == CameraOverlayMode.FROZEN && bitmap != null && !bitmap.isRecycled) {
                normalized * bitmap.height * frozenBitmapScale
            } else {
                normalized * height
            },
        )
    }
    private fun minimumTextSize() =
        (if (mode == CameraOverlayMode.FROZEN) FROZEN_MIN_READABLE_TEXT_SP else LIVE_MIN_READABLE_TEXT_SP) *
            scaledDensity

    private fun RectF.paragraphRect() = ParagraphPanelRect(left, top, right, bottom)
    private fun ParagraphPanelRect.rectF() = RectF(left, top, right, bottom)

    private fun roleColor(role: String): Int = when (role.lowercase()) {
        "source", "fallback", "translation-fallback" -> CYAN
        "pending", "label", "status" -> AMBER
        "failure", "error", "translation-failed" -> RED
        else -> GREEN
    }

    private data class AdaptiveItem(
        val index: Int,
        val stableId: String,
        val item: CameraOverlayItem,
        val source: RectF,
    )
    private data class AdaptivePanel(
        val sourceIndex: Int,
        val stableId: String,
        val source: RectF,
        val rect: RectF,
        val text: String,
        val color: Int,
        val layout: StaticLayout,
        val pad: Float,
        val textSize: Float,
        val minimumTextSize: Float,
        val lineHeight: Float,
        val truncated: Boolean,
        val retainedTextFraction: Float,
    )
    private data class AdaptiveScene(
        val panels: List<AdaptivePanel> = emptyList(),
        val sourceOutlines: List<RectF> = emptyList(),
        val stats: LayoutStats = LayoutStats(),
    )
    private data class LayoutStats(
        val panels: Int = 0,
        val suppressed: Int = 0,
        val truncated: Int = 0,
        val diagnostics: List<PanelDiagnostic> = emptyList(),
    )
    private data class PanelDiagnostic(
        val stableId: String,
        val reason: PanelDiagnosticReason,
        val blockerStableId: String? = null,
        val retainedTextPercent: Int? = null,
    )
    private enum class PanelDiagnosticReason { NO_LEGAL_CANDIDATE, DUPLICATE_ID, COLLISION_SUPPRESSED, TRUNCATED }
    private data class LayoutFit(
        val layout: StaticLayout,
        val textSize: Float,
        val panelRect: ParagraphPanelRect,
        val truncated: Boolean,
        val retainedTextFraction: Float,
    )
    private data class PanelRefitKey(
        val sourceIndex: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )
    private data class LayoutCacheKey(
        val text: String,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val growUp: Int,
        val growDown: Int,
        val growLeft: Int,
        val growRight: Int,
        val startingSize: Int,
        val minimumSize: Int,
        val pad: Int,
    )
    private data class FixedLayoutCacheKey(
        val text: String,
        val width: Int,
        val size: Int,
        val maxLines: Int,
        val ellipsize: Boolean,
    )
    private data class HudGeometry(
        val lines: List<String>,
        val rect: RectF,
        val padding: Float,
        val baselines: FloatArray,
    )

    companion object {
        private const val TAG = "CameraOverlayView"
        private val GREEN = Color.rgb(0, 255, 102)
        private val CYAN = Color.rgb(80, 220, 255)
        private val AMBER = Color.rgb(255, 179, 0)
        private val RED = Color.rgb(255, 96, 96)
        private const val MIN_LEGACY_TEXT_SP = 11f
        private const val MAX_STATUS_CHARS = 48
        private const val LAYOUT_CACHE_SIZE = 64
        private const val COMPLETE_LINE_EPSILON = 0.5f
        private const val HUD_STABLE_ID = "__hud__"

        private fun <K, V> lruMap(): LinkedHashMap<K, V> = object : LinkedHashMap<K, V>(
            LAYOUT_CACHE_SIZE,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > LAYOUT_CACHE_SIZE
        }
    }
}
