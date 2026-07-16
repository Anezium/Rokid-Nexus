package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmWirelessBootstrapPolicyTest {
    @Test
    fun releaseFirstRunIsAllowedWithoutDebugBuildGate() {
        assertTrue(
            SelfArmWirelessBootstrapPolicy.mayStart(
                SelfArmWirelessBootstrapReceiver.ACTION_SELFARM_WIRELESS_START,
                SelfArmWirelessBootstrapPolicy.CallerTrust.SAME_APP_OR_SIGNATURE,
                sdkInt = 31,
            ),
        )
    }

    @Test
    fun untrustedWrongActionAndOldFirmwareAreRejected() {
        val action = SelfArmWirelessBootstrapReceiver.ACTION_SELFARM_WIRELESS_START

        assertFalse(
            SelfArmWirelessBootstrapPolicy.mayStart(
                action,
                SelfArmWirelessBootstrapPolicy.CallerTrust.UNTRUSTED,
                sdkInt = 31,
            ),
        )
        assertFalse(
            SelfArmWirelessBootstrapPolicy.mayStart(
                "wrong.action",
                SelfArmWirelessBootstrapPolicy.CallerTrust.SAME_APP_OR_SIGNATURE,
                sdkInt = 31,
            ),
        )
        assertFalse(
            SelfArmWirelessBootstrapPolicy.mayStart(
                action,
                SelfArmWirelessBootstrapPolicy.CallerTrust.SAME_APP_OR_SIGNATURE,
                sdkInt = 29,
            ),
        )
    }
}
