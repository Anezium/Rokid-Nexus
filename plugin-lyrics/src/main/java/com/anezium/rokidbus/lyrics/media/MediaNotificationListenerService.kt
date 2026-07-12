package com.anezium.rokidbus.lyrics.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.service.notification.NotificationListenerService
import com.anezium.rokidbus.lyrics.LyricsRuntimeGraph
import com.anezium.rokidbus.plugin.lyrics.LyricsPluginService

class MediaNotificationListenerService : NotificationListenerService() {
    private var pluginBound = false
    private val pluginConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit

        override fun onServiceDisconnected(name: ComponentName?) {
            pluginBound = false
        }

        override fun onBindingDied(name: ComponentName?) {
            pluginBound = false
        }

        override fun onNullBinding(name: ComponentName?) {
            pluginBound = false
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        LyricsRuntimeGraph.start(this)
        if (!pluginBound) {
            pluginBound = runCatching {
                bindService(
                    Intent(this, LyricsPluginService::class.java),
                    pluginConnection,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
        }
    }

    override fun onListenerDisconnected() {
        LyricsRuntimeGraph.refresh()
        releasePluginBinding()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        releasePluginBinding()
        super.onDestroy()
    }

    private fun releasePluginBinding() {
        if (!pluginBound) return
        runCatching { unbindService(pluginConnection) }
        pluginBound = false
    }
}
