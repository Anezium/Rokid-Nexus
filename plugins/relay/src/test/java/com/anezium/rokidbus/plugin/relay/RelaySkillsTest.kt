package com.anezium.rokidbus.plugin.relay

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Process
import android.service.notification.StatusBarNotification
import com.anezium.rokidbus.shared.skills.SkillCatalog
import com.anezium.rokidbus.shared.skills.SkillCatalogParseResult
import com.anezium.rokidbus.shared.skills.SkillCatalogParser
import com.anezium.rokidbus.shared.skills.SkillEffect
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import com.anezium.rokidbus.shared.skills.SkillSchemaValidator
import com.anezium.rokidbus.shared.skills.SkillValidation
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
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RelaySkillsTest {
    private val source = "com.example.chat"
    private lateinit var context: Application
    private lateinit var settings: RelaySettings
    private lateinit var skills: RelaySkills
    private var access = true
    private var now = 1_000L
    private val drafts = RelayReplyDrafts(clock = { now }, ids = { "draft-${counter++}" })
    private var counter = 0
    private val sentTexts = mutableListOf<String>()
    private val catalog: SkillCatalog = (
        SkillCatalogParser.parse(File("src/main/res/raw/nexus_skills.json").readText(Charsets.UTF_8))
            as SkillCatalogParseResult.Valid
        ).catalog

    private val replyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence("text")?.let { sentTexts += it.toString() }
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("relay_settings", 0).edit().clear().commit()
        settings = RelaySettings(context)
        settings.setClearAfterReply(false)
        ReplyRepository.clear()
        skills = RelaySkills(context, { access }, drafts, wallClock = { System.currentTimeMillis() })
        context.registerReceiver(replyReceiver, IntentFilter(REPLY_ACTION), Context.RECEIVER_NOT_EXPORTED)
    }

    @After
    fun tearDown() {
        context.unregisterReceiver(replyReceiver)
        ReplyRepository.clear()
    }

    private fun chat(
        id: Int = 1,
        sender: String = "Coralie",
        body: String = "on est devant",
        whenMs: Long = 100,
        reply: Boolean = true,
    ): StatusBarNotification {
        val builder = Notification.Builder(context, "chat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(sender)
            .setContentText(body)
            .setWhen(whenMs)
        if (reply) {
            val intent = PendingIntent.getBroadcast(
                context,
                id,
                Intent(REPLY_ACTION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_send), "Reply", intent)
                    .addRemoteInput(RemoteInput.Builder("text").setAllowFreeFormInput(true).build())
                    .build(),
            )
        }
        return StatusBarNotification(source, source, id, null, 10_123, 0, 0, builder.build(), Process.myUserHandle(), 100)
    }

    private fun post(sbn: StatusBarNotification): String {
        val capture = ReplyRepository.capture(context, sbn, sbn.notification.actions?.firstOrNull())
        return requireNotNull(capture).reply.id
    }

    private fun run(operation: String, arguments: JSONObject = JSONObject()): RelaySkillOutcome {
        val outcome = skills.run(operation, arguments)
        if (outcome is RelaySkillOutcome.Completed) {
            val output = catalog.operation(operation)!!.output
            assertEquals(SkillValidation.Valid, SkillSchemaValidator.validate(outcome.data, output))
        }
        return outcome
    }

    private fun prepare(thread: String, text: String = "We meet at 3 pm"): String =
        (run(RelaySkills.PREPARE_REPLY, JSONObject().put("thread", thread).put("text", text)) as RelaySkillOutcome.Completed)
            .data.getString("draft")

    private fun send(draft: String) = run(RelaySkills.SEND_REPLY, JSONObject().put("draft", draft))

    private fun listed(name: String? = null): List<JSONObject> {
        val args = JSONObject().apply { name?.let { put("name", it) } }
        val threads = (run(RelaySkills.LIST_REPLY_THREADS, args) as RelaySkillOutcome.Completed).data.getJSONArray("threads")
        return List(threads.length()) { threads.getJSONObject(it) }
    }

    private fun failure(code: String) = RelaySkillOutcome.Failed(code)

    @Test
    fun `the catalog is valid and only the send has an effect`() {
        val operations = listOf(RelaySkills.LIST_REPLY_THREADS, RelaySkills.PREPARE_REPLY, RelaySkills.SEND_REPLY)
        assertEquals(operations, catalog.operations.map { it.id })
        assertEquals(listOf(SkillEffect.READ, SkillEffect.READ, SkillEffect.ACTION), catalog.operations.map { it.effect })
        assertFalse(catalog.operation(RelaySkills.SEND_REPLY)!!.cancellable)
        assertTrue(catalog.operation(RelaySkills.SEND_REPLY)!!.deduplicates)
    }

    @Test
    fun `the manifest asks for the skills grant through its own key and not capabilities`() {
        val manifest = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        assertTrue(manifest.contains("com.anezium.rokidbus.plugin.SKILLS"))
        assertTrue(manifest.contains("@raw/nexus_skills"))
        assertFalse(manifest.contains("skills_provider"))
    }

    @Test
    fun `listing gives sender app and age without message text`() {
        val id = post(chat(body = "the secret message"))

        val row = listed().single()

        assertEquals(id, row.getString("thread"))
        assertEquals("Coralie", row.getString("sender"))
        assertFalse(row.toString().contains("secret"))
    }

    @Test
    fun `listing filters by name ignoring case and accents and keeps the newest five`() {
        post(chat(id = 1, sender = "Coralié"))
        post(chat(id = 2, sender = "Mika"))
        assertEquals(listOf("Coralié"), listed("cOrAlie").map { it.getString("sender") })

        (3..9).forEach { post(chat(id = it, sender = "Person $it")) }
        val newest = listed()
        assertEquals(RelaySkills.MAX_LISTED_THREADS, newest.size)
        assertEquals("Person 9", newest.first().getString("sender"))
    }

    @Test
    fun `threads from apps set to off, read only, redacted, or hidden are not listed`() {
        val id = post(chat())
        assertEquals(1, listed().size)

        settings.setAppPolicy(source, RelayAppPolicy.OFF)
        assertTrue(listed().isEmpty())
        assertEquals(
            failure(RelaySkills.AUTHORITY_LOST),
            run(RelaySkills.PREPARE_REPLY, JSONObject().put("thread", id).put("text", "hi")),
        )
        settings.setAppPolicy(source, RelayAppPolicy.ALL)

        settings.setHideInboxPreviews(true)
        assertTrue(listed().isEmpty())
        assertEquals(
            failure(RelaySkills.AUTHORITY_LOST),
            run(RelaySkills.PREPARE_REPLY, JSONObject().put("thread", id).put("text", "hi")),
        )
        settings.setHideInboxPreviews(false)

        ReplyRepository.clear()
        post(chat(id = 2, reply = false))
        assertTrue(listed().isEmpty())

        ReplyRepository.clear()
        post(chat(id = 3, body = SensitiveNotificationDetector.HIDDEN_BODY))
        assertTrue(listed().isEmpty())
    }

    @Test
    fun `without notification access nothing runs and an unknown operation is refused`() {
        access = false
        assertEquals(failure(SkillErrorCodes.SETUP_REQUIRED), run(RelaySkills.LIST_REPLY_THREADS))
        assertEquals(failure(SkillErrorCodes.UNSUPPORTED_OPERATION), run("delete_everything"))
    }

    @Test
    fun `a prepared reply previews the provider's facts and sends nothing`() {
        val id = post(chat())

        val data = (run(RelaySkills.PREPARE_REPLY, JSONObject().put("thread", id).put("text", " We meet at 3 pm ")) as RelaySkillOutcome.Completed).data

        assertEquals("Coralie", data.getString("recipient"))
        assertEquals("We meet at 3 pm", data.getString("text"))
        assertTrue(data.getString("via").isNotBlank())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(sentTexts.isEmpty())
    }

    @Test
    fun `a prepare needs a live thread and text that fits the band`() {
        val id = post(chat())
        assertEquals(
            failure(RelaySkills.AUTHORITY_LOST),
            run(RelaySkills.PREPARE_REPLY, JSONObject().put("thread", "unknown").put("text", "hi")),
        )
        assertEquals(
            failure(SkillErrorCodes.INVALID_ARGUMENTS),
            run(RelaySkills.PREPARE_REPLY, JSONObject().put("thread", id).put("text", "   ")),
        )
        assertEquals(
            failure(SkillErrorCodes.INVALID_ARGUMENTS),
            run(RelaySkills.PREPARE_REPLY, JSONObject().put("thread", id).put("text", "x".repeat(RelaySkills.MAX_TEXT_CHARS + 1))),
        )
    }

    @Test
    fun `a send hands the drafted text to the app and is recorded as a handoff`() {
        val id = post(chat())
        val draft = prepare(id)

        assertEquals(RelaySkillOutcome.Accepted, send(draft))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(listOf("We meet at 3 pm"), sentTexts)
        val handoffs = ReplyRepository.threadEntries().single().snapshot.handoffs
        assertEquals("We meet at 3 pm", handoffs.single().text)
        assertEquals(RelayHandoffStatus.HANDED_OFF, handoffs.single().status)
    }

    @Test
    fun `a draft goes once`() {
        val draft = prepare(post(chat()))

        assertEquals(RelaySkillOutcome.Accepted, send(draft))
        assertEquals(failure(SkillErrorCodes.STALE_REFERENCE), send(draft))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(1, sentTexts.size)
    }

    @Test
    fun `a draft expires after two minutes and an unknown one fails with nothing sent`() {
        val draft = prepare(post(chat()))
        now += 120_000L

        assertEquals(failure(SkillErrorCodes.STALE_REFERENCE), send(draft))
        assertEquals(failure(SkillErrorCodes.STALE_REFERENCE), send("never-issued"))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(sentTexts.isEmpty())
    }

    @Test
    fun `a notification removed before Send fails with nothing sent`() {
        val sbn = chat()
        val draft = prepare(post(sbn))

        ReplyRepository.forget(sbn)

        assertEquals(failure(RelaySkills.AUTHORITY_LOST), send(draft))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(sentTexts.isEmpty())
    }

    @Test
    fun `a dropped reply action before Send fails with nothing sent`() {
        val sbn = chat()
        val draft = prepare(post(sbn))

        ReplyRepository.dropAuthority(sbn)

        assertEquals(failure(RelaySkills.AUTHORITY_LOST), send(draft))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(sentTexts.isEmpty())
    }

    @Test
    fun `the same key reposted with another sender fails with nothing sent`() {
        val draft = prepare(post(chat(sender = "Coralie")))

        post(chat(sender = "Someone Else", whenMs = 200))

        assertEquals(failure(RelaySkills.AUTHORITY_LOST), send(draft))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(sentTexts.isEmpty())
    }

    @Test
    fun `the same key reposted by the same sender also fails, since the draft pins the exact reply`() {
        val draft = prepare(post(chat(body = "first")))

        post(chat(body = "second", whenMs = 200))

        assertEquals(failure(RelaySkills.AUTHORITY_LOST), send(draft))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(sentTexts.isEmpty())
    }

    @Test
    fun `an app turned off after the draft fails with nothing sent`() {
        val draft = prepare(post(chat()))

        settings.setAppPolicy(source, RelayAppPolicy.OFF)

        assertEquals(failure(RelaySkills.AUTHORITY_LOST), send(draft))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(sentTexts.isEmpty())
    }

    @Test
    fun `a reply intent the app cancelled reports the authority lost and a later draft cannot use it`() {
        val sbn = chat()
        val id = post(sbn)
        val draft = prepare(id)
        sbn.notification.actions.first().actionIntent.cancel()

        assertEquals(failure(RelaySkills.AUTHORITY_LOST), send(draft))
        assertFalse(ReplyRepository.contains(id))
        assertTrue(listed().isEmpty())
    }

    private companion object {
        const val REPLY_ACTION = "test.relay.skills.REPLY"
    }
}
