package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessAdbShellTest {
    @Test
    fun onlyAuthenticatedWirelessTlsPortIsUsed() {
        assertEquals(
            listOf(WirelessAdbShell.CandidateTarget("127.0.0.1", 37123)),
            WirelessAdbShell.candidateTargets(wirelessPort = 37123),
        )
    }

    @Test
    fun invalidWirelessPortsAreSkipped() {
        assertEquals(emptyList<WirelessAdbShell.CandidateTarget>(), WirelessAdbShell.candidateTargets(0))
        assertEquals(emptyList<WirelessAdbShell.CandidateTarget>(), WirelessAdbShell.candidateTargets(-1))
    }
}
