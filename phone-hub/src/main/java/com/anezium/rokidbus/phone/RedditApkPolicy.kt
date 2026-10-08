package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.RedditSetupContract

internal object RedditApkPolicy {
    const val SOURCE_URL = "https://www.apkmirror.com/apk/redditinc/reddit/reddit-2026-14-0-release/"

    fun validate(archive: ArtifactArchiveInfo) {
        require(archive.packageName == RedditSetupContract.REDDIT &&
            archive.versionName == RedditSetupContract.VERSION_NAME &&
            archive.versionCode == RedditSetupContract.VERSION_CODE) {
            "Use Reddit ${RedditSetupContract.VERSION_NAME} from Patcher with Rokid Reddit controls."
        }
        YoutubeApkPolicy.signer(archive)
    }

    fun requireHud(file: java.io.File) {
        java.util.zip.ZipFile(file).use { zip ->
            val entry = zip.getEntry("assets/rokid/reddit.json")
                ?: error("This APK does not include Rokid Reddit controls. Patch it again with Patcher.")
            require(entry.size in 1..2048) { "Invalid Reddit HUD metadata." }
            val bytes = java.io.ByteArrayOutputStream()
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(256)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= 2048) { "Invalid Reddit HUD metadata." }
                    bytes.write(buffer, 0, count)
                }
            }
            val json = org.json.JSONObject(bytes.toString("UTF-8"))
            require(json.opt("schema") == 1 && json.opt("package") == RedditSetupContract.REDDIT &&
                json.opt("version") == RedditSetupContract.VERSION_NAME &&
                json.optLong("versionCode") == RedditSetupContract.VERSION_CODE && json.opt("hud") == "rokid-reddit-1") {
                "The Reddit HUD does not match the supported app version."
            }
        }
    }
}
