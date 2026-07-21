package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.LinkStateBits
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneLinkStateTest {
    @Test
    fun `wearing status sets and clears its independent link bit`() {
        val transportState = PhoneLinkState.compose(
            cxrControlUp = true,
            sppDataUp = true,
            glassesBondedOrPhoneConnected = true,
            glassesWorn = false,
        )
        val wornState = PhoneLinkState.compose(
            cxrControlUp = true,
            sppDataUp = true,
            glassesBondedOrPhoneConnected = true,
            glassesWorn = true,
        )

        assertEquals(
            LinkStateBits.CXR_CONTROL_UP or
                LinkStateBits.SPP_DATA_UP or
                LinkStateBits.GLASSES_BT_BONDED_OR_PHONE_CONNECTED,
            transportState,
        )
        assertEquals(transportState or LinkStateBits.GLASSES_WORN, wornState)
    }

    @Test
    fun `worn remains independently observable without transport bits`() {
        assertEquals(
            LinkStateBits.GLASSES_WORN,
            PhoneLinkState.compose(
                cxrControlUp = false,
                sppDataUp = false,
                glassesBondedOrPhoneConnected = false,
                glassesWorn = true,
            ),
        )
    }
}
