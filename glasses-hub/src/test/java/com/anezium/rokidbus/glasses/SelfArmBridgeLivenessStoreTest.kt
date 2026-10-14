package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SelfArmBridgeLivenessStoreTest {
    @Test
    fun armRecordsTheCurrentBootAndOnlyARunningBridgeSettlesTheDemand() {
        val context = RuntimeEnvironment.getApplication()
        SelfArmBridgeLivenessStore.noteBridgeDemandUnanswered()

        SelfArmBridgeLivenessStore.recordArmed(context, tlsPort = 42799, bridgeRunning = false)
        assertTrue(SelfArmBridgeLivenessStore.isBridgeDemandPending())

        SelfArmBridgeLivenessStore.recordArmed(context, tlsPort = 42799, bridgeRunning = true)
        assertFalse(SelfArmBridgeLivenessStore.isBridgeDemandPending())

        val armed = SelfArmBridgeLivenessStore.armedBootInstantMillis(context)
        assertNotNull(armed)
        assertEquals(0L, armed!! % 1_000L)
        // Same boot as the recording: nothing to presume dead.
        assertFalse(SelfArmBridgeLivenessStore.presumedDead(context))
    }

    @Test
    fun eachArmReplacesWhatItFoundBeforeActing() {
        val context = RuntimeEnvironment.getApplication()

        SelfArmBridgeLivenessStore.recordArmed(
            context,
            tlsPort = 42799,
            bridgeRunning = true,
            helperRunningBefore = false,
        )
        assertEquals(false, SelfArmBridgeLivenessStore.armedHelperWasRunning(context))

        SelfArmBridgeLivenessStore.recordArmed(
            context,
            tlsPort = 42799,
            bridgeRunning = true,
            helperRunningBefore = true,
        )
        assertEquals(true, SelfArmBridgeLivenessStore.armedHelperWasRunning(context))

        // An arm that could not tell must not leave the previous arm's answer behind.
        SelfArmBridgeLivenessStore.recordArmed(context, tlsPort = 42799, bridgeRunning = true)
        assertEquals(null, SelfArmBridgeLivenessStore.armedHelperWasRunning(context))
    }
}
