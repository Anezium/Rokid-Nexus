package com.anezium.rokidbus.plugin.meta

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.plugin.meta.nativebridge.whatsapp.Engine
import com.anezium.rokidbus.plugin.meta.nativebridge.whatsapp.Whatsapp
import java.io.File
import java.util.concurrent.Executors

internal interface WhatsAppEngine : OwnedWhatsAppEngine {
    fun status(): String
    fun awaitLogin(timeoutMs: Long): String
    fun contacts(): String
    fun chats(perChat: Long): String
    /** The stored thumbnail a message preview names; never logged. */
    fun thumbnail(preview: String): ByteArray
    fun requestHistory(chat: String, count: Long)
    fun startLink(method: String, phone: String): String
    fun link(session: String): String
    fun abortLink()
    fun logout()
    fun send(id: String, recipient: String, name: String, text: String): String
    fun sendImage(id: String, recipient: String, name: String, data: ByteArray): String
    fun lookup(id: String): String
}

private class EmbeddedWhatsAppEngine(private val native: Engine) : WhatsAppEngine {
    override fun status() = native.status()
    override fun awaitLogin(timeoutMs: Long) = native.awaitLogin(timeoutMs)
    override fun contacts() = native.contacts()
    override fun chats(perChat: Long) = native.chats(perChat)
    override fun thumbnail(preview: String): ByteArray = native.thumbnail(preview)
    override fun requestHistory(chat: String, count: Long) = native.requestHistory(chat, count)
    override fun startLink(method: String, phone: String) = native.startLink(method, phone)
    override fun link(session: String) = native.link(session)
    override fun abortLink() = native.abortLink()
    override fun logout() = native.logout()
    override fun send(id: String, recipient: String, name: String, text: String) = native.send(id, recipient, name, text)
    override fun sendImage(id: String, recipient: String, name: String, data: ByteArray) = native.sendImage(id, recipient, name, data)
    override fun lookup(id: String) = native.lookup(id)
    override fun cancel() = native.cancel()
    override fun close() = native.close()
}

/** Native disk/network work stays on the worker; owner cancellation can interrupt a login wait. */
internal object WhatsMeowSession {
    private val main = Handler(Looper.getMainLooper())
    private val session = OwnedWhatsAppSession<WhatsAppEngine>(
        factory = { EmbeddedWhatsAppEngine(Whatsapp.open(it)) },
        worker = Executors.newSingleThreadExecutor(),
        cancellation = Executors.newSingleThreadExecutor(),
        post = { main.post(it) },
        now = SystemClock::elapsedRealtime,
    )

    fun acquire(owner: Any) = session.acquire(owner)
    fun release(owner: Any) = session.release(owner)
    fun releaseSettings(owner: Any) = session.release(owner) { it.abortLink() }

    fun <T> call(context: Context, owner: Any, work: (WhatsAppEngine) -> T, done: (Result<T>) -> Unit) {
        session.call(File(context.applicationContext.noBackupFilesDir, "whatsmeow").absolutePath, owner, work, done)
    }
}
