package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.YoutubeInventory
import com.anezium.rokidbus.shared.YoutubeSetupContract
import org.json.JSONObject
import java.security.MessageDigest

internal data class MicroGRelease(val url: String, val sha256: String, val size: Long)

internal object YoutubeApkPolicy {
    const val MICROG_RELEASE_URL = "https://api.github.com/repos/MorpheApp/MicroG-RE/releases/latest"
    const val MAX_APK_BYTES = 350L * 1024 * 1024

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

    fun updateError(archive: ArtifactArchiveInfo, minSdk: Int, inventory: YoutubeInventory): String? {
        if (minSdk > inventory.sdk) return "This APK requires a newer Android version than the glasses."
        val installed = inventory.apps.singleOrNull { it.packageName == archive.packageName }
            ?: return "The glasses did not report this package. Refresh and retry."
        if (!installed.installed) return null
        if (installed.signer.isEmpty() || installed.signer != signer(archive)) {
            return "This APK has a different signing key. Use an update signed with the original key; Nexus will not remove the installed app."
        }
        if (archive.versionCode < installed.versionCode) return "A newer version is already installed on the glasses."
        return null
    }
}
