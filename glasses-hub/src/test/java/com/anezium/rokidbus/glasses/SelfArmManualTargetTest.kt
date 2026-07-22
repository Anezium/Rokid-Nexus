package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmManualTargetTest {
    @Test
    fun developerEnableTargetsBuildNumberAndUsesExactlySixRapidTaps() {
        assertEquals(
            BUILD_NUMBER_PREFERENCE_KEY,
            SelfArmManualTarget.ENABLE_DEVELOPER_OPTIONS.settingsPreferenceKey,
        )
        assertEquals(6, SelfArmDeveloperOptionsTapPolicy.REQUIRED_TAPS)
        assertEquals(7, SelfArmDeveloperOptionsTapPolicy.MAX_TAPS)
        assertEquals(140L, SelfArmDeveloperOptionsTapPolicy.TAP_INTERVAL_MS)
        assertFalse(SelfArmDeveloperOptionsTapPolicy.isCompatibilityTap(5))
        assertTrue(SelfArmDeveloperOptionsTapPolicy.isCompatibilityTap(6))
    }

    @Test
    fun developerOptionsUsesThePublicTopLevelScreen() {
        assertNull(SelfArmManualTarget.DEVELOPER_OPTIONS.settingsPreferenceKey)
    }

    @Test
    fun wirelessAndLegacyPairingActionsHighlightTheWirelessPreference() {
        assertEquals(
            WIRELESS_DEBUGGING_PREFERENCE_KEY,
            SelfArmManualTarget.WIRELESS_DEBUGGING.settingsPreferenceKey,
        )
        assertEquals(
            WIRELESS_DEBUGGING_PREFERENCE_KEY,
            SelfArmManualTarget.PAIRING_DIALOG.settingsPreferenceKey,
        )
    }
}
