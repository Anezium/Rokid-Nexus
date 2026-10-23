package com.anezium.rokidbus.phone

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Reads Hi Rokid's current-scenes report into the native glasses scenes that take the lens away
 * from a third-party session: a call, a recording, navigation, a payment, an update.
 *
 * The scene names are client-l 1.1.2's own (`SCENES_TAKEOVER`). client-l matches them as
 * substrings anywhere in the report; the format is undocumented and no device capture exists yet,
 * so this reads only positive evidence instead: a JSON array of names, or a top-level object entry
 * set to `true`, `1` or `"true"`. Any other shape reads as nothing running, which keeps the
 * assist button with Nexus, as before scene reports were used at all.
 */
internal object NativeSceneTakeover {
    val SCENES: List<String> = listOf(
        "audioRecordRunning",
        "brightnessRunning",
        "navigationRunning",
        "notesRunning",
        "phoneCallRunning",
        "translateRunning",
        "videoRecordRunning",
        "wordTipsRunning",
        "paymentRunning",
        "cityGuideRunning",
        "otaRunning",
        "liveBroadcastRunning",
        "accessibilityRunning",
        "cameraPageRunning",
    )

    private const val SHAPE_MAX_KEYS = 16
    private val SHAPE_KEY = Regex("[A-Za-z_][A-Za-z0-9_]{0,39}")

    fun running(scenesJson: String?): Set<String> =
        when (val report = parse(scenesJson)) {
            is JSONArray -> {
                val names = (0 until report.length()).mapTo(HashSet()) { report.opt(it) }
                SCENES.filterTo(LinkedHashSet()) { it in names }
            }
            is JSONObject -> SCENES.filterTo(LinkedHashSet()) { isSet(report.opt(it)) }
            else -> emptySet()
        }

    /**
     * The report's structure without its values, safe for release logs: `array[n]`,
     * `object{key,…}` limited to identifier-like keys, `other` or `empty`.
     */
    fun shape(scenesJson: String?): String =
        when (val report = parse(scenesJson)) {
            is JSONArray -> "array[${report.length()}]"
            is JSONObject -> {
                val keys = report.keys().asSequence().toList()
                val shown = keys.filter { SHAPE_KEY.matches(it) }.sorted().take(SHAPE_MAX_KEYS)
                val more = if (keys.size > shown.size) "+${keys.size - shown.size}" else null
                "object{${(shown + listOfNotNull(more)).joinToString(",")}}"
            }
            null -> "empty"
            else -> "other"
        }

    /**
     * Whether the assist button should be left to Rokid: the takeover's exit burst is aimed at the
     * assistant popup and could land on the native scene instead. `accessibilityRunning` is
     * ignored because the glasses hub is itself an accessibility service, so it may describe Nexus.
     */
    fun blocksAssistantTakeover(running: Set<String>): Boolean =
        running.any { it != ACCESSIBILITY_SCENE }

    private const val ACCESSIBILITY_SCENE = "accessibilityRunning"

    private fun parse(scenesJson: String?): Any? {
        val text = scenesJson?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { JSONTokener(text).nextValue() }.getOrDefault(Unit)
    }

    private fun isSet(value: Any?): Boolean = value == true || value == 1 || value == "true"
}
