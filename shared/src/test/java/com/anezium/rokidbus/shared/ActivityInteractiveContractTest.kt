package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityInteractiveContractTest {
    @Test
    fun `a destination-first activity validates and round trips`() {
        val payload = start()
            .put("destination", JSONObject().put("pageId", "todo:list").put("params", JSONObject().put("list", "groceries")))
            .put("persistence", "ongoing")
        val content = valid(payload)
        assertEquals(PageDestination("todo:list", """{"list":"groceries"}"""), content.destination)
        assertEquals(ActivityActivation.DESTINATION, content.effectiveActivation)
        assertEquals(ActivityPersistence.ONGOING, content.persistence)
        assertTrue(ActivitySurfaceContract.destinationAllowed(content, "todo"))
        assertFalse(ActivitySurfaceContract.destinationAllowed(content, "relay"))
        assertEquals(content, valid(JSONObject(ActivitySurfaceContract.toPayload("todo:activity", content).toString())))
    }

    @Test
    fun `the activation default follows destination then actions then nothing`() {
        assertEquals(ActivityActivation.COMMANDS, valid(start().put("actions", actions("play"))).effectiveActivation)
        assertNull(valid(start()).effectiveActivation)
        val both = valid(start().put("actions", actions("play")).put("destination", JSONObject().put("pageId", "media:main")))
        assertEquals(ActivityActivation.DESTINATION, both.effectiveActivation)
        val explicit = valid(
            start().put("actions", actions("play")).put("destination", JSONObject().put("pageId", "media:main")).put("activation", "commands"),
        )
        assertEquals(ActivityActivation.COMMANDS, explicit.effectiveActivation)
        assertEquals("play", explicit.effectiveDefaultActionId)
    }

    @Test
    fun `an explicit activation without its prerequisite is invalid`() {
        invalid(start().put("activation", "destination"))
        invalid(start().put("activation", "commands"))
        invalid(start().put("activation", "teleport"))
        invalid(start().put("actions", actions("a")).put("defaultActionId", "b"))
        invalid(start().put("persistence", "forever"))
        val focused = valid(start().put("actions", actions("prev", "play", "next")).put("defaultActionId", "play"))
        assertEquals("play", focused.effectiveDefaultActionId)
    }

    @Test
    fun `destinations are provider pages within the params budget`() {
        invalid(start().put("destination", JSONObject().put("pageId", "nexus:activities")))
        invalid(start().put("destination", JSONObject().put("pageId", "no-namespace")))
        invalid(start().put("destination", "todo:list"))
        invalid(start().put("destination", JSONObject().put("pageId", "todo:list").put("params", "x")))
        invalid(start().put("destination", JSONObject().put("pageId", "todo:list").put("params", JSONObject().put("k", "x".repeat(PageSurfaceContract.MAX_PARAMS_BYTES)))))
        // Another plugin's page is well formed here; the owner check is the hub's, where the owner is known.
        val foreign = valid(start().put("destination", JSONObject().put("pageId", "relay:main")))
        assertFalse(ActivitySurfaceContract.destinationAllowed(foreign, "todo"))
    }

    @Test
    fun `actions may only be typed media`() {
        val media = valid(start().put("actions", JSONArray().put(action("play").put("typed", JSONObject().put("type", "media.play")))))
        assertEquals(PageActionType.MediaPlay, media.actions.single().typed)
        invalid(start().put("actions", JSONArray().put(action("check").put("typed", JSONObject().put("type", "setChecked").put("checked", true)))))
        invalid(start().put("actions", JSONArray().put(action("send").put("typed", JSONObject().put("type", "compose.send")))))
        invalid(start().put("actions", JSONArray().put(action("shuffle").put("typed", JSONObject().put("type", "media.shuffle")))))
        invalid(start().put("actions", JSONArray().put(action("bad").put("typed", "media.play"))))
    }

    @Test
    fun `the id rule tightens only once a launcher field is used`() {
        val long = "x".repeat(200)
        val repeated = JSONArray().put(action(long)).put(action(long))
        // The v1 shape stays permissive, exactly as shipped.
        assertEquals(2, valid(start().put("actions", repeated)).actions.size)
        invalid(start().put("actions", repeated).put("persistence", "ongoing"))
        invalid(start().put("actions", JSONArray().put(action("a b"))).put("destination", JSONObject().put("pageId", "todo:main")))
        invalid(start().put("actions", JSONArray().put(action("a")).put(action("a").put("typed", JSONObject().put("type", "media.next")))))
        // Defaults never count as use: an update that clears every launcher field keeps v1 rules.
        val cleared = start().put("actions", repeated).put("destination", JSONObject.NULL).put("activation", JSONObject.NULL)
            .put("defaultActionId", JSONObject.NULL).put("persistence", "session")
        assertTrue(ActivitySurfaceContract.validateUpdate(cleared) is ActivitySurfacePatchResult.Valid)
    }

    @Test
    fun `a plugin can never stamp itself`() {
        val forged = start()
            .put("actions", JSONArray().put(action("play").put("typed", JSONObject().put("type", "media.play")).put("hubQuick", true)))
            .put("state", "offline").put("userPinned", true).put("knownAtWallMs", 5)
        val content = valid(forged)
        assertNull(content.state)
        assertFalse(content.userPinned)
        assertNull(content.knownAtWallMs)
        assertFalse(content.actions.single().hubQuick)

        val stripped = ActivitySurfaceContract.stripHubStamps(forged)
        listOf("state", "userPinned", "knownAtWallMs").forEach { assertFalse(it, stripped.has(it)) }
        assertFalse(stripped.getJSONArray("actions").getJSONObject(0).has("hubQuick"))
        assertTrue(forged.has("state"))
    }

    @Test
    fun `the phone's stamps replace whatever arrived and read back on the glasses`() {
        val plugin = start().put("actions", JSONArray()
            .put(action("prev").put("typed", JSONObject().put("type", "media.previous")))
            .put(action("open").put("hubQuick", true)))
            .put("userPinned", false)
        val content = valid(plugin)
        val quick = ActivitySurfaceContract.quickActionIds(content, setOf(QuickActionFamily.MEDIA))
        assertEquals(setOf("prev"), quick)
        assertTrue(ActivitySurfaceContract.quickActionIds(content, setOf(QuickActionFamily.CHECKLIST)).isEmpty())

        val stamped = ActivitySurfaceContract.withHubStamps(
            ActivitySurfaceContract.toPayload("media:activity", content),
            ActivityHubStamps(ActivityState.LIVE, userPinned = true, knownAtWallMs = 1_700_000_000_000),
            quick,
        )
        val glasses = (ActivitySurfaceContract.validateStart(stamped, hubStamps = true) as ActivitySurfaceValidationResult.Valid).content
        assertEquals(ActivityState.LIVE, glasses.state)
        assertTrue(glasses.userPinned)
        assertEquals(1_700_000_000_000, glasses.knownAtWallMs)
        assertTrue(glasses.actions.first { it.id == "prev" }.hubQuick)
        assertFalse(glasses.actions.first { it.id == "open" }.hubQuick)

        // A stamp on an action whose type can never be quick does not stand, even from the phone.
        val forgedQuick = ActivitySurfaceContract.withHubStamps(stamped, ActivityHubStamps(), setOf("open"))
        val reread = (ActivitySurfaceContract.validateStart(forgedQuick, hubStamps = true) as ActivitySurfaceValidationResult.Valid).content
        assertFalse(reread.actions.first { it.id == "open" }.hubQuick)
        assertFalse(reread.actions.first { it.id == "prev" }.hubQuick)
    }

    @Test
    fun `old glasses get a plain v1 activity`() {
        val content = valid(
            start().put("actions", JSONArray().put(action("play").put("typed", JSONObject().put("type", "media.play"))))
                .put("destination", JSONObject().put("pageId", "media:main")).put("activation", "commands")
                .put("defaultActionId", "play").put("persistence", "ongoing").put("badge", "38"),
        )
        val stamped = ActivitySurfaceContract.withHubStamps(
            ActivitySurfaceContract.toPayload("media:activity", content),
            ActivityHubStamps(userPinned = true),
            setOf("play"),
        )
        val legacy = ActivitySurfaceContract.stripLauncherFields(stamped)
        listOf("destination", "activation", "defaultActionId", "persistence", "state", "userPinned", "knownAtWallMs").forEach {
            assertFalse(it, legacy.has(it))
        }
        val legacyAction = legacy.getJSONArray("actions").getJSONObject(0)
        assertFalse(legacyAction.has("typed"))
        assertFalse(legacyAction.has("hubQuick"))
        assertEquals("38", legacy.getString("badge"))
        val reread = valid(legacy)
        assertFalse(reread.usesInteractiveFields)
    }

    @Test
    fun `a v1 activity serializes without any launcher field`() {
        val payload = ActivitySurfaceContract.toPayload("timer:activity", valid(start().put("actions", actions("stop"))))
        listOf("destination", "activation", "defaultActionId", "persistence", "state", "userPinned", "knownAtWallMs").forEach {
            assertFalse(it, payload.has(it))
        }
        assertFalse(payload.getJSONArray("actions").getJSONObject(0).has("typed"))
    }

    @Test
    fun `a full update clears the destination and returns to session`() {
        val content = valid(start().put("destination", JSONObject().put("pageId", "todo:list")).put("persistence", "ongoing"))
        val update = ActivitySurfaceContract.toUpdatePayload("todo:activity", content.copy(destination = null, persistence = ActivityPersistence.SESSION), significant = false)
        assertTrue(update.isNull("destination"))
        assertEquals("session", update.getString("persistence"))
        val patch = (ActivitySurfaceContract.validateUpdate(update) as ActivitySurfacePatchResult.Valid).patch
        val applied = patch.applyTo(content)
        assertNull(applied.destination)
        assertEquals(ActivityPersistence.SESSION, applied.persistence)
        assertNull(ActivitySurfaceContract.interactiveProblem(applied))

        // An update that names an action the activity no longer has is caught on the patched content.
        val actionsLater = valid(start().put("actions", actions("a", "b")))
        val focusOnB = (ActivitySurfaceContract.validateUpdate(JSONObject().put("defaultActionId", "b")) as ActivitySurfacePatchResult.Valid).patch
        assertNull(ActivitySurfaceContract.interactiveProblem(focusOnB.applyTo(actionsLater)))
        val dropB = (ActivitySurfaceContract.validateUpdate(JSONObject().put("actions", actions("a"))) as ActivitySurfacePatchResult.Valid).patch
        assertTrue(ActivitySurfaceContract.interactiveProblem(dropB.applyTo(focusOnB.applyTo(actionsLater))) != null)
        // Self-contained contradictions are refused at once.
        assertTrue(ActivitySurfaceContract.validateUpdate(JSONObject().put("actions", actions("a")).put("defaultActionId", "z")) is ActivitySurfacePatchResult.Invalid)
    }

    @Test
    fun `an offline snapshot keeps what it said and loses what acts`() {
        val live = valid(
            start().put("actions", actions("play")).put("activation", "commands")
                .put("destination", JSONObject().put("pageId", "media:main")).put("defaultActionId", "play"),
        )
        val offline = ActivitySurfaceContract.offlineSnapshot(live, knownAtWallMs = 42)
        assertTrue(offline.isOffline)
        assertTrue(offline.actions.isEmpty())
        assertEquals(live.destination, offline.destination)
        assertEquals(ActivityActivation.DESTINATION, offline.effectiveActivation)
        assertEquals(42L, offline.knownAtWallMs)
        assertNull(ActivitySurfaceContract.interactiveProblem(offline))
        assertTrue(ActivitySurfaceContract.quickActionIds(offline, QuickActionFamily.entries.toSet()).isEmpty())
        val wire = ActivitySurfaceContract.withHubStamps(
            ActivitySurfaceContract.toPayload("media:activity", offline),
            ActivityHubStamps(ActivityState.OFFLINE, knownAtWallMs = 42),
            emptySet(),
        )
        assertEquals(offline, (ActivitySurfaceContract.validateStart(wire, hubStamps = true) as ActivitySurfaceValidationResult.Valid).content)
    }

    @Test
    fun `commands carry an invocation and a revision and results are bounded`() {
        val fromGlasses = ActivityCommand("media:activity", "play", "inv-1", seq = 41, mode = ActivityCommandMode.QUICK)
        val payload = ActivitySurfaceContract.commandPayload(fromGlasses)
        assertTrue(ActivitySurfaceContract.isCommandPayload(payload))
        assertEquals(fromGlasses, ActivitySurfaceContract.parseCommand(payload, requireMode = true))
        assertNull(ActivitySurfaceContract.parseCommand(JSONObject(payload.toString()).apply { remove("mode") }, requireMode = true))
        assertNull(ActivitySurfaceContract.parseCommand(JSONObject(payload.toString()).put("mode", "eager")))
        assertNull(ActivitySurfaceContract.parseCommand(JSONObject(payload.toString()).put("seq", -1)))
        assertNull(ActivitySurfaceContract.parseCommand(JSONObject(payload.toString()).put("invocationId", "has space")))
        assertFalse(ActivitySurfaceContract.isCommandPayload(ActivitySurfaceContract.actionPayload("media:activity", "play")))

        val delivered = ActivitySurfaceContract.commandPayload(fromGlasses.copy(mode = null, typed = PageActionType.MediaPlay))
        assertEquals(PageActionType.MediaPlay, ActivitySurfaceContract.parseCommand(delivered)!!.typed)

        val action = ActivityAction("play", "play", "Play", typed = PageActionType.MediaPlay)
        assertTrue(ActivitySurfaceContract.modeAllowedFor(ActivityCommandMode.QUICK, action))
        assertFalse(ActivitySurfaceContract.modeAllowedFor(ActivityCommandMode.QUICK, action.copy(typed = null)))
        assertTrue(ActivitySurfaceContract.modeAllowedFor(ActivityCommandMode.CONFIRMED, action.copy(typed = null)))

        val result = ActivityCommandResult("media:activity", "inv-1", ActivityCommandStatus.DONE, "Playing")
        assertEquals(result, ActivitySurfaceContract.parseResult(ActivitySurfaceContract.resultPayload(result)))
        assertNull(ActivitySurfaceContract.parseResult(ActivitySurfaceContract.resultPayload(result).put("status", "ok")))
        assertNull(ActivitySurfaceContract.parseResult(ActivitySurfaceContract.resultPayload(result).put("message", "x".repeat(65))))
        assertEquals("ACTIVITY_DISABLED", ActivitySurfaceContract.ERROR_ACTIVITY_DISABLED)
    }

    @Test
    fun `geometry and heading messages validate their payloads`() {
        val geometry = RouteGeometry(
            spanMeters = 300.0,
            paths = listOf(RoutePath(RoutePathRole.ROUTE, listOf(RoutePoint(500, 900), RoutePoint(500, 100)))),
        )
        val update = ActivityGeometryUpdate(3, geometry)
        assertEquals(update, ActivitySurfaceContract.parseGeometry(JSONObject(ActivitySurfaceContract.geometryPayload(update).toString())))
        assertNull(ActivitySurfaceContract.parseGeometry(ActivitySurfaceContract.geometryPayload(update).put("revision", -3)))
        assertNull(ActivitySurfaceContract.parseGeometry(ActivitySurfaceContract.geometryPayload(update).put("geometry", JSONObject())))

        val heading = ActivityHeading(deg = 359.5, atWallMs = 1_000, accuracyDeg = 12.0)
        assertEquals(heading, ActivitySurfaceContract.parseHeading(JSONObject(ActivitySurfaceContract.headingPayload(heading).toString())))
        assertNull(ActivitySurfaceContract.parseHeading(JSONObject().put("deg", 360).put("atWallMs", 1)))
        assertNull(ActivitySurfaceContract.parseHeading(JSONObject().put("deg", -0.5).put("atWallMs", 1)))
        assertNull(ActivitySurfaceContract.parseHeading(JSONObject().put("deg", 10)))
        assertNull(ActivitySurfaceContract.parseHeading(JSONObject().put("deg", 10).put("atWallMs", 1).put("accuracyDeg", 181)))
        assertEquals(ActivityHeading(0.0, 1), ActivitySurfaceContract.parseHeading(JSONObject().put("deg", 0).put("atWallMs", 1)))
    }

    private fun valid(payload: JSONObject): ActivitySurfaceContent {
        val result = ActivitySurfaceContract.validateStart(payload)
        assertTrue("$result", result is ActivitySurfaceValidationResult.Valid)
        return (result as ActivitySurfaceValidationResult.Valid).content
    }

    private fun invalid(payload: JSONObject) {
        val result = ActivitySurfaceContract.validateStart(payload)
        assertTrue("$payload", result is ActivitySurfaceValidationResult.Invalid)
    }

    private fun start(): JSONObject = JSONObject()
        .put("kind", ActivitySurfaceContract.KIND)
        .put("glyph", "timer")
        .put("primary", "4 min")

    private fun action(id: String): JSONObject = JSONObject().put("id", id).put("glyph", "play").put("label", id)

    private fun actions(vararg ids: String): JSONArray = JSONArray().apply { ids.forEach { put(action(it)) } }
}
