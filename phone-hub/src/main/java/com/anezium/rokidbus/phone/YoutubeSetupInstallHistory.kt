package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.YoutubeInventory
import com.anezium.rokidbus.shared.YoutubeSetupContract

internal data class YoutubeInstalledApk(val versionCode: Long, val versionName: String?, val signer: String)

/** UI provenance only; installer validation always uses fresh glasses inventory. */
internal class YoutubeSetupInstallHistory(context: Context) {
    private val preferences = context.getSharedPreferences("youtube_setup_installs", Context.MODE_PRIVATE)

    fun installed(packageName: String): YoutubeInstalledApk? {
        val versionCode = preferences.getLong("$packageName.versionCode", 0)
        if (versionCode <= 0) return null
        return YoutubeInstalledApk(versionCode, preferences.getString("$packageName.versionName", null),
            preferences.getString("$packageName.signer", "").orEmpty())
    }

    fun confirmed(archive: ArtifactArchiveInfo) {
        preferences.edit().putLong("${archive.packageName}.versionCode", archive.versionCode)
            .putString("${archive.packageName}.versionName", archive.versionName)
            .putString("${archive.packageName}.signer", YoutubeApkPolicy.signer(archive)).apply()
    }
}

internal fun youtubeInstallStatus(inventory: YoutubeInventory?, confirmed: YoutubeInstalledApk?): String {
    if (inventory == null) return "Needs attention — refresh glasses apps"
    val youtube = inventory.apps.singleOrNull { it.packageName == YoutubeSetupContract.YOUTUBE && it.installed }
        ?: return "To do"
    if (youtube.signer.isEmpty() || confirmed == null || youtube.signer != confirmed.signer) {
        return "Needs attention — signing key not confirmed by Nexus"
    }
    if (youtube.versionCode != confirmed.versionCode || confirmed.versionName != YoutubeApkPolicy.STOCK_YOUTUBE_VERSION) {
        return "Needs attention — version not confirmed as ${YoutubeApkPolicy.STOCK_YOUTUBE_VERSION}"
    }
    return "Done"
}
