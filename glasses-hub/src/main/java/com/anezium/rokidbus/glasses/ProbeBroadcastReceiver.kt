package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anezium.rokidbus.shared.BusPaths

class ProbeBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val probe = intent.getStringExtra("probe").orEmpty()
        val result = when (probe) {
            "server", "hub" -> {
                GlassesHub.start(context.applicationContext)
                "hubStartRequested=true"
            }
            "wake-echo" -> GlassesHub.debugWake(context.applicationContext, BusPaths.PROBE_ECHO)
            "wake-http" -> GlassesHub.debugWake(context.applicationContext, BusPaths.PROBE_HTTP)
            "phone-wake-echo" -> GlassesHub.debugPhoneWakeEcho(context.applicationContext)
            "surface-activity" -> SurfaceController.showDemoCard(
                context.applicationContext,
                SurfaceDisplayPath.ACTIVITY,
            )
            "surface-overlay" -> SurfaceController.showDemoCard(
                context.applicationContext,
                SurfaceDisplayPath.OVERLAY,
            )
            "launcher-open" -> GlassesHub.openLauncherEntry(intent.getStringExtra("pluginId").orEmpty())
            "state" -> "spp=${SppServerManager.isConnected()} cxr=${CxrBusBridge.isUp()}"
            else -> "unknown probe '$probe'; use hub, state, wake-echo, wake-http, phone-wake-echo, launcher-open, surface-activity, or surface-overlay"
        }
        log("Broadcast probe result: $result")
    }
}
