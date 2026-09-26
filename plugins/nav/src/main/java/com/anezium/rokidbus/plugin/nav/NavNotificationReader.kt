package com.anezium.rokidbus.plugin.nav

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/** Copies what a parser may read out of a posted notification. */
internal object NavNotificationReader {
    fun read(context: Context, sbn: StatusBarNotification): NavNotification? {
        val source = NavSource.of(sbn.packageName) ?: return null
        val notification = sbn.notification
        val extras = notification.extras
        fun text(key: String): String? = extras.getCharSequence(key)?.toString()
        return NavNotification(
            packageName = sbn.packageName,
            channelId = notification.channelId,
            category = notification.category,
            ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            title = text(Notification.EXTRA_TITLE),
            text = text(Notification.EXTRA_TEXT),
            subText = text(Notification.EXTRA_SUB_TEXT),
            bigText = text(Notification.EXTRA_BIG_TEXT),
            textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                .orEmpty()
                .mapNotNull { it?.toString() },
            shortCriticalText = text(EXTRA_SHORT_CRITICAL_TEXT),
            progress = extras.getInt(Notification.EXTRA_PROGRESS),
            progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX),
            actions = notification.actions.orEmpty().mapNotNull { it.title?.toString() },
            nowBarPrimary = text(EXTRA_NOW_BAR_PRIMARY),
            nowBarSecondary = text(EXTRA_NOW_BAR_SECONDARY),
            viewTexts = if (source == NavSource.CITYMAPPER) layoutTexts(context, notification) else emptyMap(),
        )
    }

    /**
     * Inflates the app's own layout (the expanded one when there is one) and
     * keeps each visible TextView's text under its resource entry name. The
     * layout is the app's, so a failure to inflate it reads as "no text".
     */
    @Suppress("DEPRECATION")
    private fun layoutTexts(context: Context, notification: Notification): Map<String, String> {
        val views = notification.bigContentView ?: notification.contentView ?: return emptyMap()
        val root = runCatching { views.apply(context, FrameLayout(context)) }.getOrNull() ?: return emptyMap()
        val texts = linkedMapOf<String, String>()
        fun collect(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is TextView && view.id != View.NO_ID) {
                val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
                val value = view.text?.toString()?.trim()
                if (name != null && !value.isNullOrEmpty()) texts.putIfAbsent(name, value)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
        }
        collect(root)
        return texts
    }

    private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
    private const val EXTRA_NOW_BAR_PRIMARY = "android.ongoingActivityNoti.primaryInfo"
    private const val EXTRA_NOW_BAR_SECONDARY = "android.ongoingActivityNoti.secondaryInfo"
}
