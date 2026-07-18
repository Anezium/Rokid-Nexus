package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmSessionCommandTest {
    @Test
    fun prepareInstallsAndDisablesLegacyWithoutStartingOrRestarting() {
        val command = SelfArmSessionCommand.buildPrepare("#!/system/bin/sh\necho ok\n")

        assertTrue(command.contains("pm grant \"\$PKG\" android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(command.contains("base64 -d > \"\$WATCHDOG\""))
        assertTrue(command.contains("setprop persist.adb.tcp.port -1"))
        assertTrue(command.contains("setprop service.adb.tcp.port -1"))
        assertFalse(command.contains("sh \"\$WATCHDOG\" start"))
        assertFalse(command.contains("sh \"\$WATCHDOG\" restart"))
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
        assertFalse(start.contains("setprop ctl.restart adbd"))
        assertTrue(restart.contains("sh \"\$WATCHDOG\" restart"))
        assertFalse(restart.contains("setprop ctl.restart adbd"))
    }

    @Test
    fun sentinelsAreStrictAndLastOneWins() {
        assertTrue(
            SelfArmSessionCommand.prepareSucceeded(
                "ROKID_NEXUS_PREPARE_RESULT grant=1 a11y=1 service=1 script=1 " +
                    "persist=-1 service_port=-1 legacy_tcp_disabled=1\n",
            ),
        )
        assertFalse(
            SelfArmSessionCommand.prepareSucceeded(
                "ROKID_NEXUS_PREPARE_RESULT grant=1 a11y=1 service=1 script=0 " +
                    "persist=-1 service_port=-1 legacy_tcp_disabled=1\n",
            ),
        )
        assertTrue(
            SelfArmSessionCommand.armSucceeded(
                "ROKID_NEXUS_ARM_RESULT grant=1 a11y=1 service=1 watchdog=0 " +
                    "persist=-1 service_port=-1 legacy_tcp_disabled=1\n" +
                    "ROKID_NEXUS_ARM_RESULT grant=1 a11y=1 service=1 watchdog=1 " +
                    "persist=-1 service_port=-1 legacy_tcp_disabled=1\n",
            ),
        )
        assertFalse(
            SelfArmSessionCommand.armSucceeded(
                "ROKID_NEXUS_ARM_RESULT grant=1 a11y=1 service=1 watchdog=0 " +
                    "persist=-1 service_port=-1 legacy_tcp_disabled=1\n",
            ),
        )
    }
}
