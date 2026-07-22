package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelfArmManualTargetTest {
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
