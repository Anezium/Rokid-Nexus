package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.anezium.rokidbus.shared.YoutubePatcherContract

/**
 * First-install APK validation has no known glasses signer to compare, so it cannot
 * authenticate a patcher. Automatic installation requires the user's existing
 * signer-bound approval of a valid principal; package/activity presence is only UI routing.
 */
internal object YoutubePatcherHandoff {
    fun patchIntent(): Intent = Intent(YoutubePatcherContract.ACTION_PATCH)
        .setComponent(ComponentName(YoutubePatcherContract.PACKAGE, YoutubePatcherContract.PATCH_ACTIVITY))

    fun storeIntent(context: Context): Intent =
        StorePluginDetailActivity.intent(context, YoutubePatcherContract.PLUGIN_ID)

    fun approvalIntent(context: Context): Intent = PluginPermissionsActivity.intent(
        context, PluginGrantTarget(YoutubePatcherContract.PACKAGE, YoutubePatcherContract.PLUGIN_ID),
    )

    @Suppress("DEPRECATION")
    fun reviewIntent(context: Context): Intent =
        if (runCatching { context.packageManager.getPackageInfo(YoutubePatcherContract.PACKAGE, 0) }.isSuccess)
            approvalIntent(context) else storeIntent(context)

    private fun principal(context: Context): PhonePluginPrincipal? = approvedPrincipal(
        PhonePluginDiscovery(context.packageManager).discover(), PluginGrantStore(context)::stateFor,
    )

    internal fun approvedPrincipal(
        candidates: List<PhonePluginCandidate>,
        grantState: (PhonePluginPrincipal) -> PluginGrantState,
    ): PhonePluginPrincipal? {
        val candidate = candidates.singleOrNull { it.packageName == YoutubePatcherContract.PACKAGE }
            as? PhonePluginCandidate.Valid ?: return null
        val principal = candidate.principal
        return principal.takeIf {
            it.descriptor.id == YoutubePatcherContract.PLUGIN_ID &&
                it.signingDigestSha256.isNotBlank() && grantState(it) is PluginGrantState.Approved
        }
    }

    fun authenticatedIdentity(context: Context): String? = runCatching {
        principal(context)?.let { identity(context.packageManager, it) }
    }.getOrNull()

    // Bind the result to the approved install observed at launch; rediscover and recheck
    // approval at the result boundary. Replacement (even by an approved signer) is not a session.
    @Suppress("DEPRECATION")
    internal fun identity(manager: PackageManager, principal: PhonePluginPrincipal): String? = runCatching {
        val activity = manager.getActivityInfo(patchIntent().component!!, 0)
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled ||
            activity.applicationInfo.uid != principal.uid) return@runCatching null
        val installed = manager.getPackageInfo(principal.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = installed.signingInfo?.apkContentsSigners.orEmpty()
        if (signers.size != 1 || signingCertificateSha256(signers.single().toByteArray()) !=
            principal.signingDigestSha256) return@runCatching null
        listOf(principal.packageName, principal.descriptor.id, principal.signingDigestSha256,
            principal.uid.toString(), principal.serviceComponent.flattenToString(),
            installed.lastUpdateTime.toString()).joinToString("|")
    }.getOrNull()

    fun acceptsResult(launchedIdentity: String?, currentIdentity: String?): Boolean =
        launchedIdentity != null && launchedIdentity == currentIdentity

    // Metadata is informational, not evidence. prepare() copies and inspects the APK itself.
    fun resultUri(result: Intent?): Uri? = result?.data?.takeIf {
        it.scheme == "content" && !it.authority.isNullOrBlank() &&
            result.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
    }
}

/** Only a manual checklist bit is persisted; it is not an authentication observation. */
internal class YoutubeSetupChecklist(context: Context) {
    private val preferences = context.getSharedPreferences("youtube_setup_checklist", Context.MODE_PRIVATE)
    var signInDone: Boolean
        get() = preferences.getBoolean("sign_in_done", false)
        set(value) { preferences.edit().putBoolean("sign_in_done", value).apply() }
}
