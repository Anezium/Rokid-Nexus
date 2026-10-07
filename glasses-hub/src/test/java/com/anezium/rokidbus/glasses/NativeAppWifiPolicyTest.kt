package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.YoutubeSetupContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAppWifiPolicyTest {
    @Test
    fun `youtube and microg need the network, other native apps do not`() {
        YoutubeSetupContract.PACKAGES.forEach { assertTrue(it, NativeAppWifiPolicy.needsWifi(it)) }
        assertFalse(NativeAppWifiPolicy.needsWifi("com.android.camera2"))
        assertFalse(NativeAppWifiPolicy.needsWifi("com.rokid.os.sprite.launcher"))
    }
}
