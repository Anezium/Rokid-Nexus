package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject

/** Fixed package inventory on the trusted native-apps routes; never contains account data. */
object YoutubeSetupContract {
    const val MICROG = "app.revanced.android.gms"
    const val YOUTUBE = "app.morphe.android.youtube"
    const val YOUTUBE_TEST = "app.morphe.android.youtube.rokidtest"
    val PACKAGES = listOf(MICROG, YOUTUBE, YOUTUBE_TEST)
    private const val REQUEST = "youtube_setup_request"
    private const val RESULT = "youtube_setup_result"

    fun request(id: String): JSONObject = base(REQUEST, id)

    fun requestId(json: JSONObject): String? = validId(json, REQUEST)

    fun result(id: String, sdk: Int, apps: List<YoutubePackage>): JSONObject {
        require(apps.map { it.packageName } == PACKAGES)
        return base(RESULT, id).put("sdk", sdk).put("apps", JSONArray().apply {
            apps.forEach { app ->
                put(JSONObject().put("packageName", app.packageName)
                    .put("versionCode", app.versionCode)
                    .put("signer", app.signer)
                    .put("launchable", app.launchable))
            }
        })
    }

    fun parseResult(json: JSONObject): YoutubeInventory? = runCatching {
        val id = validId(json, RESULT) ?: return null
        val sdk = (json.opt("sdk") as? Int)?.takeIf { it in 23..100 } ?: return null
        val apps = json.getJSONArray("apps")
        if (apps.length() != PACKAGES.size) return null
        val entries = PACKAGES.mapIndexed { index, expected ->
            val item = apps.getJSONObject(index)
            if (item.opt("packageName") != expected) return null
            val version = when (val value = item.opt("versionCode")) {
                is Int -> value.toLong()
                is Long -> value
                else -> return null
            }
            if (version !in 0..9_007_199_254_740_991L) return null
            val signer = item.opt("signer") as? String ?: return null
            if (signer.isNotEmpty() && !signer.matches(Regex("[a-f0-9]{64}"))) return null
            val launchable = item.opt("launchable") as? Boolean ?: return null
            if (version == 0L && (signer.isNotEmpty() || launchable)) return null
            YoutubePackage(expected, version, signer, launchable)
        }
        YoutubeInventory(id, sdk, entries)
    }.getOrNull()

    private fun validId(json: JSONObject, type: String): String? {
        if (json.toString().toByteArray(Charsets.UTF_8).size > 2_000 ||
            json.opt("version") != 1 || json.opt("type") != type) return null
        return (json.opt("requestId") as? String)?.takeIf(NativeAppContract::isValidRequestId)
    }

    private fun base(type: String, id: String): JSONObject {
        require(NativeAppContract.isValidRequestId(id))
        return JSONObject().put("version", 1).put("type", type).put("requestId", id)
    }
}

data class YoutubePackage(
    val packageName: String,
    val versionCode: Long = 0,
    val signer: String = "",
    val launchable: Boolean = false,
) {
    val installed: Boolean get() = versionCode > 0
}

data class YoutubeInventory(val requestId: String, val sdk: Int, val apps: List<YoutubePackage>)
