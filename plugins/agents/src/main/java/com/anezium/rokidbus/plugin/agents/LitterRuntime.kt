package com.anezium.rokidbus.plugin.agents

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Process-local owners come only from visible activities or an open Nexus session. */
internal object LitterRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    val client = LitterClient(http, scope, AgentsRuntime.store)
    private val owners = mutableSetOf<Any>()

    fun acquire(context: Context, owner: Any) {
        if (!owners.add(owner) || owners.size != 1) return
        reload(context)
    }

    fun release(owner: Any) {
        owners.remove(owner)
        if (owners.isEmpty()) client.stop()
    }

    fun reload(context: Context) {
        client.stop()
        client.closeConversation()
        AgentsRuntime.store.replaceProvider(AgentProvider.CODEX, emptyList())
        if (owners.isEmpty()) return
        val config = runCatching { LitterEndpointStore(context.applicationContext).load() }.getOrNull()
        if (config != null) client.start(config)
    }

    fun run(action: suspend LitterClient.() -> Unit) {
        scope.launch { client.runOperation { client.action() } }
    }
}
