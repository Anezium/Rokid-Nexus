package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageInteractiveContractTest {
    private val send = PageSurfaceAction("send", "Send", "plugin", confirm = true, typed = PageActionType.ComposeSend)
    private val play = PageSurfaceAction("play", "Play", "plugin", confirm = true, typed = PageActionType.MediaPlay)
    private val next = PageSurfaceAction("next", "Next", "plugin", confirm = true, typed = PageActionType.MediaNext)
    private val check = PageSurfaceAction("check:milk:1", "Done", "plugin", confirm = true, typed = PageActionType.SetChecked(true))
    private val plain = PageSurfaceAction("open", "Open", "plugin", confirm = true)

    private val conversation = PageBody.Conversation(
        threadKey = "relay:t:9f2c",
        caption = "captured 12:41",
        messages = listOf(
            ConversationMessage("m1", ConversationRole.RECEIVED, "On se voit à 8h ?", sender = "Mika"),
            ConversationMessage("m2", ConversationRole.OURS, "Oui", status = ConversationMessageStatus.HANDED_OFF),
            ConversationMessage("m3", ConversationRole.UNKNOWN, "Someone joined"),
        ),
        composer = ConversationComposer("Mika", "send", maxChars = 500, replyable = true),
    )
    private val media = PageBody.Media(
        title = "Teardrop",
        state = MediaPlaybackState.PAUSED,
        artist = "Massive Attack",
        album = "Mezzanine",
        source = "Spotify",
        controls = listOf("play", "next"),
        defaultControl = "play",
    )
    private val geometry = RouteGeometry(300.0, listOf(RoutePath(RoutePathRole.ROUTE, listOf(RoutePoint(500, 900), RoutePoint(500, 100)))))
    private val route = PageBody.Route(
        position = RoutePositionQuality.STALE,
        maneuver = RouteManeuver("turn-right", "120 m", street = "Rue de Rivoli", eta = "12:41"),
        facts = listOf(PageFact("left", "Left", "1.2 km")),
        follow = geometry,
        overview = geometry.copy(spanMeters = 2_000.0),
        ribbon = RouteRibbon(
            stops = listOf(
                RouteRibbonStop("Châtelet", RouteStopState.PASSED, "12:30"),
                RouteRibbonStop("Louvre", RouteStopState.UPCOMING),
                RouteRibbonStop("Concorde", RouteStopState.ALIGHT, "12:38"),
            ),
            line = "1",
            between = RouteRibbonBetween(after = 0, fraction = 0.5),
            note = "Get off at Concorde",
        ),
        fixAgeS = 12,
    )

    @Test
    fun `the new bodies decode only once the bundle is negotiated`() {
        val actions = listOf(send, play, next)
        listOf(conversation, media, route).forEach { body ->
            val encoded = PageTemplateContract.encode(body).toString()
            assertEquals(body, valid(PageTemplateContract.decode(body.template, encoded, actions, "relay", extended = true)))
            // Without the bundle they stay reserved: never read as another template.
            invalid(PageTemplateContract.decode(body.template, encoded, actions, "relay"))
            assertFalse(PageTemplateContract.isSupported(body.template))
            assertTrue(PageTemplateContract.isSupported(body.template, extended = true))
        }
        assertFalse(PageTemplateContract.isSupported(PageSurfaceContract.TEMPLATE_INK, extended = true))
        assertEquals(PageTemplateContract.supportedTemplates, PageTemplateContract.supportedTemplates(extended = false))
        assertEquals(
            PageTemplateContract.supportedTemplates + setOf("conversation", "media", "route"),
            PageTemplateContract.supportedTemplates(extended = true),
        )
    }

    @Test
    fun `an unknown template is never decoded as commands`() {
        val commandsBody = """{"items":[{"id":"a","label":"A"}]}"""
        invalid(PageTemplateContract.decode("ink", commandsBody, emptySet(), "nav", extended = true))
        invalid(PageTemplateContract.decode("future", commandsBody, emptySet(), "nav", extended = true))
    }

    @Test
    fun `the composer must name a compose send action`() {
        val encoded = PageTemplateContract.encode(conversation).toString()
        invalid(PageTemplateContract.decode("conversation", encoded, listOf(send.copy(typed = null)), "relay", extended = true))
        invalid(PageTemplateContract.decode("conversation", encoded, listOf(play.copy(id = "send")), "relay", extended = true))
        invalid(PageTemplateContract.decode("conversation", encoded, emptyList<PageSurfaceAction>(), "relay", extended = true))
        // Knowing only ids is not knowing the type.
        invalid(PageTemplateContract.decode("conversation", encoded, setOf("send"), "relay", extended = true))
        // Without a composer, the conversation reads on its own.
        valid(PageTemplateContract.decode("conversation", PageTemplateContract.encode(conversation.copy(composer = null)).toString(), emptySet(), "relay", extended = true))
    }

    @Test
    fun `media controls must be media actions and the default one of them`() {
        val encoded = PageTemplateContract.encode(media).toString()
        invalid(PageTemplateContract.decode("media", encoded, listOf(play, next.copy(typed = PageActionType.SetChecked(true))), "media", extended = true))
        invalid(PageTemplateContract.decode("media", encoded, listOf(play), "media", extended = true))
        invalid(PageTemplateContract.decode("media", PageTemplateContract.encode(media.copy(defaultControl = "stop")).toString(), listOf(play, next), "media", extended = true))
        invalid(PageTemplateContract.decode("media", PageTemplateContract.encode(media.copy(controls = listOf("play", "play"))).toString(), listOf(play, next), "media", extended = true))
        val four = JSONObject(encoded).put("controls", JSONArray().put("play").put("next").put("play").put("next"))
        invalid(PageTemplateContract.decode("media", four.toString(), listOf(play, next), "media", extended = true))
        invalid(PageTemplateContract.decode("media", JSONObject(encoded).put("state", "buffering").toString(), listOf(play, next), "media", extended = true))
        invalid(PageTemplateContract.decode("media", JSONObject(encoded).put("title", " ").toString(), listOf(play, next), "media", extended = true))
    }

    @Test
    fun `no row can target a send`() {
        val list = JSONObject().put("items", JSONArray().put(JSONObject().put("id", "r").put("label", "Send").put("target", JSONObject().put("actionId", "send")))).toString()
        invalid(PageTemplateContract.decode("selectableList", list, listOf(send), "relay"))
        valid(PageTemplateContract.decode("selectableList", list, listOf(send.copy(typed = null)), "relay"))
    }

    @Test
    fun `conversation and route limits hold`() {
        fun conversationWith(edit: JSONObject.() -> Unit) = JSONObject(PageTemplateContract.encode(conversation).toString()).apply(edit).toString()
        invalid(PageTemplateContract.decode("conversation", conversationWith { put("threadKey", "has space") }, listOf(send), "relay", extended = true))
        invalid(PageTemplateContract.decode("conversation", conversationWith { put("caption", "x".repeat(65)) }, listOf(send), "relay", extended = true))
        invalid(PageTemplateContract.decode("conversation", conversationWith { getJSONArray("messages").getJSONObject(0).put("role", "them") }, listOf(send), "relay", extended = true))
        invalid(PageTemplateContract.decode("conversation", conversationWith { getJSONArray("messages").getJSONObject(0).put("text", "x".repeat(2001)) }, listOf(send), "relay", extended = true))
        invalid(PageTemplateContract.decode("conversation", conversationWith { getJSONArray("messages").getJSONObject(1).put("status", "read") }, listOf(send), "relay", extended = true))
        invalid(PageTemplateContract.decode("conversation", conversationWith { getJSONObject("composer").put("maxChars", 1001) }, listOf(send), "relay", extended = true))
        invalid(PageTemplateContract.decode("conversation", conversationWith { getJSONObject("composer").put("maxChars", 0) }, listOf(send), "relay", extended = true))
        val many = JSONArray().apply { repeat(PageTemplateContract.MAX_MESSAGES + 1) { put(JSONObject().put("id", "m$it").put("role", "received").put("text", "hi")) } }
        invalid(PageTemplateContract.decode("conversation", conversationWith { put("messages", many) }, listOf(send), "relay", extended = true))

        fun routeWith(edit: JSONObject.() -> Unit) = JSONObject(PageTemplateContract.encode(route).toString()).apply(edit).toString()
        invalid(PageTemplateContract.decode("route", routeWith { put("position", "approximate") }, emptySet(), "nav", extended = true))
        invalid(PageTemplateContract.decode("route", routeWith { remove("position") }, emptySet(), "nav", extended = true))
        invalid(PageTemplateContract.decode("route", routeWith { getJSONObject("ribbon").put("between", JSONObject().put("after", 2).put("fraction", 0.5)) }, emptySet(), "nav", extended = true))
        invalid(PageTemplateContract.decode("route", routeWith { getJSONObject("ribbon").put("between", JSONObject().put("after", 0).put("fraction", 1.5)) }, emptySet(), "nav", extended = true))
        invalid(PageTemplateContract.decode("route", routeWith { getJSONObject("follow").put("spanMeters", 0) }, emptySet(), "nav", extended = true))
        invalid(PageTemplateContract.decode("route", routeWith { getJSONObject("maneuver").put("glyph", "Turn Right") }, emptySet(), "nav", extended = true))
        val facts = JSONArray().apply { repeat(PageTemplateContract.MAX_ROUTE_FACTS + 1) { put(JSONObject().put("id", "f$it").put("label", "L").put("value", "v")) } }
        invalid(PageTemplateContract.decode("route", routeWith { put("facts", facts) }, emptySet(), "nav", extended = true))
        valid(PageTemplateContract.decode("route", """{"position":"none"}""", emptySet(), "nav", extended = true))
    }

    @Test
    fun `checked rows round trip on every base template`() {
        val rows = listOf(
            PageRow("milk", "Milk", target = PageTarget.Action("check:milk:1"), checked = false),
            PageRow("eggs", "Eggs", checked = true),
            PageRow("note", "Note"),
        )
        val body = PageBody.SelectableList(rows)
        assertEquals(body, valid(PageTemplateContract.decode("selectableList", PageTemplateContract.encode(body).toString(), listOf(check), "todo")))
        invalid(PageTemplateContract.decode("selectableList", """{"items":[{"id":"a","label":"A","checked":"yes"}]}""", emptySet(), "todo"))
    }

    @Test
    fun `typed actions parse, unknown types stay ordinary and only the phone can stamp quick`() {
        val payload = page(
            JSONArray()
                .put(actionJson("check:milk:1", typed = JSONObject().put("type", "setChecked").put("checked", true)).put("hubQuick", true))
                .put(actionJson("future", typed = JSONObject().put("type", "media.shuffle")).put("hubQuick", true))
                .put(actionJson("send", typed = JSONObject().put("type", "compose.send")).put("hubQuick", true))
                .put(actionJson("launch", kind = "immersion", typed = JSONObject().put("type", "media.play"))),
        )
        val fromProvider = pageOf(PageSurfaceContract.validateResponse(payload))
        assertEquals(PageActionType.SetChecked(true), fromProvider.actions[0].typed)
        assertNull(fromProvider.actions[1].typed)
        assertEquals(PageActionType.ComposeSend, fromProvider.actions[2].typed)
        assertNull("a type only means something on a plugin action", fromProvider.actions[3].typed)
        assertTrue(fromProvider.actions.none { it.hubQuick })

        val fromPhone = pageOf(PageSurfaceContract.validateResponse(payload, hubStamps = true))
        assertTrue(fromPhone.actions[0].hubQuick)
        assertFalse("an unknown type is never quick", fromPhone.actions[1].hubQuick)
        assertFalse("a send is never quick", fromPhone.actions[2].hubQuick)

        val malformed = page(JSONArray().put(actionJson("x", typed = JSONObject().put("type", "setChecked"))))
        assertTrue(PageSurfaceContract.validateResponse(malformed) is PageSurfaceValidationResult.Invalid)
    }

    @Test
    fun `the phone strips provider stamps and writes its own`() {
        val payload = page(
            JSONArray()
                .put(actionJson("check:milk:1", typed = JSONObject().put("type", "setChecked").put("checked", true)).put("hubQuick", true))
                .put(actionJson("plain").put("hubQuick", true)),
        ).put("body", JSONObject().put("items", JSONArray().put(JSONObject().put("id", "milk").put("label", "Milk").put("checked", true))))
        val stripped = PageSurfaceContract.stripHubStamps(payload)
        (0 until 2).forEach { assertFalse(stripped.getJSONArray("actions").getJSONObject(it).has("hubQuick")) }
        val stamped = PageSurfaceContract.withHubQuick(payload, setOf("check:milk:1"))
        assertTrue(stamped.getJSONArray("actions").getJSONObject(0).getBoolean("hubQuick"))
        assertFalse(stamped.getJSONArray("actions").getJSONObject(1).has("hubQuick"))

        val legacy = PageSurfaceContract.stripLauncherFields(stamped)
        val legacyAction = legacy.getJSONArray("actions").getJSONObject(0)
        assertFalse(legacyAction.has("typed"))
        assertFalse(legacyAction.has("hubQuick"))
        assertFalse(legacy.getJSONObject("body").getJSONArray("items").getJSONObject(0).has("checked"))

        val result = JSONObject().put("invocationId", "i1").put("status", "done").put("replacement", stamped)
        val strippedResult = PageSurfaceContract.stripHubStamps(result)
        assertFalse(strippedResult.getJSONObject("replacement").getJSONArray("actions").getJSONObject(0).has("hubQuick"))
        // The input is never mutated.
        assertTrue(payload.getJSONArray("actions").getJSONObject(1).getBoolean("hubQuick"))
    }

    @Test
    fun `invocation modes and composer text`() {
        val base = PageSurfaceInvocation("inv-1", 3, 1, "relay:t", 7, "send")
        // A confirmed invocation serializes exactly as before modes existed.
        assertFalse(base.toPayload().has("mode"))
        assertEquals(base, invocation(base.toPayload()))

        val compose = base.copy(mode = PageSurfaceContract.MODE_COMPOSE, text = "J'arrive", typed = PageActionType.ComposeSend)
        assertEquals(compose, invocation(compose.toPayload()))
        val quick = base.copy(actionId = "check:milk:1", mode = PageSurfaceContract.MODE_QUICK)
        assertEquals(quick, invocation(quick.toPayload()))

        listOf(
            base.toPayload().put("mode", "eager"),
            base.toPayload().put("text", "orphan"),
            base.toPayload().put("mode", "compose"),
            base.toPayload().put("mode", "compose").put("text", "   "),
            base.toPayload().put("mode", "compose").put("text", "x".repeat(PageSurfaceContract.MAX_COMPOSE_TEXT_CHARS + 1)),
            base.toPayload().put("mode", "quick").put("text", "x"),
        ).forEach { assertTrue("$it", PageSurfaceContract.validateAction(it) is PageSurfaceValidationResult.Invalid) }
        assertEquals(1000, invocation(base.toPayload().put("mode", "compose").put("text", "x".repeat(1000))).text!!.length)
    }

    @Test
    fun `a send is reachable only through the composer and quick only for eligible types`() {
        assertTrue(PageSurfaceContract.modeAllowedFor(PageSurfaceContract.MODE_COMPOSE, PageActionType.ComposeSend))
        assertFalse(PageSurfaceContract.modeAllowedFor(PageSurfaceContract.MODE_CONFIRMED, PageActionType.ComposeSend))
        assertFalse(PageSurfaceContract.modeAllowedFor(PageSurfaceContract.MODE_QUICK, PageActionType.ComposeSend))
        assertFalse(PageSurfaceContract.modeAllowedFor(PageSurfaceContract.MODE_COMPOSE, PageActionType.MediaPlay))
        assertTrue(PageSurfaceContract.modeAllowedFor(PageSurfaceContract.MODE_QUICK, PageActionType.SetChecked(false)))
        assertFalse(PageSurfaceContract.modeAllowedFor(PageSurfaceContract.MODE_QUICK, null))
        assertTrue(PageSurfaceContract.modeAllowedFor(PageSurfaceContract.MODE_CONFIRMED, null))
        assertFalse(PageSurfaceContract.modeAllowedFor("eager", null))
    }

    @Test
    fun `results may carry a short printable code`() {
        val result = JSONObject().put("invocationId", "i1").put("status", "rejected").put("code", PageSurfaceContract.CODE_AUTHORITY_LOST)
        val parsed = (PageSurfaceContract.validateResult(result) as PageSurfaceValidationResult.Valid).value
        assertEquals("authority_lost", parsed.code)
        assertNull((PageSurfaceContract.validateResult(JSONObject().put("invocationId", "i1").put("status", "done")) as PageSurfaceValidationResult.Valid).value.code)
        listOf("", "x".repeat(33), "has space", 7).forEach {
            assertTrue("$it", PageSurfaceContract.validateResult(JSONObject(result.toString()).put("code", it)) is PageSurfaceValidationResult.Invalid)
        }
    }

    @Test
    fun `destinations read provider pages with bounded params`() {
        val read = PageTemplateContract.readDestination(JSONObject().put("pageId", "relay:t:9f2c").put("params", JSONObject().put("k", 1)))
        val destination = (read as PageSurfaceValidationResult.Valid).value
        assertTrue(PageTemplateContract.isDestinationOf(destination, "relay"))
        assertFalse(PageTemplateContract.isDestinationOf(destination, "nav"))
        assertEquals(destination, (PageTemplateContract.readDestination(PageTemplateContract.encodeDestination(destination)) as PageSurfaceValidationResult.Valid).value)
        listOf(
            JSONObject().put("pageId", "nexus:notifications"),
            JSONObject().put("pageId", "relay"),
            JSONObject().put("pageId", "Relay:main"),
            JSONObject().put("pageId", "relay:main").put("params", JSONArray()),
            "relay:main",
        ).forEach { assertTrue("$it", PageTemplateContract.readDestination(it) is PageSurfaceValidationResult.Invalid) }
    }

    private fun page(actions: JSONArray): JSONObject = PageSurfaceContract.correlationPayload(PageCorrelation("r", 1, 1, "todo:main"))
        .put("revision", 1).put("template", "selectableList").put("title", "Todo")
        .put("body", JSONObject().put("items", JSONArray())).put("actions", actions).put("live", true)

    private fun actionJson(id: String, kind: String = "plugin", typed: JSONObject? = null): JSONObject =
        JSONObject().put("id", id).put("label", id.take(24)).put("kind", kind).put("confirm", true).apply {
            typed?.let { put("typed", it) }
        }

    private fun pageOf(result: PageSurfaceValidationResult<PageSurfaceResponse>): PageSurfaceResponse.Page {
        assertTrue("$result", result is PageSurfaceValidationResult.Valid)
        return (result as PageSurfaceValidationResult.Valid).value as PageSurfaceResponse.Page
    }

    private fun invocation(payload: JSONObject): PageSurfaceInvocation {
        val result = PageSurfaceContract.validateAction(JSONObject(payload.toString()))
        assertTrue("$result", result is PageSurfaceValidationResult.Valid)
        return (result as PageSurfaceValidationResult.Valid).value
    }

    private fun valid(result: PageSurfaceValidationResult<PageBody>): PageBody {
        assertTrue("$result", result is PageSurfaceValidationResult.Valid)
        return (result as PageSurfaceValidationResult.Valid).value
    }

    private fun invalid(result: PageSurfaceValidationResult<PageBody>) {
        assertTrue("$result", result is PageSurfaceValidationResult.Invalid)
        assertEquals(PageSurfaceContract.ERROR_INVALID_PAGE, (result as PageSurfaceValidationResult.Invalid).error)
    }
}
