package com.anezium.rokidbus.glasses

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import com.anezium.rokidbus.client.ui.BusTheme

class SurfaceActivity : Activity() {
    private lateinit var hudView: SurfaceHudView
    private var unsubscribe: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BusTheme.glassesBg
        window.navigationBarColor = BusTheme.glassesBg
        hudView = SurfaceHudView(this)
        setContentView(hudView)
        unsubscribe = SurfaceController.observe { surface ->
            hudView.render(surface)
            if (surface == null && !isFinishing) finish()
        }
    }

    override fun onDestroy() {
        unsubscribe?.invoke()
        unsubscribe = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (SurfaceController.handleKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
