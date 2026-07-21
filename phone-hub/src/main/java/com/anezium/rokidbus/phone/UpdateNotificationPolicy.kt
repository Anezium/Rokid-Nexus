package com.anezium.rokidbus.phone

internal object UpdateNotificationPolicy {
    fun shouldNotifyAppUpdate(
        availableVersion: String,
        lastNotifiedVersion: String?,
    ): Boolean = availableVersion.isNotBlank() && availableVersion != lastNotifiedVersion

    fun pluginUpdateSet(updates: Collection<PluginUpdateInfo>): Set<String> =
        updates.mapTo(linkedSetOf()) { update ->
            "${update.pluginId.length}:${update.pluginId}${update.availableVersionName}"
        }

    fun shouldNotifyPluginUpdates(
        currentUpdateSet: Set<String>,
        lastNotifiedUpdateSet: Set<String>?,
    ): Boolean = currentUpdateSet.isNotEmpty() && currentUpdateSet != lastNotifiedUpdateSet
}
