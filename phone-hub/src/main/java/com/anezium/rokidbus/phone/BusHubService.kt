package com.anezium.rokidbus.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.util.Base64
import android.util.Log
import com.anezium.rokidbus.client.IBusCallback
import com.anezium.rokidbus.client.IBusService
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.FrameProtocol
import com.anezium.rokidbus.shared.LinkStateBits
import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.CxrDefs
import com.example.cxrglobal.callbacks.ICXRLinkCbk
import com.example.cxrglobal.callbacks.ICustomCmdCbk
import com.rokid.cxr.Caps
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ROKIDBUS-PHONE"
private const val CHANNEL_ID = "rokidbus_phone"
private const val ACTION_LOG = "com.anezium.rokidbus.phone.LOG"
private const val ACTION_SET_TOKEN = "com.anezium.rokidbus.phone.SET_TOKEN"
private const val ACTION_STOP = "com.anezium.rokidbus.phone.STOP"
private const val EXTRA_AUTH_TOKEN = "auth_token"
private const val PREF_ENABLED = "hub_enabled"
private const val GLASSES_MAC = "AC:86:D1:55:1E:ED"
private const val GLASSES_NAME = "Glasses_3723"
private const val GLASSES_HUB_PACKAGE = "com.anezium.rokidbus.glasses"
private const val PREFS = "rokidbus_phone"
private const val PREF_TOKEN = "cxrl_token"

class BusHubService : Service() {
    private data class Registration(
        val clientId: String,
        val prefixes: List<String>,
        val uid: Int,
        val callback: IBusCallback,
    )

    private val executor = Executors.newCachedThreadPool()
    private val registrations = CopyOnWriteArrayList<Registration>()
    private val sppLoopStarted = AtomicBoolean(false)
    @Volatile private var sppLoopStop = false
    @Volatile private var hubEnabled = true
    private val writeLock = Any()
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var cxrLink: CXRLink? = null
    @Volatile private var cxrConnected = false
    @Volatile private var glassBtConnected = false

    private val binder = object : IBusService.Stub() {
        override fun apiVersion(): Int = BusConstants.API_VERSION

        override fun register(clientId: String, pathPrefixes: Array<out String>, cb: IBusCallback) {
            unregister(cb)
            registrations += Registration(clientId, pathPrefixes.filter { it.isNotBlank() }, Binder.getCallingUid(), cb)
            log("client registered id=$clientId prefixes=${pathPrefixes.joinToString()}")
            runCatching { cb.onLinkState(linkState()) }
        }

        override fun unregister(cb: IBusCallback) {
            registrations.removeAll { it.callback.asBinder() == cb.asBinder() }
        }

        override fun send(path: String, id: String, payload: ByteArray) {
            val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrElse { JSONObject() }
            routeLocal(BusEnvelope(path = path, id = id, payload = json), Binder.getCallingUid())
        }

        override fun linkState(): Int = this@BusHubService.linkState()
    }

    private val linkCallback = object : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            cxrConnected = connected
            log("CXR-L connected=$connected")
            notifyLinkState()
        }

        override fun onGlassBtConnected(connected: Boolean) {
            glassBtConnected = connected
            log("Hi Rokid glass BT connected=$connected")
            notifyLinkState()
        }
    }

    private val customCmdCallback = object : ICustomCmdCbk {
        override fun onCustomCmdResult(key: String, payload: ByteArray) {
            if (key != BusConstants.CXR_KEY) return
            val envelope = decodeCxrPayload(payload) ?: return
            log("CXR RX ${envelope.path} id=${envelope.id}")
            routeRemote(envelope)
        }
    }

    override fun onCreate() {
        super.onCreate()
        hubEnabled = prefs().getBoolean(PREF_ENABLED, true)
        if (hubEnabled) {
            startForegroundWithType()
            startCxrIfTokenAvailable()
        }
        connectSpp()
        log("BusHubService created enabled=$hubEnabled")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopHub()
                return START_NOT_STICKY
            }
            ACTION_SET_TOKEN -> {
                val token = intent.getStringExtra(EXTRA_AUTH_TOKEN).orEmpty()
                if (token.isNotBlank()) {
                    prefs().edit().putString(PREF_TOKEN, token).apply()
                    enableHub()
                    startCxr(token)
                }
            }
            else -> {
                enableHub()
                startCxrIfTokenAvailable()
            }
        }
        connectSpp()
        return START_STICKY
    }

    private fun enableHub() {
        prefs().edit().putBoolean(PREF_ENABLED, true).apply()
        hubEnabled = true
        startForegroundWithType()
    }

    /** Release every radio resource: SPP socket, CXR-L session, foreground state. */
    private fun stopHub() {
        prefs().edit().putBoolean(PREF_ENABLED, false).apply()
        hubEnabled = false
        runCatching { cxrLink?.disconnect() }
        cxrLink = null
        cxrConnected = false
        glassBtConnected = false
        closeSocket()
        notifyLinkState()
        log("Hub stopped; SPP socket and CXR-L session released")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        sppLoopStop = true
        runCatching { cxrLink?.disconnect() }
        closeSocket()
        registrations.clear()
        super.onDestroy()
    }

    private fun routeLocal(envelope: BusEnvelope, senderUid: Int) {
        if (handleHubPath(envelope, replyRemote = false)) return
        if (deliverLocal(envelope, excludeUid = senderUid)) return
        val errorCode = sendRemote(envelope)
        if (errorCode != null) {
            deliverLocal(errorEnvelope(envelope.id, errorCode))
        }
    }

    private fun routeRemote(envelope: BusEnvelope) {
        if (envelope.path == "/hub/probe") {
            log("hub probe received from glasses")
            return
        }
        if (handleHubPath(envelope, replyRemote = true)) return
        if (deliverLocal(envelope)) return
        if (envelope.path == BusPaths.ERROR) {
            log("dropping undeliverable remote error id=${envelope.id}")
            return
        }
        sendRemote(errorEnvelope(envelope.id, "NO_LOCAL_CLIENT"))
    }

    private fun handleHubPath(envelope: BusEnvelope, replyRemote: Boolean): Boolean {
        if (envelope.path != BusPaths.HTTP_REQUEST) return false
        executor.execute { fetchAndStream(envelope, replyRemote) }
        return true
    }

    private fun deliverLocal(envelope: BusEnvelope, excludeUid: Int? = null): Boolean {
        val payload = envelope.payload.toString().toByteArray(Charsets.UTF_8)
        var delivered = false
        registrations.forEach { registration ->
            if (excludeUid != null && registration.uid == excludeUid) return@forEach
            if (registration.prefixes.any { envelope.path.startsWith(it) }) {
                runCatching {
                    registration.callback.onMessage(envelope.path, envelope.id, payload)
                    delivered = true
                }.onFailure {
                    registrations.remove(registration)
                }
            }
        }
        return delivered
    }

    private fun sendRemote(envelope: BusEnvelope): String? {
        val bytes = FrameProtocol.toJsonBytes(envelope)
        if (bytes.size <= BusConstants.CXR_CONTROL_MAX_BYTES && isCxrUp()) {
            if (sendCxr(envelope)) return null
        }
        if (bytes.size > BusConstants.CXR_CONTROL_MAX_BYTES && output == null) {
            return "NO_DATA_PLANE"
        }
        if (writeSpp(envelope)) return null
        return if (bytes.size > BusConstants.CXR_CONTROL_MAX_BYTES) "NO_DATA_PLANE" else "NO_LINK"
    }

    private fun sendCxr(envelope: BusEnvelope): Boolean =
        runCatching {
            val json = FrameProtocol.toJson(envelope).toString()
            val result = cxrLink?.sendCustomCmd(
                BusConstants.CXR_KEY,
                Caps().apply { write(json) }.serialize(),
            )
            log("CXR TX ${envelope.path} id=${envelope.id} result=$result")
            result != null && result >= 0
        }.getOrElse {
            log("CXR TX failed ${it.javaClass.simpleName}: ${it.message}")
            false
        }

    private fun writeSpp(envelope: BusEnvelope): Boolean {
        val out = output ?: return false
        return runCatching {
            synchronized(writeLock) { FrameProtocol.write(out, envelope) }
            log("SPP TX ${envelope.path} id=${envelope.id}")
            true
        }.getOrElse {
            log("SPP TX failed ${it.javaClass.simpleName}: ${it.message}")
            // Close the broken socket; the permanent connect thread notices and retries.
            closeSocket()
            false
        }
    }

    private fun fetchAndStream(envelope: BusEnvelope, replyRemote: Boolean) {
        val request = envelope.payload
        val urlText = request.optString("url")
        val reply = { payload: JSONObject ->
            val response = BusEnvelope(path = BusPaths.HTTP_REPLY, id = envelope.id, payload = payload)
            if (replyRemote) sendRemote(response) else deliverLocal(response)
        }
        val url = runCatching { URL(urlText) }.getOrNull()
        if (url == null || url.host != "api.transitous.org") {
            reply(
                JSONObject()
                    .put("status", 0)
                    .put("error", "HTTP host not allowed")
                    .put("done", true)
                    .put("totalBytes", 0),
            )
            return
        }
        var connection: HttpURLConnection? = null
        try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                requestMethod = request.optString("method", "GET").ifBlank { "GET" }
                request.optJSONObject("headers")?.let { headers ->
                    headers.keys().forEach { key -> setRequestProperty(key, headers.optString(key)) }
                }
                val body = request.optString("body")
                if (body.isNotBlank()) {
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            var total = 0L
            val buffer = ByteArray(16 * 1024)
            stream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    reply(
                        JSONObject()
                            .put("status", status)
                            .put("bytes", read)
                            .put("chunk", Base64.encodeToString(buffer.copyOf(read), Base64.NO_WRAP))
                            .put("done", false),
                    )
                }
            }
            reply(JSONObject().put("status", status).put("done", true).put("totalBytes", total))
            log("HTTP proxy complete status=$status totalBytes=$total")
        } catch (t: Throwable) {
            reply(
                JSONObject()
                    .put("status", 0)
                    .put("error", t.javaClass.simpleName + ": " + (t.message ?: "no message"))
                    .put("done", true)
                    .put("totalBytes", 0),
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Single permanent connection thread: the only place that ever creates,
     * assigns or retires the SPP socket. A parallel connect attempt against a
     * live RFCOMM link kills it at the stack level, so there must be exactly one.
     */
    @SuppressLint("MissingPermission")
    private fun connectSpp() {
        if (!sppLoopStarted.compareAndSet(false, true)) return
        Thread({
            var backoffMs = 1_000L
            while (!sppLoopStop) {
                if (!hubEnabled) {
                    sleepQuietly(750L)
                    continue
                }
                if (!hasBluetoothConnect()) {
                    log("Missing BLUETOOTH_CONNECT; SPP loop waiting")
                    sleepQuietly(5_000L)
                    continue
                }
                val device = pickBondedDevice()
                if (device == null) {
                    log("No bonded glasses device found; SPP loop waiting")
                    sleepQuietly(10_000L)
                    continue
                }
                var current: BluetoothSocket? = null
                try {
                    log("SPP connecting to bonded glasses name=${device.name ?: "unknown"}")
                    current = device.createInsecureRfcommSocketToServiceRecord(BusConstants.SPP_UUID)
                    current.connect()
                    socket = current
                    output = current.outputStream
                    backoffMs = 1_000L
                    log("SPP connected")
                    notifyLinkState()
                    readSppLoop(current)
                    log("SPP link closed")
                } catch (t: Throwable) {
                    log("SPP connect failed: ${t.javaClass.simpleName}; retrying in ${backoffMs}ms")
                } finally {
                    runCatching { current?.close() }
                    if (socket === current) {
                        socket = null
                        output = null
                    }
                    notifyLinkState()
                }
                sleepQuietly(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }, "rokidbus-spp").apply { isDaemon = true }.start()
    }

    private fun readSppLoop(activeSocket: BluetoothSocket) {
        val input = activeSocket.inputStream
        while (true) {
            val envelope = FrameProtocol.read(input) ?: return
            log("SPP RX ${envelope.path} id=${envelope.id}")
            routeRemote(envelope)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickBondedDevice(): BluetoothDevice? {
        val bonded = BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()
        return bonded.firstOrNull { it.address.equals(GLASSES_MAC, ignoreCase = true) }
            ?: bonded.firstOrNull { it.name.equals(GLASSES_NAME, ignoreCase = true) }
    }

    private fun startCxrIfTokenAvailable() {
        val token = prefs().getString(PREF_TOKEN, "").orEmpty()
        if (token.isNotBlank()) startCxr(token)
    }

    private fun startCxr(token: String) {
        val link = cxrLink ?: CXRLink(applicationContext).apply {
            configCXRSession(
                CxrDefs.CXRSession(
                    CxrDefs.CXRSessionType.CUSTOMAPP,
                    GLASSES_HUB_PACKAGE,
                ),
            )
            setCXRLinkCbk(linkCallback)
            setCXRCustomCmdCbk(customCmdCallback)
        }.also { cxrLink = it }
        val bound = runCatching { link.connect(token) }.getOrDefault(false)
        log("CXR-L connect requested bound=$bound")
        if (!bound) {
            cxrConnected = false
            glassBtConnected = false
            notifyLinkState()
        }
    }

    private fun decodeCxrPayload(payload: ByteArray): BusEnvelope? =
        runCatching {
            val caps = Caps.fromBytes(payload)
            if (caps.size() == 0) return@runCatching null
            FrameProtocol.fromJson(JSONObject(caps.at(0).string))
        }.onFailure {
            log("CXR decode failed: ${it.message}")
        }.getOrNull()

    private fun startForegroundWithType() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Rokid Nexus", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Rokid Nexus hub")
            .setContentText("CXR-L and SPP bus hub running")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
        startForeground(1, notification, ServiceInfoCompat.connectedDeviceType())
    }

    private fun linkState(): Int {
        var state = 0
        if (isCxrUp()) state = state or LinkStateBits.CXR_CONTROL_UP
        if (output != null && socket?.isConnected == true) state = state or LinkStateBits.SPP_DATA_UP
        if (isGlassesBonded()) state = state or LinkStateBits.GLASSES_BT_BONDED_OR_PHONE_CONNECTED
        return state
    }

    @SuppressLint("MissingPermission")
    private fun isGlassesBonded(): Boolean =
        hasBluetoothConnect() &&
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty().any {
                it.address.equals(GLASSES_MAC, ignoreCase = true)
            }

    private fun isCxrUp(): Boolean =
        cxrConnected && glassBtConnected && cxrLink?.isServiceConnected() == true

    private fun notifyLinkState() {
        val state = linkState()
        registrations.forEach { registration ->
            runCatching { registration.callback.onLinkState(state) }
                .onFailure { registrations.remove(registration) }
        }
    }

    private fun errorEnvelope(id: String, code: String): BusEnvelope =
        BusEnvelope(
            path = BusPaths.ERROR,
            id = id,
            payload = JSONObject().put("code", code).put("forId", id),
        )

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    private fun hasBluetoothConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun log(message: String) {
        Log.i(TAG, message)
        sendBroadcast(Intent(ACTION_LOG).setPackage(packageName).putExtra("line", message))
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        fun startWithToken(context: android.content.Context, token: String) {
            val intent = Intent(context, BusHubService::class.java)
                .setAction(ACTION_SET_TOKEN)
                .putExtra(EXTRA_AUTH_TOKEN, token)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun start(context: android.content.Context) {
            val intent = Intent(context, BusHubService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Plain startService: callers are foreground UI; the hub must not re-promote itself. */
        fun stop(context: android.content.Context) {
            context.startService(
                Intent(context, BusHubService::class.java).setAction(ACTION_STOP),
            )
        }

        fun isEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_ENABLED, true)
    }
}
