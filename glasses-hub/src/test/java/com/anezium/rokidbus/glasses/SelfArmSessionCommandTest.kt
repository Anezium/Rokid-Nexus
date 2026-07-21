package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class SelfArmSessionCommandTest {
    @Test
    fun commandSequencePublishesParityDigestForPhoneMirror() {
        val material = buildString {
            append(SelfArmSessionCommand.buildPrepare(PARITY_WATCHDOG, PARITY_BRIDGE))
            append('\u0000')
            append(SelfArmSessionCommand.buildRestartAdbd())
            append('\u0000')
            append(SelfArmSessionCommand.buildArm(restartWatchdog = false))
            append('\u0000')
            append(SelfArmSessionCommand.buildArm(restartWatchdog = true))
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray())
            .joinToString("") { "%02x".format(it) }

        assertEquals(EXPECTED_PARITY_DIGEST, digest)
    }

    @Test
    fun prepareInstallsAndDisablesLegacyWithoutStartingOrRestarting() {
        val command = SelfArmSessionCommand.buildPrepare(
            watchdogScript = "#!/system/bin/sh\necho watchdog\n",
            bridgeScript = "#!/system/bin/sh\necho bridge\n",
        )

        assertTrue(command.contains("pm grant \"\$PKG\" android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(command.contains("base64 -d > \"\$WATCHDOG\""))
        assertTrue(command.contains("base64 -d > \"\$BRIDGE\""))
        assertTrue(command.contains("setprop persist.adb.tcp.port -1"))
        assertTrue(command.contains("setprop service.adb.tcp.port -1"))
        assertFalse(command.contains("sh \"\$WATCHDOG\" start"))
        assertFalse(command.contains("sh \"\$WATCHDOG\" restart"))
        assertFalse(command.contains("sh \"\$BRIDGE\" start"))
        assertFalse(command.contains("sh \"\$BRIDGE\" restart"))
        assertFalse(command.contains("setprop ctl.restart adbd"))
    }

    @Test
    fun restartCommandCapturesOldPidAndSchedulesOnlyOneRestart() {
        val command = SelfArmSessionCommand.buildRestartAdbd()

        assertTrue(command.contains("pidof adbd"))
        assertEquals(1, Regex("setprop ctl\\.restart adbd").findAll(command).count())
        assertNotNull(
            SelfArmSessionCommand.restartRequest(
                "ROKID_NEXUS_ADBD_RESTART_RESULT scheduled=1 old_adbd_pid=123,456\n",
            ),
        )
        assertTrue(
            SelfArmSessionCommand.restartRequest(
                "ROKID_NEXUS_ADBD_RESTART_RESULT scheduled=1 old_adbd_pid=123\n",
            )?.oldAdbdPid == "123",
        )
        assertTrue(
            SelfArmSessionCommand.restartRequest(
                "ROKID_NEXUS_ADBD_RESTART_RESULT scheduled=0 old_adbd_pid=123\n",
            ) == null,
        )
    }

    @Test
    fun armStartsOrRestartsWithoutTouchingAdbd() {
        val start = SelfArmSessionCommand.buildArm(restartWatchdog = false)
        val restart = SelfArmSessionCommand.buildArm(restartWatchdog = true)

        assertTrue(start.contains("sh \"\$WATCHDOG\" start"))
        assertTrue(start.contains("sh \"\$BRIDGE\" start"))
        assertTrue(start.indexOf("sh \"\$WATCHDOG\" start") < start.indexOf("sh \"\$WATCHDOG\" repair"))
        assertTrue(start.indexOf("sh \"\$WATCHDOG\" repair") < start.indexOf("sh \"\$BRIDGE\" start"))
        assertFalse(start.contains("setprop ctl.restart adbd"))
        assertTrue(restart.contains("sh \"\$WATCHDOG\" restart"))
        assertTrue(restart.contains("sh \"\$BRIDGE\" restart"))
        assertFalse(restart.contains("setprop ctl.restart adbd"))
    }

    @Test
    fun sentinelsAreStrictAndLastOneWins() {
        assertTrue(
            SelfArmSessionCommand.prepareSucceeded(
                "ROKID_NEXUS_PREPARE_RESULT grant=1 a11y=1 service=1 script=1 bridge_script=1 " +
                    "persist=-1 service_port=-1 legacy_tcp_disabled=1\n",
            ),
        )
        assertFalse(
            SelfArmSessionCommand.prepareSucceeded(
                "ROKID_NEXUS_PREPARE_RESULT grant=1 a11y=1 service=1 script=0 bridge_script=1 " +
                    "persist=-1 service_port=-1 legacy_tcp_disabled=1\n",
            ),
        )
        // The command bridge is best-effort: a missing bridge script must not fail prepare.
        assertTrue(
            SelfArmSessionCommand.prepareSucceeded(
                "ROKID_NEXUS_PREPARE_RESULT grant=1 a11y=1 service=1 script=1 bridge_script=0 " +
                    "persist=-1 service_port=-1 legacy_tcp_disabled=1\n",
            ),
        )
        assertTrue(
            SelfArmSessionCommand.armSucceeded(
                "ROKID_NEXUS_ARM_RESULT grant=1 a11y=1 service=1 watchdog=0 bridge=1 " +
                    "persist=-1 service_port=-1 legacy_tcp_disabled=1\n" +
                    "ROKID_NEXUS_ARM_RESULT grant=1 a11y=1 service=1 watchdog=1 bridge=1 " +
                    "persist=-1 service_port=-1 legacy_tcp_disabled=1\n",
            ),
        )
        assertFalse(
            SelfArmSessionCommand.armSucceeded(
                "ROKID_NEXUS_ARM_RESULT grant=1 a11y=1 service=1 watchdog=0 bridge=1 " +
                    "persist=-1 service_port=-1 legacy_tcp_disabled=1\n",
            ),
        )
        // A bridge that failed to start must not fail the arm; Wi-Fi falls back to accessibility.
        assertTrue(
            SelfArmSessionCommand.armSucceeded(
                "ROKID_NEXUS_ARM_RESULT grant=1 a11y=1 service=1 watchdog=1 bridge=0 " +
                    "persist=-1 service_port=-1 legacy_tcp_disabled=1\n",
            ),
        )
    }

    private companion object {
        const val PARITY_WATCHDOG = "#!/system/bin/sh\necho parity-watchdog\n"
        const val PARITY_BRIDGE = "#!/system/bin/sh\necho parity-bridge\n"
        const val EXPECTED_PARITY_DIGEST =
            "16504bbf597b5c0a5926272f69412f70e73a0ab653b59fd743464fcc69cf5aa7"
    }
}
