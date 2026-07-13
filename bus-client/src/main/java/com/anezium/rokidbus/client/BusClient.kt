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
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "RokidBusClient"
private const val DEFAULT_TIMEOUT_MS = 15_000L

sealed class BusEvent {
    data class Message(val path: String, val id: String, val payload: JSONObject) : BusEvent()
    data class Binary(val path: String, val id: String, val meta: JSONObject, val data: ByteArray) : BusEvent()
    data class LinkState(val state: Int) : BusEvent()
    data class Error(val message: String, val cause: Throwable? = null) : BusEvent()
}

class BusClient(
    context: Context,
    private val clientId: String,
    pathPrefixes: List<String>,
    private val hubTarget: HubTarget = HubTarget.PHONE,
    private val pluginId: String? = null,
    private val pluginRegistrationListener: (Int) -> Unit = {},
    private val listener: (BusEvent) -> Unit,
) {
    private data class Pending(
        val requestPath: String,
        val onSuccess: (JSONObject) -> Unit,
        val onFailure: (Throwable) -> Unit,
    )

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val receivePrefixes = (pathPrefixes + BusPaths.ERROR).distinct().toTypedArray()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val queued = BoundedOutgoingQueue()
    private var service: IBusService? = null
    private var bound = false
    private var closed = false
    private var reconnectPosted = false
    private var pluginRegistrationState: Int? = null
    @Volatile private var hubCapabilities = 0

    private val callback = object : IBusCallback.Stub() {
        override fun onMessage(path: String, id: String, payload: ByteArray) {
            val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }
                .getOrElse { JSONObject().put("raw", String(payload, Charsets.UTF_8)) }
            main.post { handleMessage(path, id, json) }
        }

        override fun onLinkState(state: Int) {
            main.post { listener(BusEvent.LinkState(state)) }
        }

        override fun onBinaryMessage(path: String, id: String, meta: ByteArray, data: ByteArray) {
            val json = runCatching { JSONObject(String(meta, Charsets.UTF_8)) }
                .getOrElse { JSONObject().put("raw", String(meta, Charsets.UTF_8)) }
            main.post { handleBinaryMessage(path, id, json, data) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IBusService.Stub.asInterface(binder)
            reconnectPosted = false
            runCatching {
                val registrationResult = if (pluginId == null) {
                    service?.register(clientId, receivePrefixes, callback)
                    null
                } else {
                    service?.registerPlugin(appContext.packageName, pluginId, callback)
                }
                if (registrationResult != null) {
                    pluginRegistrationState = registrationResult
                    pluginRegistrationListener(registrationResult)
                }
                hubCapabilities = runCatching { service?.capabilities() ?: 0 }.getOrDefault(0)
                service?.linkState()?.let { listener(BusEvent.LinkState(it)) }
                if (pluginId == null || registrationResult == PluginRegistrationResult.APPROVED) {
                    flushQueued()
                } else {
                    queued.clear()
                }
            }.onFailure {
                pluginRegistrationState = PluginRegistrationResult.REGISTRATION_FAILED
                if (pluginId != null) {
                    pluginRegistrationListener(PluginRegistrationResult.REGISTRATION_FAILED)
                }
                listener(BusEvent.Error("Hub registration failed", it))
                scheduleReconnect()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            pluginRegistrationState = null
            hubCapabilities = 0
            if (!closed) scheduleReconnect()
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            pluginRegistrationState = null
            hubCapabilities = 0
            if (!closed) scheduleReconnect()
        }

        override fun onNullBinding(name: ComponentName) {
            service = null
            pluginRegistrationState = PluginRegistrationResult.REGISTRATION_FAILED
            hubCapabilities = 0
            if (pluginId != null) {
                pluginRegistrationListener(PluginRegistrationResult.REGISTRATION_FAILED)
            }
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
        if (pluginId != null && pluginRegistrationState != PluginRegistrationResult.APPROVED) {
            listener(BusEvent.Error("Plugin registration is not approved"))
            return id
        }
        val hub = service
        if (hub == null) {
            reportQueueMutation(
                queued.offerJson(path, id, bytes),
                operation = "json queue",
            )
            connect()
            return id
        }
        runCatching {
            hub.send(path, id, bytes)
        }.onFailure {
            val mutation = queued.offerJson(path, id, bytes)
            service = null
            listener(
                BusEvent.Error(
                    mutation.errorMessage("json send failed; reconnect queue")
                        ?: "json send failed; queued for reconnect",
                ),
            )
            scheduleReconnect()
        }
        return id
    }

    fun sendBinary(path: String, meta: JSONObject, data: ByteArray): String =
        sendBinary(path, UUID.randomUUID().toString(), meta, data)

    fun sendBinary(path: String, id: String, meta: JSONObject, data: ByteArray): String {
        trySendBinary(path, id, meta, data)
        return id
    }

    fun trySendBinary(path: String, id: String, meta: JSONObject, data: ByteArray): Boolean {
        val bytes = meta.toString().toByteArray(Charsets.UTF_8)
        if (pluginId != null && pluginRegistrationState != PluginRegistrationResult.APPROVED) {
            listener(BusEvent.Error("Plugin registration is not approved"))
            return false
        }
        val hub = service
        if (hub == null) {
            reportQueueMutation(
                queued.rejectBinary(),
                operation = "binary send offline; payload not retained",
            )
            connect()
            return false
        }
        return runCatching {
            hub.sendBinary(path, id, bytes, data)
            true
        }.onFailure {
            service = null
            reportQueueMutation(
                queued.rejectBinary(),
                operation = "binary send failed; payload not retained",
            )
            scheduleReconnect()
        }.getOrDefault(false)
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

    /** Reads the live Binder value; feature availability may change without a rebind. */
    fun capabilities(): Int =
        runCatching { service?.capabilities() ?: 0 }
            .getOrDefault(0)
            .also { hubCapabilities = it }

    fun supportsImageSurface(): Boolean =
        capabilities() and BusCapabilityBits.IMAGE_SURFACE != 0 &&
            linkState() and com.anezium.rokidbus.shared.LinkStateBits.SPP_DATA_UP != 0

    fun close() {
        closed = true
        pending.values.forEach { it.onFailure(IllegalStateException("BusClient closed")) }
        pending.clear()
        runCatching { service?.unregister(callback) }
        if (bound) runCatching { appContext.unbindService(connection) }
        bound = false
        service = null
        pluginRegistrationState = null
        hubCapabilities = 0
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

    private fun handleBinaryMessage(path: String, id: String, meta: JSONObject, data: ByteArray) {
        val waiting = pending.remove(id)
        if (waiting != null) {
            if (path == BusPaths.ERROR) {
                waiting.onFailure(IllegalStateException(meta.toString()))
            } else {
                waiting.onSuccess(meta)
            }
            return
        }
        listener(BusEvent.Binary(path, id, meta, data))
    }

    private fun flushQueued() {
        val hub = service ?: return
        while (true) {
            val poll = queued.poll()
            if (poll.expiredCount > 0) {
                reportQueueMutation(
                    QueueMutation(accepted = true, expiredCount = poll.expiredCount),
                    operation = "json reconnect queue",
                )
            }
            val next = poll.item ?: return
            try {
                hub.send(next.path, next.id, next.payload)
            } catch (_: RemoteException) {
                val mutation = queued.requeueFirst(next)
                service = null
                listener(
                    BusEvent.Error(
                        mutation.errorMessage("json resend failed; reconnect queue")
                            ?: "json resend failed; queued for reconnect",
                    ),
                )
                scheduleReconnect()
                return
            }
        }
    }

    private fun reportQueueMutation(mutation: QueueMutation, operation: String) {
        mutation.errorMessage(operation)?.let { message ->
            listener(BusEvent.Error(message))
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
        val query = Intent(hubTarget.action).setPackage(hubTarget.packageName)
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.queryIntentServices(
                query,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.queryIntentServices(query, 0)
        }
        val records = matches.mapNotNull { match ->
            val service = match.serviceInfo ?: return@mapNotNull null
            HubServiceRecord(service.packageName, service.name, setOf(hubTarget.action))
        }
        val selected = HubServiceResolver.select(hubTarget, records) ?: return null
        Log.d(TAG, "resolved explicit hub package=${selected.packageName}")
        return Intent(hubTarget.action).setComponent(
            ComponentName(selected.packageName, selected.serviceClassName),
        )
    }
}
