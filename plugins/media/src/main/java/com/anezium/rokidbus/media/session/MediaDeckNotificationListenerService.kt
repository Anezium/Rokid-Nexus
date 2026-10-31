package com.anezium.rokidbus.media.session

import android.service.notification.NotificationListenerService
import com.anezium.rokidbus.media.MediaDeckActivityControl

class MediaDeckNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaDeckAccessSignal.notifyChanged()
        // The system binds this listener again after a restart: an enabled activity resumes here.
        MediaDeckActivityControl.resume(this)
    }

    override fun onListenerDisconnected() {
        MediaDeckAccessSignal.notifyChanged()
        super.onListenerDisconnected()
    }
}
