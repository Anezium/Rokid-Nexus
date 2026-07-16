package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInstaller as AndroidPackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal const val NEXUS_SELF_UPDATE_INSTALL_ID = "nexus-phone-self-update"

internal const val NEXUS_UPDATE_SIGNATURE_MISMATCH_MESSAGE =
    "This build is signed differently from the release. Reinstall Rokid Nexus from the release, then try again."

internal fun interface InstalledArtifactPackageInspector {
    fun inspect(packageName: String): ArtifactArchiveInfo?
}

class NexusSelfUpdater internal constructor(
    private val pipeline: ArtifactInstallPipeline,
    private val installedPackageInspector: InstalledArtifactPackageInspector,
) {
    fun install(
        release: NexusAppRelease,
        packageName: String,
        listener: (PluginInstallState) -> Unit,
    ): PluginInstallOperation = pipeline.install(
        ArtifactInstallRequest(
            cacheFileName = "nexus-phone-${release.versionName}.apk",
            url = release.apkUrl,
            sha256 = release.sha256,
            expectedPackageName = packageName,
            installationId = NEXUS_SELF_UPDATE_INSTALL_ID,
            sha256FailureMessage = "Downloaded update failed SHA-256 verification",
            fallbackFailureMessage = "Rokid Nexus update failed",
            verifyArchive = { archive -> verifyArchive(packageName, release, archive) },
            mapInstallFailure = ::selfUpdateInstallFailureMessage,
            successState = {
                PluginInstallState.Success(NEXUS_SELF_UPDATE_INSTALL_ID, packageName)
            },
            postInstallFailureMessage = "Rokid Nexus was updated, but completion could not be recorded",
        ),
        listener,
    )

    private fun verifyArchive(
        packageName: String,
        release: NexusAppRelease,
        archive: ArtifactArchiveInfo?,
    ): String? {
        if (archive == null || archive.packageName != packageName) {
            return "Downloaded update is not the Rokid Nexus phone app"
        }
        if (archive.versionName != release.versionName) {
            return "Downloaded update version does not match the GitHub release"
        }
        val installed = installedPackageInspector.inspect(packageName)
            ?: return "Could not verify the installed Rokid Nexus signature"
        if (!hasCompatibleSigner(installed, archive)) {
            return NEXUS_UPDATE_SIGNATURE_MISMATCH_MESSAGE
        }
        return null
    }

    companion object {
        private val DEFAULT_IO_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nexus-self-update").apply { isDaemon = true }
        }

        fun create(context: Context): NexusSelfUpdater {
            val appContext = context.applicationContext
            return NexusSelfUpdater(
                pipeline = ArtifactInstallPipeline(
                    cacheDirectory = File(appContext.cacheDir, "app-updates"),
                    downloader = HttpsArtifactDownloader(),
                    packageInspector = AndroidArtifactPackageInspector(appContext.packageManager),
                    packageInstaller = AndroidPackageInstallGateway(appContext),
                    ioExecutor = DEFAULT_IO_EXECUTOR,
                    callbackExecutor = Executor { runnable -> Handler(Looper.getMainLooper()).post(runnable) },
                ),
                installedPackageInspector = AndroidInstalledArtifactPackageInspector(appContext.packageManager),
            )
        }

        internal fun hasCompatibleSigner(
            installed: ArtifactArchiveInfo,
            update: ArtifactArchiveInfo,
        ): Boolean {
            val installedHistory = installed.signingCertificateHistory.ifEmpty { installed.signingCertificates }
            val updateHistory = update.signingCertificateHistory.ifEmpty { update.signingCertificates }
            return installedHistory.any { installedSigner ->
                updateHistory.any(installedSigner::contentEquals)
            }
        }
    }
}

internal fun selfUpdateInstallFailureMessage(failure: PackageInstallEvent.Failure): String {
    val detail = failure.message.trim()
    val normalized = detail.lowercase()
    val signatureMismatch = failure.statusCode == AndroidPackageInstaller.STATUS_FAILURE_CONFLICT ||
        normalized.contains("signature") ||
        normalized.contains("update_incompatible") ||
        normalized.contains("update incompatible")
    return if (signatureMismatch) {
        NEXUS_UPDATE_SIGNATURE_MISMATCH_MESSAGE
    } else {
        "Rokid Nexus update failed: ${detail.ifBlank { "Package installation failed" }}"
    }
}

private class AndroidInstalledArtifactPackageInspector(
    private val packageManager: PackageManager,
) : InstalledArtifactPackageInspector {
    override fun inspect(packageName: String): ArtifactArchiveInfo? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags)
        }
        val currentSigners = packageInfo.signingInfo?.apkContentsSigners.orEmpty()
            .map { signer -> signer.toByteArray() }
        val signerHistory = packageInfo.signingInfo?.signingCertificateHistory.orEmpty()
            .map { signer -> signer.toByteArray() }
            .ifEmpty { currentSigners }
        return ArtifactArchiveInfo(
            packageName = packageInfo.packageName,
            versionCode = packageInfo.longVersionCode,
            signingCertificates = currentSigners,
            versionName = packageInfo.versionName,
            signingCertificateHistory = signerHistory,
        )
    }
}

internal sealed interface RecoveredNexusUpdateInstall {
    data object Success : RecoveredNexusUpdateInstall
    data object Cancelled : RecoveredNexusUpdateInstall
    data class Failure(val message: String) : RecoveredNexusUpdateInstall
}

internal class NexusUpdateInstallRecoveryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(result: RecoveredNexusUpdateInstall) {
        when (result) {
            RecoveredNexusUpdateInstall.Success -> preferences.edit()
                .putString(KEY_TYPE, "success")
                .remove(KEY_MESSAGE)
                .commit()
            RecoveredNexusUpdateInstall.Cancelled -> preferences.edit()
                .putString(KEY_TYPE, "cancelled")
                .remove(KEY_MESSAGE)
                .commit()
            is RecoveredNexusUpdateInstall.Failure -> preferences.edit()
                .putString(KEY_TYPE, "failure")
                .putString(KEY_MESSAGE, result.message)
                .commit()
        }
    }

    fun consume(): RecoveredNexusUpdateInstall? {
        val result = when (preferences.getString(KEY_TYPE, null)) {
            "success" -> RecoveredNexusUpdateInstall.Success
            "cancelled" -> RecoveredNexusUpdateInstall.Cancelled
            "failure" -> RecoveredNexusUpdateInstall.Failure(
                preferences.getString(KEY_MESSAGE, null)
                    ?.takeIf(String::isNotBlank)
                    ?: "Rokid Nexus update failed",
            )
            else -> null
        }
        if (result != null) preferences.edit().remove(KEY_TYPE).remove(KEY_MESSAGE).commit()
        return result
    }

    companion object {
        private const val PREFERENCES = "nexus_update_install_recovery"
        private const val KEY_TYPE = "last_result_type"
        private const val KEY_MESSAGE = "last_result_message"
    }
}

internal fun Activity.resumeRecoveredNexusUpdateInstall() {
    when (val result = NexusUpdateInstallRecoveryStore(applicationContext).consume()) {
        RecoveredNexusUpdateInstall.Success -> {
            NexusPhoneState.clearAvailableUpdate()
            Toast.makeText(this, "Rokid Nexus updated", Toast.LENGTH_SHORT).show()
        }
        RecoveredNexusUpdateInstall.Cancelled ->
            Toast.makeText(this, "Update cancelled", Toast.LENGTH_SHORT).show()
        is RecoveredNexusUpdateInstall.Failure ->
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        null -> Unit
    }
}
