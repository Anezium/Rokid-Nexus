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
            if (surface?.isInk == true) {
                hudView.render(null)
                if (!isFinishing) finishAndRemoveTask()
                return@observe
            }
            hudView.render(surface)
            // finishAndRemoveTask, not finish: a plain finish reveals whatever
            // this activity's own task happened to have underneath it (seen on
            // hardware: the Nexus launcher, left behind from earlier
            // navigation) instead of returning to what the wearer was actually
            // looking at before this surface interrupted it.
            if (surface == null && !isFinishing) finishAndRemoveTask()
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
