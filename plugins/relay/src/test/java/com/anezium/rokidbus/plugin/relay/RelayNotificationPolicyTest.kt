package com.anezium.rokidbus.plugin.relay

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.PowerManager
import android.os.Process
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RelayNotificationPolicyTest {
    private lateinit var context: Application
    private lateinit var settings: RelaySettings
    private val source = "com.example.source"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("relay_settings", 0).edit().clear().commit()
        settings = RelaySettings(context)
        settings.setClearAfterReply(false)
        ReplyRepository.clear()
    }

    @After
    fun tearDown() {
        ReplyRepository.clear()
    }

    @Test
    fun `read only requires app opt in and cannot send or offer Reply`() {
        val notification = notification()
        assertNull(ReplyRepository.capture(context, notification, null))
        settings.setAppPolicy(source, RelayAppPolicy.ALL)
        val capture = requireNotNull(ReplyRepository.capture(context, notification, null))
        assertTrue(capture.shouldShowNow)
        assertFalse(capture.reply.canReply)
        assertTrue(capture.reply.footer.endsWith(" · read only"))
        assertFalse(ReplyRepository.contains(capture.reply.id))
        assertEquals(RelayReplyAvailability.READ_ONLY, ReplyRepository.inboxEntries().single().availability)
        assertEquals(ReplySendResult.Missing, ReplyRepository.sendReply(context, capture.reply.id, "hello"))
        assertTrue(RelayNoticeRuntime.arrivalActions(hidden = false, canReply = false).isEmpty())
        assertEquals(listOf("show"), RelayNoticeRuntime.arrivalActions(hidden = true, canReply = false).map { it.id })
    }

    @Test
    fun `timestamp only reposts do not reorder or redisplay a read only notification`() {
        settings.setAppPolicy(source, RelayAppPolicy.ALL)
        val first = requireNotNull(ReplyRepository.capture(context, notification(whenMs = 100), null))
        val second = requireNotNull(ReplyRepository.capture(context, notification(whenMs = 200), null))
        assertFalse(second.contentChanged)
        assertFalse(second.shouldShowNow)
        assertEquals(first.reply.capturedAtMs, second.reply.capturedAtMs)
        val changed = requireNotNull(ReplyRepository.capture(context, notification(body = "Delivered"), null))
        assertTrue(changed.contentChanged)
    }

    @Test
    fun `opting out prunes content and reply authority even before listener refresh`() {
        val action = replyAction()
        val capture = requireNotNull(ReplyRepository.capture(context, notification(), action))
        settings.setAppPolicy(source, RelayAppPolicy.OFF)
        assertEquals(ReplySendResult.Missing, ReplyRepository.sendReply(context, capture.reply.id, "hello"))
        ReplyRepository.prune(settings)
        assertTrue(ReplyRepository.inboxEntries().isEmpty())
        assertFalse(ReplyRepository.isActive(capture.reply.id))
    }

    @Test
    fun `messages only removes read only history but retains message history`() {
        settings.setAppPolicy(source, RelayAppPolicy.ALL)
        ReplyRepository.capture(context, notification(id = 1), null)
        val message = requireNotNull(ReplyRepository.capture(context, notification(id = 2), replyAction()))
        settings.setAppPolicy(source, RelayAppPolicy.MESSAGES)
        ReplyRepository.prune(settings)
        assertEquals(listOf(message.reply.id), ReplyRepository.inboxEntries().map { it.id })
    }

    @Test
    fun `a non repliable update drops previous reply authority`() {
        settings.setAppPolicy(source, RelayAppPolicy.ALL)
        val first = requireNotNull(ReplyRepository.capture(context, notification(), replyAction()))
        assertTrue(ReplyRepository.contains(first.reply.id))
        ReplyRepository.capture(context, notification(), null)
        assertFalse(ReplyRepository.contains(first.reply.id))
        assertEquals(ReplySendResult.Missing, ReplyRepository.sendReply(context, first.reply.id, "hello"))
    }

    @Test
    fun `foreground services group summaries and empty read only notifications are excluded`() {
        settings.setAppPolicy(source, RelayAppPolicy.ALL)
        for (flag in listOf(Notification.FLAG_ONGOING_EVENT, Notification.FLAG_FOREGROUND_SERVICE, Notification.FLAG_GROUP_SUMMARY)) {
            assertNull(ReplyRepository.capture(context, notification(flags = flag), null))
        }
        assertNull(ReplyRepository.capture(context, notification(title = "", body = ""), null))
    }

    @Test
    fun `readable history and live notification objects share the same bound`() {
        settings.setAppPolicy(source, RelayAppPolicy.ALL)
        val oldest = requireNotNull(ReplyRepository.capture(context, notification(id = 0), replyAction()))
        for (id in 1..RelayInboxCatalog.MAX_ENTRIES) {
            ReplyRepository.capture(context, notification(id = id), null)
        }
        assertEquals(RelayInboxCatalog.MAX_ENTRIES, ReplyRepository.inboxEntries().size)
        assertFalse(ReplyRepository.isActive(oldest.reply.id))
    }

    @Test
    fun `global switch blocks both kinds without losing app choices`() {
        settings.setAppPolicy(source, RelayAppPolicy.ALL)
        settings.setEnabled(false)
        assertNull(ReplyRepository.capture(context, notification(), null))
        assertNull(ReplyRepository.capture(context, notification(), replyAction()))
        assertEquals(RelayAppPolicy.ALL, settings.appPolicy(source))
        settings.setEnabled(true)
        assertNotNull(ReplyRepository.capture(context, notification(), null))
    }

    @Test
    fun `settings refresh prunes while no notification listener is attached`() {
        val capture = requireNotNull(ReplyRepository.capture(context, notification(), replyAction()))
        settings.setAppPolicy(source, RelayAppPolicy.OFF)
        NotificationControl.refreshFromSettings(context)
        assertTrue(ReplyRepository.inboxEntries().isEmpty())
        assertFalse(ReplyRepository.isActive(capture.reply.id))
    }

    @Test
    fun `reset removes overrides while keeping unrelated settings`() {
        settings.setAppPolicy(source, RelayAppPolicy.ALL)
        settings.setAppPolicy("com.example.other", RelayAppPolicy.OFF)
        settings.setHideNoticeText(true)
        settings.resetAppPolicies()
        assertTrue(settings.configuredPackages().isEmpty())
        assertEquals(RelayAppPolicy.MESSAGES, settings.appPolicy(source))
        assertTrue(settings.hideNoticeText())
    }

    @Test
    fun `a just answered notification does not return as the same read only alert`() {
        settings.setAppPolicy(source, RelayAppPolicy.ALL)
        val posted = notification()
        val capture = requireNotNull(ReplyRepository.capture(context, posted, replyAction()))
        assertEquals(ReplySendResult.Sent, ReplyRepository.sendReply(context, capture.reply.id, "Thanks"))
        assertNull(ReplyRepository.capture(context, posted, null))
        assertTrue(ReplyRepository.inboxEntries().isEmpty())
        assertNotNull(ReplyRepository.capture(context, notification(body = "A new alert"), null))
    }

    @Test
    fun `removing a just answered source retires its suppression marker`() {
        settings.setAppPolicy(source, RelayAppPolicy.ALL)
        val posted = notification()
        val capture = requireNotNull(ReplyRepository.capture(context, posted, replyAction()))
        assertEquals(ReplySendResult.Sent, ReplyRepository.sendReply(context, capture.reply.id, "Thanks"))
        assertNull(ReplyRepository.capture(context, posted, null))
        ReplyRepository.forget(posted)
        assertNotNull(ReplyRepository.capture(context, posted, null))
    }

    @Test
    fun `Show reveals a read only band without rearming the answered question`() {
        settings.setAppPolicy(source, RelayAppPolicy.ALL)
        settings.setHideNoticeText(true)
        val capture = requireNotNull(ReplyRepository.capture(context, notification(), null))
        val runtime = RelayNoticeRuntime(context)
        try {
            setField(runtime, "currentReply", capture.reply)
            setField(runtime, "activeNotice", true)
            setField(runtime, "lastNoticeMessageAtMs", SystemClock.uptimeMillis())
            runtime.onNoticeAction(RelayNoticeRuntime.ACTION_SHOW)
            shadowOf(Looper.getMainLooper()).idle()
            val updates = getField(runtime, "essentialUpdates") as java.util.ArrayDeque<*>
            val reveal = updates.single() as NexusNoticeUpdate
            assertEquals(capture.reply.content.renderedText, reveal.lines.joinToString("\n"))
            assertTrue(reveal.actions.isEmpty())
            assertNull(reveal.interactive)
            assertNull(reveal.rearm)
        } finally {
            runtime.shutdown()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `source removal after Send keeps Sent and the deferred notification`() {
        val first = requireNotNull(ReplyRepository.capture(context, notification(id = 1), replyAction()))
        val next = requireNotNull(ReplyRepository.capture(context, notification(id = 2), replyAction()))
        val runtime = RelayNoticeRuntime(context)
        try {
            setField(runtime, "currentReply", first.reply)
            setField(runtime, "activeNotice", true)
            setField(runtime, "currentTranscript", "Thanks")
            setField(runtime, "deferredShow", next.reply)
            runtime.onNoticeAction(RelayNoticeRuntime.ACTION_SEND)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(getField(runtime, "replyHandedOff") as Boolean)
            runtime.onRemoved(first.reply.id)
            runtime.onSettingsChanged()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(first.reply, getField(runtime, "currentReply"))
            assertEquals(next.reply, getField(runtime, "deferredShow"))
            assertTrue(getField(runtime, "activeNotice") as Boolean)
        } finally {
            runtime.shutdown()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    // Robolectric 4.13 does not shadow API 33's service-query flags overload.
    @Config(sdk = [32])
    fun `removing an unsent band starts the other deferred notification`() {
        val first = requireNotNull(ReplyRepository.capture(context, notification(id = 1), replyAction()))
        val next = requireNotNull(ReplyRepository.capture(context, notification(id = 2), replyAction()))
        val runtime = RelayNoticeRuntime(context)
        try {
            setField(runtime, "currentReply", first.reply)
            setField(runtime, "activeNotice", true)
            setField(runtime, "deferredShow", next.reply)
            ReplyRepository.forget(notification(id = 1))
            runtime.onRemoved(first.reply.id)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(next.reply, getField(runtime, "currentReply"))
            assertEquals(next.reply, getField(runtime, "pendingShow"))
        } finally {
            runtime.shutdown()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `inbox keeps Sent on source removal but clears it when its app is disabled`() {
        val capture = requireNotNull(ReplyRepository.capture(context, notification(), replyAction()))
        val controller = Robolectric.buildService(RelayPluginService::class.java)
        val inbox = controller.get()
        val selection = openInboxThread(inbox, capture.reply.id, "SENT")
        try {
            assertEquals(ReplySendResult.Sent, ReplyRepository.sendReply(context, capture.reply.id, "Thanks"))
            inbox.onCaptureChanged(capture.reply.id)
            inbox.onSettingsChanged()
            assertEquals("SENT", getField(inbox, "threadMode").toString())
            assertEquals(RelayInboxView.THREAD, selection.view)
            settings.setAppPolicy(source, RelayAppPolicy.OFF)
            ReplyRepository.prune(settings)
            inbox.onSettingsChanged()
            assertEquals(RelayInboxView.LIST, selection.view)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `inbox explains authority loss and retains the unsent words without a countdown`() {
        val capture = requireNotNull(ReplyRepository.capture(context, notification(), replyAction()))
        val controller = Robolectric.buildService(RelayPluginService::class.java)
        val inbox = controller.get()
        openInboxThread(inbox, capture.reply.id, "REVIEW")
        try {
            setField(inbox, "transcript", "My unsent reply")
            setField(inbox, "sendDeadlineMs", 1000L)
            ReplyRepository.forget(notification())
            inbox.onCaptureChanged(capture.reply.id)
            assertEquals("READING", getField(inbox, "threadMode").toString())
            assertTrue(getField(inbox, "threadStatus").toString().contains("My unsent reply"))
            assertNull(getField(inbox, "sendDeadlineMs"))
            assertFalse(ReplyRepository.contains(capture.reply.id))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `a paused settings refresh preserves valid replies but a no action post revokes them`() {
        val capture = requireNotNull(ReplyRepository.capture(context, notification(), replyAction()))
        settings.setPauseWhilePhoneScreenOn(true)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val controller = Robolectric.buildService(RelayNotificationListener::class.java).create()
        val listener = controller.get()
        try {
            listener.refreshFromSettings()
            assertTrue(ReplyRepository.contains(capture.reply.id))
            listener.onNotificationPosted(notification())
            assertFalse(ReplyRepository.contains(capture.reply.id))
            assertEquals(RelayReplyAvailability.READ_ONLY, ReplyRepository.inboxEntries().single().availability)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `app discovery includes configured unavailable sources with fallback labels`() {
        val unavailable = "com.example.not.installed"
        settings.setAppPolicy(unavailable, RelayAppPolicy.OFF)
        val apps = RelaySourceApps.list(context, settings)
        val app = apps.single { it.packageName == unavailable }
        assertEquals(unavailable, app.label)
        assertNull(app.icon)
        assertFalse(app.detailsAvailable)
    }

    private fun openInboxThread(inbox: RelayPluginService, id: String, mode: String): RelayInboxSelection {
        setField(inbox, "entries", ReplyRepository.inboxEntries())
        val selection = getField(inbox, "selection") as RelayInboxSelection
        selection.reset(listOf(id))
        selection.openSelected()
        val field = inbox.javaClass.getDeclaredField("threadMode").apply { isAccessible = true }
        field.set(inbox, field.type.enumConstants.first { (it as Enum<*>).name == mode })
        return selection
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun replyAction(): Notification.Action = Notification.Action.Builder(
        null, "Reply", PendingIntent.getBroadcast(context, 0, Intent("test.REPLY").setPackage(context.packageName), PendingIntent.FLAG_MUTABLE),
    ).addRemoteInput(RemoteInput.Builder("text").setAllowFreeFormInput(true).build()).build()

    private fun notification(id: Int = 1, title: String = "Parcel", body: String = "On the way", whenMs: Long = 100, flags: Int = 0): StatusBarNotification {
        val notification = Notification.Builder(context, "test").setContentTitle(title).setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setWhen(whenMs).build()
        notification.flags = flags
        return StatusBarNotification(source, source, id, null, 1000, 0, 0, notification, Process.myUserHandle(), 100)
    }
}
