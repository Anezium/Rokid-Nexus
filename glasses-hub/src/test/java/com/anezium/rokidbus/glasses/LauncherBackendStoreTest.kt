package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.glasses.input.LauncherBackend
import com.anezium.rokidbus.glasses.input.LauncherBackendSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The backend selection over the real preferences the host uses, across a simulated reboot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LauncherBackendStoreTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun `the last announced bundle and the phone's choice survive a reboot apart from the glasses' choice`() {
        assertNull(ActivityPresentationSettings.announcedLauncherBundle(app))
        val switches = mutableListOf<LauncherBackend>()
        LauncherBackendSelection(ActivityPresentationSettings.backendStore(app), { switches += it }, LauncherBackend.SESSION)
            .onPhoneAnnounced(null, 1)
        assertEquals(listOf(LauncherBackend.SESSION), switches)

        // A new process reads the same preferences before any phone connects.
        val rebooted = LauncherBackendSelection(ActivityPresentationSettings.backendStore(app), {}, LauncherBackend.SESSION)
        assertEquals(LauncherBackend.SESSION, rebooted.current())
        assertEquals(1, ActivityPresentationSettings.announcedLauncherBundle(app))
        assertNull(ActivityPresentationSettings.phoneLauncherBackend(app))

        rebooted.onPhoneAnnounced("legacy", 1)
        assertEquals("LEGACY", ActivityPresentationSettings.phoneLauncherBackend(app))
        assertEquals(LauncherBackend.LEGACY, rebooted.current())
        // The glasses' own stored choice is untouched by the phone's.
        assertNull(ActivityPresentationSettings.launcherBackend(app))
    }
}
