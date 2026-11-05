package com.anezium.rokidbus.plugin.relay

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FakeConversationScenariosTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var settings: RelaySettings

    @Before
    fun setUp() {
        context.getSharedPreferences("relay_settings", 0).edit().clear().commit()
        settings = RelaySettings(context)
        ReplyRepository.clear()
        manager.cancelAll()
    }

    @After
    fun tearDown() {
        manager.cancelAll()
        ReplyRepository.clear()
    }

    private fun postAndCapture() {
        assertTrue(FakeNotificationHarness.postReadingConversations(context))
        val notifications = manager.activeNotifications
        assertEquals(3, notifications.size)
        notifications.forEach { notification ->
            requireNotNull(ReplyRepository.capture(context, notification, notification.notification.actions.first()))
        }
    }

    @Test
    fun `the demo posts distinct realistic conversations with both sides and a real group`() {
        postAndCapture()
        // Reposting replaces the same synthetic threads rather than making duplicate conversations.
        postAndCapture()
        val entries = ReplyRepository.readableThreads(settings).associateBy { it.snapshot.sender }
        assertEquals(setOf("Mika et José", "Jean", "Léa"), entries.keys)
        val group = entries.getValue("Mika et José").snapshot.messages
        assertEquals(setOf("Mika", "José"), group.mapNotNull { it.sender }.toSet())
        assertEquals(2, group.count { it.role == RelayMessageRole.OURS })
        assertTrue(group.any { it.text.contains("19 h 30") })
        assertTrue(group.any { it.role == RelayMessageRole.OURS && it.text.contains("samedi à 20 h") })
        assertTrue(group.any { it.sender == "Mika" && it.text.contains("dessert") })
        assertTrue(group.any { it.sender == "José" && it.text.contains("boissons") })
        val groupNotification = manager.activeNotifications.single {
            it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "Mika et José"
        }
        assertTrue(groupNotification.notification.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))
        assertTrue(entries.values.all { it.snapshot.messages.any { message -> message.role == RelayMessageRole.OURS } })
    }

    @Test
    fun `Jean has several separate requests and the reader keeps them apart from other conversations`() {
        postAndCapture()
        val skills = RelaySkills(context, { true })
        val listing = (skills.run(RelayReadingSkills.LIST_READABLE_THREADS, JSONObject())
            as RelaySkillOutcome.Completed).data.getJSONArray("threads")
        val jean = (0 until listing.length()).map(listing::getJSONObject).single { it.getString("sender") == "Jean" }
        fun read(scope: String) = (skills.run(RelayReadingSkills.READ_THREAD,
            JSONObject().put("thread", jean.getString("thread")).put("scope", scope))
            as RelaySkillOutcome.Completed).data.getJSONArray("messages")

        val conversation = read(RelayReadingSkills.CONVERSATION)
        val texts = (0 until conversation.length()).map { conversation.getJSONObject(it).getString("text") }
        assertTrue(texts.any { it.contains("copie signée") && it.contains("demain midi") })
        assertTrue(texts.any { it.contains("adresse postale") })
        assertTrue(texts.any { it.contains("référence du paiement") })
        assertFalse(texts.any { it.contains("dîner") || it.contains("cinéma") })
        val latest = read(RelayReadingSkills.LATEST_RECEIVED)
        assertEquals(1, latest.length())
        assertTrue(latest.getJSONObject(0).getString("text").contains("Préviens-moi"))
    }
}
