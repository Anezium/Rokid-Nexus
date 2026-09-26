package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.ActivityProgress
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.PinSurfaceLine
import com.anezium.rokidbus.shared.PinSurfacePosition
import com.anezium.rokidbus.shared.PinSurfaceSize

/**
 * The activity tier's one fixed full-screen window.
 *
 * Each activity is one [HudIslandView]: a single outline that springs between
 * the chip, the expanded panel, and the flare, with the content of each form
 * revealed inside it. The WindowManager layout is never animated or updated.
 */
internal object ActivityOverlayRenderer {
    private data class FlareSession(
        val surfaceId: String,
        val startedOrder: Long,
    )

    private val main = Handler(Looper.getMainLooper())
    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var unsubscribe: (() -> Unit)? = null
    private var insetUnsubscribe: (() -> Unit)? = null
    private val nodes = linkedMapOf<String, ActivityIsland>()
    private val processedMotionTokens = mutableMapOf<String, Long>()
    private var flareGeneration = 0L
    private var flareCollapse: Runnable? = null
    private var activeFlare: FlareSession? = null
    private var latestState = ActivityRenderState()
    private var hudTopInsetDp = 0

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
        insetUnsubscribe?.invoke()
        insetUnsubscribe = HudTopInset.observe(service, ::applyHudTopInset)
        unsubscribe?.invoke()
        unsubscribe = ActivityController.observe(::render)
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service !== service) return
        unsubscribe?.invoke()
        unsubscribe = null
        insetUnsubscribe?.invoke()
        insetUnsubscribe = null
        teardown()
        processedMotionTokens.clear()
        this.service = null
        windowManager = null
    }

    /** Ambient stack order is pin, activity, notice. */
    fun ensureOnTop() {
        val manager = windowManager ?: return
        val currentRoot = root ?: return
        runCatching {
            manager.removeView(currentRoot)
            manager.addView(currentRoot, params())
        }.onFailure { logError("Activity overlay z-order refresh failed", it) }
    }

    private fun render(state: ActivityRenderState) {
        latestState = state
        val liveIds = state.items.mapTo(mutableSetOf()) { it.activity.surfaceId }
        processedMotionTokens.keys.retainAll(liveIds)
        if (state.items.isEmpty()) {
            dismissAll()
            return
        }

        // Hidden updates still consume their motion token. Restoring after the
        // camera therefore restores current state without replaying a flare.
        state.items
            .filter { it.presentation == ActivityPresentation.HIDDEN }
            .forEach { processedMotionTokens[it.activity.surfaceId] = it.activity.motionToken }

        val visible = state.items.filter { it.presentation != ActivityPresentation.HIDDEN }
        if (visible.isEmpty()) {
            // The camera needs the glasses clear now, not after a fold.
            teardown(keepMotionTokens = true)
            return
        }
        val activeService = service ?: return
        val container = ensureWindow(activeService) ?: return
        activeFlare?.let { flare ->
            // A flare is a timed presentation event, not a property every
            // subsequent state publish repeats. Keep it running across content,
            // pin, and context publishes; removal, hiding, or a same-owner
            // activity restart interrupts the old session.
            val stillCurrent = visible.any {
                it.activity.surfaceId == flare.surfaceId &&
                    it.activity.startedOrder == flare.startedOrder
            }
            if (!stillCurrent) cancelFlare()
        }

        nodes.keys.filterNot(liveIds::contains).forEach { id ->
            nodes.remove(id)?.let { island -> island.dismiss { container.removeView(island) } }
        }

        var pendingFlare: Pair<ActivityRenderItem, ActivityIsland>? = null
        visible.forEach { item ->
            val island = nodes[item.activity.surfaceId] ?: ActivityIsland(activeService).also {
                nodes[item.activity.surfaceId] = it
                container.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            val flareInProgress = activeFlare?.let { flare ->
                item.activity.surfaceId == flare.surfaceId &&
                    item.activity.startedOrder == flare.startedOrder
            } == true
            val previousToken = processedMotionTokens[item.activity.surfaceId] ?: Long.MIN_VALUE
            val newMotion = item.activity.motionToken > previousToken
            island.place(item.activity.corner, hudTopInsetDp)
            island.render(item)
            island.visibility = View.VISIBLE
            // A running flare holds its form; the steady form returns when it folds.
            if (!flareInProgress) island.showSteady(item.presentation)
            when (item.presentation) {
                ActivityPresentation.PULSE -> {
                    if (newMotion) island.bump(dp(activeService, PULSE_DP).toFloat())
                }
                ActivityPresentation.FLARE -> {
                    if (newMotion) pendingFlare = item to island
                }
                ActivityPresentation.CHIP,
                ActivityPresentation.PANEL,
                ActivityPresentation.HIDDEN,
                -> Unit
            }
            if (newMotion) {
                processedMotionTokens[item.activity.surfaceId] = item.activity.motionToken
            }
        }
        activeFlare?.let { flare -> nodes[flare.surfaceId]?.bringToFront() }
        pendingFlare?.let { (item, island) -> startFlare(item, island) }
    }

    private fun ensureWindow(service: AccessibilityService): FrameLayout? {
        root?.let { return it }
        val manager = windowManager
            ?: service.getSystemService(WindowManager::class.java)
            ?: return null
        val nextRoot = FrameLayout(service)
        if (runCatching { manager.addView(nextRoot, params()) }.isFailure) {
            logError("Activity overlay window could not be added")
            return null
        }
        root = nextRoot
        HudOverlayStack.reassert()
        return nextRoot
    }

    private fun applyHudTopInset(value: Int) {
        hudTopInsetDp = HudTopInset.sanitize(value)
        nodes.values.forEach { island -> island.place(island.corner, hudTopInsetDp) }
    }

    private fun startFlare(item: ActivityRenderItem, island: ActivityIsland) {
        // Another activity's flare folds back into its own steady form.
        cancelFlare()
        val generation = ++flareGeneration
        activeFlare = FlareSession(
            surfaceId = item.activity.surfaceId,
            startedOrder = item.activity.startedOrder,
        )
        island.bringToFront()
        island.showFlare(urgent = item.urgent)
        val collapse = Runnable { collapseFlare(generation) }
        flareCollapse = collapse
        main.postDelayed(collapse, HudMotion.STANDARD_MS + HudMotion.HOLD_MS)
    }

    private fun collapseFlare(generation: Long) {
        if (generation != flareGeneration) return
        flareCollapse = null
        val flare = activeFlare ?: return
        activeFlare = null
        foldToSteady(flare)
    }

    private fun cancelFlare() {
        flareGeneration++
        flareCollapse?.let(main::removeCallbacks)
        flareCollapse = null
        val flare = activeFlare ?: return
        activeFlare = null
        foldToSteady(flare)
    }

    private fun foldToSteady(flare: FlareSession) {
        val island = nodes[flare.surfaceId] ?: return
        val item = latestState.items.firstOrNull { it.activity.surfaceId == flare.surfaceId } ?: return
        island.showSteady(item.presentation)
    }

    /** The last activity ended: every island folds away, then the window goes. */
    private fun dismissAll() {
        flareGeneration++
        flareCollapse?.let(main::removeCallbacks)
        flareCollapse = null
        activeFlare = null
        val container = root ?: return
        val leaving = nodes.values.toList()
        nodes.clear()
        if (leaving.isEmpty()) {
            teardown()
            return
        }
        leaving.forEach { island ->
            island.dismiss {
                container.removeView(island)
                if (root === container && nodes.isEmpty() && container.childCount == 0) teardown()
            }
        }
    }

    private fun teardown(keepMotionTokens: Boolean = false) {
        flareGeneration++
        flareCollapse?.let(main::removeCallbacks)
        flareCollapse = null
        activeFlare = null
        val currentRoot = root
        if (currentRoot != null) {
            runCatching { windowManager?.removeView(currentRoot) }
                .onFailure { logError("Activity overlay removal failed", it) }
        }
        root = null
        nodes.clear()
        if (!keepMotionTokens) processedMotionTokens.clear()
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /**
     * One activity: the medium chip and the expanded panel at its corner, and
     * the notice-band flare at the top, all drawn inside one island outline.
     */
    private class ActivityIsland(context: Context) : HudIslandView(context) {
        private val chip = PinOverlayRenderer.PinPanelView(context).apply { dropChrome() }
        private val panel = ActivityPanelView(context)
        private val flare = NoticeOverlayRenderer.NoticeBandView(context, chromeless = true)
        private val renderedKeys = mutableMapOf<View, Any?>()
        var corner = PinSurfacePosition.TOP_LEFT
            private set
        private var topInsetDp = -1

        init {
            val width = resources.displayMetrics.widthPixels
            addForm(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addForm(panel, LayoutParams((width * PANEL_WIDTH_FRACTION).toInt(), LayoutParams.WRAP_CONTENT))
            addForm(
                flare,
                LayoutParams((width * BAND_WIDTH_FRACTION).toInt(), LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                },
            )
        }

        fun place(corner: PinSurfacePosition, topInsetDp: Int) {
            if (corner == this.corner && topInsetDp == this.topInsetDp) return
            this.corner = corner
            this.topInsetDp = topInsetDp
            val edge = dp(context, EDGE_MARGIN_DP)
            val topEdge = dp(context, EDGE_MARGIN_DP + topInsetDp)
            val top = corner == PinSurfacePosition.TOP_LEFT || corner == PinSurfacePosition.TOP_RIGHT
            val gravity = when (corner) {
                PinSurfacePosition.TOP_LEFT -> Gravity.TOP or Gravity.START
                PinSurfacePosition.TOP_RIGHT -> Gravity.TOP or Gravity.END
                PinSurfacePosition.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
                PinSurfacePosition.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            }
            listOf(chip, panel).forEach { form ->
                form.layoutParams = (form.layoutParams as LayoutParams).apply {
                    this.gravity = gravity
                    setMargins(edge, if (top) topEdge else edge, edge, edge)
                }
            }
            flare.setHudTopInsetDp(topInsetDp)
            flare.layoutParams = (flare.layoutParams as LayoutParams).apply { topMargin = topEdge }
        }

        fun render(item: ActivityRenderItem) {
            val content = item.activity.content
            val owner = item.activity.ownerPluginId
            update(chip, content.primary to content.secondary to content.glyph) {
                chip.render(
                    titleText = content.primary,
                    lineContent = content.secondary
                        ?.let { listOf(PinSurfaceLine(it)) }
                        .orEmpty(),
                    size = PinSurfaceSize.MEDIUM,
                    leadingGlyph = GlassesHub.activityGlyphDrawable(context, owner, content.glyph),
                )
            }
            update(panel, content to item.activity.selectedActionIndex) {
                panel.render(
                    content = content,
                    mainGlyph = GlassesHub.activityGlyphDrawable(context, owner, content.glyph),
                    selectedActionIndex = item.activity.selectedActionIndex,
                )
            }
            update(flare, content) {
                flare.render(
                    titleText = content.primary,
                    bodyText = buildList {
                        content.secondary?.let(::add)
                        addAll(content.detail)
                    }.joinToString("  •  ").takeIf(String::isNotEmpty),
                    footerText = content.eta,
                    leadingGlyph = content.badge?.let { ActivityBadgeDrawable(context, it) }
                        ?: GlassesHub.activityGlyphDrawable(context, owner, content.glyph),
                    track = content.track,
                )
            }
        }

        fun showSteady(presentation: ActivityPresentation) {
            setOutline(BusTheme.hairline, dp(context, 1).toFloat())
            show(if (presentation == ActivityPresentation.PANEL) panel else chip)
        }

        /**
         * The urgent flare is the same notice band with a bright outline and a
         * bouncier arrival: noticeable on additive optics without lighting a
         * whole block of the wearer's view.
         */
        fun showFlare(urgent: Boolean) {
            if (urgent) {
                setOutline(BusTheme.phosphor, dp(context, URGENT_OUTLINE_DP).toFloat())
            } else {
                setOutline(BusTheme.hairline, dp(context, 1).toFloat())
            }
            show(flare, if (urgent) HudSpring.URGENT else HudSpring.STANDARD)
        }

        private fun update(form: View, key: Any?, change: () -> Unit) {
            if (renderedKeys.containsKey(form) && renderedKeys[form] == key) return
            renderedKeys[form] = key
            crossfade(form, change)
        }
    }

    /** The platform-owned expanded geometry; no plugin field controls it. */
    private class ActivityPanelView(context: Context) : LinearLayout(context) {
        private val glyph = ImageView(context)
        private val primary = text(PRIMARY_SP, BusTheme.phosphor, bold = true)
        private val eta = text(ETA_SP, BusTheme.muted)
        private val secondary = text(SECONDARY_SP, BusTheme.muted)
        private val etaBelow = text(ETA_SP, BusTheme.muted)
        private val progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            progressTintList = ColorStateList.valueOf(BusTheme.phosphor)
            progressBackgroundTintList = ColorStateList.valueOf(BusTheme.hairline)
            indeterminateTintList = ColorStateList.valueOf(BusTheme.phosphor)
            max = 100
        }
        private val track = ActivityTrackView(context)
        private val details = List(2) { text(DETAIL_SP, BusTheme.muted) }
        private val actions = HudActionRowView(context)
        private val secondaryRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(secondary, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(
                etaBelow,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(context, 8) },
            )
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            val horizontal = dp(context, 12)
            val vertical = dp(context, 10)
            setPadding(horizontal, vertical, horizontal, vertical)

            addView(
                glyph,
                LayoutParams(dp(context, GLYPH_DP), dp(context, GLYPH_DP)).apply {
                    marginEnd = dp(context, GLYPH_GAP_DP)
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(
                        LinearLayout(context).apply {
                            orientation = HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(primary, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                            addView(
                                eta,
                                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                                    .apply { marginStart = dp(context, ETA_GAP_DP) },
                            )
                        },
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                    )
                    addView(
                        secondaryRow,
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(context, 2)
                        },
                    )
                    addView(
                        progress,
                        LayoutParams(LayoutParams.MATCH_PARENT, dp(context, PROGRESS_HEIGHT_DP)).apply {
                            topMargin = dp(context, 7)
                        },
                    )
                    addView(
                        track,
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(context, 6)
                        },
                    )
                    details.forEach { detail ->
                        addView(
                            detail,
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                                topMargin = dp(context, 4)
                            },
                        )
                    }
                    addView(
                        actions,
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(context, 8)
                        },
                    )
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        fun render(
            content: ActivitySurfaceContent,
            mainGlyph: Drawable,
            selectedActionIndex: Int,
        ) {
            glyph.setImageDrawable(
                content.badge?.let { ActivityBadgeDrawable(context, it) } ?: mainGlyph,
            )
            primary.text = content.primary
            val fit = fitPrimary(content.primary, content.eta)
            primary.setTextSize(TypedValue.COMPLEX_UNIT_SP, fit.sizeSp)
            eta.text = content.eta.orEmpty()
            eta.visibility = visibleIf(content.eta != null && !fit.etaBelow)
            etaBelow.text = content.eta.orEmpty()
            etaBelow.visibility = visibleIf(content.eta != null && fit.etaBelow)
            secondary.text = content.secondary.orEmpty()
            secondary.visibility = visibleIf(content.secondary != null)
            secondaryRow.visibility = visibleIf(content.secondary != null || fit.etaBelow)
            // Glasses that draw the track draw it instead of the bar; the bar
            // stays in the payload for glasses that do not.
            track.render(content.track)
            when (val value = content.progress?.takeIf { content.track == null }) {
                null -> progress.visibility = View.GONE
                ActivityProgress.Indeterminate -> {
                    progress.visibility = View.VISIBLE
                    progress.isIndeterminate = true
                }
                is ActivityProgress.Percent -> {
                    progress.visibility = View.VISIBLE
                    progress.isIndeterminate = false
                    progress.progress = value.value
                }
            }
            details.forEachIndexed { index, view ->
                val line = content.detail.getOrNull(index)
                view.text = line.orEmpty()
                view.visibility = visibleIf(line != null)
            }
            actions.render(
                actions = content.actions.map { HudActionChip(it.glyph, it.label) },
                selectedIndex = selectedActionIndex,
            )
        }

        /**
         * The text column's width is fixed by the panel geometry, so the fit is
         * computed from it rather than from a layout pass that has not run yet.
         */
        private fun fitPrimary(text: String, etaText: String?): ActivityPrimaryFit {
            val metrics = resources.displayMetrics
            val panelWidth = metrics.widthPixels * PANEL_WIDTH_FRACTION
            val available = panelWidth - paddingLeft - paddingRight -
                dp(context, GLYPH_DP) - dp(context, GLYPH_GAP_DP) - dp(context, FIT_SLACK_DP)
            val measure = TextPaint(primary.paint)
            return fitActivityPrimary(
                availablePx = available,
                inlineEtaPx = etaText?.let { eta.paint.measureText(it) + dp(context, ETA_GAP_DP) },
                widthAtSp = { sizeSp ->
                    measure.textSize = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        sizeSp,
                        metrics,
                    )
                    measure.measureText(text)
                },
            )
        }

        private fun text(sizeSp: Float, color: Int, bold: Boolean = false) =
            TextView(context).apply {
                textSize = sizeSp
                setTextColor(color)
                typeface = Typeface.create(
                    Typeface.MONOSPACE,
                    if (bold) Typeface.BOLD else Typeface.NORMAL,
                )
                includeFontPadding = false
                maxLines = 1
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }

        private fun visibleIf(visible: Boolean): Int =
            if (visible) View.VISIBLE else View.GONE
    }

    /** Forms draw no border of their own; the island draws the only one. */
    private fun View.dropChrome() {
        val left = paddingLeft
        val top = paddingTop
        val right = paddingRight
        val bottom = paddingBottom
        background = null
        setPadding(left, top, right, bottom)
    }

    private fun dp(context: Context, value: Int): Int = BusTheme.dp(context, value)

    private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    private const val EDGE_MARGIN_DP = 12
    private const val BAND_WIDTH_FRACTION = 0.92f
    private const val PANEL_WIDTH_FRACTION = 0.78f
    private const val PULSE_DP = 4
    private const val URGENT_OUTLINE_DP = 2
    private const val GLYPH_DP = 48
    private const val PROGRESS_HEIGHT_DP = 4
    private const val PRIMARY_SP = ACTIVITY_PRIMARY_MAX_SP
    private const val GLYPH_GAP_DP = 12
    private const val ETA_GAP_DP = 8
    private const val FIT_SLACK_DP = 2
    private const val SECONDARY_SP = 13f
    private const val ETA_SP = 13f
    private const val DETAIL_SP = 11f
}
