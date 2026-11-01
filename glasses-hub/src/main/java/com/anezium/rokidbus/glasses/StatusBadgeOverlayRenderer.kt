package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.NexusGlyphs
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.PhoneBatteryContract

/**
 * The tethered phone's charge in the Rokid launcher's own status row, sitting
 * with the glasses' clock and weather.
 *
 * ## Why it is not a pin
 *
 * A pin is a panel the wearer reads and dismisses; this is an indicator they
 * glance past. It is also *launcher-scoped by design*, which no other HUD tier
 * is: the ROM's own indicators vanish the moment an app opens, and a battery
 * chip that behaved differently would read as ours intruding rather than as
 * one more status icon.
 *
 * ## Coexisting with R08AccessBridge's ring chip
 *
 * The bridge draws its own ring chip into this row, mid-right (fallback slot
 * `[276..375]` measured 2026-07-28), and keeps doing so — overlays are mutually
 * invisible, in `getWindows()` and in the node tree alike, so the two chips
 * coexist by *side*, not by seeing each other. Ours anchors left beside the
 * weather and grows rightward; phone glyph plus a three-digit label peaks
 * ~65px wide, ending near x=182, a ~94px margin from the bridge's slot. A ring
 * migration into this renderer was built and reverted: once the sides diverge,
 * the coupling bought nothing. If this row ever grows more chips, mind that
 * boundary before minding anything else.
 *
 * Placement itself is in [StatusBadgeGeometry] (left-pinned beside the clock
 * and weather, growing rightward — the reasoning for the side is there) and
 * [StatusBadgeReserve] (adapts to the left cluster's observed capacity, never
 * to its moment-to-moment content).
 *
 * ## What is read from the ROM, and why neither read can race
 *
 * One tree walk answers the badge questions and identifies the top package,
 * all from the same node so they cannot disagree.
 *
 * **Is the launcher on top** — the only genuinely live question, and an event is
 * exactly what answers it.
 *
 * **The row's vertical centre**, from `status_power_iv`. Chosen over the
 * status-bar container because the container spans y 306..400 while the icons sit
 * at 365 — its centre is 12px wrong — and over `status_wifi_iv` because the
 * battery is the one indicator always present. A container's position does not
 * change when its contents do, so a late read is not a wrong read.
 *
 * **How far right the clock-and-weather cluster reaches**, folded into
 * [StatusBadgeReserve]. This is the capacity observation, and it is safe for the
 * one structural reason that the reserve never shrinks: it can only push the
 * badges further from the ROM's text. A stale reading leaves them where they
 * are; it can never walk them underneath something.
 *
 * If the ROM renames these ids, the chip disappears rather than guesses: a row
 * it cannot find is a row it must not draw into — the same status bar relocates
 * wholesale between layouts (top third on the home, bottom edge in the
 * teleprompter), so there is no constant that is safe to fall back to.
 */
internal object StatusBadgeOverlayRenderer {

    private const val ROKID_LAUNCHER_PACKAGE = "com.rokid.os.sprite.launcher"
    private const val STATUS_POWER_VIEW_ID = "$ROKID_LAUNCHER_PACKAGE:id/status_power_iv"
    private const val STATUS_TIME_VIEW_ID = "$ROKID_LAUNCHER_PACKAGE:id/status_time_tv"

    /** The left cluster, rightmost element last; absent ones simply do not bound us. */
    private val LEFT_CLUSTER_VIEW_IDS = listOf(
        STATUS_TIME_VIEW_ID,
        "$ROKID_LAUNCHER_PACKAGE:id/status_weather_iv",
        "$ROKID_LAUNCHER_PACKAGE:id/status_weather_tv",
    )

    /**
     * A window smaller than this is not the launcher and cannot hide it — the
     * ROM's double-tap exit banner is the case this exists for.
     */
    private const val FULLSCREEN_COVERAGE_PERCENT = 50

    private val main = Handler(Looper.getMainLooper())

    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var root: PhoneChipView? = null
    private var params: WindowManager.LayoutParams? = null
    private var unsubscribe: (() -> Unit)? = null
    private var launcherOnTop = false
    private var rowCenterY: Int? = null
    private var reserve: StatusBadgeReserve? = null
    private var reservePx = 0
    private var lastSignature: StatusBadgeReserve.Signature? = null
    private val refreshScheduler = StatusBadgeRefreshScheduler(
        postDelayed = { callback, delay -> main.postDelayed(callback, delay); Unit },
        removeCallbacks = { callback -> main.removeCallbacks(callback) },
        refresh = { refresh() },
    )
    private var lastReadDiagnostic: String? = null
    private var refreshCount = 0L

    fun onServiceConnected(service: AccessibilityService) {
        refreshScheduler.cancel()
        lastReadDiagnostic = null
        refreshCount = 0L
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
        launcherOnTop = false
        rowCenterY = null
        reserve = StatusBadgeReserve.forContext(service)
        reservePx = 0
        unsubscribe?.invoke()
        unsubscribe = PhoneBatteryController.observe { refresh() }
        refresh()
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service !== service) return
        unsubscribe?.invoke()
        unsubscribe = null
        refreshScheduler.cancel()
        hide()
        this.service = null
        windowManager = null
        rowCenterY = null
        reserve = null
    }

    /**
     * Re-evaluate after the window stack or the launcher's own content changed.
     *
     * A window event may precede the ROM restoring its status row. Bounded
     * follow-up reads cover that transition even if its final event is missing.
     */
    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val relevant = event == null ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            (
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                    ROKID_LAUNCHER_PACKAGE.contentEquals(event.packageName ?: "")
                )
        if (!relevant) return
        refreshScheduler.onEvent(
            windowChanged = event == null ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )
    }

    private fun refresh() {
        val activeService = service ?: return
        refreshCount++
        val metrics = activeService.resources.displayMetrics
        val read = readLauncher(activeService)
        refreshScheduler.onRead(
            needsRecovery = PhoneBatteryController.reading() != null &&
                (read.launcherOnTop || !read.topWindowReadable) && read.rowCenterY == null,
        )
        val diagnostic = "top=${read.topPackageName} readable=${read.topWindowReadable} " +
            "launcher=${read.launcherOnTop} row=${read.rowCenterY} " +
            "phone=${PhoneBatteryController.reading() != null}"
        if (diagnostic != lastReadDiagnostic) {
            log("status badge read $diagnostic reads=$refreshCount")
            lastReadDiagnostic = diagnostic
        }
        launcherOnTop = read.launcherOnTop
        // Fresh, not kept: the ROM relocates the whole row between layouts
        // (home: top third; teleprompter: bottom edge), so yesterday's centre is
        // another layout's middle of the screen. No row on screen, no chip.
        rowCenterY = read.rowCenterY
        if (read.weatherVisible) {
            // Only the home layout carries weather; launcher app screens move
            // this same row for their own content and are not position samples.
            read.rowCenterY?.let { centerYpx ->
                HudTopInset.onRomRowMeasured(activeService, centerYpx, metrics.density)
            }
        }
        val signature = if (read.weatherVisible) {
            StatusBadgeReserve.Signature.WEATHER
        } else {
            StatusBadgeReserve.Signature.CLOCK_ONLY
        }
        val widened = reserve?.observe(
            signature = signature,
            clusterRightPx = read.clusterRightX,
            screenWidth = metrics.widthPixels,
            density = metrics.density,
        ) ?: 0
        if (signature == lastSignature && widened != reservePx && reservePx != 0) {
            // Rare and permanent: this layout's cluster got wider. Worth a line,
            // because a capacity read that silently went wrong would otherwise
            // only show up as the chip drifting for no reason.
            log("status badge reserve grew ${reservePx}px -> ${widened}px cluster=${read.clusterRightX}")
        }
        lastSignature = signature
        reservePx = widened
        render()
    }

    private fun render() {
        val phone = PhoneBatteryController.reading()
        val centre = rowCenterY
        if (!launcherOnTop || phone == null || centre == null || reservePx <= 0) {
            hide()
            return
        }
        val activeService = service ?: return
        val manager = windowManager
            ?: activeService.getSystemService(WindowManager::class.java)
            ?: return
        val metrics = activeService.resources.displayMetrics
        val origin = StatusBadgeGeometry.originFor(
            reservePx = reservePx,
            rowCentreY = centre,
            density = metrics.density,
        )
        val currentRoot = root ?: PhoneChipView(activeService).also { next ->
            val nextParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                StatusBadgeGeometry.px(StatusBadgeGeometry.ROW_HEIGHT_DP, metrics.density),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Never focusable: a focusable overlay shows up in getWindows()
                // and would defeat every other service's topmost check, which is
                // precisely the failure this renderer relies on not causing.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                // START gravity makes x the inset of the window's *left* edge, so
                // a widening label or an appearing chip grows the window rightward
                // into the row's free middle, and the edge beside the weather —
                // the one the wearer's eye anchors to — never moves.
                gravity = Gravity.TOP or Gravity.START
                x = origin.leftInset
                y = origin.y
            }
            if (runCatching { manager.addView(next, nextParams) }.isFailure) return
            root = next
            params = nextParams
        }
        currentRoot.render(phone)
        params?.let { layout ->
            if (layout.x == origin.leftInset && layout.y == origin.y) return
            layout.x = origin.leftInset
            layout.y = origin.y
            runCatching { manager.updateViewLayout(currentRoot, layout) }
        }
    }

    private fun hide() {
        val currentRoot = root ?: return
        runCatching { windowManager?.removeView(currentRoot) }
        root = null
        params = null
    }

    internal data class LauncherRead(
        val launcherOnTop: Boolean,
        val rowCenterY: Int?,
        val clusterRightX: Int?,
        val weatherVisible: Boolean,
        val topPackageName: String?,
        val topWindowReadable: Boolean,
    )

    private val nothing = LauncherRead(
        launcherOnTop = false,
        rowCenterY = null,
        clusterRightX = null,
        weatherVisible = false,
        topPackageName = null,
        topWindowReadable = false,
    )

    /**
     * One tree walk identifies the top package and answers the badge questions:
     * is the launcher on top, where is its status row, and how far right its
     * clock-and-weather cluster reaches.
     * They come from the same node, so they can never disagree with each other.
     *
     * No [AccessibilityNodeInfo] escapes this function — everything it opens is
     * released before it returns, and callers get plain values.
     */
    internal fun readLauncher(service: AccessibilityService): LauncherRead {
        val windows = runCatching { service.windows }.getOrNull() ?: return nothing
        val metrics = service.resources.displayMetrics
        val screenArea = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
        if (screenArea <= 0L) return nothing

        var topWindow: AccessibilityWindowInfo? = null
        var topLayer = Int.MIN_VALUE
        windows.forEach { window ->
            if (window == null) return@forEach
            val bounds = Rect()
            runCatching { window.getBoundsInScreen(bounds) }.getOrElse { return@forEach }
            val area = bounds.width().toLong() * bounds.height().toLong()
            if (area * 100L < screenArea * FULLSCREEN_COVERAGE_PERCENT) return@forEach
            if (window.layer > topLayer) {
                topLayer = window.layer
                topWindow = window
            }
        }

        val node = runCatching { topWindow?.root }.getOrNull() ?: return nothing
        return try {
            val topPackageName = node.packageName?.toString()?.takeIf(String::isNotBlank)
                ?: return nothing
            if (topPackageName != ROKID_LAUNCHER_PACKAGE) {
                return LauncherRead(
                    launcherOnTop = false,
                    rowCenterY = null,
                    clusterRightX = null,
                    weatherVisible = false,
                    topPackageName = topPackageName,
                    topWindowReadable = true,
                )
            }
            // The rightmost visible edge of the left cluster. Known view ids, no
            // tree walk and no tolerance constants — unlike the right-hand radio
            // cluster, the left one is a fixed set of named views, which is part
            // of why the chip lives on this side. Weather visibility doubles as
            // the layout signature: the home shows it, in-launcher app screens
            // (teleprompter, subtitles) drop it and fill the centre lane.
            var clusterRight: Int? = null
            var weatherVisible = false
            LEFT_CLUSTER_VIEW_IDS.forEach { viewId ->
                val bounds = boundsOf(node, viewId) ?: return@forEach
                clusterRight = maxOf(clusterRight ?: bounds.right, bounds.right)
                if (viewId != STATUS_TIME_VIEW_ID) weatherVisible = true
            }
            LauncherRead(
                launcherOnTop = true,
                rowCenterY = boundsOf(node, STATUS_POWER_VIEW_ID)?.centerY(),
                clusterRightX = clusterRight,
                weatherVisible = weatherVisible,
                topPackageName = topPackageName,
                topWindowReadable = true,
            )
        } finally {
            recycle(node)
        }
    }

    private fun boundsOf(root: AccessibilityNodeInfo, viewId: String): Rect? {
        val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }
            .getOrNull()?.filterNotNull().orEmpty()
        return try {
            nodes.firstNotNullOfOrNull(::visibleBounds)
        } finally {
            nodes.forEach(::recycle)
        }
    }

    private fun visibleBounds(node: AccessibilityNodeInfo): Rect? = runCatching {
        if (!node.isVisibleToUser) return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) null else bounds
    }.getOrNull()

    private fun recycle(node: AccessibilityNodeInfo?) {
        // Stale nodes throw rather than release; the ROM swaps status-row
        // descendants out from under us while it is updating them.
        runCatching { node?.recycle() }
    }

    /**
     * Chip metrics match the ROM's own row — an 18px glyph beside its 20px
     * icons, regular-weight text like its clock and temperature. Bold was tried
     * and visibly out-weighed the row this chip is supposed to disappear into.
     */
    private class PhoneChipView(context: Context) : LinearLayout(context) {
        private val label = TextView(context).apply {
            setTextColor(NexusUi.GREEN)
            textSize = StatusBadgeGeometry.LABEL_SP
            includeFontPadding = false
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val density = resources.displayMetrics.density
            val size = StatusBadgeGeometry.px(StatusBadgeGeometry.GLYPH_SIZE_DP, density)
            val glyph = ImageView(context).apply {
                setImageResource(NexusGlyphs.drawableFor("phone"))
                imageTintList = ColorStateList.valueOf(NexusUi.GREEN)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(glyph, LayoutParams(size, size))
            addView(
                label,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                    marginStart = StatusBadgeGeometry.px(StatusBadgeGeometry.GLYPH_GAP_DP, density)
                },
            )
        }

        fun render(reading: PhoneBatteryContract.Reading) {
            label.text = PhoneBatteryContract.label(reading)
        }
    }
}
