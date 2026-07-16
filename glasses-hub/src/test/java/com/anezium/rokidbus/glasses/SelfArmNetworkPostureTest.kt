package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class SelfArmNetworkPostureTest {
    @Test
    fun authenticatedTlsMayRemainEnabledWhenLegacyTcpIsClosed() {
        val posture = SelfArmNetworkPosture(
            wirelessDebuggingEnabled = true,
            persistentLegacyPort = "-1",
            serviceLegacyPort = "-1",
            legacyLoopbackListening = false,
        )

        assertEquals(SelfArmNetworkPosture.TeardownDecision.SAFE, posture.teardownDecision())
    }

    @Test
    fun legacyPropertiesAreClearedBeforeRestartingAdbd() {
        val configured = SelfArmNetworkPosture(
            wirelessDebuggingEnabled = true,
            persistentLegacyPort = "5555",
            serviceLegacyPort = "5555",
            legacyLoopbackListening = true,
        )
        val lingeringListener = configured.copy(
            persistentLegacyPort = "-1",
            serviceLegacyPort = "-1",
        )

        assertEquals(
            SelfArmNetworkPosture.TeardownDecision.CLEAR_LEGACY_PROPERTIES,
            configured.teardownDecision(),
        )
        assertEquals(
            SelfArmNetworkPosture.TeardownDecision.RESTART_ADBD,
            lingeringListener.teardownDecision(),
        )
    }

    @Test
    fun wirelessDebuggingMayAlsoBeOffInSafePosture() {
        val posture = SelfArmNetworkPosture(
            wirelessDebuggingEnabled = false,
            persistentLegacyPort = "",
            serviceLegacyPort = "0",
            legacyLoopbackListening = false,
        )

        assertEquals(SelfArmNetworkPosture.TeardownDecision.SAFE, posture.teardownDecision())
    }
}
