package com.anezium.rokidbus.plugin.relay

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class RelayNotificationListener : NotificationListenerService() {
    private val runtime by lazy { RelayNoticeRuntime(applicationContext) }

    /** Where the plugin service forwards the notice's callbacks. */
    internal val noticeCallbacks: RelayNoticeRuntime
        get() = runtime

    override fun onCreate() {
        super.onCreate()
        NotificationControl.instanceCreated(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationControl.attach(this)
        RelayDiagnostics.recordListenerConnected(this)
        RelayGuardianService.requestImmediateHealthEvaluation()
        rebuildFromActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        RelayDiagnostics.recordRawNotificationPosted(this)
        sbn ?: return
        ingest(sbn, mayShow = true)
    }

    /**
     * The notification left the phone, for whatever reason: answered or read there, swiped, or
     * cleared by Relay itself after a reply. That ends its reply authority, and nothing more is
     * concluded from it. The band stops offering to send, the glasses are told the event is gone,
     * and a visible thread page follows with its reply withdrawn but its text still readable.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val id = ReplyRepository.stableId(sbn.key)
        if (ReplyRepository.forget(sbn)) runtime.onRemoved(id)
        // Answering on the phone empties the row here too, while the wearer is
        // looking straight at it.
        NotificationControl.notifyCaptured(id)
    }

    /**
     * Losing the grant drops what the grant bought.
     *
     * Detaching alone left captured message text and live reply tokens in
     * memory, usable for as long as the inbox kept the process alive — so a
     * wearer could revoke Notification Access and still be shown the message,
     * and still send through the retained PendingIntent. The authority is gone;
     * so is everything it authorised.
     */
    override fun onListenerDisconnected() {
        RelayDiagnostics.recordListenerDisconnected(this)
        val component = ComponentName(this, RelayNotificationListener::class.java)
        val accessGranted = accessGranted(component)
        // Access revoked: the glasses forget what they were shown, too. A mere rebind keeps it.
        if (!accessGranted) runtime.onForgotten(null)
        ReplyRepository.clear()
        NotificationControl.notifyInboxChanged()
        NotificationControl.detach(this)
        runtime.shutdown()
        Log.w(TAG, "notification listener disconnected")
        super.onListenerDisconnected()
        if (!RelayGuardianService.requestImmediateHealthEvaluation()) {
            RelayDiagnostics.recordAccessState(this, accessGranted)
            if (accessGranted) requestRebind(component)
        }
    }

    private fun accessGranted(component: ComponentName): Boolean = try {
        getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component) == true
    } catch (_: RuntimeException) {
        false
    }

    override fun onDestroy() {
        NotificationControl.detach(this)
        NotificationControl.instanceDestroyed(this)
        RelayDiagnostics.recordListenerDestroyed(this)
        runtime.shutdown()
        RelayGuardianService.requestImmediateHealthEvaluation()
        super.onDestroy()
    }

    /** Hands the bus to the inbox: the band's client closes and its notice goes. */
    internal fun suspendBand() {
        runtime.shutdown()
    }

    internal fun refreshFromSettings() {
        if (!RelaySettings(this).enabled()) {
            // Switched off means kept nowhere: what the glasses were shown goes too.
            runtime.onForgotten(null)
            ReplyRepository.clear()
            NotificationControl.notifyInboxChanged()
            runtime.shutdown()
            return
        }
        rebuildFromActiveNotifications()
        runtime.onSettingsChanged()
    }

    private fun rebuildFromActiveNotifications() {
        if (!RelaySettings(this).enabled() || NotificationForwardingPolicy.isPaused(this)) return
        ReplyRepository.markAllReadOnly()
        val count = runCatching {
            activeNotifications.orEmpty().count { sbn -> ingest(sbn, mayShow = false) }
        }.onFailure { failure ->
            Log.w(TAG, "active notification sync failed cause=${failure.javaClass.simpleName}")
        }.getOrDefault(0)
        Log.i(TAG, "active notification sync repliableCount=$count")
    }

    private fun ingest(sbn: StatusBarNotification, mayShow: Boolean): Boolean {
        RelaySourceApps.observe(sbn.packageName)
        val settings = RelaySettings(this)
        val source = if (mayShow) "posted" else "rebuild"
        val keyHash = ReplyRepository.stableId(sbn.key).take(8)
        val interactive = NotificationForwardingPolicy.isPhoneScreenOn(this)

        fun finish(decision: String, accepted: Boolean, redacted: Boolean = false): Boolean {
            Log.i(
                TAG,
                "ingest source=$source keyHash=$keyHash interactive=$interactive " +
                    "redacted=$redacted decision=$decision",
            )
            return accepted
        }

        val action = sbn.notification.findRepliableAction()
        val notificationId = ReplyRepository.stableId(sbn.key)
        // A repost without Reply ends the reply authority even while paused or filtered. The event
        // is still on the phone, so nothing is withdrawn; its page stops offering Send.
        if (action == null && ReplyRepository.dropAuthority(sbn)) {
            runtime.onReplyAuthorityLost(notificationId)
            NotificationControl.notifyCaptured(notificationId)
        }
        if (!settings.enabled()) return finish("disabled", accepted = false)
        if (settings.pauseWhilePhoneScreenOn() && interactive) {
            return finish("paused", accepted = false)
        }

        if (!settings.admits(
                sbn.packageName,
                repliable = action != null,
                ongoing = sbn.isOngoing || sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
                groupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            )) {
            if (ReplyRepository.dropAuthority(sbn)) {
                runtime.onReplyAuthorityLost(notificationId)
                NotificationControl.notifyCaptured(notificationId)
            }
            return finish(if (action == null) "no_action" else "filtered", accepted = false)
        }

        val capture = ReplyRepository.capture(this, sbn, action)
            ?: return finish("capture_failed", accepted = false)
        RelayDiagnostics.recordAcceptedCapture(this)
        // The inbox, if it is open, is the only thing that will show this: the
        // band stands down while it holds the bus.
        NotificationControl.notifyCaptured(capture.reply.id)
        val decision = when {
            !capture.contentChanged -> "unchanged"
            !mayShow -> "captured rebuild_only"
            else -> "captured show=${capture.shouldShowNow}"
        }
        val accepted = finish(decision, accepted = true, redacted = capture.redacted)
        if (mayShow && capture.shouldShowNow) runtime.show(capture.reply)
        return accepted
    }

    private companion object {
        const val TAG = "NexusRelayListener"
    }
}
