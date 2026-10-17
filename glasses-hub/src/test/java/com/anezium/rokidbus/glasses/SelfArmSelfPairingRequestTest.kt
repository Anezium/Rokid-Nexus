package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.SetupCompletionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class SelfArmSelfPairingRequestTest {
    private fun requestLines(): List<String> = ShadowLog.getLogsForTag(TAG)
        .map { it.msg }
        .filter { it.startsWith("self-pairing requested") }

    @Test
    fun everyRequestIsLoggedWithItsOutcome() {
        val context = RuntimeEnvironment.getApplication()
        ShadowLog.clear()

        // No accessibility service is connected in a unit test, so nothing can drive Settings.
        val outcome = SelfArmLocalAdbBootstrapper.requestSelfPairing(context, owner = true)

        assertEquals(SelfPairingOutcome.SERVICE_UNAVAILABLE, outcome)
        assertEquals(
            listOf("self-pairing requested owner=true outcome=service_unavailable backgroundAttempt=0/6"),
            requestLines(),
        )
    }

    @Test
    fun backgroundBudgetExhaustionIsLoggedAndTheOwnerStillGetsThrough() {
        val context = RuntimeEnvironment.getApplication()
        ShadowLog.clear()

        val outcomes = (1..7).map {
            SelfArmLocalAdbBootstrapper.requestSelfPairing(context, owner = false)
        }

        assertEquals(SelfPairingOutcome.BUDGET_EXHAUSTED, outcomes.last())
        assertTrue(requestLines().last().contains("outcome=budget_exhausted backgroundAttempt=6/6"))
        assertEquals(
            SelfPairingOutcome.SERVICE_UNAVAILABLE,
            SelfArmLocalAdbBootstrapper.requestSelfPairing(context, owner = true),
        )
    }

    @Test
    fun backgroundPairingNeedsAutomaticRepairOrAPairingAlreadyAskedFor() {
        val context = RuntimeEnvironment.getApplication()
        SelfArmOnboardingStore.invalidateSession(context)
        SelfArmBootRepairStore.setAutoRepairEnabled(context, false)
        ShadowLog.clear()

        // The wearer turned automatic repair off: a background arm may not take over the screen.
        assertEquals(
            SelfPairingOutcome.NOT_PERMITTED,
            SelfArmLocalAdbBootstrapper.requestSelfPairing(context, owner = false),
        )
        assertTrue(requestLines().last().contains("owner=false outcome=auto_repair_off"))
        // The owner asking from the phone is consent enough.
        assertEquals(
            SelfPairingOutcome.SERVICE_UNAVAILABLE,
            SelfArmLocalAdbBootstrapper.requestSelfPairing(context, owner = true),
        )

        // A pairing already asked for in the current session is consent too.
        SelfArmOnboardingStore.invalidateSession(context)
        val sessionId = SelfArmOnboardingStore.beginSession(context)
        SelfArmOnboardingStore.markPairingRequested(context, sessionId)
        assertTrue(
            SelfArmLocalAdbBootstrapper.requestSelfPairing(context, owner = false) !=
                SelfPairingOutcome.NOT_PERMITTED,
        )

        // And so is the switch left on.
        SelfArmOnboardingStore.invalidateSession(context)
        SelfArmBootRepairStore.setAutoRepairEnabled(context, true)
        assertTrue(
            SelfArmLocalAdbBootstrapper.requestSelfPairing(context, owner = false) !=
                SelfPairingOutcome.NOT_PERMITTED,
        )
    }

    @Test
    fun aSessionStopsBeingLiveWhenItFinishesExpiresOrIsSuperseded() {
        val context = RuntimeEnvironment.getApplication()
        SelfArmOnboardingStore.invalidateSession(context)

        val finished = SelfArmOnboardingStore.beginSession(context)
        assertTrue(SelfArmOnboardingStore.isSessionLive(context, finished))
        SelfArmOnboardingStore.finish(
            context = context,
            sessionId = finished,
            setupState = "wireless_setup_failed",
            success = false,
        )
        assertFalse(SelfArmOnboardingStore.isSessionLive(context, finished))

        val expiring = SelfArmOnboardingStore.beginSession(context)
        SelfArmOnboardingStore.markRunning(context, expiring)
        assertTrue(SelfArmOnboardingStore.isSessionLive(context, expiring))
        assertFalse(
            SelfArmOnboardingStore.isSessionLive(
                context,
                expiring,
                nowMillis = System.currentTimeMillis() + SelfArmOnboardingStore.LEASE_TIMEOUT_MS,
            ),
        )

        val superseding = SelfArmOnboardingStore.beginSession(context)
        assertFalse(SelfArmOnboardingStore.isSessionLive(context, expiring))
        assertTrue(SelfArmOnboardingStore.isSessionLive(context, superseding))
        SelfArmOnboardingStore.invalidateSession(context)
    }

    @Test
    fun aPhoneArmCompletesSetupWithoutClaimingTheGlassesPairedThemselves() {
        val context = RuntimeEnvironment.getApplication()
        SelfArmOnboardingStore.invalidateSession(context)
        assertFalse(SelfArmLocalAdbBootstrapper.isBootstrapComplete(context))
        val sessionId = SelfArmOnboardingStore.beginSession(context)

        SelfArmPhoneArmConfirmation.recordVerified(
            context = context,
            sessionId = sessionId,
            posture = SelfArmNetworkPosture(
                wirelessDebuggingEnabled = true,
                persistentLegacyPort = "-1",
                serviceLegacyPort = "-1",
                legacyLoopbackListening = false,
            ),
            completionMode = SetupCompletionMode.PHONE_ASSISTED,
        )

        val snapshot = SelfArmOnboardingStore.snapshot(context)
        assertFalse(SelfArmLocalAdbBootstrapper.isBootstrapComplete(context))
        assertFalse(snapshot.maintenanceReady)
        assertEquals(SetupCompletionMode.PHONE_ASSISTED, snapshot.completionMode)
        assertFalse(SelfArmOnboardingStore.isCurrentSession(context, sessionId))
    }
}
