package com.anezium.rokidbus.glasses

import android.Manifest
import android.provider.Settings
import com.anezium.rokidbus.glasses.SelfArmSetupResumePolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SelfArmPairingIntentResumeTest {
    private val context = RuntimeEnvironment.getApplication()

    /** Accessibility on and the secure-settings grant held: what any onboarded unit reports. */
    @Before
    fun coreReady() {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            SelfArmConstants.ACCESSIBILITY_SERVICE,
        )
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        SelfArmOnboardingStore.invalidateSession(context)
    }

    @Test
    fun `a re-pair survives the accessibility service being recreated mid-run`() {
        val sessionId = SelfArmOnboardingStore.beginSession(context)
        SelfArmOnboardingStore.markPairingRequested(context, sessionId)
        SelfArmOnboardingStore.markRunning(context, sessionId)
        assertTrue(SelfArmOnboardingStore.snapshot(context).coreReady)

        // What onDestroy does to a running bootstrap when the Wireless debugging toggle tears the
        // service down.
        SelfArmOnboardingStore.pause(context, sessionId, "wireless_setup_service_restarting")

        // The recreated service holds nothing in memory: it resumes from the store alone, and
        // must not read core readiness as "nothing left to do".
        assertTrue(SelfArmOnboardingStore.isSetupRequested(context))
        assertEquals(sessionId, SelfArmOnboardingStore.currentActiveSessionId(context))
        assertTrue(SelfArmOnboardingStore.isPairingRequested(context, sessionId))
        val step = SelfArmOnboardingStore.resumeStep(
            context,
            sessionId,
            SelfArmOnboardingStore.snapshot(context),
        )
        assertTrue("resumed re-pair closed itself: $step", step != Step.FINISH)
    }

    @Test
    fun `a plain setup session on a core-ready unit is still closed as already set up`() {
        val sessionId = SelfArmOnboardingStore.beginSession(context)

        assertEquals(
            Step.FINISH,
            SelfArmOnboardingStore.resumeStep(
                context,
                sessionId,
                SelfArmOnboardingStore.snapshot(context),
            ),
        )
    }

    @Test
    fun `the pairing intent belongs to its session and ends with it`() {
        val finished = SelfArmOnboardingStore.beginSession(context)
        SelfArmOnboardingStore.markPairingRequested(context, finished)
        SelfArmOnboardingStore.finish(
            context = context,
            sessionId = finished,
            setupState = "wireless_bootstrap_complete",
            success = true,
        )
        assertFalse(SelfArmOnboardingStore.isPairingRequested(context, finished))

        val next = SelfArmOnboardingStore.beginSession(context)
        assertFalse(SelfArmOnboardingStore.isPairingRequested(context, next))
        SelfArmOnboardingStore.markPairingRequested(context, next)
        SelfArmOnboardingStore.invalidateSession(context)
        assertFalse(SelfArmOnboardingStore.isPairingRequested(context, next))
    }

    @Test
    fun `a recreation inside the paused window still resumes the re-pair`() {
        val sessionId = SelfArmOnboardingStore.beginSession(context)
        SelfArmOnboardingStore.markPairingRequested(context, sessionId)
        SelfArmOnboardingStore.markRunning(context, sessionId)
        SelfArmOnboardingStore.pause(context, sessionId, "wireless_setup_service_restarting")
        val now = System.currentTimeMillis()

        // Past the running lease, inside the paused window: a slow recreation or a Wi-Fi wait.
        val later = now + SelfArmOnboardingStore.LEASE_TIMEOUT_MS + 15_000L
        assertTrue(SelfArmOnboardingStore.isPairingRequested(context, sessionId, later))
        assertFalse(SelfArmOnboardingStore.isPairingAbandoned(context, sessionId, later))
    }

    @Test
    fun `an intent nobody is behind any more stops being consent`() {
        val paused = SelfArmOnboardingStore.beginSession(context)
        SelfArmOnboardingStore.markPairingRequested(context, paused)
        SelfArmOnboardingStore.pause(context, paused, "waiting_for_wifi_network")
        val expired = System.currentTimeMillis() + SelfArmOnboardingStore.PAUSED_WIFI_NEED_TIMEOUT_MS + 1L
        assertFalse(SelfArmOnboardingStore.isPairingRequested(context, paused, expired))
        assertTrue(SelfArmOnboardingStore.isPairingAbandoned(context, paused, expired))
        // What the service does with it instead of resuming: the session ends.
        assertTrue(
            SelfArmOnboardingStore.endPairingSession(
                context,
                paused,
                SelfArmOnboardingStore.SELF_PAIRING_ABANDONED,
            ),
        )
        assertEquals("", SelfArmOnboardingStore.currentActiveSessionId(context))

        // Running work loses it with its lease, long before the paused window.
        val running = SelfArmOnboardingStore.beginSession(context)
        SelfArmOnboardingStore.markPairingRequested(context, running)
        SelfArmOnboardingStore.markRunning(context, running)
        val lapsed = System.currentTimeMillis() + SelfArmOnboardingStore.LEASE_TIMEOUT_MS + 1L
        assertFalse(SelfArmOnboardingStore.isPairingRequested(context, running, lapsed))
    }

    @Test
    fun `an expired intent does not let a background request take over`() {
        SelfArmBootRepairStore.setAutoRepairEnabled(context, false)
        try {
            val sessionId = SelfArmOnboardingStore.beginSession(context)
            SelfArmOnboardingStore.markPairingRequested(context, sessionId)
            SelfArmOnboardingStore.pause(context, sessionId, "waiting_for_wifi_network")
            assertTrue(
                SelfArmLocalAdbBootstrapper.requestSelfPairing(context, owner = false) !=
                    SelfPairingOutcome.NOT_PERMITTED,
            )

            // The owner walked away long ago; the run's last sign of life is past the window.
            context.getSharedPreferences("selfarm_onboarding", 0).edit()
                .putLong(
                    "setup_last_heartbeat",
                    System.currentTimeMillis() - SelfArmOnboardingStore.PAUSED_WIFI_NEED_TIMEOUT_MS - 1L,
                )
                .commit()
            assertEquals(
                SelfPairingOutcome.NOT_PERMITTED,
                SelfArmLocalAdbBootstrapper.requestSelfPairing(context, owner = false),
            )
        } finally {
            SelfArmBootRepairStore.setAutoRepairEnabled(context, true)
        }
    }

    @Test
    fun `cancelling while waiting for Wi-Fi ends the re-pair for good`() {
        SelfArmBootRepairStore.setAutoRepairEnabled(context, false)
        try {
            val sessionId = SelfArmOnboardingStore.beginSession(context)
            SelfArmOnboardingStore.markPairingRequested(context, sessionId)
            SelfArmOnboardingStore.pause(context, sessionId, "waiting_for_wifi_network")

            // The phone's CLOSE with abandonPairing, with no manual-navigation session to end.
            assertTrue(RokidBusAccessibilityService.abandonSelfPairing(context, sessionId))

            assertFalse(SelfArmOnboardingStore.isPairingRequested(context, sessionId))
            // Nothing is left for a Wi-Fi edge or a reconnecting service to resume.
            assertEquals("", SelfArmOnboardingStore.currentActiveSessionId(context))
            assertEquals(
                SelfArmOnboardingStore.SELF_PAIRING_CANCELLED,
                SelfArmOnboardingStore.snapshot(context).failureState,
            )
            assertEquals(
                SelfPairingOutcome.NOT_PERMITTED,
                SelfArmLocalAdbBootstrapper.requestSelfPairing(context, owner = false),
            )
        } finally {
            SelfArmBootRepairStore.setAutoRepairEnabled(context, true)
        }
    }

    @Test
    fun `cancelling leaves a plain setup session alone`() {
        val sessionId = SelfArmOnboardingStore.beginSession(context)

        assertFalse(RokidBusAccessibilityService.abandonSelfPairing(context, sessionId))
        assertEquals(sessionId, SelfArmOnboardingStore.currentActiveSessionId(context))
    }

    @Test
    fun `a screen that started nothing cannot end a pairing started elsewhere`() {
        // Repair now, or a background pairing: the screen being left never adopted it.
        val foreign = SelfArmOnboardingStore.beginSession(context)
        SelfArmOnboardingStore.markPairingRequested(context, foreign)

        // What the CLOSE of an older phone build, or of a screen with nothing adopted, carries.
        assertFalse(RokidBusAccessibilityService.abandonSelfPairing(context, ""))
        assertFalse(RokidBusAccessibilityService.abandonSelfPairing(context, "another-session"))

        assertEquals(foreign, SelfArmOnboardingStore.currentActiveSessionId(context))
        assertTrue(SelfArmOnboardingStore.isPairingRequested(context, foreign))
    }

    @Test
    fun `a stale close does not end the pairing that replaced its own`() {
        val stale = SelfArmOnboardingStore.beginSession(context)
        SelfArmOnboardingStore.markPairingRequested(context, stale)
        SelfArmOnboardingStore.invalidateSession(context)
        val replacement = SelfArmOnboardingStore.beginSession(context)
        SelfArmOnboardingStore.markPairingRequested(context, replacement)

        assertFalse(RokidBusAccessibilityService.abandonSelfPairing(context, stale))

        assertEquals(replacement, SelfArmOnboardingStore.currentActiveSessionId(context))
        assertTrue(SelfArmOnboardingStore.isPairingRequested(context, replacement))
        // The replacement's own screen still ends it.
        assertTrue(RokidBusAccessibilityService.abandonSelfPairing(context, replacement))
        assertEquals("", SelfArmOnboardingStore.currentActiveSessionId(context))
    }

    @Test
    fun `core readiness only closes a session nobody asked to pair`() {
        assertEquals(Step.FINISH, SelfArmSetupResumePolicy.next(true, false, wifiReady = true))
        assertEquals(Step.FINISH, SelfArmSetupResumePolicy.next(true, false, wifiReady = false))
        assertEquals(Step.RUN_BOOTSTRAP, SelfArmSetupResumePolicy.next(true, true, wifiReady = true))
        assertEquals(Step.WAIT_FOR_WIFI, SelfArmSetupResumePolicy.next(true, true, wifiReady = false))
        assertEquals(Step.RUN_BOOTSTRAP, SelfArmSetupResumePolicy.next(false, false, wifiReady = true))
        assertEquals(Step.WAIT_FOR_WIFI, SelfArmSetupResumePolicy.next(false, false, wifiReady = false))
    }
}
