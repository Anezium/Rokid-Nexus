package com.anezium.rokidbus.plugin.line

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anezium.rokidbus.plugin.line.nativebridge.mobile.Mobile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit on-device acceptance check; never sends messages or emits chat content. */
@RunWith(AndroidJUnit4::class)
class LineAccountReadOnlyTest {
    @Test fun recentConversationCanBeRead() {
        val expected = InstrumentationRegistry.getArguments().getString("conversationLabel").orEmpty()
        assertTrue("Pass -e conversationLabel with the owner's chosen conversation", expected.isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = Mobile.open(File(context.noBackupFilesDir, "line-session").absolutePath)
        try {
            engine.connect()
            val snapshot = JSONObject(engine.snapshot())
            assertEquals("Saved LINE session did not connect", "ready", snapshot.getString("state"))
            val chats = snapshot.getJSONArray("chats")
            val matches = (0 until chats.length()).map { chats.getJSONObject(it) }
                .filter { it.getString("title").equals(expected, ignoreCase = true) }
            val unnamed = (0 until chats.length()).count { chats.getJSONObject(it).getString("title") == "Conversation" }
            assertEquals("Chosen conversation is missing or ambiguous; listed=${chats.length()}, unnamed=$unnamed, " +
                "inbox=${snapshot.optInt("inboxEntries", -1)}, contacts=${snapshot.optInt("contactEntries", -1)}, " +
                "excluded=${snapshot.optInt("excludedEntries", -1)}", 1, matches.size)
            val id = matches.single().getString("id")
            engine.loadHistory(id)
            val loaded = JSONObject(engine.snapshot()).getJSONArray("chats")
            val history = (0 until loaded.length()).map { loaded.getJSONObject(it) }
                .single { it.getString("id") == id }.getJSONArray("history")
            val rows = (0 until history.length()).map { history.getJSONObject(it) }
            assertTrue("No messages in the chosen conversation yet", rows.isNotEmpty())
            assertTrue("At least one message failed decryption", rows.none {
                it.getString("text") == "[Unable to decrypt message]"
            })
        } finally {
            engine.cancel()
            engine.close()
        }
    }
}
