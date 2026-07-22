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
    fun accessibilitySettingsActionRoundTripsItsWireValue() {
        assertEquals(
            "open_accessibility_settings",
            SelfArmManualAction.OPEN_ACCESSIBILITY_SETTINGS.wireValue,
        )
        assertEquals(
            SelfArmManualAction.OPEN_ACCESSIBILITY_SETTINGS,
            SelfArmManualAction.fromWireValue("open_accessibility_settings"),
        )
    }

    @Test
    fun manualActionWireValuesStayStable() {
        assertEquals(
            listOf(
                "enable_developer_options",
                "open_developer_options",
                "open_wireless_debugging",
                "open_pairing_dialog",
                "open_accessibility_settings",
                "close",
            ),
            SelfArmManualAction.entries.map { it.wireValue },
        )
    }

    @Test
    fun wirelessAndLegacyPairingActionsOpenTheWirelessFragmentDirectly() {
        assertEquals(
            WIRELESS_DEBUGGING_PREFERENCE_KEY,
            SelfArmManualTarget.WIRELESS_DEBUGGING.settingsPreferenceKey,
        )
        assertEquals(
            WIRELESS_DEBUGGING_PREFERENCE_KEY,
            SelfArmManualTarget.PAIRING_DIALOG.settingsPreferenceKey,
        )
        assertEquals(
            WIRELESS_DEBUGGING_FRAGMENT_CLASS,
            SelfArmManualTarget.WIRELESS_DEBUGGING.directFragmentClassName,
        )
        assertEquals(
            WIRELESS_DEBUGGING_FRAGMENT_CLASS,
            SelfArmManualTarget.PAIRING_DIALOG.directFragmentClassName,
        )
    }
}
