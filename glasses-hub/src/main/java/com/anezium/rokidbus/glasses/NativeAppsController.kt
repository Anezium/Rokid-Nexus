package com.anezium.rokidbus.glasses

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.anezium.rokidbus.shared.NativeAppContract
import com.anezium.rokidbus.shared.NativeAppEntry
import com.anezium.rokidbus.shared.NativeAppErrorCode
import com.anezium.rokidbus.shared.NativeAppLaunchResult
import com.anezium.rokidbus.shared.NativeAppListResult
import org.json.JSONObject
import java.util.Locale

/** Minimal glasses-side catalog/launcher behind the phone's native-apps skeleton. */
internal object NativeAppsController {
    private const val MAX_CXR_PAYLOAD_BYTES = 2_200
    internal const val LAUNCHER_QUERY_FLAGS = 0

    fun handle(
        context: Context,
        payload: JSONObject,
        reply: (JSONObject) -> Boolean,
    ): Boolean {
        com.anezium.rokidbus.shared.RedditSetupContract.requestId(payload)?.let { id ->
            runCatching { YoutubePackageInventory.redditResult(context, id) }.onSuccess { reply(it) }
            return true
        }
        com.anezium.rokidbus.shared.YoutubeSetupContract.requestId(payload)?.let { id ->
            runCatching { YoutubePackageInventory.result(context, id) }.onSuccess { reply(it) }
            return true
        }
        NativeAppContract.parseListRequest(payload)?.let { requestId ->
            val apps = runCatching { discover(context) }.getOrElse {
                reply(
                    NativeAppContract.listResult(
                        NativeAppListResult(
                            requestId = requestId,
                            apps = emptyList(),
                            errorCode = NativeAppErrorCode.INTERNAL,
                        ),
                    ),
                )
                return true
            }
            val full = listPayloadWithin(requestId, apps, NativeAppContract.MAX_MESSAGE_BYTES)
            if (!reply(full)) {
                reply(listPayloadWithin(requestId, apps, MAX_CXR_PAYLOAD_BYTES))
            }
            return true
        }

        val request = NativeAppContract.parseLaunchRequest(payload) ?: return false
        val error = launch(context, request.packageName)
        reply(
            NativeAppContract.launchResult(
                NativeAppLaunchResult(
                    requestId = request.requestId,
                    packageName = request.packageName,
                    errorCode = error,
                ),
            ),
        )
        return true
    }

    internal fun listPayloadWithin(
        requestId: String,
        apps: List<NativeAppEntry>,
        maxBytes: Int,
    ): JSONObject {
        var count = minOf(apps.size, NativeAppContract.MAX_APPS)
        while (count >= 0) {
            val payload = runCatching {
                NativeAppContract.listResult(NativeAppListResult(requestId, apps.take(count)))
            }.getOrNull()
            if (payload != null && payload.toString().toByteArray(Charsets.UTF_8).size <= maxBytes) {
                return payload
            }
            count--
        }
        error("An empty native-app result must fit the control envelope")
    }

    internal fun discover(context: Context): List<NativeAppEntry> {
        val packageManager = context.packageManager
        return launcherActivities(packageManager)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull { resolved ->
                val packageName = resolved.activityInfo.packageName
                if (!NativeAppContract.isValidPackageName(packageName)) return@mapNotNull null
                val label = sanitizeLabel(resolved.loadLabel(packageManager)?.toString(), packageName)
                val versionCode = runCatching {
                    packageManager.getPackageInfo(packageName, 0).longVersionCode
                }.getOrNull()
                NativeAppEntry(packageName, label, versionCode)
            }
            .sortedWith(
                compareBy<NativeAppEntry>(
                    { it.label.lowercase(Locale.ROOT) },
                    NativeAppEntry::packageName,
                ),
            )
            .take(NativeAppContract.MAX_APPS)
            .toList()
    }

    internal fun launch(context: Context, packageName: String): NativeAppErrorCode? {
        if (packageName == context.packageName) return NativeAppErrorCode.NOT_ALLOWED
        val resolved = launcherActivities(context.packageManager)
            .firstOrNull { it.activityInfo.packageName == packageName }
            ?: return NativeAppErrorCode.NOT_LAUNCHABLE
        val component = ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)
        val intent = Intent.makeMainActivity(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Asynchronous on purpose: the app opens now and reconnects once the radio is up.
        GlassesHub.ensureWifiForNativeApp(context, packageName)
        return runCatching { context.startActivity(intent) }
            .fold(onSuccess = { null }, onFailure = { NativeAppErrorCode.INTERNAL })
    }

    internal fun icon(context: Context, packageName: String): Drawable =
        runCatching { context.packageManager.getApplicationIcon(packageName) }
            .getOrElse { context.applicationInfo.loadIcon(context.packageManager) }

    private fun launcherActivities(packageManager: PackageManager) =
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            LAUNCHER_QUERY_FLAGS,
        )

    internal fun sanitizeLabel(value: String?, packageName: String): String {
        val sanitized = value.orEmpty()
            .trim()
            .filterNot(Char::isISOControl)
            .take(NativeAppContract.MAX_LABEL_LENGTH)
            .trim()
        return sanitized.ifBlank { packageName.take(NativeAppContract.MAX_LABEL_LENGTH) }
    }
}
