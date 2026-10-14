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
    fun `core readiness only closes a session nobody asked to pair`() {
        assertEquals(Step.FINISH, SelfArmSetupResumePolicy.next(true, false, wifiReady = true))
        assertEquals(Step.FINISH, SelfArmSetupResumePolicy.next(true, false, wifiReady = false))
        assertEquals(Step.RUN_BOOTSTRAP, SelfArmSetupResumePolicy.next(true, true, wifiReady = true))
        assertEquals(Step.WAIT_FOR_WIFI, SelfArmSetupResumePolicy.next(true, true, wifiReady = false))
        assertEquals(Step.RUN_BOOTSTRAP, SelfArmSetupResumePolicy.next(false, false, wifiReady = true))
        assertEquals(Step.WAIT_FOR_WIFI, SelfArmSetupResumePolicy.next(false, false, wifiReady = false))
    }
}
