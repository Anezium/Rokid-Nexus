package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.AmbientPolicy
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.AmbientSizeEdit
import com.anezium.rokidbus.shared.AmbientSizes
import com.anezium.rokidbus.shared.ForegroundAmbientMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The glasses' mirror of the phone's ambient sizes and per-app policy (plan 028a §8.1, §8.2). */
class AmbientPreferencesMirrorTest {
    private val sent = mutableListOf<AmbientSizeEdit>()
    private var linkUp = true
    private val storage = AmbientPreferencesStorage.memory()
    private val mirror = AmbientPreferencesMirror(storage) { edit -> linkUp.also { if (it) sent += edit } }

    @Test
    fun `an online edit applies at once and is sent, never queued`() {
        assertTrue(mirror.edit("maps", AmbientSize.PANEL, online = true))
        assertEquals(AmbientSize.PANEL, mirror.size("maps"))
        assertEquals(listOf(AmbientSizeEdit("maps", AmbientSize.PANEL)), sent)
        assertEquals(emptyMap<String, AmbientSize?>(), mirror.queued)
    }

    @Test
    fun `offline edits queue latest per owner and are sent before the next snapshot applies`() {
        linkUp = false
        mirror.edit("maps", AmbientSize.PANEL, online = false)
        mirror.edit("maps", AmbientSize.TEXT, online = false)
        mirror.edit("transit", null, online = false)
        assertEquals(mapOf("maps" to AmbientSize.TEXT, "transit" to null), mirror.queued)
        assertEquals(AmbientSize.TEXT, mirror.size("maps"))
        assertTrue(sent.isEmpty())

        linkUp = true
        // The snapshot predates the queued edits: they are sent first and stay applied over it.
        assertTrue(mirror.onSizes(AmbientSizes(4, mapOf("maps" to AmbientSize.MINIMAP, "transit" to AmbientSize.PANEL, "bus" to AmbientSize.TEXT))))
        assertEquals(listOf(AmbientSizeEdit("maps", AmbientSize.TEXT), AmbientSizeEdit("transit", null)), sent)
        assertEquals(mapOf("maps" to AmbientSize.TEXT, "bus" to AmbientSize.TEXT), mirror.sizes)
        assertEquals(emptyMap<String, AmbientSize?>(), mirror.queued)

        // The phone's answer to them, with the full map, replaces everything.
        mirror.onSizes(AmbientSizes(5, mapOf("maps" to AmbientSize.TEXT)))
        assertEquals(mapOf("maps" to AmbientSize.TEXT), mirror.sizes)
    }

    @Test
    fun `a queued edit that still cannot leave stays queued and applied`() {
        linkUp = false
        mirror.edit("maps", AmbientSize.PANEL, online = false)
        mirror.onSizes(AmbientSizes(1, emptyMap()))
        assertEquals(mapOf("maps" to AmbientSize.PANEL), mirror.queued)
        assertEquals(AmbientSize.PANEL, mirror.size("maps"))
    }

    @Test
    fun `sizes and policy compare revisions per kind, from one rising counter`() {
        assertTrue(mirror.onPolicy(AmbientPolicy(7, mapOf("com.example.reader" to ForegroundAmbientMode.NOTHING))))
        // A sizes snapshot numbered below the policy is still news for sizes.
        assertTrue(mirror.onSizes(AmbientSizes(3, mapOf("maps" to AmbientSize.PANEL))))
        assertFalse(mirror.onSizes(AmbientSizes(3, mapOf("maps" to AmbientSize.TEXT))))
        assertFalse(mirror.onSizes(AmbientSizes(2, emptyMap())))
        assertEquals(AmbientSize.PANEL, mirror.size("maps"))
        assertFalse(mirror.onPolicy(AmbientPolicy(6, emptyMap())))
        assertEquals(ForegroundAmbientMode.NOTHING, mirror.modeFor("com.example.reader", isHome = false))

        // A phone that restarted on an earlier clock is heard after the link went.
        mirror.onLinkLost()
        assertTrue(mirror.onSizes(AmbientSizes(1, emptyMap())))
        assertNull(mirror.size("maps"))
    }

    @Test
    fun `unknown apps signal, home keeps residents whatever the policy says`() {
        mirror.onPolicy(AmbientPolicy(1, mapOf("com.example.game" to ForegroundAmbientMode.RESIDENTS)))
        assertEquals(ForegroundAmbientMode.SIGNALS, mirror.modeFor("com.example.other", isHome = false))
        assertEquals(ForegroundAmbientMode.RESIDENTS, mirror.modeFor("com.example.game", isHome = false))
        assertEquals(ForegroundAmbientMode.RESIDENTS, mirror.modeFor("com.rokid.home", isHome = true))
        assertEquals(ForegroundAmbientMode.RESIDENTS, mirror.modeFor(null, isHome = false))
    }

    @Test
    fun `the mirror survives a restart through its storage`() {
        linkUp = false
        mirror.edit("maps", AmbientSize.MINIMAP, online = false)
        mirror.onPolicy(AmbientPolicy(2, mapOf("com.example.reader" to ForegroundAmbientMode.SIGNALS)))

        val restarted = AmbientPreferencesMirror(storage) { false }
        assertEquals(AmbientSize.MINIMAP, restarted.size("maps"))
        assertEquals(mapOf("maps" to AmbientSize.MINIMAP), restarted.queued)
        assertEquals(ForegroundAmbientMode.SIGNALS, restarted.modeFor("com.example.reader", isHome = false))
    }
}
