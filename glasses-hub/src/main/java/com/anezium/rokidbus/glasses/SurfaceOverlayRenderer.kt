package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout

object SurfaceOverlayRenderer {
    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null
    private var root: OverlayRoot? = null

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        windowManager = service.getSystemService(WindowManager::class.java)
        SurfaceController.activeSurface()?.let { active ->
            if (SurfaceController.displayPath(service) == SurfaceDisplayPath.OVERLAY) {
                show(service, active)
            }
        }
    }

    fun onServiceDestroyed(service: AccessibilityService) {
        if (this.service === service) {
            hide()
            this.service = null
            windowManager = null
        }
    }

    fun show(context: Context, surface: NexusSurface): Boolean {
        val activeService = service ?: return false
        val manager = windowManager ?: activeService.getSystemService(WindowManager::class.java) ?: return false
        val currentRoot = root ?: OverlayRoot(activeService).also { next ->
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
        currentRoot.render(surface)
        currentRoot.requestFocus()
        return true
    }

    fun hide() {
        val manager = windowManager
        val currentRoot = root ?: return
        runCatching { manager?.removeView(currentRoot) }
        currentRoot.render(null)
        root = null
    }

    private class OverlayRoot(context: Context) : FrameLayout(context) {
        private val hud = SurfaceHudView(context)

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            addView(
                hud,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }

        fun render(surface: NexusSurface?) {
            hud.render(surface)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (SurfaceController.handleKeyEvent(event)) return true
            return super.dispatchKeyEvent(event)
        }
    }
}
