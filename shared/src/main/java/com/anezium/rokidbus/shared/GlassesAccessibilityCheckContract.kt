package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lets the phone ask the glasses which accessibility services besides Nexus's own are currently
 * enabled. A foreign service sitting in front of Nexus's key/gesture interception has repeatedly
 * turned out to be the real cause behind "input stopped working" reports on this project (e.g. a
 * leftover legacy app's accessibility service swallowing all keyboard input) — this lets the
 * owner catch that from the phone instead of hunting it down over adb again.
 */
object GlassesAccessibilityCheckContract {
    const val VERSION = 1

    fun requestToJson(): JSONObject = JSONObject().put("version", VERSION)

    fun replyToJson(foreignServices: List<String>): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("foreignServices", JSONArray(foreignServices))

    /** Null for a reply this build cannot parse, so version skew reads as "no answer". */
    fun foreignServicesFromReply(payload: JSONObject?): List<String>? {
        val json = payload ?: return null
        if (json.optInt("version", 0) < 1) return null
        val array = json.optJSONArray("foreignServices") ?: return null
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf(String::isNotBlank)
        }
    }
}
