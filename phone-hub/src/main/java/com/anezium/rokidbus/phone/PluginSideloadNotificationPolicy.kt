package com.anezium.rokidbus.phone

import android.content.Intent

object PluginSideloadNotificationPolicy {
    fun shouldNotify(
        developerModeEnabled: Boolean,
        action: String?,
        replacing: Boolean,
        candidate: PhonePluginCandidate?,
        hasExistingGrant: Boolean,
    ): Boolean = developerModeEnabled &&
        action == Intent.ACTION_PACKAGE_ADDED &&
        !replacing &&
        candidate is PhonePluginCandidate.Valid &&
        !hasExistingGrant
}
