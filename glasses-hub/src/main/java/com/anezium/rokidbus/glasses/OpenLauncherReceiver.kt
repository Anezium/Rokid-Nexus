package com.anezium.rokidbus.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anezium.rokidbus.glasses.input.LauncherBackend

class OpenLauncherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = if (NexusInput.backend != LauncherBackend.LEGACY) {
            "ignored: launcher backend is ${NexusInput.backend.name}"
        } else if (LauncherOverlayRenderer.isShown()) {
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

/**
 * Stores the glasses' own launcher backend choice; the phone's explicit one still outranks it
 * (plan 028a §8.3). Exposed like [OpenLauncherReceiver], behind `android.permission.DUMP`, which
 * `adb shell am broadcast` holds and no app does.
 */
class SetLauncherBackendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requested = intent.getStringExtra(EXTRA_BACKEND)
        val backend = LauncherBackend.parse(requested)
        val result = if (backend == null) {
            "ignored: unknown backend=$requested"
        } else {
            NexusInput.setBackend(context, backend)
            "backend=${backend.name}"
        }
        log("Set launcher backend broadcast result: $result")
    }

    private companion object {
        const val EXTRA_BACKEND = "backend"
    }
}
