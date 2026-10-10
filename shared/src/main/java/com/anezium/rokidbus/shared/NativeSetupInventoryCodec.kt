package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject

/** Only fixed hub-owned inventories instantiate this codec; packages are never wire input. */
internal class NativeSetupInventoryCodec(
    private val packages: List<String>,
    private val requestType: String,
    private val resultType: String,
) {
    fun request(id: String): JSONObject = base(requestType, id)

    fun requestId(json: JSONObject): String? = validId(json, requestType)

    fun result(id: String, sdk: Int, apps: List<YoutubePackage>): JSONObject {
        require(apps.map { it.packageName } == packages)
        return base(resultType, id).put("sdk", sdk).put("apps", JSONArray().apply {
            apps.forEach { app ->
                put(JSONObject().put("packageName", app.packageName)
                    .put("versionCode", app.versionCode)
                    .put("signer", app.signer)
                    .put("launchable", app.launchable))
            }
        })
    }

    fun parseResult(json: JSONObject): YoutubeInventory? = runCatching {
        val id = validId(json, resultType) ?: return null
        val sdk = (json.opt("sdk") as? Int)?.takeIf { it in 23..100 } ?: return null
        val apps = json.getJSONArray("apps")
        if (apps.length() != packages.size) return null
        val entries = packages.mapIndexed { index, expected ->
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
