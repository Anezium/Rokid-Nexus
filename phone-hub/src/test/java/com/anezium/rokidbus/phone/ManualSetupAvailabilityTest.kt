package com.anezium.rokidbus.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSetupAvailabilityTest {
    @Test
    fun incompleteInstalledGlassesAlwaysOfferManualRecovery() {
        assertTrue(
            shouldOfferManualSetup(
                cxrReady = true,
                glassesAppInstalled = true,
                glassesSetupComplete = false,
            ),
        )
    }

    @Test
    fun recoveryStaysHiddenWhenItCannotBeUsedOrSetupIsComplete() {
        assertFalse(shouldOfferManualSetup(false, true, false))
        assertFalse(shouldOfferManualSetup(true, false, false))
        assertFalse(shouldOfferManualSetup(true, true, true))
    }
}
