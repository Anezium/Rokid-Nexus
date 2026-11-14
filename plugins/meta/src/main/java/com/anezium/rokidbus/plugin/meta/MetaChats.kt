package com.anezium.rokidbus.plugin.meta

import org.json.JSONArray

/** The engines' conversation lists as [MetaChat]s, read the same way by the glasses pages and the pin picker. */
internal object MetaChats {
    fun parse(rows: JSONArray, idKey: String, titleKey: String, groupKey: String): List<MetaChat> =
        (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val messages = row.optJSONArray("messages") ?: JSONArray()
            MetaChat(row.getString(idKey), row.getString(titleKey), row.optBoolean(groupKey), row.optBoolean("supported", true),
                (0 until messages.length()).map { j ->
                    val message = messages.getJSONObject(j)
                    HistoryMessage(message.getString("id"), message.optBoolean("mine"), message.optString("text"),
                        message.optString("sender").takeIf { it.isNotEmpty() },
                        preview = message.optString("preview").takeIf(MetaThumbnails::fetchable))
                })
        }

    /** Stored conversations with their messages; the synced contacts until any message is stored. */
    fun whatsApp(engine: WhatsAppEngine, perChat: Long): List<MetaChat> {
        val chats = parse(JSONArray(engine.chats(perChat)), "jid", "name", "isGroup")
        if (chats.isNotEmpty()) return chats
        val rows = JSONArray(engine.contacts())
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            MetaChat(row.getString("jid"), row.getString("name"), group = row.optBoolean("isGroup"), supported = true)
        }
    }

    fun account(engine: AccountEngine): List<MetaChat> = parse(JSONArray(engine.chats()), "id", "title", "group")
}
