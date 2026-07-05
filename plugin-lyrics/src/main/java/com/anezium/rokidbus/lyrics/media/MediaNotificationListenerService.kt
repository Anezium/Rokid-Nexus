package com.anezium.rokidbus.lyrics.media

import android.service.notification.NotificationListenerService
import com.anezium.rokidbus.lyrics.LyricsRuntimeGraph

class MediaNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        LyricsRuntimeGraph.start(this)
    }

    override fun onListenerDisconnected() {
        LyricsRuntimeGraph.refresh()
        super.onListenerDisconnected()
    }
}
