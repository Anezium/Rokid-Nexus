package com.anezium.rokidbus.shared

import org.json.JSONObject

/** Fixed Reddit inventory on the existing trusted hub-to-hub native-apps routes. */
object RedditSetupContract {
    const val REDDIT = "com.reddit.frontpage"
    const val VERSION_NAME = "2026.14.0"
    const val VERSION_CODE = 2614001L
    val PACKAGES = listOf(REDDIT)
    private val codec = NativeSetupInventoryCodec(PACKAGES, "reddit_setup_request", "reddit_setup_result")
    fun request(id: String) = codec.request(id)
    fun requestId(json: JSONObject) = codec.requestId(json)
    fun result(id: String, sdk: Int, apps: List<YoutubePackage>) = codec.result(id, sdk, apps)
    fun parseResult(json: JSONObject) = codec.parseResult(json)
}
