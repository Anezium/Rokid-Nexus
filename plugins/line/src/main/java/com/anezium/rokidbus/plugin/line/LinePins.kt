package com.anezium.rokidbus.plugin.line

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** A conversation the owner pinned: LINE's id, and the name the phone shows while LINE is not connected. */
internal data class LinePin(val id: String, val title: String, val group: Boolean)

/**
 * The pinned conversations, in the owner's order, kept on this phone only. The glasses list
 * shows them first; nothing here leaves the phone. Forgetting the connection drops them.
 */
internal object LinePins {
    private const val KEY = "pins"

    private fun prefs(context: Context) = context.getSharedPreferences("line-pins", Context.MODE_PRIVATE)

    fun get(context: Context): List<LinePin> = runCatching {
        val rows = JSONArray(prefs(context).getString(KEY, null) ?: return emptyList())
        (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            LinePin(row.getString("id"), row.optString("title"), row.optBoolean("group"))
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    fun ids(context: Context): List<String> = get(context).map { it.id }

    fun set(context: Context, pins: List<LinePin>) {
        val rows = JSONArray()
        pins.distinctBy { it.id }.forEach { rows.put(JSONObject().put("id", it.id).put("title", it.title).put("group", it.group)) }
        prefs(context).edit().putString(KEY, rows.toString()).apply()
    }

    /** Takes the names LINE lists now, so a renamed contact reads right on the phone while offline. */
    fun rename(context: Context, chats: List<LineChat>) {
        val titles = chats.associate { it.id to it.title }
        val pins = get(context)
        val renamed = pins.map { pin -> titles[pin.id]?.takeIf { it != pin.title }?.let { pin.copy(title = it) } ?: pin }
        if (renamed != pins) set(context, renamed)
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Calls [changed] on the main thread whenever the pins change; the returned function stops it. */
    fun observe(context: Context, changed: () -> Unit): () -> Unit {
        val preferences = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changed() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
