package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.anezium.rokidbus.shared.YoutubePackage
import com.anezium.rokidbus.shared.YoutubeSetupContract
import org.json.JSONObject
import java.security.MessageDigest

internal object YoutubePackageInventory {
    fun result(context: Context, requestId: String): JSONObject = YoutubeSetupContract.result(
        requestId,
        Build.VERSION.SDK_INT,
        packages(context, YoutubeSetupContract.PACKAGES),
    )

    fun redditResult(context: Context, requestId: String): JSONObject = com.anezium.rokidbus.shared.RedditSetupContract.result(
        requestId, Build.VERSION.SDK_INT,
        packages(context, com.anezium.rokidbus.shared.RedditSetupContract.PACKAGES),
    )

    private fun packages(context: Context, names: List<String>): List<YoutubePackage> =
        names.map { packageName ->
            val pm = context.packageManager
            val info = try {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            if (info == null) YoutubePackage(packageName) else {
                val signer = info.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray()
                YoutubePackage(
                    packageName,
                    info.longVersionCode,
                    signer?.let { bytes ->
                        MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it) }
                    }.orEmpty(),
                    pm.getLaunchIntentForPackage(packageName) != null,
                )
            }
        }
}
