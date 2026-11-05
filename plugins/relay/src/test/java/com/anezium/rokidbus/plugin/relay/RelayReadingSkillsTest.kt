package com.anezium.rokidbus.plugin.relay

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Process
import android.service.notification.StatusBarNotification
import com.anezium.rokidbus.shared.skills.SkillCatalogParseResult
import com.anezium.rokidbus.shared.skills.SkillCatalogParser
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import com.anezium.rokidbus.shared.skills.SkillLimits
import com.anezium.rokidbus.shared.skills.SkillSchemaValidator
import com.anezium.rokidbus.shared.skills.SkillValidation
import org.json.JSONArray
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RelayReadingSkillsTest {
    private lateinit var context: Application
    private lateinit var settings: RelaySettings
    private lateinit var skills: RelaySkills
    private val source = "com.example.chat"
    private var access = true
    private val catalog = SkillCatalogParser.parse(
        File("src/main/res/raw/nexus_skills.json").readText(Charsets.UTF_8),
    ).let { result ->
        check(result is SkillCatalogParseResult.Valid) { result.toString() }
        result.catalog
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("relay_settings", 0).edit().clear().commit()
        settings = RelaySettings(context)
        settings.setAppPolicy(source, RelayAppPolicy.ALL)
        settings.setClearAfterReply(false)
        ReplyRepository.clear()
        skills = RelaySkills(context, { access })
    }

    @After
    fun tearDown() = ReplyRepository.clear()

    private fun post(
        text: String = "meet at the station",
        id: Int = 1,
        title: String = "Coralie",
        messages: List<Pair<String?, String>> = emptyList(),
        reply: Boolean = false,
    ): StatusBarNotification {
        val builder = Notification.Builder(context, "chat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text)
        if (messages.isNotEmpty()) {
            val style = Notification.MessagingStyle(Person.Builder().setName("Me").build())
            messages.forEachIndexed { index, (sender, body) ->
                style.addMessage(Notification.MessagingStyle.Message(
                    body, 100L + index, sender?.let { Person.Builder().setName(it).build() },
                ))
            }
            builder.setStyle(style)
        }
        if (reply) {
            val intent = PendingIntent.getBroadcast(context, id,
                Intent("test.relay.reading.REPLY").setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(
                Icon.createWithResource(context, android.R.drawable.ic_menu_send), "Reply", intent,
            ).addRemoteInput(RemoteInput.Builder("text").setAllowFreeFormInput(true).build()).build())
        }
        val notification = builder.build().apply {
            extras.putCharSequence(Notification.EXTRA_TITLE, title)
            // The builder clips plain text before Relay sees it; raw extras exercise its own budget.
            if (messages.isEmpty()) extras.putCharSequence(Notification.EXTRA_TEXT, text)
        }
        return StatusBarNotification(source, source, id, null, 10_123, 0, 0, notification,
            Process.myUserHandle(), 100).also {
            requireNotNull(ReplyRepository.capture(context, it, it.notification.actions?.firstOrNull()))
        }
    }

    private fun run(operation: String, arguments: JSONObject = JSONObject()): RelaySkillOutcome =
        skills.run(operation, arguments).also { result ->
            if (result is RelaySkillOutcome.Completed) {
                assertEquals(SkillValidation.Valid,
                    SkillSchemaValidator.validate(result.data, catalog.operation(operation)!!.output))
                assertTrue(result.data.toString().toByteArray(Charsets.UTF_8).size <= SkillLimits.MAX_RESULT_BYTES)
            }
        }

    private fun listed(name: String? = null): List<JSONObject> {
        val data = (run(RelayReadingSkills.LIST_READABLE_THREADS,
            JSONObject().apply { name?.let { put("name", it) } }) as RelaySkillOutcome.Completed).data
        return rows(data.getJSONArray("threads"))
    }

    private fun read(reference: String = listed().single().getString("thread"),
        scope: String = RelayReadingSkills.CONVERSATION, cursor: String? = null): RelaySkillOutcome =
        run(RelayReadingSkills.READ_THREAD, JSONObject().put("thread", reference).put("scope", scope)
            .apply { cursor?.let { put("cursor", it) } })

    private fun data(result: RelaySkillOutcome) = (result as RelaySkillOutcome.Completed).data
    private fun rows(array: JSONArray) = List(array.length()) { array.getJSONObject(it) }

    @Test
    fun `discovery includes retained read only conversations without exposing text`() {
        val notification = post(text = "private content", title = "Coralié")
        ReplyRepository.forget(notification)
        val row = listed("CORALIE").single()
        assertFalse(row.toString().contains("private content"))
        assertFalse(row.getBoolean("can_reply"))
        assertEquals("private content", rows(data(read()).getJSONArray("messages")).single().getString("text"))
        (2..14).forEach { post(id = it, title = "Person $it") }
        assertEquals(10, listed().size)
        assertEquals("Person 14", listed().first().getString("sender"))
    }

    @Test
    fun `reading preserves authors order and our handoff without sending anything`() {
        val notification = post(messages = listOf("Coralie" to "hello", null to "hi", "Coralie" to "tomorrow at 3"), reply = true)
        val id = ReplyRepository.stableId(notification.key)
        assertEquals(ReplySendResult.Sent, ReplyRepository.sendDrafted(context, id, source, "Coralie", "see you"))
        val reference = listed().single().getString("thread")
        val before = ReplyRepository.threadEntries()
        val result = data(read(reference))
        val messages = rows(result.getJSONArray("messages"))
        assertEquals(listOf("received", "ours", "received", "ours"), messages.map { it.getString("role") })
        assertEquals(listOf("hello", "hi", "tomorrow at 3", "see you"), messages.map { it.getString("text") })
        assertEquals("handed_to_app", messages.last().getString("status"))
        assertEquals("captured_notifications_only", result.getString("coverage"))
        val latest = rows(data(read(reference, RelayReadingSkills.LATEST_RECEIVED)).getJSONArray("messages"))
        assertEquals(listOf("tomorrow at 3"), latest.map { it.getString("text") })
        assertEquals(before, ReplyRepository.threadEntries())
    }

    @Test
    fun `privacy and current source policy apply both to discovery and held references`() {
        post()
        val reference = listed().single().getString("thread")
        settings.setHideInboxPreviews(true)
        assertTrue(listed().isEmpty())
        assertEquals(RelaySkillOutcome.Failed(RelaySkills.AUTHORITY_LOST), read(reference))
        settings.setHideInboxPreviews(false)
        settings.setAppPolicy(source, RelayAppPolicy.MESSAGES)
        assertTrue(listed().isEmpty())
        assertEquals(RelaySkillOutcome.Failed(RelaySkills.AUTHORITY_LOST), read(reference))
        settings.setAppPolicy(source, RelayAppPolicy.ALL)
        settings.setEnabled(false)
        assertTrue(listed().isEmpty())
        assertEquals(RelaySkillOutcome.Failed(RelaySkills.AUTHORITY_LOST), read(reference))
        settings.setEnabled(true)
        post(text = SensitiveNotificationDetector.HIDDEN_BODY)
        assertTrue(listed().isEmpty())
        assertEquals(RelaySkillOutcome.Failed(RelaySkills.AUTHORITY_LOST), read(reference))
        access = false
        assertEquals(RelaySkillOutcome.Failed(SkillErrorCodes.SETUP_REQUIRED), read(reference))
    }

    @Test
    fun `reading references cannot follow a reused notification key to another person`() {
        post()
        val reference = listed().single().getString("thread")
        post(title = "Someone Else")
        assertEquals(RelaySkillOutcome.Failed(RelaySkills.AUTHORITY_LOST), read(reference))
        assertEquals("Someone Else", data(read()).getString("sender"))
        assertEquals(RelaySkillOutcome.Failed(RelaySkills.AUTHORITY_LOST), read("never-issued"))
    }

    @Test
    fun `unknown authors are not guessed and reading has a distinct reference type`() {
        val notification = post()
        val message = rows(data(read()).getJSONArray("messages")).single()
        assertEquals("unknown", message.getString("role"))
        assertEquals("", message.getString("sender"))
        assertEquals(RelaySkillOutcome.Failed(RelaySkills.AUTHORITY_LOST), read(ReplyRepository.stableId(notification.key)))
        assertEquals(RelaySkillOutcome.Failed(SkillErrorCodes.INVALID_ARGUMENTS), read(scope = "everything"))
    }

    @Test
    fun `large escaped unicode messages page within the wire budget without losing text`() {
        post(text = ("ab\u0001\u0002\u0003\u0004😀漢").repeat(900))
        val original = ReplyRepository.threadEntries().single().snapshot.messages.single().text
        val reference = listed().single().getString("thread")
        val pieces = mutableListOf<JSONObject>()
        var page = data(read(reference))
        assertTrue("captured chars=${original.length}, page bytes=${page.toString().toByteArray(Charsets.UTF_8).size}, " +
            "chunks=${page.getJSONArray("messages").length()}", page.has("cursor"))
        val cursor = page.getString("cursor")
        var pages = 0
        while (true) {
            pieces += rows(page.getJSONArray("messages"))
            pages++
            assertTrue(pages < 20)
            if (!page.getBoolean("has_more")) break
            page = data(read(reference, cursor = page.getString("cursor")))
        }
        assertTrue(pages > 1)
        assertEquals(original, pieces.sortedBy { it.getInt("part") }.joinToString("") { it.getString("text") })
        assertTrue(pieces.all { part ->
            val text = part.getString("text")
            !Character.isLowSurrogate(text.first()) && !Character.isHighSurrogate(text.last())
        })
        assertEquals(RelaySkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE),
            read(reference, RelayReadingSkills.LATEST_RECEIVED, cursor))
        post(text = "new message")
        assertEquals(RelaySkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE), read(reference, cursor = cursor))
    }
}
