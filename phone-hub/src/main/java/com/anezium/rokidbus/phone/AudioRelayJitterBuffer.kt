package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.GlassesAudioRelayContract as C
import java.util.TreeMap

internal class AudioRelayJitterBuffer {
    data class Frame(val seq: Long, val packets: List<ByteArray>)
    private val frames = TreeMap<Long, Frame>()
    private var primed = false
    private var played = -1L

    @Synchronized fun offer(seq: Long, packets: List<ByteArray>) {
        if (seq <= played) return
        frames[seq] = Frame(seq, packets)
        while (frames.size > C.JITTER_MAX_FRAMES) frames.pollFirstEntry()
    }
    @Synchronized fun poll(): Frame? {
        if (!primed && frames.size >= C.JITTER_PRIME_FRAMES) primed = true
        if (!primed) return null
        val next = frames.pollFirstEntry()?.value
        if (next == null) primed = false else played = next.seq
        return next
    }
    @Synchronized fun depth() = frames.size
}
