package com.anezium.rokidbus.plugin.relay

import android.os.Handler
import android.os.Looper

internal object NotificationControl {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: RelayNotificationListener? = null

    @Volatile
    private var liveInstance: RelayNotificationListener? = null

    fun instanceCreated(service: RelayNotificationListener) {
        liveInstance = service
    }

    fun instanceDestroyed(service: RelayNotificationListener) {
        if (liveInstance === service) liveInstance = null
    }

    fun attach(service: RelayNotificationListener) {
        liveInstance = service
        listener = service
    }

    fun detach(service: RelayNotificationListener) {
        if (listener === service) listener = null
    }

    fun isListenerConnected(): Boolean = listener != null

    fun hasLiveListenerInstance(): Boolean = liveInstance != null

    fun requestListenerUnbind(): Boolean {
        val current = liveInstance ?: return false
        current.requestUnbind()
        return true
    }

    /**
     * Whether the wearer is in the inbox, which decides who owns the bus.
     *
     * Relay has two things that talk to the hub — the band runtime, woken by a
     * notification, and this plugin's own service, opened from the menu — and
     * each was creating its own `NexusPluginClient` under the same plugin id.
     * The hub keeps both, since it identifies a registration by callback binder
     * rather than by id, but several paths downstream assume there is one:
     * external lifecycle delivery resolves with `singleOrNull` and finds
     * nothing, outbound sends pick whichever registration matched first, and
     * speech replies follow the binder that was chosen rather than the one that
     * asked. In practice that means opening the inbox while a band is live can
     * fail outright, or dictation from a band can hang on "Listening…" because
     * its transcript went to the other client.
     *
     * So only one is ever connected. The inbox wins while it is open: the
     * wearer is already looking at their messages, and a band over the top of
     * the list would be announcing something they can see.
     */
    @Volatile
    var inboxOpen: Boolean = false
        private set

    @Volatile
    private var inbox: RelayPluginService? = null

    fun inboxOpened(service: RelayPluginService) {
        inbox = service
        inboxOpen = true
        main.post { listener?.suspendBand() }
    }

    fun inboxClosed(service: RelayPluginService) {
        if (inbox === service) inbox = null
        inboxOpen = false
    }

    /**
     * A message arrived while the wearer is in the inbox, so its visible surface redraws.
     *
     * This matters more than it looks: the band stands down while the inbox
     * holds the bus, so if the inbox did not refresh, a message arriving during
     * that time would be announced by nothing and appear nowhere — visible only
     * after closing and reopening. The capture is already in the repository by
     * the time this runs; the visible inbox surface only has to look again.
     */
    fun notifyCaptured(notificationId: String) {
        main.post {
            inbox?.onCaptureChanged(notificationId)
            pageListener?.invoke()
        }
    }

    /**
     * The repository or what it may show changed without a capture: access was lost, Relay was
     * switched off, or a preview setting changed. Only a visible page looks again; no notice or
     * reply follows from it.
     */
    fun notifyInboxChanged() {
        main.post { pageListener?.invoke() }
    }

    /** The notice band's callbacks, when the listener is connected; the plugin service forwards to them. */
    fun noticeCallbacks(): RelayNoticeRuntime? = listener?.noticeCallbacks

    /** Told when the inbox changes, so a visible page can follow it; set by the plugin service. */
    @Volatile
    var pageListener: (() -> Unit)? = null

    fun refreshFromSettings() {
        main.post { listener?.refreshFromSettings() }
    }

    fun cancelAfterReply(notificationKey: String) {
        if (notificationKey.isBlank()) return
        CANCEL_DELAYS_MS.forEach { delayMs ->
            main.postDelayed({ listener?.cancelNotification(notificationKey) }, delayMs)
        }
    }

    private val CANCEL_DELAYS_MS = longArrayOf(250L, 1_000L, 2_500L)
}
