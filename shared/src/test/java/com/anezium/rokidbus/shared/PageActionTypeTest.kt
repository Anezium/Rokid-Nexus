package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageActionTypeTest {
    private val all = listOf(
        PageActionType.SetChecked(true),
        PageActionType.SetChecked(false),
        PageActionType.MediaPrevious,
        PageActionType.MediaPlay,
        PageActionType.MediaPause,
        PageActionType.MediaNext,
        PageActionType.ComposeSend,
    )

    @Test
    fun `every type round trips through its wire object`() {
        all.forEach { type ->
            val read = PageActionTypeContract.read(JSONObject(PageActionTypeContract.encode(type).toString()))
            assertEquals(PageActionTypeRead.Known(type), read)
        }
        val desired = PageActionTypeContract.encode(PageActionType.SetChecked(false))
        assertEquals("setChecked", desired.getString("type"))
        assertFalse(desired.getBoolean("checked"))
    }

    @Test
    fun `families decide what may ever be quick and a send never is`() {
        assertEquals(QuickActionFamily.CHECKLIST, PageActionType.SetChecked(true).family)
        listOf(PageActionType.MediaPrevious, PageActionType.MediaPlay, PageActionType.MediaPause, PageActionType.MediaNext).forEach {
            assertEquals(QuickActionFamily.MEDIA, it.family)
            assertTrue(it.isMedia)
            assertTrue(it.quickEligible)
        }
        assertNull(PageActionType.ComposeSend.family)
        assertFalse(PageActionType.ComposeSend.quickEligible)
        assertFalse(QuickActionPolicyContract.isQuick(PageActionType.ComposeSend, QuickActionFamily.entries.toSet()))
        assertFalse(QuickActionPolicyContract.isQuick(null, QuickActionFamily.entries.toSet()))
        assertFalse(QuickActionPolicyContract.isQuick(PageActionType.MediaPlay, setOf(QuickActionFamily.CHECKLIST)))
        assertTrue(QuickActionPolicyContract.isQuick(PageActionType.SetChecked(false), setOf(QuickActionFamily.CHECKLIST)))
    }

    @Test
    fun `unknown types are ordinary and malformed ones are refused`() {
        assertEquals(PageActionTypeRead.Absent, PageActionTypeContract.read(null))
        assertEquals(PageActionTypeRead.Absent, PageActionTypeContract.read(JSONObject.NULL))
        assertEquals(PageActionTypeRead.Unknown("media.shuffle"), PageActionTypeContract.read(JSONObject().put("type", "media.shuffle")))
        assertNull(PageActionTypeContract.parseOrNull(JSONObject().put("type", "toggle")))
        listOf(
            "setChecked",
            JSONObject(),
            JSONObject().put("type", 3),
            JSONObject().put("type", "setChecked"),
            JSONObject().put("type", "setChecked").put("checked", "yes"),
            JSONObject().put("type", "a b"),
            JSONObject().put("type", "x".repeat(PageActionTypeContract.MAX_TYPE_CHARS + 1)),
        ).forEach { assertTrue("$it", PageActionTypeContract.read(it) is PageActionTypeRead.Invalid) }
    }

    @Test
    fun `declared families ignore unknown entries and are never a capability`() {
        assertEquals(setOf(QuickActionFamily.CHECKLIST, QuickActionFamily.MEDIA), QuickActionPolicyContract.parseDeclaredFamilies(" Media , checklist,teleport,,media"))
        assertTrue(QuickActionPolicyContract.parseDeclaredFamilies(null).isEmpty())
        assertTrue(PluginCapability.entries.none { it.wireValue == QuickActionPolicyContract.META_PLUGIN_QUICK_ACTIONS })
        assertTrue(PluginCapability.entries.none { it.wireValue in setOf("checklist", "media", "quick_actions") })
    }

    @Test
    fun `policy round trips and drops plugins without a known family`() {
        val policy = QuickActionPolicy(
            revision = 7,
            approvals = mapOf("todo" to setOf(QuickActionFamily.CHECKLIST), "media.deck" to setOf(QuickActionFamily.MEDIA, QuickActionFamily.CHECKLIST)),
        )
        val parsed = QuickActionPolicyContract.parse(JSONObject(QuickActionPolicyContract.toPayload(policy).toString()))
        assertEquals(policy, parsed)
        assertTrue(parsed!!.approves("todo", QuickActionFamily.CHECKLIST))
        assertFalse(parsed.approves("todo", QuickActionFamily.MEDIA))
        assertFalse(parsed.approves("other", QuickActionFamily.MEDIA))

        val future = JSONObject().put("revision", 2).put(
            "approvals",
            JSONObject().put("todo", JSONArray().put("checklist").put("future")).put("nav", JSONArray().put("future")),
        )
        assertEquals(QuickActionPolicy(2, mapOf("todo" to setOf(QuickActionFamily.CHECKLIST))), QuickActionPolicyContract.parse(future))
    }

    @Test
    fun `malformed policies are refused whole`() {
        val base = QuickActionPolicyContract.toPayload(QuickActionPolicy(1, mapOf("todo" to setOf(QuickActionFamily.CHECKLIST))))
        listOf(
            JSONObject(base.toString()).put("revision", -1),
            JSONObject(base.toString()).put("revision", "1"),
            JSONObject(base.toString()).put("approvals", JSONArray()),
            JSONObject(base.toString()).put("approvals", JSONObject().put("Not An Id", JSONArray().put("media"))),
            JSONObject(base.toString()).put("approvals", JSONObject().put("todo", "checklist")),
            JSONObject(base.toString()).put("approvals", JSONObject().put("todo", JSONArray().put(1))),
        ).forEach { assertNull("$it", QuickActionPolicyContract.parse(it)) }
        assertEquals(QuickActionPolicy.NONE, QuickActionPolicyContract.parse(JSONObject().put("revision", 0).put("approvals", JSONObject())))
    }
}
