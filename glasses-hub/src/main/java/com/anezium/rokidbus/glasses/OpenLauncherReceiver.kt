package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class OpenLauncherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = if (LauncherOverlayRenderer.isShown()) {
            LauncherOverlayRenderer.hide()
            "hidden"
        } else if (LauncherOverlayRenderer.show()) {
            "shown"
        } else {
            "show failed: accessibility service not connected"
        }
        log("Open launcher broadcast result: $result")
    }
}
