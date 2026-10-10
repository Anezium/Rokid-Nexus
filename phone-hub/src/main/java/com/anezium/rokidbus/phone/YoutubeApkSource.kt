package com.anezium.rokidbus.phone

import android.content.Context
import android.net.Uri
import com.anezium.rokidbus.shared.YoutubeSetupContract
import java.io.File
import java.net.URL
import java.util.UUID

internal data class PreparedYoutubeApk(
    val file: File,
    val archive: ArtifactArchiveInfo,
    val minSdk: Int,
    val sha256: String,
) {
    val label: String get() = "${when (archive.packageName) {
        YoutubeSetupContract.MICROG -> "MicroG-RE"
        com.anezium.rokidbus.shared.RedditSetupContract.REDDIT -> "Reddit"
        else -> "YouTube"
    }} ${archive.versionName ?: archive.versionCode}"
}

internal class YoutubeApkSource(private val context: Context, private val target: NativeSetupTarget = NativeSetupTarget.YOUTUBE) {
    fun clearAbandonedFiles() {
        File(context.cacheDir, "${target.id}-setup").listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("[a-f0-9-]{36}\\.apk")) }
            ?.forEach { it.delete() }
    }

    fun prepare(uri: Uri?, cancelled: () -> Boolean, progress: (String) -> Unit): PreparedYoutubeApk {
        val directory = File(context.cacheDir, "${target.id}-setup").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.apk")
        try {
            if (uri == null) {
                require(target == NativeSetupTarget.YOUTUBE) { "Choose a patched Reddit APK from Patcher." }
                progress("Finding the latest MicroG-RE release…")
                val response = HttpsNexusUpdateTransport(URL(YoutubeApkPolicy.MICROG_RELEASE_URL)).fetch(null)
                check(response.statusCode == 200 && response.body != null) { "Could not reach the official MicroG-RE release. Retry later." }
                val release = runCatching { YoutubeApkPolicy.microGRelease(response.body!!) }
                    .getOrElse { error("The MicroG-RE release has no supported APK with a SHA-256 digest.") }
                HttpsArtifactDownloader().download(release.url, file, cancelled) { bytes, _ ->
                    check(bytes <= release.size) { "The MicroG-RE download size was unexpected." }
                    progress("Downloading MicroG-RE: ${bytes * 100 / release.size}%")
                }
                check(file.length() == release.size && PluginInstaller.sha256Matches(file, release.sha256)) {
                    "MicroG-RE failed download verification. Retry the download."
                }
            } else {
                require(uri.scheme == "content") { "Choose the APK using the file picker." }
                progress("Reading your patched ${target.label} APK…")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            check(!cancelled()) { "Preparation cancelled." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= YoutubeApkPolicy.MAX_APK_BYTES) { "The APK is too large." }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("The selected APK could not be read. Choose it again.")
            }
            check(!cancelled()) { "Preparation cancelled." }
            progress("Checking the APK…")
            val archive = AndroidArtifactPackageInspector(context.packageManager).inspect(file)
                ?: error("This file is not a readable, signed APK. Choose a standalone APK, not an APK bundle.")
            if (target == NativeSetupTarget.REDDIT) {
                RedditApkPolicy.validate(archive)
                RedditApkPolicy.requireHud(file)
            }
            else YoutubeApkPolicy.validatePackage(archive, microG = uri == null)
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            require(info?.splitNames.isNullOrEmpty()) {
                "Choose a standalone APK, not a split APK."
            }
            val minSdk = info?.applicationInfo?.minSdkVersion ?: error("The APK manifest could not be read.")
            return PreparedYoutubeApk(file, archive, minSdk, sha256(file))
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    companion object {
        fun sha256(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
