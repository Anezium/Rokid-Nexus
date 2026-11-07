package com.anezium.rokidbus.phone

internal enum class PhoneAudioRelayRouteError { ROUTE_IS_GLASSES, NO_EXTERNAL_SINK }

internal object PhoneAudioRelayGate {
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
