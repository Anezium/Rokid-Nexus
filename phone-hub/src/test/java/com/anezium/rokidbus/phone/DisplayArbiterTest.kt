package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayArbiterTest {
    private data class Row(
        val policy: PluginDisplayPolicy,
        val tier: DisplayTier,
        val allowed: Boolean,
        val code: String? = null,
    )

    @Test
    fun `each policy times each tier matches the plan table`() {
        val busy = DisplayArbiter.ERROR_SURFACE_BUSY
        val muted = DisplayArbiter.ERROR_DISPLAY_MUTED
        val rows = buildList {
            DisplayTier.entries.forEach { add(Row(PluginDisplayPolicy.NORMAL, it, true)) }
            add(Row(PluginDisplayPolicy.DEMOTE, DisplayTier.PIN, true))
            add(Row(PluginDisplayPolicy.DEMOTE, DisplayTier.NOTICE, true))
            add(Row(PluginDisplayPolicy.DEMOTE, DisplayTier.ACTIVITY, true))
            add(Row(PluginDisplayPolicy.DEMOTE, DisplayTier.SURFACE, false, busy))
            add(Row(PluginDisplayPolicy.DEMOTE, DisplayTier.AMBIENT, true))
            add(Row(PluginDisplayPolicy.DEMOTE, DisplayTier.WAKE, false, muted))
            add(Row(PluginDisplayPolicy.NOTICES, DisplayTier.PIN, false, muted))
            add(Row(PluginDisplayPolicy.NOTICES, DisplayTier.NOTICE, true))
            add(Row(PluginDisplayPolicy.NOTICES, DisplayTier.ACTIVITY, false, muted))
            add(Row(PluginDisplayPolicy.NOTICES, DisplayTier.SURFACE, false, busy))
            add(Row(PluginDisplayPolicy.NOTICES, DisplayTier.AMBIENT, false, muted))
            add(Row(PluginDisplayPolicy.NOTICES, DisplayTier.WAKE, true))
            DisplayTier.entries.forEach { add(Row(PluginDisplayPolicy.MUTE, it, false, muted)) }
        }
        rows.forEach { row ->
            val decision = DisplayArbiter.decide(row.policy, row.tier)
            if (row.allowed) {
                assertEquals("${row.policy} ${row.tier}", DisplayDecision.Allow, decision)
            } else {
                assertEquals("${row.policy} ${row.tier}", DisplayDecision.Deny(row.code!!), decision)
            }
        }
    }

    @Test
    fun `normal paint paths stay allowed so existing routers remain the oracle`() {
        listOf(
            BusPaths.PIN_SHOW,
            BusPaths.NOTICE_SHOW,
            BusPaths.NOTICE_UPDATE,
            BusPaths.ACTIVITY_START,
            BusPaths.ACTIVITY_UPDATE,
            BusPaths.SURFACE_SHOW,
            BusPaths.SURFACE_UPDATE,
            BusPaths.INK_SHOW,
            BusPaths.INK_UPDATE,
        ).forEach { path ->
            val tier = DisplayArbiter.tierFor(path)
            assertEquals(path, DisplayDecision.Allow, DisplayArbiter.decide(PluginDisplayPolicy.NORMAL, tier!!))
        }
        assertTrue(DisplayArbiter.allowsWake(PluginDisplayPolicy.NORMAL))
        assertNull(DisplayArbiter.tierFor(BusPaths.PIN_HIDE))
        assertNull(DisplayArbiter.tierFor(BusPaths.NOTICE_HIDE))
        assertNull(DisplayArbiter.tierFor(BusPaths.ACTIVITY_END))
        assertNull(DisplayArbiter.tierFor(BusPaths.SURFACE_HIDE))
        assertNull(DisplayArbiter.tierFor(BusPaths.INK_HIDE))
    }

    @Test
    fun `ambient widget paths are already classified so slice 4 can fold in`() {
        assertEquals(DisplayTier.AMBIENT, DisplayArbiter.tierFor(DisplayArbiter.AMBIENT_WIDGET_SHOW))
        assertEquals(DisplayTier.AMBIENT, DisplayArbiter.tierFor(DisplayArbiter.AMBIENT_WIDGET_UPDATE))
        assertEquals(
            DisplayDecision.Deny(DisplayArbiter.ERROR_DISPLAY_MUTED),
            DisplayArbiter.decide(PluginDisplayPolicy.MUTE, DisplayTier.AMBIENT),
        )
        assertEquals(
            DisplayDecision.Allow,
            DisplayArbiter.decide(PluginDisplayPolicy.DEMOTE, DisplayTier.AMBIENT),
        )
    }

    @Test
    fun `demote may keep a foreground it already holds`() {
        assertEquals(
            DisplayDecision.Allow,
            DisplayArbiter.decide(PluginDisplayPolicy.DEMOTE, DisplayTier.SURFACE, holdsForeground = true),
        )
        assertEquals(
            DisplayDecision.Deny(DisplayArbiter.ERROR_SURFACE_BUSY),
            DisplayArbiter.decide(PluginDisplayPolicy.NOTICES, DisplayTier.SURFACE, holdsForeground = true),
        )
    }

    @Test
    fun `demote and mute strip notice wake`() {
        assertFalse(DisplayArbiter.allowsWake(PluginDisplayPolicy.DEMOTE))
        assertFalse(DisplayArbiter.allowsWake(PluginDisplayPolicy.MUTE))
        assertTrue(DisplayArbiter.allowsWake(PluginDisplayPolicy.NOTICES))
        val stripped = DisplayArbiter.applyNoticeWake(
            PluginDisplayPolicy.DEMOTE,
            JSONObject().put("wakeDisplay", true),
        )
        assertFalse(stripped.has("wakeDisplay"))
        val muted = DisplayArbiter.applyNoticeWake(
            PluginDisplayPolicy.MUTE,
            JSONObject().put("wakeDisplay", true),
        )
        assertFalse(muted.has("wakeDisplay"))
        val kept = DisplayArbiter.applyNoticeWake(
            PluginDisplayPolicy.NORMAL,
            JSONObject().put("wakeDisplay", true),
        )
        assertTrue(kept.getBoolean("wakeDisplay"))
    }
}
