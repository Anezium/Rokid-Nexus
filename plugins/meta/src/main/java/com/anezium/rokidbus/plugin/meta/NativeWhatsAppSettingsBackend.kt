package com.anezium.rokidbus.plugin.meta

import android.content.Context
import org.json.JSONObject

internal class NativeWhatsAppSettingsBackend(private val context: Context) : WhatsAppSettingsBackend {
    private val owner = Any()
    override fun acquire() = WhatsMeowSession.acquire(owner)
    override fun release() = WhatsMeowSession.releaseSettings(owner)
    override fun query(timeoutMs: Long, done: (Result<WhatsAppConnection>) -> Unit) =
        WhatsMeowSession.call(context, owner, { engine ->
            val state = JSONObject(engine.awaitLogin(timeoutMs))
            WhatsAppConnection(state.getString("link"), state.getBoolean("connected"),
                state.getBoolean("loggedIn"), state.optString("blocker"))
        }, done)

    private fun pairing(raw: String): WhatsAppPairing {
        val state = JSONObject(raw)
        return WhatsAppPairing(state.getString("session"), state.getString("status"), state.optString("code"))
            .also { check(it.session.isNotEmpty()) }
    }

    override fun startLink(number: String, done: (Result<WhatsAppPairing>) -> Unit) =
        WhatsMeowSession.call(context, owner, { pairing(it.startLink("code", number)) }, done)
    override fun link(session: String, done: (Result<WhatsAppPairing>) -> Unit) =
        WhatsMeowSession.call(context, owner, { pairing(it.link(session)) }, done)
    override fun logout(done: (Result<Unit>) -> Unit) =
        WhatsMeowSession.call(context, owner, { it.logout() }, done)
}
