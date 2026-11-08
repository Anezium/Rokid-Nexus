package com.anezium.rokidbus.plugin.meta

import com.anezium.rokidbus.plugin.meta.nativebridge.instagram.Instagram
import com.anezium.rokidbus.plugin.meta.nativebridge.messenger.Messenger
import java.util.concurrent.Executors

/** The login/connection/self-send surface shared by the Meta account engines. */
internal interface AccountEngine {
    fun status(): String
    fun startLogin(username: String, password: String)
    fun submitCode(code: String)
    fun abortLogin()
    fun connect(timeoutMs: Long): String
    fun disconnect()
    fun sendSelfText(id: String, text: String): String
    fun sendSelfImage(id: String, data: ByteArray): String
    fun chats(): String
    fun setHistoryLimit(n: Long)
    fun loadOlder(chatId: String)
    fun sendText(id: String, chatId: String, title: String, text: String): String
    fun sendImage(id: String, chatId: String, title: String, data: ByteArray): String
    fun forget()
    fun close()
}

private class InstagramEngine(private val native: com.anezium.rokidbus.plugin.meta.nativebridge.instagram.Engine) : AccountEngine {
    override fun status(): String = native.status()
    override fun startLogin(username: String, password: String) = native.startLogin(username, password)
    override fun submitCode(code: String) = native.submitCode(code)
    override fun abortLogin() = native.abortLogin()
    override fun connect(timeoutMs: Long): String = native.connect(timeoutMs)
    override fun disconnect() = native.disconnect()
    override fun sendSelfText(id: String, text: String): String = native.sendSelfText(id, text)
    override fun sendSelfImage(id: String, data: ByteArray): String = native.sendSelfImage(id, data)
    override fun chats(): String = native.chats()
    override fun setHistoryLimit(n: Long) = native.setHistoryLimit(n)
    override fun loadOlder(chatId: String) = native.loadOlder(chatId)
    override fun sendText(id: String, chatId: String, title: String, text: String): String = native.sendText(id, chatId, title, text)
    override fun sendImage(id: String, chatId: String, title: String, data: ByteArray): String = native.sendImage(id, chatId, title, data)
    override fun forget() = native.forget()
    override fun close() = native.close()
}

private class MessengerEngine(private val native: com.anezium.rokidbus.plugin.meta.nativebridge.messenger.Engine) : AccountEngine {
    override fun status(): String = native.status()
    override fun startLogin(username: String, password: String) = native.startLogin(username, password)
    override fun submitCode(code: String) = native.submitCode(code)
    override fun abortLogin() = native.abortLogin()
    override fun connect(timeoutMs: Long): String = native.connect(timeoutMs)
    override fun disconnect() = native.disconnect()
    override fun sendSelfText(id: String, text: String): String = native.sendSelfText(id, text)
    override fun sendSelfImage(id: String, data: ByteArray): String = native.sendSelfImage(id, data)
    override fun chats(): String = native.chats()
    override fun setHistoryLimit(n: Long) = native.setHistoryLimit(n)
    override fun loadOlder(chatId: String) = native.loadOlder(chatId)
    override fun sendText(id: String, chatId: String, title: String, text: String): String = native.sendText(id, chatId, title, text)
    override fun sendImage(id: String, chatId: String, title: String, data: ByteArray): String = native.sendImage(id, chatId, title, data)
    override fun forget() = native.forget()
    override fun close() = native.close()
}

/**
 * A Meta service signed in with a username and password. [open] is null while the
 * engine for that service is not built into this plugin yet.
 */
internal enum class MetaAccount(
    val id: String,
    val title: String,
    val directory: String,
    val identifierHint: String,
    val selfText: String,
    val open: ((String) -> AccountEngine)?,
) {
    INSTAGRAM("instagram", "Instagram", "instameow", "Instagram username",
        "Rokid Nexus is connected to Instagram.", { InstagramEngine(Instagram.open(it)) }),
    MESSENGER("messenger", "Messenger", "messenger", "Email or phone number",
        "Rokid Nexus is connected to Messenger.", { MessengerEngine(Messenger.open(it)) });

    /** Only Instagram offers the send-yourself connection check on its page. */
    val offersCheck: Boolean get() = this == INSTAGRAM

    /** The file whose presence means a saved session, readable without opening the engine. */
    val sessionFile: String get() = if (this == INSTAGRAM) "native-session.json" else "session.json"

    /** Instagram names an account by its handle; Messenger by the profile name. */
    fun handle(name: String): String = if (this == INSTAGRAM) "@$name" else name

    val host: AccountEngineHost by lazy { AccountEngineHost() }

    companion object {
        fun byId(id: String?): MetaAccount? = entries.firstOrNull { it.id == id }
    }
}

/** Native work runs on one worker so a closing screen always releases the engine before the next opens. */
internal class AccountEngineHost {
    val worker = Executors.newSingleThreadExecutor()
    val probe = Executors.newSingleThreadExecutor()
    @Volatile var engine: AccountEngine? = null
    @Volatile var visible = false
    /** The glasses conversation flow holds the engine while it is open, like a visible page. */
    @Volatile var glasses = false
    @Volatile var loginInterrupted = false
    @Volatile var graceOutcome = ""

    /** Closes the engine unless a page or the glasses still use it; closing also cancels any login. */
    fun releaseIfHidden() = worker.execute {
        if (!visible && !glasses) {
            engine?.close()
            engine = null
        }
    }
}
