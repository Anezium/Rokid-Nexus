package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.LinkStateBits

internal object PhoneLinkState {
    fun compose(
        cxrControlUp: Boolean,
        sppDataUp: Boolean,
        glassesBondedOrPhoneConnected: Boolean,
        glassesWorn: Boolean,
    ): Int {
        var state = 0
        if (cxrControlUp) state = state or LinkStateBits.CXR_CONTROL_UP
        if (sppDataUp) state = state or LinkStateBits.SPP_DATA_UP
        if (glassesBondedOrPhoneConnected) {
            state = state or LinkStateBits.GLASSES_BT_BONDED_OR_PHONE_CONNECTED
        }
        if (glassesWorn) state = state or LinkStateBits.GLASSES_WORN
        return state
    }
}
