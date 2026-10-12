package com.anezium.rokidbus.glasses.input

import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 028a §8.3 with the prospective SESSION default (the acceptance flip) injected, so the
 * selection that ships with LEGACY is proven to hold once the default changes.
 */
class LauncherBackendSelectionTest {
    private class MemoryStore : LauncherBackendSelection.Store {
        var phone: String? = null
        var glasses: String? = null
        var announced: Int? = null
        override fun phoneBackend(): String? = phone
        override fun setPhoneBackend(value: String?) { phone = value }
        override fun glassesBackend(): String? = glasses
        override fun setGlassesBackend(value: String) { glasses = value }
        override fun announcedBundleVersion(): Int? = announced
        override fun setAnnouncedBundleVersion(version: Int) { announced = version }
    }

    private val store = MemoryStore()
    private val switches = mutableListOf<LauncherBackend>()

    private fun selection() = LauncherBackendSelection(store, { switches += it }, bundleDefault = LauncherBackend.SESSION)

    private fun negotiated(payload: JSONObject): Int =
        PhoneHubCapabilitiesContract.negotiatedLauncherInteractiveVersion(PhoneHubCapabilitiesContract.parse(payload))

    private fun bundlePhone() = JSONObject().put("features", 0).put("launcherInteractiveVersion", LauncherInteractiveContract.VERSION)

    @Test
    fun `nothing before a phone announcement opts into the bundle default`() {
        assertEquals(LauncherBackend.LEGACY, selection().current())
        assertTrue(switches.isEmpty())
    }

    @Test
    fun `a bundle announcement selects the session default and an offline boot keeps it`() {
        selection().onPhoneAnnounced(null, negotiated(bundlePhone()))
        assertEquals(listOf(LauncherBackend.SESSION), switches)

        // Reboot without the phone: a new selection over the same persisted store.
        assertEquals(LauncherBackend.SESSION, selection().current())
    }

    @Test
    fun `a link loss is no input here, so the launcher and its open session stay`() {
        val live = selection()
        live.onPhoneAnnounced(null, negotiated(bundlePhone()))
        switches.clear()

        // The link drops and returns without a new announcement: nothing re-resolves the backend,
        // so no backend switch can abort the session holding a draft, a conversation or a route.
        assertEquals(LauncherBackend.SESSION, live.current())
        assertTrue(switches.isEmpty())
    }

    @Test
    fun `a fresh announcement from a phone without the bundle returns to legacy`() {
        val live = selection()
        live.onPhoneAnnounced(null, negotiated(bundlePhone()))
        live.onPhoneAnnounced(null, negotiated(JSONObject().put("features", 0)))
        assertEquals(LauncherBackend.LEGACY, switches.last())
        assertEquals(LauncherBackend.LEGACY, selection().current())
    }

    @Test
    fun `a malformed or unknown bundle version in an announcement never opts in`() {
        selection().onPhoneAnnounced(null, negotiated(JSONObject().put("features", 0).put("launcherInteractiveVersion", "one")))
        assertEquals(LauncherBackend.LEGACY, switches.single())
        selection().onPhoneAnnounced(null, -3)
        assertEquals(LauncherBackend.LEGACY, selection().current())
    }

    @Test
    fun `explicit choices keep their precedence over the bundle default`() {
        val live = selection()
        live.onPhoneAnnounced(null, negotiated(bundlePhone()))

        // The glasses' own LEGACY wins over the default.
        assertEquals(LauncherBackend.LEGACY, live.onGlassesChoice(LauncherBackend.LEGACY))
        // The phone's explicit SESSION outranks the glasses' choice without erasing it.
        live.onPhoneAnnounced("session", negotiated(bundlePhone()))
        assertEquals(LauncherBackend.SESSION, live.current())
        assertEquals("LEGACY", store.glasses)
        // The phone's explicit LEGACY is the rollback even with the bundle.
        live.onPhoneAnnounced("legacy", negotiated(bundlePhone()))
        assertEquals(LauncherBackend.LEGACY, live.current())
        // No opinion again: the glasses' own choice applies.
        live.onPhoneAnnounced(null, negotiated(bundlePhone()))
        assertEquals(LauncherBackend.LEGACY, live.current())
    }

    @Test
    fun `the shipped default stays legacy even after a bundle announcement`() {
        val shipped = LauncherBackendSelection(store, { switches += it })
        shipped.onPhoneAnnounced(null, negotiated(bundlePhone()))
        assertEquals(LauncherBackend.LEGACY, shipped.current())
    }
}
