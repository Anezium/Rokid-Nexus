package com.anezium.rokidbus.glasses

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.shared.BusConstants

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BusTheme.bg
        window.navigationBarColor = BusTheme.bg
        val status = TextView(this).apply {
            text = "Rokid Nexus Glasses Hub\nSPP: ${BusConstants.SERVICE_NAME}\nUUID: ${BusConstants.SPP_UUID_STRING}\nEnable accessibility service to run headless."
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(BusTheme.phosphor)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(
                BusTheme.dp(this@MainActivity, 24),
                BusTheme.dp(this@MainActivity, 24),
                BusTheme.dp(this@MainActivity, 24),
                BusTheme.dp(this@MainActivity, 24),
            )
            setBackgroundColor(BusTheme.glassesBg)
        }
        setContentView(status)
        requestBluetoothConnectIfNeeded()
        GlassesHub.start(applicationContext)
        log("Launcher activity opened")
    }

    private fun requestBluetoothConnectIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 10)
        }
    }
}
