package com.anezium.rokidbus.glasses

internal object SelfArmWirelessBootstrapPolicy {
    enum class CallerTrust {
        SAME_APP_OR_SIGNATURE,
        UNTRUSTED,
    }

    fun mayStart(
        action: String?,
        callerTrust: CallerTrust,
        sdkInt: Int,
    ): Boolean =
        action == SelfArmWirelessBootstrapReceiver.ACTION_SELFARM_WIRELESS_START &&
            callerTrust == CallerTrust.SAME_APP_OR_SIGNATURE &&
            sdkInt >= 30
}
