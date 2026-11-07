package com.anezium.rokidbus.glasses.audio

import android.media.AudioRecord
import android.os.SystemClock
import android.util.Log
import com.anezium.rokidbus.shared.GlassesAudioRelayContract as C
import org.json.JSONObject
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.reflect.InvocationTargetException

object GlassesAudioCaptureMain {
    @JvmStatic fun main(args: Array<String>) {
        // app_process keeps the VM alive while framework threads (AudioRecord, binder) linger.
        try { capture(args); System.exit(0) } catch (failure: Throwable) {
            var cause = failure
            while (cause is InvocationTargetException && cause.targetException != null) cause = cause.targetException
            Log.e("NexusAudioCapture", "${cause.javaClass.name}: ${cause.message}")
            System.exit(1)
        }
    }

    private fun capture(args: Array<String>) {
        require(args.size == 2 && args[0].toInt() in 1024..65535 && Regex("[0-9a-f]{32}").matches(args[1]))
        val token = System.getenv("NEXUS_AUDIO_TOKEN") ?: error("Missing session token")
        require(Regex("[0-9a-f]{64}").matches(token))
        val running = AtomicBoolean(true)
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        var recorder: AudioRecord? = null
        val socket = Socket(InetAddress.getByName("127.0.0.1"), args[0].toInt())
        socket.tcpNoDelay = true
        val output = socket.getOutputStream()
        fun stop() {
            running.set(false)
            runCatching { socket.close() }
            runCatching { recorder?.stop() }
        }
        try {
            output.write(ByteArray(32) { token.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
            output.flush()
            val lastKeepalive = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
            watchdog.scheduleAtFixedRate({
                if (SystemClock.elapsedRealtime() - lastKeepalive.get() >= C.LOCAL_KEEPALIVE_TIMEOUT_MS) stop()
            }, 250, 250, TimeUnit.MILLISECONDS)
            Thread({
                try {
                    while (running.get()) {
                        when (LocalCaptureProtocol.read(socket.getInputStream())?.type) {
                            LocalCaptureProtocol.KEEPALIVE -> lastKeepalive.set(SystemClock.elapsedRealtime())
                            else -> break
                        }
                    }
                } catch (_: Exception) { } finally { stop() }
            }, "nexus-audio-control").apply { isDaemon = true }.start()
            val context = AudioCaptureWorkarounds.shellContext()
            val record = AudioCaptureWorkarounds.createAudioRecord(context)
            recorder = record
            val silence = LocalCaptureProtocol.SilenceDetector()
            var headerSent = false
            var lastSilent: Boolean? = null
            OpusEncoderPump(
                header = { LocalCaptureProtocol.write(output, LocalCaptureProtocol.HEADER, it); headerSent = true },
                packet = { LocalCaptureProtocol.write(output, LocalCaptureProtocol.PACKET, it) },
            ).use { encoder ->
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start" }
                val stereo = ShortArray(1920)
                while (running.get()) {
                    var offset = 0
                    while (offset < stereo.size && running.get()) {
                        val count = record.read(stereo, offset, stereo.size - offset, AudioRecord.READ_BLOCKING)
                        check(count > 0) { "AudioRecord read returned $count" }
                        offset += count
                    }
                    if (!running.get()) break
                    val mono = LocalCaptureProtocol.mono(stereo)
                    val silent = silence.accept(mono)
                    encoder.feed(mono, silent)
                    if (headerSent && lastSilent != silent) {
                        LocalCaptureProtocol.write(output, LocalCaptureProtocol.STATUS,
                            JSONObject().put("silent", silent).toString().toByteArray(Charsets.UTF_8))
                        lastSilent = silent
                    }
                }
            }
        } finally {
            stop()
            recorder?.release()
            watchdog.shutdownNow()
        }
    }
}
