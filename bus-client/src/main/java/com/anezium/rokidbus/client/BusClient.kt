package com.anezium.rokidbus.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "RokidBusClient"
private const val DEFAULT_TIMEOUT_MS = 15_000L

sealed class BusEvent {
    data class Message(val path: String, val id: String, val payload: JSONObject) : BusEvent()
    data class LinkState(val state: Int) : BusEvent()
    data class Error(val message: String, val cause: Throwable? = null) : BusEvent()
}

class BusClient(
    context: Context,
    private val clientId: String,
    pathPrefixes: List<String>,
    private val listener: (BusEvent) -> Unit,
) {
    private data class Pending(
        val requestPath: String,
        val onSuccess: (JSONObject) -> Unit,
        val onFailure: (Throwable) -> Unit,
    )

    private data class Outgoing(val path: String, val id: String, val payload: ByteArray)

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val receivePrefixes = (pathPrefixes + BusPaths.ERROR).distinct().toTypedArray()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val queued = ConcurrentLinkedQueue<Outgoing>()
    private var service: IBusService? = null
    private var bound = false
    private var closed = false
    private var reconnectPosted = false

    private val callback = object : IBusCallback.Stub() {
        override fun onMessage(path: String, id: String, payload: ByteArray) {
            val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }
                .getOrElse { JSONObject().put("raw", String(payload, Charsets.UTF_8)) }
            main.post { handleMessage(path, id, json) }
        }

        override fun onLinkState(state: Int) {
            main.post { listener(BusEvent.LinkState(state)) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IBusService.Stub.asInterface(binder)
            reconnectPosted = false
            runCatching {
                service?.register(clientId, receivePrefixes, callback)
                service?.linkState()?.let { listener(BusEvent.LinkState(it)) }
                flushQueued()
            }.onFailure {
                listener(BusEvent.Error("Hub registration failed", it))
                scheduleReconnect()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            if (!closed) scheduleReconnect()
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            if (!closed) scheduleReconnect()
        }

        override fun onNullBinding(name: ComponentName) {
            service = null
            listener(BusEvent.Error("Hub returned a null binding"))
            if (!closed) scheduleReconnect()
        }
    }

    fun connect() {
        if (closed) return
        val intent = resolveHubIntent()
        if (intent == null) {
            listener(BusEvent.Error("No RokidBus hub service visible"))
            scheduleReconnect()
            return
        }
        if (bound) return
        bound = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            listener(BusEvent.Error("bindService failed", it))
            false
        }
        if (!bound) scheduleReconnect()
    }

    fun send(path: String, payload: JSONObject): String =
        send(path, UUID.randomUUID().toString(), payload)

    fun send(path: String, id: String, payload: JSONObject): String {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val hub = service
        if (hub == null) {
            queued += Outgoing(path, id, bytes)
            connect()
            return id
        }
        runCatching {
            hub.send(path, id, bytes)
        }.onFailure {
            queued += Outgoing(path, id, bytes)
            service = null
            listener(BusEvent.Error("send failed; queued for reconnect", it))
            scheduleReconnect()
        }
        return id
    }

    suspend fun request(
        path: String,
        payload: JSONObject,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): JSONObject {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = Pending(
            requestPath = path,
            onSuccess = { deferred.complete(it) },
            onFailure = { deferred.completeExceptionally(it) },
        )
        send(path, id, payload)
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun request(
        path: String,
        payload: JSONObject,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        callback: (Result<JSONObject>) -> Unit,
    ): String {
        val id = UUID.randomUUID().toString()
        val timeout = Runnable {
            pending.remove(id)?.onFailure?.invoke(
                java.util.concurrent.TimeoutException("RokidBus request timed out: $path"),
            )
        }
        pending[id] = Pending(
            requestPath = path,
            onSuccess = {
                main.removeCallbacks(timeout)
                callback(Result.success(it))
            },
            onFailure = {
                main.removeCallbacks(timeout)
                callback(Result.failure(it))
            },
        )
        main.postDelayed(timeout, timeoutMs)
        send(path, id, payload)
        return id
    }

    fun linkState(): Int =
        runCatching { service?.linkState() ?: 0 }.getOrDefault(0)

    fun close() {
        closed = true
        pending.values.forEach { it.onFailure(IllegalStateException("BusClient closed")) }
        pending.clear()
        runCatching { service?.unregister(callback) }
        if (bound) runCatching { appContext.unbindService(connection) }
        bound = false
        service = null
        queued.clear()
    }

    private fun handleMessage(path: String, id: String, payload: JSONObject) {
        val waiting = pending.remove(id)
        if (waiting != null) {
            if (path == BusPaths.ERROR) {
                waiting.onFailure(IllegalStateException(payload.toString()))
            } else {
                waiting.onSuccess(payload)
            }
            return
        }
        listener(BusEvent.Message(path, id, payload))
    }

    private fun flushQueued() {
        val hub = service ?: return
        while (true) {
            val next = queued.poll() ?: return
            try {
                hub.send(next.path, next.id, next.payload)
            } catch (e: RemoteException) {
                queued += next
                service = null
                scheduleReconnect()
                return
            }
        }
    }

    private fun scheduleReconnect() {
        if (closed || reconnectPosted) return
        reconnectPosted = true
        main.postDelayed({
            reconnectPosted = false
            if (!closed) {
                if (bound) runCatching { appContext.unbindService(connection) }
                bound = false
                connect()
            }
        }, 1_000L)
    }

    private fun resolveHubIntent(): Intent? {
        val query = Intent(BusConstants.ACTION_HUB)
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.queryIntentServices(
                query,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.queryIntentServices(query, 0)
        }
        val serviceInfo = matches.firstOrNull()?.serviceInfo ?: return null
        Log.d(TAG, "resolved hub ${serviceInfo.packageName}/${serviceInfo.name}")
        return Intent(BusConstants.ACTION_HUB).setComponent(
            ComponentName(serviceInfo.packageName, serviceInfo.name),
        )
    }
}
