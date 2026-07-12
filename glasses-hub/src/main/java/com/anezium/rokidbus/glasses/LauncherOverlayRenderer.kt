package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Layout
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme

object LauncherOverlayRenderer {
    private const val KEYCODE_PROG_BLUE = 186

    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var root: LauncherOverlayRoot? = null
    private var unsubscribeLauncher: (() -> Unit)? = null
    private var launcherEntries: List<GlassesHub.LauncherEntry> = emptyList()
    private var selectedIndex = 0
    private val swipeDedupe = DpadPairDedupe()

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service === service) {
            hide()
            this.service = null
            windowManager = null
        }
    }

    fun isShown(): Boolean = root != null

    fun show(): Boolean {
        val activeService = service ?: return false
        return show(activeService)
    }

    fun show(context: Context): Boolean {
        val activeService = service ?: return false
        launcherReturnCoordinator.clearPendingLauncherOpen()
        GlassesHub.start(activeService.applicationContext)
        val manager = windowManager ?: activeService.getSystemService(WindowManager::class.java) ?: return false
        val currentRoot = root ?: LauncherOverlayRoot(activeService).also { next ->
            root = next
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT,
            )
            manager.addView(next, params)
        }
        if (unsubscribeLauncher == null) {
            unsubscribeLauncher = GlassesHub.observeLauncher { entries ->
                launcherEntries = entries
                selectedIndex = selectedIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
                root?.render(launcherEntries, selectedIndex)
            }
        }
        currentRoot.render(launcherEntries, selectedIndex)
        currentRoot.requestFocus()
        log("Launcher overlay opened")
        return true
    }

    fun hide() {
        unsubscribeLauncher?.invoke()
        unsubscribeLauncher = null
        val manager = windowManager
        val currentRoot = root ?: return
        runCatching { manager?.removeView(currentRoot) }
        currentRoot.render(emptyList(), 0)
        root = null
        log("Launcher overlay closed")
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (root == null) return false
        if (event.keyCode == KEYCODE_PROG_BLUE) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
            return true
        }

        when (swipeDedupe.onKey(event.keyCode, event.action, event.repeatCount, event.eventTime)) {
            DpadPairDedupe.Direction.FORWARD -> {
                moveSelection(1)
                return true
            }
            DpadPairDedupe.Direction.BACKWARD -> {
                moveSelection(-1)
                return true
            }
            null -> Unit
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> {
                openSelected()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                hide()
                true
            }
            else -> true
        }
    }

    private fun moveSelection(delta: Int) {
        if (launcherEntries.isEmpty()) return
        selectedIndex = (selectedIndex + delta + launcherEntries.size) % launcherEntries.size
        root?.render(launcherEntries, selectedIndex)
    }

    private fun openSelected() {
        val entry = launcherEntries.getOrNull(selectedIndex) ?: return
        val result = GlassesHub.openLauncherEntry(entry.id)
        log("Launcher overlay open result: $result")
        if (result.startsWith("launcherOpen=true")) {
            launcherReturnCoordinator.recordLauncherOpen(entry.id)
            hide()
        }
    }

    private class LauncherOverlayRoot(context: Context) : FrameLayout(context) {
        private val menu = LauncherMenuView(context)

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            addView(menu, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        fun render(entries: List<GlassesHub.LauncherEntry>, selectedIndex: Int) {
            menu.render(entries, selectedIndex)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (LauncherOverlayRenderer.handleKeyEvent(event)) return true
            return super.dispatchKeyEvent(event)
        }
    }

    private class LauncherMenuView(context: Context) : LinearLayout(context) {
        private val countView = monoText(10.5f, BusTheme.dim)
        private val listView = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        private val emptyView = monoText(17f, BusTheme.dim).apply {
            text = "No phone plugins synced"
            gravity = Gravity.CENTER
        }
        private val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(
                listView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.TOP
            setBackgroundColor(BusTheme.glassesBg)
            setPadding(dp(18), dp(16), dp(18), dp(12))

            addView(monoText(12f, BusTheme.phosphor, bold = true).apply {
                text = "ROKID NEXUS"
                gravity = Gravity.CENTER_HORIZONTAL
            }, matchWrap())
            addView(gap(14))
            addView(monoText(24f, BusTheme.text, bold = true).apply {
                text = "Launcher"
                gravity = Gravity.CENTER_HORIZONTAL
            }, matchWrap())
            addView(gap(8))
            addView(countView.apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }, matchWrap())
            addView(gap(18))
            addView(monoText(10.5f, BusTheme.dim).apply {
                text = "PLUGINS"
                gravity = Gravity.CENTER_HORIZONTAL
            }, matchWrap())
            addView(gap(10))
            addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        fun render(entries: List<GlassesHub.LauncherEntry>, selectedIndex: Int) {
            listView.removeAllViews()
            if (entries.isEmpty()) {
                countView.text = "Waiting for phone"
                listView.addView(
                    emptyView,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, scroll.height.coerceAtLeast(dp(260))),
                )
                return
            }

            countView.text = "${selectedIndex + 1}/${entries.size}"
            var selectedRow: View? = null
            entries.forEachIndexed { index, entry ->
                val row = pluginRow(entry, selected = index == selectedIndex)
                if (index == selectedIndex) selectedRow = row
                listView.addView(row, matchWrap().apply {
                    topMargin = if (index == 0) 0 else dp(8)
                })
            }
            selectedRow?.let { row ->
                row.post {
                    row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true)
                }
            }
        }

        private fun pluginRow(
            entry: GlassesHub.LauncherEntry,
            selected: Boolean,
        ): TextView =
            monoText(18f, if (selected) BusTheme.phosphor else BusTheme.text, bold = selected).apply {
                text = if (selected) "> ${entry.displayName}" else "  ${entry.displayName}"
                minHeight = dp(52)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(8), 0)
                background = outline(selected)
            }

        private fun monoText(sizeSp: Float, color: Int, bold: Boolean = false): TextView =
            TextView(context).apply {
                textSize = sizeSp
                setTextColor(color)
                typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
                includeFontPadding = false
                isSingleLine = false
                setHorizontallyScrolling(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
                    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                }
            }

        private fun outline(selected: Boolean): GradientDrawable =
            GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp(if (selected) 2 else 1), if (selected) BusTheme.phosphor else BusTheme.hairline)
                cornerRadius = dp(4).toFloat()
            }

        private fun gap(value: Int): View =
            View(context).apply {
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(value))
            }

        private fun matchWrap(): LayoutParams =
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        private fun dp(value: Int): Int =
            BusTheme.dp(context, value)
    }
}
