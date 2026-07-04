package com.anezium.rokidbus.clientprobe

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView

private const val TAG = "ROKIDBUS-CLIENT"

class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "ProbeActivity onCreate")
        setContentView(
            TextView(this).apply {
                text = "RokidBus Client Probe"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 20f
                gravity = Gravity.CENTER
                setBackgroundColor(0xFF000000.toInt())
            },
        )
    }
}
