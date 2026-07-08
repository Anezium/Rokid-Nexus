package com.anezium.rokidbus.phone

internal object NexusPhoneState {
    const val ACTION_LOG = "com.anezium.rokidbus.phone.LOG"
    const val AUTH_REQUEST = 42
    const val PREFS = "rokidbus_phone"
    const val PREF_TOKEN = "cxrl_token"
    const val UPDATE_VERSION_LABEL = "Rokid Nexus 1.4"

    var updateAvailable: Boolean = false
}
