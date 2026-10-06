package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.YoutubeInventory
import com.anezium.rokidbus.shared.YoutubeSetupContract
import org.json.JSONObject
import java.security.MessageDigest

internal data class MicroGRelease(val url: String, val sha256: String, val size: Long)

internal object YoutubeApkPolicy {
    const val MICROG_RELEASE_URL = "https://api.github.com/repos/MorpheApp/MicroG-RE/releases/latest"
    const val MAX_APK_BYTES = 350L * 1024 * 1024

    // The Rokid controls patch targets exactly one stock YouTube build; bump this with the patch.
    const val STOCK_YOUTUBE_VERSION = "21.04.223"
    const val STOCK_YOUTUBE_URL =
        "https://www.apkmirror.com/apk/google-inc/youtube/youtube-21-04-223-release/"
    const val MORPHE_MANAGER_PACKAGE = "app.morphe.manager"
    const val MORPHE_MANAGER_URL = "https://github.com/MorpheApp/morphe-manager/releases/latest"
    const val MORPHE_MANAGER_ACTIVITY = "app.morphe.manager.MainActivity"

    // Morphe Manager asks before adding the source, then keeps it updated from the fork's releases.
    const val ROKID_PATCHES_REPO = "Anezium/morphe-patches"
    const val ROKID_PATCHES_SOURCE_URL =
        "https://morphe.software/add-source?github=$ROKID_PATCHES_REPO&name=Rokid%20glasses"
    const val ROKID_PATCHES_README_URL = "https://github.com/$ROKID_PATCHES_REPO/tree/rokid#readme"

    // Opens Morphe's usual patch dialog for one app, as its launcher shortcuts do.
    const val MORPHE_ACTION_PATCH_APP = "app.morphe.manager.action.PATCH_APP"
    const val MORPHE_EXTRA_PATCH_PACKAGE = "patch_package"
    const val STOCK_YOUTUBE_PACKAGE = "com.google.android.youtube"

    fun microGRelease(raw: String): MicroGRelease {
        val json = JSONObject(raw)
        require(json.opt("draft") == false && json.opt("prerelease") == false)
        val tag = json.getString("tag_name")
        require(tag.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        val version = tag.removePrefix("v")
        val assets = json.getJSONArray("assets")
        // The glasses are arm64 and the CXR transfer is slow: the arm64 asset is less than
        // half the size of the universal one. Both carry the launcher icon; "noicon" never.
        val name = listOf("microg-$version-arm64-v8a.apk", "microg-$version.apk").first { candidate ->
            (0 until assets.length()).any { assets.getJSONObject(it).optString("name") == candidate }
        }
        val matches = (0 until assets.length()).map(assets::getJSONObject)
            .filter { it.optString("name") == name }
        val asset = matches.single()
        val url = asset.getString("browser_download_url")
        require(url == "https://github.com/MorpheApp/MicroG-RE/releases/download/$tag/$name")
        val digest = asset.getString("digest")
        require(digest.matches(Regex("sha256:[a-f0-9]{64}")))
        val size = asset.getLong("size")
        require(size in 1..MAX_APK_BYTES)
        return MicroGRelease(url, digest.removePrefix("sha256:"), size)
    }

    fun signer(archive: ArtifactArchiveInfo): String {
        val certificate = archive.signingCertificates.singleOrNull()
            ?: error("Use an APK with one signing certificate.")
        return MessageDigest.getInstance("SHA-256").digest(certificate)
            .joinToString("") { "%02x".format(it) }
    }

    fun validatePackage(archive: ArtifactArchiveInfo, microG: Boolean) {
        val allowed = if (microG) listOf(YoutubeSetupContract.MICROG)
            else listOf(YoutubeSetupContract.YOUTUBE, YoutubeSetupContract.YOUTUBE_TEST)
        require(archive.packageName in allowed) {
            if (microG) "The download is not Morphe MicroG-RE."
            else "Choose a Morphe-patched YouTube APK, including the Rokid controls patch."
        }
        require(archive.versionCode > 0) { "The APK has no valid version." }
        signer(archive)
    }

    /** A patched APK built from another stock build lacks the Rokid controls the patch was written for. */
    fun versionNotice(archive: ArtifactArchiveInfo): String? {
        if (archive.packageName == YoutubeSetupContract.MICROG) return null
        if (archive.versionName == STOCK_YOUTUBE_VERSION) return null
        return "This APK is YouTube ${archive.versionName ?: archive.versionCode}, not $STOCK_YOUTUBE_VERSION which the Rokid controls patch targets."
    }

    fun updateError(archive: ArtifactArchiveInfo, minSdk: Int, inventory: YoutubeInventory): String? {
        if (minSdk > inventory.sdk) return "This APK requires a newer Android version than the glasses."
        val installed = inventory.apps.singleOrNull { it.packageName == archive.packageName }
            ?: return "The glasses did not report this package. Refresh and retry."
        if (!installed.installed) return null
        if (installed.signer.isEmpty() || installed.signer != signer(archive)) {
            return "This APK has a different signing key. Use an update signed with the original key; Nexus will not remove the installed app. If Patcher produced the installed app, import the key backup you exported from it; otherwise remove YouTube from the glasses by hand only if you accept losing its data."
        }
        if (archive.versionCode < installed.versionCode) return "A newer version is already installed on the glasses."
        return null
    }
}
