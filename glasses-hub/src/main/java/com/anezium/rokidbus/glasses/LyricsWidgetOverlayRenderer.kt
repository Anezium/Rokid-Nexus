package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.WidgetAnchor
import com.anezium.rokidbus.shared.WidgetTimedLine
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The ambient lyrics widget: two lines — current lyric prominent, next dimmed — drawn over the
 * ROM launcher's own home row. It is an overlay like a pin but ambient: it never takes the
 * foreground-surface slot, never wakes or holds the display by itself, and is hidden whenever a
 * full-screen surface or the launcher overlay is up (the same visibility gating
 * [StatusBadgeOverlayRenderer] uses for foreign fullscreen windows is applied here through the
 * hide gate).
 *
 * Geometry: the chip is at most 60% of the 480 px screen, horizontally centered, sitting above
 * the ROM home row (calibrated row centre 364 px via [HudTopInset]).
 */
internal object LyricsWidgetOverlayRenderer {

    /** Max width as a fraction of screen width (60% of the 480 px frame). */
    private const val MAX_WIDTH_FRACTION = 0.60f
    private const val CURRENT_LINE_SP = 15f
    private const val NEXT_LINE_SP = 11.5f
    private const val VERTICAL_STACK_DP = 26

    private val main = Handler(Looper.getMainLooper())

    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var root: WidgetView? = null
    private var params: WindowManager.LayoutParams? = null
    private var unsubscribe: (() -> Unit)? = null
    private var insetUnsubscribe: (() -> Unit)? = null
    private var hudTopInsetDp = 0

    /** Whether the launcher or a full-screen surface currently owns the display. */
    @Volatile private var hiddenByForeground = false

    private val tick = object : Runnable {
        override fun run() {
            if (WidgetStateMachine.clock != null) {
                if (isOccluded()) {
                    hide()
                } else {
                    render()
                    main.postDelayed(this, TICK_MS)
                }
            }
        }
    }

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
        LyricsWidgetDisplayHold.setContext(service)
        insetUnsubscribe?.invoke()
        insetUnsubscribe = HudTopInset.observe(service, ::applyHudTopInset)
        unsubscribe?.invoke()
        unsubscribe = WidgetStateMachine.observe(::onWidgetChanged)
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service !== service) return
        unsubscribe?.invoke()
        unsubscribe = null
        insetUnsubscribe?.invoke()
        insetUnsubscribe = null
        main.removeCallbacks(tick)
        LyricsWidgetDisplayHold.forceStop()
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
        val clock = WidgetStateMachine.clock
        if (clock == null || isOccluded()) {
            hide()
            LyricsWidgetDisplayHold.update(false, null, null)
            return
        }
        LyricsWidgetDisplayHold.update(true, clock.currentAnchor, WidgetStateMachine.currentContentKey())
        render()
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
    }

    /** Widget hides behind any full-screen surface or the launcher overlay, like StatusBadge. */
    private fun isOccluded(): Boolean =
        hiddenByForeground ||
            LauncherOverlayRenderer.isShown() ||
            SurfaceController.activeSurface() != null

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

        val currentRoot = root ?: WidgetView(activeService).also { nextView ->
            val nextParams = baseParams(activeService)
            if (runCatching { manager.addView(nextView, nextParams) }.isFailure) return
            root = nextView
            params = nextParams
        }
        currentRoot.render(current, next, metrics.density)
        params?.let { layout ->
            applyGeometry(layout, activeService)
            runCatching { manager.updateViewLayout(currentRoot, layout) }
        }
    }

    private fun hide() {
        main.removeCallbacks(tick)
        LyricsWidgetDisplayHold.update(false, null, null)
        val currentRoot = root
        if (currentRoot != null) {
            runCatching { windowManager?.removeView(currentRoot) }
            currentRoot.render(null, null, 1f)
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
        val metrics = activeService.resources.displayMetrics
        val maxWidthPx = (metrics.widthPixels * MAX_WIDTH_FRACTION).roundToInt()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            width = min(WindowManager.LayoutParams.WRAP_CONTENT, maxWidthPx)
        }
    }

    private fun applyGeometry(layout: WindowManager.LayoutParams, activeService: AccessibilityService) {
        layout.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // Above the ROM home row (row centre 364 px), on top of the user's own vertical inset.
        val density = activeService.resources.displayMetrics.density
        val stackPx = (VERTICAL_STACK_DP * density).roundToInt().coerceAtLeast(0)
        layout.y = HUD_ROW_CENTER_Y_PX.roundToInt() - stackPx + BusTheme.dp(activeService, hudTopInsetDp)
        layout.x = 0
    }

    private class WidgetView(context: Context) : LinearLayout(context) {
        private val currentLine = line(CURRENT_LINE_SP, bold = true)
        private val nextLine = line(NEXT_LINE_SP, bold = false)

        init {
            orientation = VERTICAL
            setBackgroundColor(BusTheme.glassesBg)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(
                currentLine,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
            addView(
                nextLine,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }

        fun render(current: WidgetTimedLine?, next: WidgetTimedLine?, density: Float) {
            currentLine.text = current?.text ?: ""
            nextLine.text = next?.text ?: ""
            nextLine.visibility = if (next == null) View.GONE else View.VISIBLE
        }

        private fun line(sp: Float, bold: Boolean): TextView = TextView(context).apply {
            setTextColor(BusTheme.phosphor)
            textSize = sp
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            includeFontPadding = false
            isSingleLine = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private const val HUD_ROW_CENTER_Y_PX = 364f
    private const val TICK_MS = 200L
}