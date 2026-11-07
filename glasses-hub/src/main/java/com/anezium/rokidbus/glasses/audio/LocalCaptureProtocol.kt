package com.anezium.rokidbus.glasses.audio

import com.anezium.rokidbus.shared.GlassesAudioRelayContract as C
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import kotlin.math.abs

internal object LocalCaptureProtocol {
    const val HEADER = 1
    const val PACKET = 2
    const val STATUS = 3
    const val KEEPALIVE = 16
    const val STOP = 17
    data class Record(val type: Int, val body: ByteArray)

    fun read(input: InputStream): Record? {
        val type = input.read()
        if (type == -1) return null
        val stream = DataInputStream(input)
        val length = stream.readUnsignedShort()
        if (type !in setOf(HEADER, PACKET, STATUS, KEEPALIVE, STOP) || length > C.MAX_FRAME_BYTES ||
            (type in setOf(KEEPALIVE, STOP) && length != 0) ||
            (type in setOf(HEADER, PACKET, STATUS) && length == 0)) throw IOException("Invalid capture record")
        return Record(type, ByteArray(length).also(stream::readFully))
    }

    fun write(output: OutputStream, type: Int, body: ByteArray = byteArrayOf()) {
        require(body.size <= C.MAX_FRAME_BYTES)
        DataOutputStream(output).apply { writeByte(type); writeShort(body.size); write(body); flush() }
    }

    fun mono(stereo: ShortArray): ShortArray {
        require(stereo.size % 2 == 0)
        return ShortArray(stereo.size / 2) { (stereo[it * 2].toInt() + stereo[it * 2 + 1].toInt()).div(2).toShort() }
    }

    class SilenceDetector {
        private var quietBlocks = 0
        var silent = false
            private set

        fun accept(mono: ShortArray): Boolean {
            if (mono.any { abs(it.toInt()) >= C.SILENCE_PEAK }) {
                quietBlocks = 0
                silent = false
            } else {
                quietBlocks = (quietBlocks + 1).coerceAtMost(C.SILENCE_HOLD_BLOCKS)
                silent = quietBlocks >= C.SILENCE_HOLD_BLOCKS
            }
            return silent
        }
    }
}
