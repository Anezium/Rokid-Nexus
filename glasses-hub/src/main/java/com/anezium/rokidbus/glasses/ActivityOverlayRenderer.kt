package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.anezium.rokidbus.glasses.page.NavIslandView
import com.anezium.rokidbus.glasses.page.OverflowLineView
import com.anezium.rokidbus.shared.ActivityProgress
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.PinSurfaceLine
import com.anezium.rokidbus.shared.PinSurfacePosition
import com.anezium.rokidbus.shared.PinSurfaceSize
import kotlin.math.ceil

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
    private var routeUnsubscribe: (() -> Unit)? = null
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
        routeUnsubscribe?.invoke()
        routeUnsubscribe = RouteLive.observe(::onRouteLive)
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service !== service) return
        unsubscribe?.invoke()
        unsubscribe = null
        routeUnsubscribe?.invoke()
        routeUnsubscribe = null
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

    private fun render(published: ActivityRenderState) {
        val state = islandStateOf(published, GlassesHub.launcherInteractiveActive() || GlassesHub.launcherBundleHeldThroughLoss())
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
        state.items.filter { it.presentation == ActivityPresentation.HIDDEN }.forEach { nodes[it.activity.surfaceId]?.visibility = View.GONE }
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

        val guidances = visible.associate { it.activity.surfaceId to guidanceOf(it) }
        val budgets = foldedWidthBudgets(activeService, visible, guidances, PinController.presentablePin())
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
            island.render(item, guidances[item.activity.surfaceId], budgets[item.activity.corner])
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

    private fun guidanceOf(item: ActivityRenderItem): GuidanceIsland? {
        val owner = item.activity.ownerPluginId
        return GuidanceIslands.of(
            item, RouteLive.geometry(owner), RouteLive.heading(owner),
            sourceLabel = GlassesHub.launcherDisplayName(owner),
            crowded = mapCrowded(latestState, pinDrawn = PinController.presentablePin() != null),
            geometryRevision = RouteLive.geometryRevision(owner),
            launcherAmbient = ActivityController.launcherAmbient(),
        )
    }

    /**
     * Each island has a window-wide frame and places its forms by gravity, so none knows what its
     * row's other corner takes: the widths are decided here, from what will be drawn, [pin] included.
     */
    internal fun foldedWidthBudgets(
        context: Context,
        visible: List<ActivityRenderItem>,
        guidances: Map<String, GuidanceIsland?>,
        pin: NexusPinSurface?,
    ): Map<PinSurfacePosition, Int> {
        val occupants = mutableMapOf<PinSurfacePosition, Int?>()
        pin?.let { occupants[it.content.position] = PinOverlayRenderer.drawnWidthPx(context, it) }
        visible.forEach { item -> occupants[item.activity.corner] = guidances[item.activity.surfaceId]?.form?.widthPx }
        return rowBudgets(context.resources.displayMetrics.widthPixels, dp(context, EDGE_MARGIN_DP), occupants)
    }

    /**
     * The widest each folded island may be so that it never meets the other occupant of its row
     * (the two top corners, or the two bottom ones) and keeps the edge margin from it, as from the
     * screen's edges. [occupants] holds every drawn corner: the width a guidance form or a pin keeps
     * whatever stands beside it, or null for a folded island, which gives way to such a width. Two
     * folded islands share the row evenly. A folded island alone in its row is absent from the
     * result and keeps its own widths; the expanded panel and the flare are not bounded by it.
     */
    internal fun rowBudgets(screenPx: Int, edgePx: Int, occupants: Map<PinSurfacePosition, Int?>): Map<PinSurfacePosition, Int> {
        val room = screenPx - 3 * edgePx
        val budgets = mutableMapOf<PinSurfacePosition, Int>()
        for ((left, right) in ROWS) {
            if (left !in occupants || right !in occupants) continue
            val leftPx = occupants[left]
            val rightPx = occupants[right]
            if (leftPx == null && rightPx == null) {
                budgets[left] = room / 2
                budgets[right] = room - room / 2
            } else if (leftPx == null && rightPx != null) {
                budgets[left] = (room - rightPx).coerceAtLeast(0)
            } else if (rightPx == null && leftPx != null) {
                budgets[right] = (room - leftPx).coerceAtLeast(0)
            }
        }
        return budgets
    }

    /**
     * Three things on the HUD crowd a map: the drawn islands and the pin. A pin whose owner is
     * kept off the glasses holds its slot but is not drawn, so it takes no room.
     */
    internal fun mapCrowded(state: ActivityRenderState, pinDrawn: Boolean): Boolean =
        state.items.count { it.presentation != ActivityPresentation.HIDDEN } + (if (pinDrawn) 1 else 0) >= 3

    /** A live route moved: its owner's guidance island redraws its map, nothing else does. */
    private fun onRouteLive(owner: String) {
        val item = latestState.items.firstOrNull { it.activity.ownerPluginId == owner } ?: return
        if (item.presentation == ActivityPresentation.HIDDEN) return
        val island = nodes[item.activity.surfaceId] ?: return
        val guidance = guidanceOf(item)
        // A map arriving or leaving changes which island the owner gets; a new glyph or form
        // changes its size: the whole state is drawn again, steady forms included.
        if (guidance == null) {
            if (island.guided) render(latestState)
            return
        }
        if (!island.guided || island.renderGuidance(guidance)) render(latestState)
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
        val collapse = object : Runnable {
            // Fixed at the usual fold: one pass of the title as it stands then, and a little more.
            private var foldByMs = Long.MAX_VALUE

            override fun run() {
                if (generation != flareGeneration) return
                // A title still crossing the flare is read to its end before the flare folds.
                val now = SystemClock.uptimeMillis()
                val left = island.flareTitleLeftMs()
                if (foldByMs == Long.MAX_VALUE) foldByMs = now + left + FLARE_GRACE_MS
                val hold = flareHoldMs(left, now, foldByMs)
                if (hold > 0) {
                    main.postDelayed(this, hold)
                    return
                }
                collapseFlare(generation)
            }
        }
        flareCollapse = collapse
        main.postDelayed(collapse, HudMotion.STANDARD_MS + HudMotion.HOLD_MS)
    }

    /**
     * How much longer a flare stays for its title's first pass; 0 folds it now. Never past
     * [foldByMs]: a title the screen or its owner keeps starting over does not hold the flare.
     */
    internal fun flareHoldMs(titleLeftMs: Long, nowMs: Long, foldByMs: Long): Long =
        if (titleLeftMs <= 0) 0 else titleLeftMs.coerceAtMost(foldByMs - nowMs).coerceAtLeast(0)

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
     * One activity: its folded form and the expanded panel at its corner, and
     * the notice-band flare at the top, all drawn inside one island outline.
     * Folded, an activity with a measure keeps the pins' medium chip; any other
     * is the two-line [compact] form. Beside a row neighbour that leaves too little
     * room for either, it folds to its [glyphOnly] form, or is withheld while even
     * that does not fit. The guidance form [nav] shares that outline.
     */
    internal class ActivityIsland(
        context: Context,
        private val nav: NavIslandView = NavIslandView(context),
        /** An owner's activity glyph by name. */
        private val glyphOf: (String, String) -> Drawable = { owner, glyph -> GlassesHub.activityGlyphDrawable(context, owner, glyph) },
        /** A command's built-in glyph by name. */
        actionGlyphOf: (String) -> Drawable = HudActionRowView.builtInGlyphs(context),
    ) : HudIslandView(context) {
        // Every form that names the activity scrolls a title wider than its line rather than cut it.
        private val chip = PinOverlayRenderer.PinPanelView(context, movingTitle = titleLine(context)).apply { dropChrome() }
        private val compact = ActivityCompactView(context)
        private val glyphOnly = ActivityGlyphView(context, compact.title.lineHeight)
        private val panel = ActivityPanelView(context, actionGlyphOf)
        private val flareTitle = titleLine(context)
        private val flare = NoticeOverlayRenderer.NoticeBandView(context, chromeless = true, movingTitle = flareTitle)

        private var renderedGuidance: GuidanceIsland? = null

        /** Drawn as a guidance island: its steady form is [nav], whatever the presentation. */
        var guided = false
            private set
        private val renderedKeys = mutableMapOf<View, Any?>()

        /** The folded form the last render chose: [compact], [chip] beside a measure, or [glyphOnly]. */
        var folded: View = compact
            private set

        /** No folded form fits beside the row's neighbour: none is drawn until the room returns. */
        var withheld = false
            private set
        var corner = PinSurfacePosition.TOP_LEFT
            private set
        private var topInsetDp = -1

        init {
            nav.outlined = false
            val width = resources.displayMetrics.widthPixels
            addForm(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addForm(compact, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addForm(glyphOnly, LayoutParams(glyphOnly.sizePx, glyphOnly.sizePx))
            addForm(panel, LayoutParams((width * PANEL_WIDTH_FRACTION).toInt(), LayoutParams.WRAP_CONTENT))
            addForm(nav, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addForm(
                flare,
                LayoutParams((width * BAND_WIDTH_FRACTION).toInt(), LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                },
            )
        }

        fun place(corner: PinSurfacePosition, topInsetDp: Int) {
            val top = corner == PinSurfacePosition.TOP_LEFT || corner == PinSurfacePosition.TOP_RIGHT
            val edgePx = dp(context, EDGE_MARGIN_DP + if (top) topInsetDp else 0)
            val available = HudTopInset.ambientHeightPx(context, top, edgePx)
            if (corner == this.corner && topInsetDp == this.topInsetDp && nav.maxHeightPx == available) return
            nav.maxHeightPx = available
            this.corner = corner
            this.topInsetDp = topInsetDp
            val edge = dp(context, EDGE_MARGIN_DP)
            val topEdge = dp(context, EDGE_MARGIN_DP + topInsetDp)
            val gravity = when (corner) {
                PinSurfacePosition.TOP_LEFT -> Gravity.TOP or Gravity.START
                PinSurfacePosition.TOP_RIGHT -> Gravity.TOP or Gravity.END
                PinSurfacePosition.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
                PinSurfacePosition.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            }
            listOf(chip, compact, glyphOnly, panel, nav).forEach { form ->
                form.layoutParams = (form.layoutParams as LayoutParams).apply {
                    this.gravity = gravity
                    setMargins(edge, if (top) topEdge else edge, edge, edge)
                }
            }
            flare.setHudTopInsetDp(topInsetDp)
            flare.layoutParams = (flare.layoutParams as LayoutParams).apply { topMargin = topEdge }
        }

        /** [widthBudgetPx] bounds the folded forms beside another occupant of the row; null leaves them their own widths. */
        fun render(item: ActivityRenderItem, guidance: GuidanceIsland?, widthBudgetPx: Int? = null) {
            guided = guidance != null
            if (guidance != null) renderGuidance(guidance) else renderedGuidance = null
            // While the phone link is down the island says it is last known and offers no action.
            val content = GuidanceIslands.lastKnown(item)
            val owner = item.activity.ownerPluginId
            val measured = content.measure != null
            // The text form while its title keeps a character's room, then the glyph alone, then nothing.
            val textMinPx = if (measured) chip.minStackedWidthPx(PinSurfaceSize.MEDIUM) else compact.minWidthPx()
            withheld = widthBudgetPx != null && widthBudgetPx < glyphOnly.sizePx
            folded = when {
                widthBudgetPx != null && widthBudgetPx < textMinPx && !withheld -> glyphOnly
                measured -> chip
                else -> compact
            }
            if (folded === glyphOnly) {
                update(glyphOnly, owner to content.glyph) { glyphOnly.setImageDrawable(glyphOf(owner, content.glyph)) }
            }
            if (measured) {
                update(chip, listOf(content.title, content.measure, content.secondary, content.glyph, widthBudgetPx)) {
                    chip.render(
                        titleText = content.title,
                        // Folded, the measure sits under the primary beside the glyph:
                        // "3 min" over "250 m", with the street still on the line below.
                        subtitleText = content.measure,
                        lineContent = content.secondary
                            ?.let { listOf(PinSurfaceLine(it)) }
                            .orEmpty(),
                        size = PinSurfaceSize.MEDIUM,
                        leadingGlyph = glyphOf(owner, content.glyph),
                        widthBudgetPx = widthBudgetPx,
                    )
                }
            } else {
                update(compact, listOf(content.title, content.secondary, content.glyph, widthBudgetPx)) {
                    compact.render(
                        titleText = content.title,
                        secondaryText = content.secondary,
                        glyph = glyphOf(owner, content.glyph),
                        widthBudgetPx = widthBudgetPx,
                    )
                }
            }
            update(panel, content to item.activity.selectedActionIndex) {
                panel.render(
                    content = content,
                    mainGlyph = glyphOf(owner, content.glyph),
                    selectedActionIndex = item.activity.selectedActionIndex,
                )
            }
            update(flare, content) {
                // A ticking measure is the same title: the line keeps its place in it.
                flareTitle.continuity = content.title
                flare.render(
                    titleText = content.primaryWithMeasure(),
                    bodyText = buildList {
                        content.secondary?.let(::add)
                        addAll(content.detail)
                    }.joinToString("  •  ").takeIf(String::isNotEmpty),
                    footerText = content.eta,
                    leadingGlyph = content.badge?.let { ActivityBadgeDrawable(context, it) }
                        ?: glyphOf(owner, content.glyph),
                    track = content.track,
                )
            }
        }

        /**
         * Only the map redraws in place when nothing but the heading or the geometry moved, so a
         * five-a-second heading never rebuilds the island; anything else binds it afresh.
         */
        fun renderGuidance(guidance: GuidanceIsland): Boolean {
            val previous = renderedGuidance
            renderedGuidance = guidance
            if (previous == guidance) return false
            val map = guidance.content.map
            val mapView = nav.mapView
            if (previous != null && map != null && mapView != null && previous.form == guidance.form &&
                previous.content.copy(map = null) == guidance.content.copy(map = null)
            ) {
                mapView.bind(map)
                return false
            }
            nav.bind(guidance.form, guidance.content)
            return true
        }

        fun showSteady(presentation: ActivityPresentation) {
            removeCallbacks(urgentBeat)
            setOutline(BusTheme.hairline, dp(context, 1).toFloat())
            val form = when {
                guided -> nav
                presentation == ActivityPresentation.PANEL -> panel
                withheld -> null
                else -> folded
            }
            // A withheld island folds away as a dismissed one does, but stays to come back by itself.
            if (form != null) show(form) else if (currentForm != null) dismiss {}
        }

        /**
         * The urgent flare is the same notice band with a bright outline that
         * beats once it has arrived: noticeable on additive optics without
         * lighting a whole block of the wearer's view. The beat waits for the
         * morph, so the band's own travel never overshoots the screen edge.
         */
        fun showFlare(urgent: Boolean) {
            removeCallbacks(urgentBeat)
            if (urgent) {
                setOutline(BusTheme.phosphor, dp(context, URGENT_OUTLINE_DP).toFloat())
                postDelayed(urgentBeat, URGENT_BEAT_DELAY_MS)
            } else {
                setOutline(BusTheme.hairline, dp(context, 1).toFloat())
            }
            show(flare)
        }

        /** How long the flare's moving title still needs to show its end; 0 once read or when it fits. */
        fun flareTitleLeftMs(): Long = if (currentForm === flare) flareTitle.firstPassLeftMs() else 0

        private val urgentBeat = Runnable {
            if (currentForm === flare) bump(dp(context, URGENT_BEAT_DP).toFloat(), HudSpring.BEAT)
        }

        private fun update(form: View, key: Any?, change: () -> Unit) {
            if (renderedKeys.containsKey(form) && renderedKeys[form] == key) return
            renderedKeys[form] = key
            crossfade(form, change)
        }
    }

    /**
     * An activity folded without a measure: its glyph beside two lines, the title over the
     * secondary, and nothing under them. The glyph is as tall as the two lines, so a missing
     * secondary leaves the title centred beside it and the form keeps its height. A title wider
     * than its line scrolls to its end while the form is shown, inside the width it already has;
     * a [render] width budget narrows the two lines so the whole form stays inside it.
     */
    internal class ActivityCompactView(context: Context) : LinearLayout(context) {
        val glyph = ImageView(context)
        val title = OverflowLineView(context).apply {
            textSize = COMPACT_TITLE_SP
            setTextColor(BusTheme.phosphor)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            softEdges()
            active = true
        }
        val secondary = TextView(context).apply {
            textSize = COMPACT_SECONDARY_SP
            setTextColor(BusTheme.muted)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 8), dp(context, COMPACT_VERTICAL_PADDING_DP), dp(context, 8), dp(context, COMPACT_VERTICAL_PADDING_DP))
            val glyphPx = title.lineHeight + dp(context, COMPACT_LINE_GAP_DP) + secondary.lineHeight
            addView(glyph, LayoutParams(glyphPx, glyphPx).apply { marginEnd = dp(context, COMPACT_GLYPH_GAP_DP) })
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                    addView(
                        secondary,
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(context, COMPACT_LINE_GAP_DP)
                        },
                    )
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }

        /** The narrowest [render] keeps this form with its text: the title line still shows one character. */
        fun minWidthPx(): Int {
            val glyphParams = glyph.layoutParams as LayoutParams
            return paddingLeft + paddingRight + glyphParams.width + glyphParams.marginEnd +
                ceil(title.paint.measureText("M")).toInt()
        }

        fun render(titleText: String, secondaryText: String?, glyph: Drawable, widthBudgetPx: Int? = null) {
            val glyphParams = this.glyph.layoutParams as LayoutParams
            val fractionPx = (resources.displayMetrics.widthPixels * COMPACT_TEXT_WIDTH_FRACTION).toInt()
            val maxWidthPx = widthBudgetPx
                ?.let { it - paddingLeft - paddingRight - glyphParams.width - glyphParams.marginEnd }
                ?.coerceIn(0, fractionPx)
                ?: fractionPx
            this.glyph.setImageDrawable(glyph)
            title.maxWidth = maxWidthPx
            title.text = titleText
            secondary.maxWidth = maxWidthPx
            secondary.text = secondaryText.orEmpty()
            secondary.visibility = if (secondaryText == null) View.GONE else View.VISIBLE
        }
    }

    /**
     * An activity folded to its glyph alone: as tall as the compact form's title line, inside the
     * compact form's vertical padding on every side. It is the narrowest form that still shows the
     * activity is there, for a row whose neighbour leaves no room for a line of text.
     */
    internal class ActivityGlyphView(context: Context, lineHeightPx: Int) : ImageView(context) {
        val sizePx: Int

        init {
            val padding = dp(context, COMPACT_VERTICAL_PADDING_DP)
            setPadding(padding, padding, padding, padding)
            sizePx = lineHeightPx + 2 * padding
        }
    }

    /** The platform-owned expanded geometry; no plugin field controls it. */
    private class ActivityPanelView(context: Context, actionGlyphOf: (String) -> Drawable) : LinearLayout(context) {
        private val glyph = ImageView(context)
        // A headline still too wide at the smallest fitting size scrolls rather than lose its end.
        private val primary = OverflowLineView(context).apply {
            textSize = PRIMARY_SP
            setTextColor(BusTheme.phosphor)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            softEdges()
            active = true
        }
        private val eta = text(ETA_SP, BusTheme.muted)
        // The contract allows 28 characters, about 21 of which fit one line of
        // the text column; a stop or street name wraps rather than losing its end.
        private val secondary = text(SECONDARY_SP, BusTheme.muted).apply {
            isSingleLine = false
            maxLines = SECONDARY_MAX_LINES
        }
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
        private val actions = HudActionRowView(context, wholeLabels = true, glyphOf = actionGlyphOf)
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
            val primaryText = content.primaryWithMeasure()
            primary.text = primaryText
            val fit = fitPrimary(primaryText, content.eta)
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

    /**
     * The islands' copy of [state]. A headline is a launcher-bundle field, read only while the
     * bundle is negotiated or held through a link loss, as the card reads it: once an older
     * phone announces, a headline kept from before names nothing and every island shows the
     * primary that phone updates.
     */
    internal fun islandStateOf(state: ActivityRenderState, bundle: Boolean): ActivityRenderState {
        if (bundle || state.items.none { it.activity.content.headline != null }) return state
        return state.copy(
            items = state.items.map { item ->
                item.copy(activity = item.activity.copy(content = item.activity.content.copy(headline = null)))
            },
        )
    }

    /** The headline where the owner sent one, else the primary; an island has room for either. */
    private val ActivitySurfaceContent.title: String
        get() = headline ?: primary

    /** Expanded, the measure stands beside the title: "3 min - 250 m". */
    private fun ActivitySurfaceContent.primaryWithMeasure(): String =
        measure?.let { "$title - $it" } ?: title

    /** A title the chip or the flare styles as its own, moving while that form is shown. */
    private fun titleLine(context: Context) = OverflowLineView(context).apply {
        softEdges()
        active = true
    }

    /** A scrolling line fades into the island's black at the side its text runs past, never cut mid-letter. */
    private fun OverflowLineView.softEdges() {
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(context, FADING_EDGE_DP))
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

    private val ROWS = listOf(
        PinSurfacePosition.TOP_LEFT to PinSurfacePosition.TOP_RIGHT,
        PinSurfacePosition.BOTTOM_LEFT to PinSurfacePosition.BOTTOM_RIGHT,
    )

    private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    private const val EDGE_MARGIN_DP = 12
    private const val BAND_WIDTH_FRACTION = 0.92f
    private const val PANEL_WIDTH_FRACTION = 0.78f
    private const val PULSE_DP = 8
    private const val URGENT_OUTLINE_DP = 2
    private const val URGENT_BEAT_DP = 10
    private const val URGENT_BEAT_DELAY_MS = 380L

    /** What a flare may stay past its title's pass as first measured, for a measure that grew. */
    private const val FLARE_GRACE_MS = 2_000L
    private const val GLYPH_DP = 48
    private const val PROGRESS_HEIGHT_DP = 4
    private const val PRIMARY_SP = ACTIVITY_PRIMARY_MAX_SP
    private const val GLYPH_GAP_DP = 12
    private const val ETA_GAP_DP = 8
    private const val FIT_SLACK_DP = 2
    private const val SECONDARY_SP = 13f
    private const val SECONDARY_MAX_LINES = 2
    private const val ETA_SP = 13f
    private const val DETAIL_SP = 11f
    private const val COMPACT_TITLE_SP = 14f
    private const val COMPACT_SECONDARY_SP = 12f
    private const val COMPACT_LINE_GAP_DP = 2
    private const val COMPACT_VERTICAL_PADDING_DP = 6
    private const val COMPACT_GLYPH_GAP_DP = 6
    private const val FADING_EDGE_DP = 14

    /** The pins' medium text width, so a folded activity reads as far as a medium pin. */
    private const val COMPACT_TEXT_WIDTH_FRACTION = 0.60f
}
