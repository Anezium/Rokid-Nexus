package com.anezium.rokidbus.phone

internal enum class PhoneAudioRelayRouteError { ROUTE_IS_GLASSES, NO_EXTERNAL_SINK }

internal object PhoneAudioRelayGate {
    // Before API 33 the gate only knows that an output exists, so a rejected route would start the
    // glasses capture again on every poll and cut their speaker. Wait for an output change instead.
    fun pollClearsRouteRejection(sdkInt: Int): Boolean = sdkInt >= 33

    fun desired(enabled: Boolean, route: PhoneTtsRoute, glassesCapable: Boolean, sppDataUp: Boolean): Boolean =
        enabled && route == PhoneTtsRoute.EXTERNAL_SINK && glassesCapable && sppDataUp

    fun waitingStatus(enabled: Boolean, route: PhoneTtsRoute, glassesCapable: Boolean, sppDataUp: Boolean): PhoneAudioRelayStatus = when {
        !enabled -> PhoneAudioRelayStatus.OFF
        route != PhoneTtsRoute.EXTERNAL_SINK -> PhoneAudioRelayStatus.WAITING_FOR_EARBUDS
        !sppDataUp -> PhoneAudioRelayStatus.WAITING_FOR_GLASSES
        !glassesCapable -> PhoneAudioRelayStatus.GLASSES_TOO_OLD
        else -> PhoneAudioRelayStatus.CONNECTING
    }
}
