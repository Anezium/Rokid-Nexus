package com.anezium.rokidbus.glasses

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import com.anezium.rokidbus.client.ui.BusTheme

class SurfaceActivity : Activity() {
    private lateinit var hudView: SurfaceHudView
    private var unsubscribe: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
