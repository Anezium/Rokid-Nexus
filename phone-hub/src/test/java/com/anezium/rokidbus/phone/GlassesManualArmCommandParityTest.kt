package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class GlassesManualArmCommandParityTest {
    @Test
    fun commandSequenceMatchesGlassesOwnedDigest() {
        val material = buildString {
            append(GlassesManualArmCommand.buildPrepare(WATCHDOG, BRIDGE))
            append('\u0000')
            append(GlassesManualArmCommand.buildRestartAdbd())
            append('\u0000')
            append(GlassesManualArmCommand.buildArm(restartWatchdog = false))
            append('\u0000')
            append(GlassesManualArmCommand.buildArm(restartWatchdog = true))
        }

        assertEquals(EXPECTED_GLASSES_COMMAND_DIGEST, sha256(material))
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val WATCHDOG = "#!/system/bin/sh\necho parity-watchdog\n"
        const val BRIDGE = "#!/system/bin/sh\necho parity-bridge\n"
        const val EXPECTED_GLASSES_COMMAND_DIGEST =
            "16504bbf597b5c0a5926272f69412f70e73a0ab653b59fd743464fcc69cf5aa7"
    }
}
