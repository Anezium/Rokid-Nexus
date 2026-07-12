package com.anezium.rokidbus.media.session

import android.service.notification.NotificationListenerService

class MediaDeckNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaDeckAccessSignal.notifyChanged()
    }

    override fun onListenerDisconnected() {
        MediaDeckAccessSignal.notifyChanged()
        super.onListenerDisconnected()
    }
}
