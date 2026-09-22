package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusNoticeActionTest {
    private class FakeTransport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        var featureBits = 0
        var directCapabilities: String? = null
        var sendAccepted = true
        val sends = mutableListOf<Pair<String, JSONObject>>()
        val binarySends = mutableListOf<Triple<String, JSONObject, ByteArray>>()

        override fun connect(listener: NexusPluginTransport.Listener) {
            this.listener = listener
        }

        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sends += path to JSONObject(payload.toString())
            return sendAccepted
        }

        override fun sendBinary(
            path: String,
            id: String,
            payload: JSONObject,
            data: ByteArray,
        ): Boolean {
            binarySends += Triple(path, JSONObject(payload.toString()), data.copyOf())
            return sendAccepted
        }

        override fun capabilities(): Int = featureBits


        // Null, so these keep exercising the registration-message path: the direct

        // call is the fast path, not the only one.

        override fun approvedCapabilities(): String? = directCapabilities
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
        override fun onNoticeClosed(reason: NexusNoticeCloseReason) {
            events += "closed:$reason"
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
    fun `a notice with no actions preserves its fields and adds callback correlation`() {
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
            setOf(
                "surfaceId", "kind", "title", "body", "footer", "interactive", "ttlMs",
                NoticeSurfaceContract.FIELD_CLIENT_TOKEN,
            ),
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
        assertTrue(NoticeSurfaceContract.validToken(payload.getString(NoticeSurfaceContract.FIELD_CLIENT_TOKEN)))
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
    fun `an update with no actions preserves its fields and adds callback correlation`() {
        val fixture = approvedFixture()

        fixture.client.updateNotice(NexusNoticeUpdate(footer = "  Answered  "))

        val (path, payload) = fixture.transport.sends.single()
        assertEquals(BusPaths.NOTICE_UPDATE, path)
        assertEquals(
            setOf("surfaceId", "footer", NoticeSurfaceContract.FIELD_CLIENT_TOKEN),
            payload.keys().asSequence().toSet(),
        )
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
            setOf("surfaceId", NoticeSurfaceContract.FIELD_CLIENT_TOKEN),
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
    fun `callbacks already in transit cannot answer or close a replacement from the same plugin`() {
        val fixture = approvedFixture(interactionVersion = 1)
        fixture.client.showNotice(NexusNotice(title = "First", interactive = true))
        val first = fixture.lastToken()
        fixture.client.showNotice(NexusNotice(title = "Second", interactive = true))
        val second = fixture.lastToken()
        assertFalse(first == second)

        fixture.deliver(BusPaths.NOTICE_ACTION, "old-action", first)
        fixture.deliver(BusPaths.NOTICE_INPUT, "old-input", first)
        fixture.deliver(BusPaths.NOTICE_CLOSED, "old-close", first)
        assertTrue(fixture.callbacks.events.isEmpty())

        fixture.deliver(BusPaths.NOTICE_ACTION, "new-action", second)
        fixture.deliver(BusPaths.NOTICE_CLOSED, "new-close", second)
        fixture.deliver(BusPaths.NOTICE_CLOSED, "duplicate-close", second)
        assertEquals(listOf("action:reply", "closed:USER"), fixture.callbacks.events)
    }

    @Test
    fun `cosmetic action countdown and text updates preserve a click in transit`() {
        val fixture = approvedFixture(interactionVersion = 1)
        fixture.client.showNotice(
            NexusNotice(title = "Send reply", actions = listOf(NexusNoticeAction("send", "send", "Sending 3s"))),
        )
        val token = fixture.lastToken()
        fixture.client.updateNotice(NexusNoticeUpdate(body = "Still this reply", ttlMs = 20_000L))
        assertEquals(token, fixture.lastToken())
        fixture.client.updateNotice(
            NexusNoticeUpdate(
                actions = listOf(NexusNoticeAction("send", "send", "Sending 2s")),
                rearm = false,
            ),
        )
        assertEquals(token, fixture.lastToken())
        assertFalse(fixture.transport.sends.last().second.getBoolean("rearm"))
        fixture.deliver(BusPaths.NOTICE_ACTION, "countdown-click", token, actionId = "send")
        assertEquals(listOf("action:send"), fixture.callbacks.events)
    }

    @Test
    fun `rearming with the same action ids invalidates the earlier question`() {
        val fixture = approvedFixture(interactionVersion = 1)
        val actions = listOf(NexusNoticeAction("reply", "reply", "Reply"))
        fixture.client.showNotice(NexusNotice(title = "First question", actions = actions))
        val first = fixture.lastToken()
        fixture.client.updateNotice(NexusNoticeUpdate(body = "Second question", actions = actions))
        val second = fixture.lastToken()
        assertFalse(first == second)
        fixture.deliver(BusPaths.NOTICE_ACTION, "old-answer", first)
        fixture.deliver(BusPaths.NOTICE_ACTION, "new-answer", second)
        assertEquals(listOf("action:reply"), fixture.callbacks.events)

        fixture.client.updateNotice(NexusNoticeUpdate(rearm = true))
        assertEquals(second, fixture.lastToken())
        fixture.client.updateNotice(NexusNoticeUpdate(actions = actions, rearm = true))
        assertFalse(second == fixture.lastToken())
    }

    @Test
    fun `rearm without interaction fields remains a display update`() {
        val fixture = approvedFixture(interactionVersion = 1)
        fixture.client.showNotice(NexusNotice(title = "Current question", interactive = true))
        val token = fixture.lastToken()
        fixture.client.updateNotice(NexusNoticeUpdate(body = "Updated text", rearm = true))
        assertEquals(token, fixture.lastToken())
        fixture.deliver(BusPaths.NOTICE_INPUT, "unchanged-question", token)
        assertEquals(listOf("input:66"), fixture.callbacks.events)
    }

    @Test
    fun `a capable hub must echo a valid current token on every notice callback`() {
        val fixture = approvedFixture(interactionVersion = 1)
        fixture.client.showNotice(NexusNotice(title = "Question", interactive = true))
        for ((index, path) in listOf(BusPaths.NOTICE_ACTION, BusPaths.NOTICE_INPUT, BusPaths.NOTICE_CLOSED).withIndex()) {
            fixture.deliver(path, "missing-$index", null)
            fixture.deliver(path, "malformed-$index", "not a valid token")
            fixture.deliver(path, "wrong-$index", "a-different-token")
        }
        assertTrue(fixture.callbacks.events.isEmpty())
        fixture.deliver(BusPaths.NOTICE_INPUT, "current-input", fixture.lastToken())
        assertEquals(listOf("input:66"), fixture.callbacks.events)
    }

    @Test
    fun `hide rejects further answers but retains one owner closed callback`() {
        val fixture = approvedFixture(interactionVersion = 1)
        fixture.client.showNotice(NexusNotice(title = "Question", interactive = true))
        val token = fixture.lastToken()
        fixture.client.hideNotice()
        assertEquals(token, fixture.lastToken())
        fixture.deliver(BusPaths.NOTICE_ACTION, "answer-after-hide", token)
        fixture.deliver(BusPaths.NOTICE_CLOSED, "hidden", token, reason = "owner")
        fixture.deliver(BusPaths.NOTICE_CLOSED, "hidden-again", token, reason = "owner")
        assertEquals(listOf("closed:OWNER"), fixture.callbacks.events)
    }

    @Test
    fun `SPP loss with CXR still up preserves current action and input callbacks`() {
        for (path in listOf(BusPaths.NOTICE_ACTION, BusPaths.NOTICE_INPUT)) {
            val fixture = approvedFixture(interactionVersion = 1)
            fixture.transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP or LinkStateBits.CXR_CONTROL_UP)
            fixture.client.showNotice(NexusNotice(title = "Question", interactive = true))
            val token = fixture.lastToken()
            fixture.transport.listener.onLinkState(LinkStateBits.CXR_CONTROL_UP)

            fixture.deliver(path, "cxr-answer", token)

            assertEquals(listOf(if (path == BusPaths.NOTICE_ACTION) "action:reply" else "input:66"), fixture.callbacks.events)
        }
    }

    @Test
    fun `SPP reconnect without a new registration preserves the current question`() {
        for (path in listOf(BusPaths.NOTICE_ACTION, BusPaths.NOTICE_INPUT)) {
            val fixture = approvedFixture(interactionVersion = 1)
            val bothLinks = LinkStateBits.SPP_DATA_UP or LinkStateBits.CXR_CONTROL_UP
            fixture.transport.listener.onLinkState(bothLinks)
            fixture.client.showNotice(NexusNotice(title = "Question", interactive = true))
            val token = fixture.lastToken()
            fixture.transport.listener.onLinkState(LinkStateBits.CXR_CONTROL_UP)
            fixture.transport.listener.onLinkState(bothLinks)

            fixture.deliver(path, "reconnected-answer", token)

            assertEquals(listOf(if (path == BusPaths.NOTICE_ACTION) "action:reply" else "input:66"), fixture.callbacks.events)
        }
    }

    @Test
    fun `a fresh registration discards the previous callback context`() {
        val fixture = approvedFixture(interactionVersion = 1)
        fixture.client.showNotice(NexusNotice(title = "Before registration", interactive = true))
        val oldRegistration = fixture.lastToken()
        fixture.transport.listener.onMessage(BusPaths.PLUGIN_REGISTRATION, "fresh-registration", registration(1))
        fixture.deliver(BusPaths.NOTICE_CLOSED, "old-registration-close", oldRegistration)
        assertTrue(fixture.callbacks.events.isEmpty())
    }

    @Test
    fun `a correlated close clears the current question after link loss`() {
        val fixture = approvedFixture(interactionVersion = 1)
        fixture.client.showNotice(NexusNotice(title = "Question", interactive = true))
        val token = fixture.lastToken()
        fixture.transport.listener.onLinkState(0)
        fixture.deliver(BusPaths.NOTICE_CLOSED, "disconnect-close", token, reason = "disconnect")
        fixture.transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        fixture.deliver(BusPaths.NOTICE_INPUT, "after-close", token)
        assertEquals(listOf("closed:DISCONNECT"), fixture.callbacks.events)
    }

    @Test
    fun `SPP reconnect does not reopen a notice explicitly hidden by its owner`() {
        val fixture = approvedFixture(interactionVersion = 1)
        fixture.client.showNotice(NexusNotice(title = "Hidden", interactive = true))
        val token = fixture.lastToken()
        fixture.client.hideNotice()
        fixture.transport.listener.onLinkState(LinkStateBits.CXR_CONTROL_UP)
        fixture.transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP or LinkStateBits.CXR_CONTROL_UP)
        fixture.deliver(BusPaths.NOTICE_ACTION, "hidden-action", token)
        fixture.deliver(BusPaths.NOTICE_INPUT, "hidden-input", token)
        fixture.deliver(BusPaths.NOTICE_CLOSED, "hidden-close", token, reason = "owner")
        assertEquals(listOf("closed:OWNER"), fixture.callbacks.events)
    }

    @Test
    fun `hide followed by show suppresses the old owner close without closing the replacement`() {
        val fixture = approvedFixture(interactionVersion = 1)
        fixture.client.showNotice(NexusNotice(title = "First", interactive = true))
        val first = fixture.lastToken()
        fixture.client.hideNotice()
        fixture.client.showNotice(NexusNotice(title = "Second", interactive = true))
        val second = fixture.lastToken()

        fixture.deliver(BusPaths.NOTICE_CLOSED, "old-owner-close", first, reason = "owner")
        fixture.deliver(BusPaths.NOTICE_ACTION, "new-action", second)
        fixture.deliver(BusPaths.NOTICE_CLOSED, "new-close", second)
        assertEquals(listOf("action:reply", "closed:USER"), fixture.callbacks.events)
    }

    @Test
    fun `the first registration metadata preserves a notice sent from synchronous approval`() {
        val fixture = fixture()
        fixture.transport.featureBits = BusCapabilityBits.NOTICE_SURFACE
        fixture.transport.directCapabilities = "surfaces"
        fixture.transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        fixture.transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        fixture.client.showNotice(NexusNotice(title = "Already shown", interactive = true))
        val token = fixture.lastToken()
        fixture.transport.listener.onMessage(BusPaths.PLUGIN_REGISTRATION, "metadata", registration(1))
        fixture.deliver(BusPaths.NOTICE_INPUT, "answer", token)
        assertEquals(listOf("input:66"), fixture.callbacks.events)
    }

    @Test
    fun `reconnect approval invalidates old context before callbacks can create the next notice`() {
        val fixture = approvedFixture(interactionVersion = 1)
        fixture.transport.directCapabilities = "surfaces"
        fixture.client.showNotice(NexusNotice(title = "Previous connection", interactive = true))
        val previous = fixture.lastToken()
        fixture.transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        fixture.deliver(BusPaths.NOTICE_INPUT, "old-connection", previous)
        fixture.client.showNotice(NexusNotice(title = "New connection", interactive = true))
        val current = fixture.lastToken()
        fixture.transport.listener.onMessage(BusPaths.PLUGIN_REGISTRATION, "new-metadata", registration(1))
        fixture.deliver(BusPaths.NOTICE_INPUT, "new-connection", current)
        assertEquals(listOf("input:66"), fixture.callbacks.events)
    }

    @Test
    fun `correlated callbacks are checked even before token support metadata arrives`() {
        val fixture = approvedFixture()
        fixture.client.showNotice(NexusNotice(title = "First", interactive = true))
        val first = fixture.lastToken()
        fixture.client.showNotice(NexusNotice(title = "Second", interactive = true))
        fixture.deliver(BusPaths.NOTICE_INPUT, "stale-correlated", first)
        assertTrue(fixture.callbacks.events.isEmpty())
        fixture.deliver(BusPaths.NOTICE_INPUT, "legacy-no-token", null)
        assertEquals(listOf("input:66"), fixture.callbacks.events)
    }

    @Test
    fun `raw show update and hide share callback identity without changing caller payloads`() {
        val fixture = approvedFixture(interactionVersion = 1)
        val shown = JSONObject()
            .put("surfaceId", "notice")
            .put("kind", "notice")
            .put("title", "Raw question")
            .put("interactive", true)
            .put(NoticeSurfaceContract.FIELD_CLIENT_TOKEN, "caller-supplied-token")
        assertTrue(fixture.client.send("  /notice/show  ", "raw-show", shown))
        val first = fixture.lastToken()
        assertFalse(first == "caller-supplied-token")
        assertEquals("caller-supplied-token", shown.getString(NoticeSurfaceContract.FIELD_CLIENT_TOKEN))
        assertEquals(BusPaths.NOTICE_SHOW, fixture.transport.sends.last().first)
        fixture.deliver(BusPaths.NOTICE_INPUT, "raw-answer", first)

        val displayUpdate = JSONObject().put("surfaceId", "notice").put("body", "Still this question")
        assertTrue(fixture.client.send(BusPaths.NOTICE_UPDATE, "raw-display-update", displayUpdate))
        assertEquals(first, fixture.lastToken())
        assertFalse(displayUpdate.has(NoticeSurfaceContract.FIELD_CLIENT_TOKEN))

        val nextQuestion = JSONObject().put("surfaceId", "notice").put("interactive", true)
        assertTrue(fixture.client.send(BusPaths.NOTICE_UPDATE, "raw-rearm", nextQuestion))
        val second = fixture.lastToken()
        assertFalse(first == second)
        fixture.deliver(BusPaths.NOTICE_INPUT, "stale-raw-answer", first)
        fixture.deliver(BusPaths.NOTICE_INPUT, "new-raw-answer", second)

        val hidden = JSONObject().put("surfaceId", "notice")
        assertTrue(fixture.client.send(BusPaths.NOTICE_HIDE, "raw-hide", hidden))
        assertEquals(second, fixture.lastToken())
        assertFalse(hidden.has(NoticeSurfaceContract.FIELD_CLIENT_TOKEN))
        fixture.deliver(BusPaths.NOTICE_INPUT, "answer-after-raw-hide", second)
        fixture.deliver(BusPaths.NOTICE_CLOSED, "raw-owner-close", second, reason = "owner")
        assertEquals(listOf("input:66", "input:66", "closed:OWNER"), fixture.callbacks.events)
    }

    @Test
    fun `raw empty action row still advances the question identity`() {
        val fixture = approvedFixture(interactionVersion = 1)
        fixture.client.showNotice(NexusNotice(title = "Question", interactive = true))
        val first = fixture.lastToken()
        val patch = JSONObject().put("surfaceId", "notice").put("actions", JSONArray())
        assertTrue(fixture.client.send(BusPaths.NOTICE_UPDATE, "clear-actions", patch))
        val second = fixture.lastToken()
        assertFalse(first == second)
        fixture.deliver(BusPaths.NOTICE_INPUT, "before-row-clear", first)
        fixture.deliver(BusPaths.NOTICE_INPUT, "after-row-clear", second)
        assertEquals(listOf("input:66"), fixture.callbacks.events)
    }

    @Test
    fun `binary shows establish the same single context used by later raw updates`() {
        val fixture = approvedFixture(interactionVersion = 1)
        val bytes = jpeg(width = 480, height = 160)
        val notice = NexusNotice(
            title = "Photo",
            interactive = true,
            image = NexusNoticeImage("photo", ImageSurfaceContract.MIME_JPEG, 480, 160),
        )
        assertEquals(NexusSdkResult.SENT, fixture.client.showNotice(notice, bytes))
        assertEquals(1, fixture.transport.binarySends.size)
        val first = fixture.transport.binarySends.single().second.getString(NoticeSurfaceContract.FIELD_CLIENT_TOKEN)
        fixture.client.updateNotice(NexusNoticeUpdate(footer = "Photo caption"))
        assertEquals(first, fixture.lastToken())
        fixture.deliver(BusPaths.NOTICE_INPUT, "typed-binary-answer", first)

        val raw = notice.toPayload(bytes)
        assertTrue(fixture.client.sendBinary(BusPaths.NOTICE_SHOW, "raw-binary", raw, bytes))
        assertFalse(raw.has(NoticeSurfaceContract.FIELD_CLIENT_TOKEN))
        assertEquals(2, fixture.transport.binarySends.size)
        val second = fixture.transport.binarySends.last().second.getString(NoticeSurfaceContract.FIELD_CLIENT_TOKEN)
        assertFalse(first == second)
        fixture.deliver(BusPaths.NOTICE_INPUT, "stale-binary-answer", first)
        fixture.deliver(BusPaths.NOTICE_INPUT, "raw-binary-answer", second)
        assertEquals(listOf("input:66", "input:66"), fixture.callbacks.events)
    }

    @Test
    fun `preflight rejection and a failed raw hide do not invalidate the visible question`() {
        val fixture = approvedFixture(interactionVersion = 1)
        fixture.client.showNotice(NexusNotice(title = "Question", interactive = true))
        val token = fixture.lastToken()
        assertEquals(
            NexusSdkResult.INVALID_PAYLOAD,
            fixture.client.showNotice(
                NexusNotice(title = "Missing bytes", image = NexusNoticeImage("photo", ImageSurfaceContract.MIME_JPEG, 480, 160)),
            ),
        )
        assertEquals(1, fixture.transport.sends.size)
        fixture.transport.sendAccepted = false
        assertFalse(fixture.client.send(BusPaths.NOTICE_HIDE, "failed-hide", JSONObject().put("surfaceId", "notice")))
        fixture.deliver(BusPaths.NOTICE_INPUT, "still-visible", token)
        assertEquals(listOf("input:66"), fixture.callbacks.events)
    }

    @Test
    fun `unapproved raw notice sends remain unsent and leave the caller payload untouched`() {
        val fixture = fixture()
        val payload = JSONObject().put("surfaceId", "notice").put("kind", "notice").put("title", "Question")
        assertFalse(fixture.client.send(BusPaths.NOTICE_SHOW, "unapproved-json", payload))
        assertFalse(fixture.client.sendBinary(BusPaths.NOTICE_SHOW, "unapproved-binary", payload, byteArrayOf()))
        assertTrue(fixture.transport.sends.isEmpty())
        assertTrue(fixture.transport.binarySends.isEmpty())
        assertFalse(payload.has(NoticeSurfaceContract.FIELD_CLIENT_TOKEN))
    }

    @Test
    fun `old hubs decline cosmetic rearm suppression while preserving default notice updates`() {
        val fixture = approvedFixture()
        fixture.client.showNotice(NexusNotice(title = "Question", interactive = true))
        val token = fixture.lastToken()
        assertEquals(
            NexusSdkResult.CAPABILITY_NOT_AVAILABLE,
            fixture.client.updateNotice(NexusNoticeUpdate(interactive = true, rearm = false)),
        )
        val raw = JSONObject().put("surfaceId", "notice").put("interactive", true).put("rearm", false)
        assertFalse(fixture.client.send(" /notice/update ", "unsupported-cosmetic", raw))
        assertEquals(1, fixture.transport.sends.size)
        assertFalse(raw.has(NoticeSurfaceContract.FIELD_CLIENT_TOKEN))
        assertEquals(token, fixture.lastToken())
        assertEquals(NexusSdkResult.SENT, fixture.client.updateNotice(NexusNoticeUpdate(body = "Updated")))
        assertEquals(token, fixture.lastToken())
    }

    private fun Fixture.lastToken(): String =
        transport.sends.last().second.getString(NoticeSurfaceContract.FIELD_CLIENT_TOKEN)

    private fun Fixture.deliver(
        path: String,
        eventId: String,
        token: String?,
        actionId: String = "reply",
        reason: String = "user",
    ) {
        val payload = pluginPayload()
            .put("noticeId", "hello:notice")
            .put("id", actionId)
            .put("keyCode", 66)
            .put("action", 0)
            .put("reason", reason)
        token?.let { payload.put(NoticeSurfaceContract.FIELD_CLIENT_TOKEN, it) }
        transport.listener.onMessage(path, eventId, payload)
    }

    private fun registration(interactionVersion: Int): JSONObject = pluginPayload()
        .put("result", PluginRegistrationResult.APPROVED)
        .put("capabilities", "surfaces")
        .put("noticeInteractionVersion", interactionVersion)

    private fun approvedFixture(interactionVersion: Int = 0): Fixture = fixture().also { fixture ->
        fixture.transport.featureBits =
            BusCapabilityBits.NOTICE_SURFACE or BusCapabilityBits.IMAGE_SURFACE
        fixture.transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "registration-${System.identityHashCode(fixture)}",
            registration(interactionVersion),
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
