package com.anezium.rokidbus.glasses

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import com.anezium.rokidbus.shared.BusConstants

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = "RokidBus Glasses Hub\nSPP: ${BusConstants.SERVICE_NAME}\nUUID: ${BusConstants.SPP_UUID_STRING}\nEnable accessibility service to run headless."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF000000.toInt())
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
