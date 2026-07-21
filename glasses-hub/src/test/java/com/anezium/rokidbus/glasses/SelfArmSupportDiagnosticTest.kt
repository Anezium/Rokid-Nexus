package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmSupportDiagnosticTest {
    @Test
    fun standalonePairingCodeIsRedacted() {
        val diagnostic = pairingFailureDiagnostic("KADB rejected pairing code 123456")

        assertEquals("PAIR-FAIL: KADB rejected pairing code ······", diagnostic)
        assertFalse(diagnostic.contains("123456"))
    }

    @Test
    fun ipv4LiteralIsRemovedAndWhitespaceIsCollapsed() {
        val diagnostic = pairingFailureDiagnostic(
            "connection to 192.168.4.2 failed\n  on port 37123",
        )

        assertEquals("PAIR-FAIL: connection to failed on port 37123", diagnostic)
    }

    @Test
    fun fullDiagnosticIsCappedAtNinetySixCharacters() {
        val diagnostic = pairingFailureDiagnostic("x".repeat(200))

        assertEquals(MAX_SUPPORT_DIAGNOSTIC_LENGTH, diagnostic.length)
        assertTrue(diagnostic.startsWith("PAIR-FAIL: "))
    }
}
