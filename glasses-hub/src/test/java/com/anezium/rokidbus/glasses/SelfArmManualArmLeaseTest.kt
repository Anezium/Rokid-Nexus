package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Looper
import com.anezium.rokidbus.shared.GlassesRepairContract
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
class SelfArmManualArmLeaseTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun noSession() {
        SelfArmOnboardingStore.invalidateSession(context)
    }

    /** Manual navigation opened, then paused waiting for validated Wi-Fi: nothing heartbeats. */
    private fun openManualNavigationAndPauseOnWifi(): String {
        val sessionId = SelfArmOnboardingStore.beginSession(context)
        SelfArmOnboardingStore.markManualArmInProgress(context)
        SelfArmOnboardingStore.pause(context, sessionId, "waiting_for_wifi_network")
        return sessionId
    }

    private fun ageHeartbeat(byMillis: Long) {
        context.getSharedPreferences("selfarm_onboarding", Context.MODE_PRIVATE)
            .edit()
            .putLong("setup_last_heartbeat", System.currentTimeMillis() - byMillis)
            .commit()
    }

    private fun ownerPairRequest(): String? {
        var answer: String? = null
        SelfArmBootRepairCoordinator.runOwnerRepair(context, GlassesRepairContract.MODE_PAIR) {
            answer = it
        }
        shadowOf(Looper.getMainLooper()).idle()
        return answer
    }

    @Test
    fun `a manual arm holds the owner off only while its session heartbeats`() {
        val sessionId = openManualNavigationAndPauseOnWifi()
        val now = System.currentTimeMillis()
        assertTrue(SelfArmOnboardingStore.isManualArmLeased(context, now))

        SelfArmOnboardingStore.heartbeat(context, sessionId, now + 30_000L)
        assertTrue(SelfArmOnboardingStore.isManualArmLeased(context, now + 60_000L))
        assertFalse(
            SelfArmOnboardingStore.isManualArmLeased(
                context,
                now + 30_000L + SelfArmOnboardingStore.LEASE_TIMEOUT_MS,
            ),
        )
    }

    @Test
    fun `an abandoned manual navigation no longer answers busy to the owner`() {
        openManualNavigationAndPauseOnWifi()
        ageHeartbeat(SelfArmOnboardingStore.LEASE_TIMEOUT_MS + 1_000L)

        // No accessibility service is connected here, so the pairing cannot start; what matters
        // is that the owner's request got past the stale session instead of hearing busy.
        assertEquals(GlassesRepairContract.RESULT_PAIRING_REQUIRED, ownerPairRequest())
        assertFalse(SelfArmOnboardingStore.isManualArmInProgress(context))
    }

    @Test
    fun `a manual arm still heartbeating keeps the owner waiting`() {
        openManualNavigationAndPauseOnWifi()

        assertEquals(GlassesRepairContract.RESULT_BUSY, ownerPairRequest())
        assertTrue(SelfArmOnboardingStore.isManualArmInProgress(context))
    }
}
