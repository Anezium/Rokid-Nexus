package com.anezium.rokidbus.glasses.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import com.anezium.rokidbus.glasses.AuthenticatedSppServer
import com.anezium.rokidbus.glasses.SppPeer
import com.anezium.rokidbus.glasses.SelfArmCommandBridgeClient
import com.anezium.rokidbus.glasses.SelfArmCommandBridgeProtocol
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.SppKeyStore
import com.anezium.rokidbus.shared.SppPairingKeyStore
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Owns only audio admission/session callbacks; it has no bus link-state callback. */
internal class GlassesAudioRelayServer(
    keys: SppPairingKeyStore,
    execute: (() -> Unit) -> Unit,
    schedule: (Long, () -> Unit) -> (() -> Unit),
    nowMs: () -> Long,
    private val newSession: ((BusEnvelope) -> Boolean, () -> Unit) -> GlassesAudioRelaySession,
) {
    private val lock = Any()
    private var peer: SppPeer? = null
    private var peerClosed = false
    private var inFlight = 0
    private var admittedKey: ByteArray? = null
    @Volatile private var relay: GlassesAudioRelaySession? = null
    private val auth: AuthenticatedSppServer = AuthenticatedSppServer(
        keys = object : SppPairingKeyStore {
            override fun load(): ByteArray? = keys.load()?.also { synchronized(lock) { admittedKey = it.copyOf() } }
            // The bus owns persistence. Key forwarding occurs after that write completes.
            override fun save(key: ByteArray) = false
        },
        execute = { task ->
            synchronized(lock) { inFlight++ }
            execute {
                try { task() } finally {
                    synchronized(lock) { inFlight--; releaseClosedPeer() }
                }
            }
        }, schedule = schedule, nowMs = nowMs,
        onConnected = { connected ->
            if (connected) {
                val current = synchronized(lock) { peer }
                if (current != null) {
                    lateinit var created: GlassesAudioRelaySession
                    created = newSession({ envelope -> relay === created && authSend(envelope) }, { current.close() })
                    relay = created
                    created.begin()
                }
            } else { relay?.close(); relay = null }
        },
        onEnvelope = { relay?.receive(it) },
    )

    private fun authSend(envelope: BusEnvelope): Boolean = auth.send(envelope)

    fun accept(candidate: SppPeer) {
        val wrapped = object : SppPeer by candidate {
            override fun close() {
                runCatching { candidate.close() }
                synchronized(lock) { if (peer === this) peerClosed = true }
            }
        }
        synchronized(lock) {
            if (peer != null) { candidate.close(); return }
            peer = wrapped
            peerClosed = false
        }
        auth.accept(wrapped)
        synchronized(lock) { releaseClosedPeer() }
    }

    private fun releaseClosedPeer() {
        if (peerClosed && inFlight == 0 && relay == null) peer = null
    }

    fun installKey(key: ByteArray) {
        val retired = synchronized(lock) { peer.takeIf { admittedKey?.contentEquals(key) == false } }
        retired?.close()
    }

    companion object {
        private val workers = Executors.newCachedThreadPool()
        private val timers = Executors.newSingleThreadScheduledExecutor()
        private var runtime: GlassesAudioRelayServer? = null
        private fun schedule(delay: Long, task: () -> Unit): () -> Unit {
            val future = timers.schedule(task, delay, TimeUnit.MILLISECONDS)
            return { future.cancel(false); Unit }
        }

        @Synchronized fun ensureStarted(context: Context) {
            if (runtime != null) return
            val app = context.applicationContext
            captureCommands.execute { SelfArmCommandBridgeClient.stopAudioCapture(app) }
            val server = GlassesAudioRelayServer(SppKeyStore(app), { workers.execute(it) }, ::schedule, SystemClock::elapsedRealtime) { send, close ->
                GlassesAudioRelaySession({ LoopbackCapture(app) }, send, close, { workers.execute(it) }, ::schedule, SystemClock::elapsedRealtime)
            }
            runtime = server
            workers.execute { acceptLoop(server) }
        }

        @Synchronized fun installPairingKey(key: ByteArray) { runtime?.installKey(key) }

        @SuppressLint("MissingPermission")
        private fun acceptLoop(server: GlassesAudioRelayServer) {
            while (true) {
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("Bluetooth unavailable")
                    adapter.listenUsingInsecureRfcommWithServiceRecord(BusConstants.AUDIO_SERVICE_NAME, BusConstants.AUDIO_SPP_UUID).use { listener ->
                        while (true) {
                            val socket = listener.accept()
                            server.accept(object : SppPeer {
                                override val bonded get() = socket.remoteDevice.bondState == BluetoothDevice.BOND_BONDED
                                override val input get() = socket.inputStream
                                override val output get() = socket.outputStream
                                override fun close() = socket.close()
                            })
                        }
                    }
                } catch (_: Exception) { Thread.sleep(2000) }
            }
        }
    }
}

private class LoopbackCapture(private val context: Context) : AudioCaptureAttachment {
    private val secret = checkNotNull(SelfArmCommandBridgeClient.loadSecretHex(context))
    private val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    override val token = SelfArmCommandBridgeProtocol.audioSessionToken(secret, nonce)
    override fun start() = captureCommands.submit<Boolean> {
        SelfArmCommandBridgeClient.startAudioCapture(context, listener.localPort, nonce)
    }.get()
    override fun stop() { captureCommands.execute { SelfArmCommandBridgeClient.stopAudioCapture(context) } }
    override fun close() = listener.close()
    override fun accept(): AudioCaptureConnection {
        val socket = listener.accept()
        socket.tcpNoDelay = true
        return object : AudioCaptureConnection {
            override val input get() = socket.getInputStream()
            override val output get() = socket.getOutputStream()
            override fun close() = socket.close()
        }
    }
}

private val captureCommands = Executors.newSingleThreadExecutor()
