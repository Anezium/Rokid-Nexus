package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.WidgetTimedLine
import com.anezium.rokidbus.shared.WidgetAnchor
import kotlin.math.roundToInt

/** Two ambient lyric lines. The hub owns visibility; this window never claims input. */
internal object LyricsWidgetOverlayRenderer {

    /** Max card width as a fraction of the 480 px frame. */
    private const val MAX_WIDTH_FRACTION = 0.76f
    private const val CURRENT_LINE_SP = 16f
    private const val NEXT_LINE_SP = 11.5f
    private const val NEXT_LINE_ALPHA = 0.55f
    private const val CARD_CORNER_DP = 7
    private const val CARD_PADDING_H_DP = 14
    private const val CARD_PADDING_V_DP = 9
    private const val LINE_GAP_DP = 3

    private val main = Handler(Looper.getMainLooper())

    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var root: WidgetView? = null
    private var params: WindowManager.LayoutParams? = null
    private var unsubscribe: (() -> Unit)? = null
    private var insetUnsubscribe: (() -> Unit)? = null
    private var surfaceUnsubscribe: (() -> Unit)? = null
    private var noticeUnsubscribe: (() -> Unit)? = null
    private var holdAnchor: WidgetAnchor? = null
    private var holdKey: String? = null
    private var holdMode = false
    private var cameraActive = false
    private var foreignFullscreen = true
    private var hudTopInsetDp = 0

    /** Whether the launcher or a full-screen surface currently owns the display. */
    @Volatile private var hiddenByForeground = false

    private val tick = object : Runnable {
        override fun run() {
            onWidgetChangedOnMain()
        }
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            refreshForeignWindow()
            onWidgetChanged()
        }
    }

    fun onContextChanged() = onWidgetChanged()

    fun setCameraActive(active: Boolean) {
        cameraActive = active
        onWidgetChanged()
    }

    private fun refreshForeignWindow() {
        val activeService = service ?: return
        val metrics = activeService.resources.displayMetrics
        val screenArea = metrics.widthPixels.toLong() * metrics.heightPixels
        foreignFullscreen = runCatching {
            val windows = activeService.windows ?: return@runCatching true
            val full = windows.filter { window ->
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    bounds.width().toLong() * bounds.height() * 2 >= screenArea
            }.maxByOrNull { it.layer }
            if (full == null) return@runCatching false
            val root = full.root ?: return@runCatching true
            try {
                val pkg = root.packageName?.toString()
                pkg == null || (pkg != activeService.packageName && pkg != "com.rokid.os.sprite.launcher")
            } finally {
                root.recycle()
            }
        }.getOrDefault(true)
    }

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
        LyricsWidgetDisplayHold.setContext(service)
        insetUnsubscribe?.invoke()
        insetUnsubscribe = HudTopInset.observe(service, ::applyHudTopInset)
        unsubscribe?.invoke()
        unsubscribe = WidgetStateMachine.observe(::onWidgetChanged)
        surfaceUnsubscribe?.invoke()
        surfaceUnsubscribe = SurfaceController.observe { onWidgetChanged() }
        noticeUnsubscribe?.invoke()
        noticeUnsubscribe = NoticeController.observe { onWidgetChanged() }
        refreshForeignWindow()
        onWidgetChanged()
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service !== service) return
        unsubscribe?.invoke()
        unsubscribe = null
        surfaceUnsubscribe?.invoke()
        surfaceUnsubscribe = null
        noticeUnsubscribe?.invoke()
        noticeUnsubscribe = null
        insetUnsubscribe?.invoke()
        insetUnsubscribe = null
        main.removeCallbacks(tick)
        LyricsWidgetDisplayHold.forceStop()
        LyricsWidgetDisplayHold.setContext(null)
        hide()
        this.service = null
        windowManager = null
        hiddenByForeground = false
    }

    /** Re-add above anything just pushed so the widget stays below pins/notices/activities. */
    fun ensureOnTop() {
        val manager = windowManager ?: return
        val currentRoot = root ?: return
        val currentParams = params ?: return
        runCatching {
            manager.removeView(currentRoot)
            manager.addView(currentRoot, currentParams)
        }.onFailure { logError("Lyrics widget z-order refresh failed", it) }
    }

    /** Ambient widget must hide whenever a full-screen surface or the launcher is showing. */
    fun setForegroundHidden(hidden: Boolean) {
        main.post { setForegroundHiddenOnMain(hidden) }
    }

    private fun setForegroundHiddenOnMain(hidden: Boolean) {
        if (hiddenByForeground == hidden) return
        hiddenByForeground = hidden
        onWidgetChangedOnMain()
    }

    private fun onWidgetChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onWidgetChangedOnMain()
        } else {
            main.post { onWidgetChangedOnMain() }
        }
    }

    private fun onWidgetChangedOnMain() {
        main.removeCallbacks(tick)
        val clock = WidgetStateMachine.clock
        if (clock == null || isOccluded()) {
            hide()
        } else {
            render()
            val key = WidgetStateMachine.currentContentKey()
            if (root != null && (holdAnchor != clock.currentAnchor || holdKey != key ||
                    holdMode != WidgetStateMachine.holdDisplay)) {
                holdAnchor = clock.currentAnchor
                holdKey = key
                holdMode = WidgetStateMachine.holdDisplay
                LyricsWidgetDisplayHold.update(true, holdAnchor, key)
            }
        }
        // Keep eligibility live during a temporary interruption, including Assistant episodes.
        if (clock != null && service != null) main.postDelayed(tick, TICK_MS)
    }

    private fun isOccluded(): Boolean = !WidgetEligibility.visible(
        hasContent = WidgetStateMachine.clock != null,
        foregroundSurface = hiddenByForeground || SurfaceController.activeSurface() != null ||
            MainActivity.isInteractiveFlowActive(),
        launcher = LauncherOverlayRenderer.isShown(),
        notice = NoticeController.visibleNotice() != null,
        camera = cameraActive,
        assistant = AssistantDisplayEpisode.isActive(),
        foreignFullscreen = foreignFullscreen,
        stale = SystemClock.elapsedRealtime() - WidgetStateMachine.receivedAt >= 120_000L,
    )

    private fun render() {
        val clock = WidgetStateMachine.clock ?: return hide()
        val activeService = service ?: return
        val manager = windowManager
            ?: activeService.getSystemService(WindowManager::class.java)
            ?: return
        val metrics = activeService.resources.displayMetrics
        val now = SystemClock.elapsedRealtime()
        val currentIndex = clock.currentIndexAt(now)
        val current = clock.line(currentIndex) ?: return hide()
        val nextIndex = clock.nextIndexFrom(currentIndex)
        val next = nextIndex?.let(clock::line)

        val isNewRoot = root == null
        val currentRoot = root ?: WidgetView(activeService)
        val maxWidthPx = (metrics.widthPixels * MAX_WIDTH_FRACTION).roundToInt()
        currentRoot.applyMaxWidth(maxWidthPx)
        currentRoot.render(current, next)
        val layout = params ?: baseParams(activeService)
        applyGeometry(layout, activeService)
        if (isNewRoot) {
            if (runCatching { manager.addView(currentRoot, layout) }.isFailure) return
            root = currentRoot
            params = layout
        }
    }

    private fun hide() {
        holdAnchor = null
        holdKey = null
        main.removeCallbacks(tick)
        LyricsWidgetDisplayHold.update(false, null, null)
        val currentRoot = root
        if (currentRoot != null) {
            runCatching { windowManager?.removeView(currentRoot) }
            currentRoot.render(null, null)
        }
        root = null
        params = null
    }

    private fun applyHudTopInset(value: Int) {
        hudTopInsetDp = HudTopInset.sanitize(value)
        val activeService = service ?: return
        val currentRoot = root ?: return
        val currentParams = params ?: return
        applyGeometry(currentParams, activeService)
        runCatching { windowManager?.updateViewLayout(currentRoot, currentParams) }
    }

    private fun baseParams(activeService: AccessibilityService): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
    }

    private fun applyGeometry(
        layout: WindowManager.LayoutParams,
        activeService: AccessibilityService,
    ) {
        layout.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // Same top anchor as the notice band: a notice that fires passes in front of
        // the card instead of stacking somewhere else. The card grows DOWNWARD into
        // empty screen, so a 1 -> 2 line wrap never moves the card's top edge.
        layout.y = HudBandGeometry.topPx(activeService, hudTopInsetDp)
        layout.x = 0
    }

    private class WidgetView(context: Context) : LinearLayout(context) {
        private val currentLine = line(CURRENT_LINE_SP, bold = true).apply {
            isSingleLine = true
            maxLines = 1
        }
        private val nextLine = line(NEXT_LINE_SP, bold = false).apply {
            isSingleLine = true
            maxLines = 1
            alpha = NEXT_LINE_ALPHA
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                // Pure black fill reads as transparent on the additive optic; the card
                // is its dim phosphor outline, matching the ROM's own pill styling.
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(BusTheme.dp(context, 1), BusTheme.dim)
                cornerRadius = BusTheme.dp(context, CARD_CORNER_DP).toFloat()
            }
            setPadding(
                BusTheme.dp(context, CARD_PADDING_H_DP),
                BusTheme.dp(context, CARD_PADDING_V_DP),
                BusTheme.dp(context, CARD_PADDING_H_DP),
                BusTheme.dp(context, CARD_PADDING_V_DP),
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(
                currentLine,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
            addView(
                nextLine,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = BusTheme.dp(context, LINE_GAP_DP)
                },
            )
        }

        fun applyMaxWidth(maxCardWidthPx: Int) {
            val textMax = (maxCardWidthPx - 2 * BusTheme.dp(context, CARD_PADDING_H_DP))
                .coerceAtLeast(1)
            currentLine.maxWidth = textMax
            nextLine.maxWidth = textMax
        }

        fun render(current: WidgetTimedLine?, next: WidgetTimedLine?) {
            val currentText = current?.text.orEmpty()
            val nextText = next?.text.orEmpty()
            if (currentLine.text.toString() != currentText) currentLine.text = currentText
            if (nextLine.text.toString() != nextText) nextLine.text = nextText
            nextLine.visibility = if (next == null) View.GONE else View.VISIBLE
        }

        private fun line(sp: Float, bold: Boolean): TextView = TextView(context).apply {
            setTextColor(BusTheme.phosphor)
            textSize = sp
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            includeFontPadding = false
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private const val TICK_MS = 200L
}
