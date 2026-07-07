package com.anezium.rokidbus.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.FrameProtocol
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object SppServerManager {
    private val started = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val writeLock = Any()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null

    fun ensureStarted(context: Context) {
        if (!started.compareAndSet(false, true)) {
            log("SPP server already running or starting")
            return
        }
        executor.execute { acceptLoop(context.applicationContext) }
    }

    fun isConnected(): Boolean =
        socket?.isConnected == true && output != null

    fun send(envelope: BusEnvelope): Boolean {
        val out = output ?: return false
        return runCatching {
            synchronized(writeLock) {
                FrameProtocol.write(out, envelope)
            }
            log("SPP TX ${envelope.path} id=${envelope.id}")
            true
        }.getOrElse {
            logError("SPP TX failed", it)
            closeCurrent()
            false
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
                    log("SPP client accepted")
                    executor.execute { handleClient(accepted) }
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

    private fun handleClient(activeSocket: BluetoothSocket) {
        try {
            activeSocket.use {
                socket = it
                output = it.outputStream
                GlassesHub.onSppConnected(true)
                val input = it.inputStream
                while (true) {
                    val envelope = FrameProtocol.read(input) ?: break
                    log("SPP RX ${envelope.path} id=${envelope.id} payloadBytes=${envelope.payload.toString().length} binaryBytes=${envelope.binary?.size ?: 0}")
                    GlassesHub.onRemoteEnvelope(envelope)
                }
            }
        } catch (t: Throwable) {
            logError("SPP client loop ended", t)
        } finally {
            if (socket === activeSocket) {
                socket = null
                output = null
                GlassesHub.onSppConnected(false)
            }
        }
    }

    private fun closeCurrent() {
        runCatching { socket?.close() }
        socket = null
        output = null
        GlassesHub.onSppConnected(false)
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
