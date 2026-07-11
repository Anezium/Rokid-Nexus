package com.anezium.rokidbus.glasses

internal data class GlassesWifiRequestResult(
    val hubOwned: Boolean,
    val applied: Boolean,
)

internal class GlassesWifiOwnership {
    private var hubEnabledWifi = false

    @Synchronized
    fun handleRequest(
        enabled: Boolean,
        wifiCurrentlyEnabled: Boolean,
        setWifiEnabled: (Boolean) -> Boolean,
    ): GlassesWifiRequestResult {
        val applied = when {
            enabled && !wifiCurrentlyEnabled -> {
                setWifiEnabled(true).also { succeeded ->
                    if (succeeded) hubEnabledWifi = true
                }
            }
            !enabled && hubEnabledWifi -> {
                setWifiEnabled(false).also {
                    hubEnabledWifi = false
                }
            }
            else -> false
        }
        return GlassesWifiRequestResult(hubOwned = hubEnabledWifi, applied = applied)
    }

    @Synchronized
    fun isHubOwned(): Boolean = hubEnabledWifi
}
