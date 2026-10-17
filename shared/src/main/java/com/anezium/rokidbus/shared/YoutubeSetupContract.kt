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

    private val codec = NativeSetupInventoryCodec(PACKAGES, REQUEST, RESULT)
    fun request(id: String) = codec.request(id)
    fun requestId(json: JSONObject) = codec.requestId(json)
    fun result(id: String, sdk: Int, apps: List<YoutubePackage>) = codec.result(id, sdk, apps)
    fun parseResult(json: JSONObject) = codec.parseResult(json)

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
