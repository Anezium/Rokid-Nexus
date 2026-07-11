package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessAdbShellTest {
    @Test
    fun classicLoopbackPrecedesWirelessTlsPort() {
        assertEquals(
            listOf(
                WirelessAdbShell.CandidateTarget("127.0.0.1", 5555),
                WirelessAdbShell.CandidateTarget("127.0.0.1", 37123),
            ),
            WirelessAdbShell.candidateTargets(wirelessPort = 37123),
        )
    }

    @Test
    fun invalidAndDuplicateWirelessPortsAreSkipped() {
        val classicOnly = listOf(WirelessAdbShell.CandidateTarget("127.0.0.1", 5555))

        assertEquals(classicOnly, WirelessAdbShell.candidateTargets(wirelessPort = 0))
        assertEquals(classicOnly, WirelessAdbShell.candidateTargets(wirelessPort = -1))
        assertEquals(classicOnly, WirelessAdbShell.candidateTargets(wirelessPort = 5555))
    }
}
