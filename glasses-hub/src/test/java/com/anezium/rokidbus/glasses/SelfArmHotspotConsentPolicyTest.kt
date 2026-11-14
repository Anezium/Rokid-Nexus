package com.anezium.rokidbus.glasses

import org.junit.Assert.*
import org.junit.Test

class SelfArmHotspotConsentPolicyTest {
    @Test fun `network consent cannot authorize debugging or unrelated settings dialogs`() {
        assertTrue(SelfArmHotspotConsentPolicy.mayHandle(false,
            "com.android.settings.wifi.NetworkRequestDialogActivity", "com.android.settings"))
        assertFalse(SelfArmHotspotConsentPolicy.mayHandle(false,
            "com.android.systemui.wifi.WifiDebuggingActivity", "com.android.systemui"))
        assertFalse(SelfArmHotspotConsentPolicy.mayHandle(false,
            "android.app.AlertDialog", "com.android.settings"))
    }

    @Test fun `debugging consent cannot confirm USB RSA or a third party activity`() {
        assertTrue(SelfArmHotspotConsentPolicy.mayHandle(true,
            "com.android.systemui.wifi.WifiDebuggingActivity", "com.android.systemui"))
        assertFalse(SelfArmHotspotConsentPolicy.mayHandle(true,
            "com.android.systemui.usb.UsbDebuggingActivity", "com.android.systemui"))
        assertFalse(SelfArmHotspotConsentPolicy.mayHandle(true,
            "com.android.systemui.wifi.WifiDebuggingActivity", "example.other"))
    }
}
