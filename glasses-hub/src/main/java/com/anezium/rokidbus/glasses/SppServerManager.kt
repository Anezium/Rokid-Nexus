package com.anezium.rokidbus.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.SppKeyStore
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object SppServerManager {
    private val started = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val keyUpdates = Executors.newSingleThreadExecutor()
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var sessions: AuthenticatedSppServer? = null

    fun ensureStarted(context: Context) {
        if (!started.compareAndSet(false, true)) {
            log("SPP server already running or starting")
            return
        }
        keyUpdates.execute {
            sessions = AuthenticatedSppServer(
                keys = SppKeyStore(context),
                execute = { task -> executor.execute(task) },
                schedule = { delay, task ->
                    val future = deadlines.schedule(task, delay, TimeUnit.MILLISECONDS)
                    ({ future.cancel(false); Unit })
                },
                nowMs = SystemClock::elapsedRealtime,
                onConnected = GlassesHub::onSppConnected,
                onEnvelope = GlassesHub::onRemoteEnvelope,
            )
            executor.execute { acceptLoop(context.applicationContext) }
        }
    }

    fun isConnected(): Boolean = sessions?.isConnected() == true

    fun send(envelope: BusEnvelope): Boolean = sessions?.send(envelope) == true

    fun installPairingKey(context: Context, key: ByteArray) {
        val application = context.applicationContext
        val copy = key.copyOf()
        keyUpdates.execute {
            val server = sessions
            if (server != null) server.installKey(copy) else SppKeyStore(application).save(copy)
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop(context: Context) {
        while (started.get()) {
            var serverSocket: BluetoothServerSocket? = null
            try {
                if (!hasBluetoothConnect(context)) {
                    logError("Missing BLUETOOTH_CONNECT; grant before hardware test")
                    Thread.sleep(5_000)
                    continue
                }
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null) {
                    logError("No BluetoothAdapter; retrying")
                    Thread.sleep(5_000)
                    continue
                }
                serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(
                    BusConstants.SERVICE_NAME,
                    BusConstants.SPP_UUID,
                )
                log("SPP server listening name=${BusConstants.SERVICE_NAME}")
                while (started.get()) {
                    val accepted = serverSocket.accept()
                    sessions?.accept(object : SppPeer {
                        override val bonded = runCatching {
                            accepted.remoteDevice.bondState == BluetoothDevice.BOND_BONDED
                        }.getOrDefault(false)
                        override val input get() = accepted.inputStream
                        override val output get() = accepted.outputStream
                        override fun close() = accepted.close()
                    }) ?: accepted.close()
                }
            } catch (t: Throwable) {
                logError("SPP accept loop failed; restarting", t)
                sleepQuietly(2_000)
            } finally {
                try {
                    serverSocket?.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun hasBluetoothConnect(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
