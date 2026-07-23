package com.anezium.rokidbus.phone

import com.anezium.rokidbus.client.R as BusClientR

internal sealed interface StoreIconFallback {
    data class InstalledDescriptor(
        val packageName: String,
        val iconKey: String?,
        val customIconResId: Int?,
        val pluginId: String,
        val legacyResId: Int,
    ) : StoreIconFallback

    data class Legacy(val resId: Int) : StoreIconFallback
}

internal fun selectStoreIconFallback(
    pluginId: String,
    installedPackageName: String?,
    iconKey: String?,
    customIconResId: Int?,
): StoreIconFallback {
    val legacyResId = iconFor(pluginId)
    return installedPackageName?.takeIf(String::isNotBlank)?.let { packageName ->
        StoreIconFallback.InstalledDescriptor(
            packageName = packageName,
            iconKey = iconKey,
            customIconResId = customIconResId,
            pluginId = pluginId,
            legacyResId = legacyResId,
        )
    } ?: StoreIconFallback.Legacy(legacyResId)
}

internal fun iconFor(id: String): Int = when (id) {
    "lyrics" -> BusClientR.drawable.ic_plugin_music
    "media" -> BusClientR.drawable.ic_plugin_disc
    "transit" -> BusClientR.drawable.ic_plugin_bus
    "lens" -> BusClientR.drawable.ic_plugin_lens
    "feeds" -> BusClientR.drawable.ic_plugin_send
    "shoplist" -> BusClientR.drawable.ic_plugin_cart
    else -> BusClientR.drawable.ic_plugin_grid
}
