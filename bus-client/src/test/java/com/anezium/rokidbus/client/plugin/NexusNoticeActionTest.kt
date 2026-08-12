package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusNoticeActionTest {
    private class FakeTransport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        var featureBits = 0
        val sends = mutableListOf<Pair<String, JSONObject>>()
        val binarySends = mutableListOf<Triple<String, JSONObject, ByteArray>>()

        override fun connect(listener: NexusPluginTransport.Listener) {
            this.listener = listener
        }

        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sends += path to JSONObject(payload.toString())
            return true
        }

        override fun sendBinary(
            path: String,
            id: String,
            payload: JSONObject,
            data: ByteArray,
        ): Boolean {
            binarySends += Triple(path, JSONObject(payload.toString()), data.copyOf())
            return true
        }

        override fun capabilities(): Int = featureBits


        // Null, so these keep exercising the registration-message path: the direct

        // call is the fast path, not the only one.

        override fun approvedCapabilities(): String? = null
        override fun close() = Unit
    }

    private class RecordingCallbacks : NexusPluginCallbacks {
        val events = mutableListOf<String>()

        override fun onOpen() = Unit
        override fun onClose() = Unit
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onNoticeInput(event: NexusInputEvent) {
            events += "input:${event.keyCode}"
        }
        override fun onNoticeAction(id: String) {
            events += "action:$id"
        }
        override fun onNoticeTextSubmitted(id: String, text: String) {
            events += "text:$id:$text"
        }
        override fun onRegistrationState(result: Int) = Unit
    }

    private data class Fixture(
        val client: NexusPluginClient,
        val transport: FakeTransport,
        val callbacks: RecordingCallbacks,
    )

    /**
     * The compatibility pin, on the SDK's side of the wire. Asserted on keys and
     * values, never on a serialised string: `JSONObject` is HashMap-backed here,
     * so key order is not stable and the receiver reads by key anyway.
     */
    @Test
    fun `a notice with no actions sends exactly what it always sent`() {
        val fixture = approvedFixture()

        assertEquals(
            NexusSdkResult.SENT,
            fixture.client.showNotice(
                NexusNotice(
                    title = "Marie",
                    body = "On my way",
                    footer = "tap to reply",
                    interactive = true,
                    ttlMs = 8_000L,
                ),
            ),
        )

        val (path, payload) = fixture.transport.sends.single()
        assertEquals(BusPaths.NOTICE_SHOW, path)
        assertEquals(
            setOf("surfaceId", "kind", "title", "body", "footer", "interactive", "ttlMs"),
            payload.keys().asSequence().toSet(),
        )
        assertFalse(payload.has("actions"))
        assertFalse(payload.has("wakeDisplay"))
        assertFalse(payload.has("backdrop"))
        assertEquals("notice", payload.getString("surfaceId"))
        assertEquals("notice", payload.getString("kind"))
        assertEquals("Marie", payload.getString("title"))
        assertEquals("On my way", payload.getString("body"))
        assertEquals("tap to reply", payload.getString("footer"))
        assertTrue(payload.getBoolean("interactive"))
        assertEquals(8_000L, payload.getLong("ttlMs"))
    }

    @Test
    fun `notice serializes wake display only when requested`() {
        val fixture = approvedFixture()

        fixture.client.showNotice(NexusNotice(title = "Quiet"))
        fixture.client.showNotice(NexusNotice(title = "Wake", wakeDisplay = true))

        assertFalse(fixture.transport.sends[0].second.has("wakeDisplay"))
        assertTrue(fixture.transport.sends[1].second.getBoolean("wakeDisplay"))
    }

    @Test
    fun `notice serializes backdrop only when requested`() {
        val fixture = approvedFixture()

        fixture.client.showNotice(NexusNotice(title = "Plain"))
        fixture.client.showNotice(NexusNotice(title = "Private", backdrop = true))

        assertFalse(fixture.transport.sends[0].second.has("backdrop"))
        assertTrue(fixture.transport.sends[1].second.getBoolean("backdrop"))
    }

    @Test
    fun `an update with no actions sends exactly what it always sent`() {
        val fixture = approvedFixture()

        fixture.client.updateNotice(NexusNoticeUpdate(footer = "  Answered  "))

        val (path, payload) = fixture.transport.sends.single()
        assertEquals(BusPaths.NOTICE_UPDATE, path)
        assertEquals(setOf("surfaceId", "footer"), payload.keys().asSequence().toSet())
        assertEquals("Answered", payload.getString("footer"))
    }

    @Test
    fun `structured lines normalize and serialize only when nonempty`() {
        val fixture = approvedFixture()

        fixture.client.showNotice(
            NexusNotice(
                lines = listOf("  First\nmessage  ", "   ", "Second message"),
            ),
        )
        fixture.client.updateNotice(NexusNoticeUpdate(lines = listOf("Updated\r\nmessage")))
        fixture.client.updateNotice(NexusNoticeUpdate(lines = emptyList()))

        val shown = fixture.transport.sends[0].second
        assertFalse(shown.has("body"))
        assertEquals(2, shown.getJSONArray("lines").length())
        assertEquals("First message", shown.getJSONArray("lines").getString(0))
        assertEquals("Second message", shown.getJSONArray("lines").getString(1))

        val updated = fixture.transport.sends[1].second
        assertEquals("Updated message", updated.getJSONArray("lines").getString(0))
        assertEquals(
            setOf("surfaceId"),
            fixture.transport.sends[2].second.keys().asSequence().toSet(),
        )
    }

    @Test
    fun `notice models enforce line exclusivity count and shared budget`() {
        NexusNotice(lines = List(NoticeSurfaceContract.MAX_LINES) { "line $it" })
        NexusNotice(lines = listOf("x".repeat(NoticeSurfaceContract.MAX_BODY_CHARS - 1)))

        assertThrows(IllegalArgumentException::class.java) {
            NexusNotice(body = "paragraph", lines = listOf("line"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusNoticeUpdate(body = "paragraph", lines = listOf("line"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusNotice(lines = List(NoticeSurfaceContract.MAX_LINES + 1) { "line $it" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusNotice(lines = listOf("x".repeat(NoticeSurfaceContract.MAX_BODY_CHARS)))
        }
    }

    @Test
    fun `actions travel trimmed and in order on show and update`() {
        val fixture = approvedFixture()
        val actions = listOf(
            NexusNoticeAction("  reply  ", "  phone  ", "  Reply  "),
            NexusNoticeAction("later", "timer", "Later"),
        )

        assertEquals(
            NexusSdkResult.SENT,
            fixture.client.showNotice(NexusNotice(title = "Marie", actions = actions)),
        )
        fixture.client.updateNotice(NexusNoticeUpdate(actions = actions.take(1)))

        val shown = fixture.transport.sends[0].second.getJSONArray("actions")
        assertEquals(2, shown.length())
        assertEquals("reply", shown.getJSONObject(0).getString("id"))
        assertEquals("phone", shown.getJSONObject(0).getString("glyph"))
        assertEquals("Reply", shown.getJSONObject(0).getString("label"))
        assertEquals("later", shown.getJSONObject(1).getString("id"))

        val updated = fixture.transport.sends[1].second.getJSONArray("actions")
        assertEquals(1, updated.length())
        assertEquals("reply", updated.getJSONObject(0).getString("id"))
    }

    @Test
    fun `an image notice uses one binary show envelope`() {
        val fixture = approvedFixture()
        val bytes = jpeg(width = 480, height = 160)
        val notice = NexusNotice(
            title = "Marie",
            body = "On my way",
            image = NexusNoticeImage(
                contentKey = "message-photo",
                mimeType = ImageSurfaceContract.MIME_JPEG,
                pixelWidth = 480,
                pixelHeight = 160,
            ),
        )

        assertEquals(NexusSdkResult.INVALID_PAYLOAD, fixture.client.showNotice(notice))
        assertEquals(NexusSdkResult.SENT, fixture.client.showNotice(notice, bytes))

        assertTrue(fixture.transport.sends.isEmpty())
        val (path, payload, sentBytes) = fixture.transport.binarySends.single()
        assertEquals(BusPaths.NOTICE_SHOW, path)
        assertEquals("notice", payload.getString("kind"))
        assertEquals("message-photo", payload.getString("contentKey"))
        assertEquals(ImageSurfaceContract.sha256(bytes), payload.getString("sha256"))
        assertTrue(bytes.contentEquals(sentBytes))
    }

    /**
     * A notice takes one answer, so asking again has to be sayable. Only a
     * plugin that actually sets the flag sends it: a text update that carried
     * it by accident would read on the glasses as the owner re-asking.
     */
    @Test
    fun `an update carries the interactive flag only when it is set`() {
        val fixture = approvedFixture()

        fixture.client.updateNotice(NexusNoticeUpdate(body = "Listening…"))
        fixture.client.updateNotice(NexusNoticeUpdate(interactive = true))
        fixture.client.updateNotice(NexusNoticeUpdate(body = "Done", interactive = false))

        assertFalse(fixture.transport.sends[0].second.has("interactive"))
        assertTrue(fixture.transport.sends[1].second.getBoolean("interactive"))
        assertFalse(fixture.transport.sends[2].second.getBoolean("interactive"))
    }

    @Test
    fun `the model refuses a fourth action and a malformed one`() {
        val three = List(NoticeSurfaceContract.MAX_ACTIONS) { index ->
            NexusNoticeAction("id-$index", "play", "Label")
        }
        NexusNotice(title = "Marie", actions = three)

        assertThrows(IllegalArgumentException::class.java) {
            NexusNotice(title = "Marie", actions = three + NexusNoticeAction("extra", "play", "X"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusNoticeUpdate(actions = three + NexusNoticeAction("extra", "play", "X"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusNoticeAction(" ", "play", "Reply")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusNoticeAction("reply", "not a glyph", "Reply")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusNoticeAction("reply", "play", " ")
        }
    }

    @Test
    fun `notice action callbacks are owner checked and deduplicated`() {
        val fixture = approvedFixture()
        val action = pluginPayload().put("noticeId", "hello:notice").put("id", "reply")

        fixture.transport.listener.onMessage(BusPaths.NOTICE_ACTION, "action-1", action)
        fixture.transport.listener.onMessage(BusPaths.NOTICE_ACTION, "action-1", action)
        fixture.transport.listener.onMessage(
            BusPaths.NOTICE_ACTION,
            "action-wrong-notice",
            pluginPayload().put("noticeId", "other:notice").put("id", "later"),
        )
        fixture.transport.listener.onMessage(
            BusPaths.NOTICE_ACTION,
            "action-wrong-plugin",
            JSONObject()
                .put("pluginId", "other")
                .put("noticeId", "other:notice")
                .put("id", "later"),
        )
        fixture.transport.listener.onMessage(
            BusPaths.NOTICE_ACTION,
            "action-no-id",
            pluginPayload().put("noticeId", "hello:notice").put("id", ""),
        )
        fixture.transport.listener.onMessage(
            BusPaths.NOTICE_INPUT,
            "input-1",
            pluginPayload().put("keyCode", 66).put("action", 0),
        )

        assertEquals(listOf("action:reply", "input:66"), fixture.callbacks.events)
    }

    @Test
    fun `notice text input is capability gated and submission is owner checked`() {
        val legacyFixture = approvedFixture(BusCapabilityBits.NOTICE_SURFACE)
        val fixture = approvedFixture()
        val notice = NexusNotice(
            title = "Assistant",
            body = "Type on your phone",
            textInput = NexusNoticeTextInput("question", "Ask Assistant"),
        )

        assertEquals(
            NexusSdkResult.CAPABILITY_NOT_AVAILABLE,
            legacyFixture.client.showNotice(notice),
        )
        assertTrue(legacyFixture.transport.sends.isEmpty())
        assertEquals(NexusSdkResult.SENT, fixture.client.showNotice(notice))
        assertEquals(
            "question",
            fixture.transport.sends.single().second
                .getJSONObject("textInput")
                .getString("id"),
        )

        val submission = pluginPayload()
            .put("noticeId", "hello:notice")
            .put("inputId", "question")
            .put("text", "private question")
        fixture.transport.listener.onMessage(BusPaths.NOTICE_TEXT_SUBMIT, "text-1", submission)
        fixture.transport.listener.onMessage(BusPaths.NOTICE_TEXT_SUBMIT, "text-1", submission)

        assertEquals(listOf("text:question:private question"), fixture.callbacks.events)
    }

    private fun approvedFixture(
        featureBits: Int = BusCapabilityBits.NOTICE_SURFACE or
            BusCapabilityBits.NOTICE_TEXT_INPUT or
            BusCapabilityBits.IMAGE_SURFACE,
    ): Fixture = fixture().also { fixture ->
        fixture.transport.featureBits = featureBits
        fixture.transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "registration-${System.identityHashCode(fixture)}",
            pluginPayload()
                .put("result", PluginRegistrationResult.APPROVED)
                .put("capabilities", "surfaces"),
        )
        fixture.transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        fixture.transport.sends.clear()
    }

    private fun fixture(): Fixture {
        val transport = FakeTransport()
        val callbacks = RecordingCallbacks()
        val client = NexusPluginClient("hello", callbacks, transport)
        client.connect()
        return Fixture(client, transport, callbacks)
    }

    private fun pluginPayload() = JSONObject().put("pluginId", "hello")

    private fun jpeg(width: Int, height: Int): ByteArray =
        ByteArray(128).also { bytes ->
            byteArrayOf(
                0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(),
                0x00, 0x11, 0x08,
                (height ushr 8).toByte(), height.toByte(),
                (width ushr 8).toByte(), width.toByte(),
                0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00,
                0xff.toByte(), 0xd9.toByte(),
            ).copyInto(bytes)
        }
}
