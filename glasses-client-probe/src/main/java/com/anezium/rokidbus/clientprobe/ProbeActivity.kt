package com.anezium.rokidbus.clientprobe

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme

private const val TAG = "ROKIDBUS-CLIENT"

class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "ProbeActivity onCreate")
        window.statusBarColor = BusTheme.bg
        window.navigationBarColor = BusTheme.bg
        setContentView(
            TextView(this).apply {
                text = "RokidBus Client Probe"
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(BusTheme.phosphor)
                textSize = 14f
                gravity = Gravity.CENTER
                setBackgroundColor(BusTheme.glassesBg)
            },
        )
    }
}
