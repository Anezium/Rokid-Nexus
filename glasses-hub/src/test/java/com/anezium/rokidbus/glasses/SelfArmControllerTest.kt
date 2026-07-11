package com.anezium.rokidbus.glasses

import java.security.PrivateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmControllerTest {
    private val testKey = AdbKeyMaterial(
        privateKey = object : PrivateKey {
            override fun getAlgorithm(): String = "RSA"
            override fun getFormat(): String = "PKCS#8"
            override fun getEncoded(): ByteArray = byteArrayOf()
        },
        publicKey = "test",
    )

    @Test
    fun nexusServiceIsOnlyAddedWhenMissing() {
        assertEquals(
            SelfArmConstants.ACCESSIBILITY_SERVICE,
            SelfArmController.servicesWithNexusService(null),
        )
        assertEquals(
            "other.service/.A:${SelfArmConstants.ACCESSIBILITY_SERVICE}",
            SelfArmController.servicesWithNexusService("other.service/.A"),
        )
        assertEquals(
            "other.service/.A:${SelfArmConstants.ACCESSIBILITY_SERVICE}",
            SelfArmController.servicesWithNexusService(
                "other.service/.A:${SelfArmConstants.ACCESSIBILITY_SERVICE}",
            ),
        )
    }

    @Test
    fun repairIsOnlyNeededWhenNexusAccessibilitySettingsAreBroken() {
        assertFalse(SelfArmController.accessibilityRepairNeeded(SelfArmConstants.ACCESSIBILITY_SERVICE, 1))
        assertTrue(SelfArmController.accessibilityRepairNeeded("other.service/.A", 1))
        assertTrue(SelfArmController.accessibilityRepairNeeded(SelfArmConstants.ACCESSIBILITY_SERVICE, 0))
    }

    @Test
    fun installCommandIsFixedToNexusWatchdogAndLoopbackPort() {
        val command = SelfArmController.buildInstallCommand(
            script = "#!/system/bin/sh\necho ok\n",
            restartWatchdog = false,
        )

        assertTrue(command.contains("setprop persist.adb.tcp.port 5555"))
        assertTrue(command.contains("cat > '${SelfArmConstants.WATCHDOG_REMOTE_PATH}'"))
        assertTrue(command.contains("sh '${SelfArmConstants.WATCHDOG_REMOTE_PATH}' start"))
        assertTrue(command.contains("sh '${SelfArmConstants.WATCHDOG_REMOTE_PATH}' repair"))
    }

    @Test
    fun installAndStopRequireVerifiedSentinels() {
        assertTrue(
            SelfArmController.installCommandSucceeded(
                "ROKID_NEXUS_INSTALL_RESULT watchdog=1 persist=5555 service=5555\n",
            ),
        )
        assertFalse(
            SelfArmController.installCommandSucceeded(
                "ROKID_NEXUS_INSTALL_RESULT watchdog=0 persist=5555 service=5555\n",
            ),
        )
        assertTrue(SelfArmController.stopCommandSucceeded("ROKID_NEXUS_STOP_RESULT watchdog=1\n"))
        assertFalse(SelfArmController.stopCommandSucceeded("ROKID_NEXUS_STOP_RESULT watchdog=0\n"))
    }

    @Test
    fun wifiCommandSelectionUsesOnlyFixedSvcLiterals() {
        val commands = mutableListOf<String>()
        val seam = WifiControlSeam(
            loadKey = { testKey },
            loopbackListening = { true },
            runShell = { command, _ ->
                commands += command
                AdbLoopbackClient.ShellResult(true, true, true, "")
            },
        )

        assertTrue(SelfArmController.setWifiEnabled(enabled = true, seam = seam))
        assertTrue(SelfArmController.setWifiEnabled(enabled = false, seam = seam))

        assertEquals(listOf("svc wifi enable", "svc wifi disable"), commands)
    }

    @Test
    fun wifiRequestGracefullyFailsWithoutKeyOrLoopback() {
        var shellCalls = 0
        val unavailableKey = WifiControlSeam(
            loadKey = { null },
            loopbackListening = { true },
            log = {},
            runShell = { _, _ ->
                shellCalls += 1
                AdbLoopbackClient.ShellResult(true, true, true, "")
            },
        )
        val unavailableLoopback = unavailableKey.copy(
            loadKey = { testKey },
            loopbackListening = { false },
        )

        assertFalse(SelfArmController.setWifiEnabled(enabled = true, seam = unavailableKey))
        assertFalse(SelfArmController.setWifiEnabled(enabled = false, seam = unavailableLoopback))
        assertEquals(0, shellCalls)
    }
}
