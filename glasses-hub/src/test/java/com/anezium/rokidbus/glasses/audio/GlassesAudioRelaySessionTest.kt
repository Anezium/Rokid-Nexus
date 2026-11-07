package com.anezium.rokidbus.glasses.audio

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.GlassesAudioRelayContract as C
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GlassesAudioRelaySessionTest {
    private val workers = Executors.newCachedThreadPool()
    private val fixtures = mutableListOf<Fixture>()
    @Volatile private var now = 0L
    private data class Timer(val at: Long, val task: () -> Unit, var canceled: Boolean = false)
    private val timers = CopyOnWriteArrayList<Timer>()
    private fun schedule(delay: Long, task: () -> Unit): () -> Unit {
        val timer = Timer(now + delay, task)
        timers += timer
        return { timer.canceled = true }
    }
    private fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = timers.filter { !it.canceled && it.at <= end }.minByOrNull { it.at } ?: break
            timers.remove(next); now = next.at; next.task()
        }
        now = end
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < end) Thread.sleep(2)
        assertTrue(condition())
    }
    private inner class Fixture(val startWorks: Boolean = true, val connects: Boolean = true) : AudioCaptureAttachment {
        override val token = ByteArray(32) { 42 }
        private val input = PipedInputStream(8192)
        val producer = PipedOutputStream(input)
        val attached = AtomicBoolean()
        val closed = AtomicBoolean()
        val spawned = AtomicBoolean()
        val peerClosed = AtomicBoolean()
        val sent = CopyOnWriteArrayList<BusEnvelope>()
        val controls = ByteArrayOutputStream()
        val localClosed = AtomicBoolean()
        val session = GlassesAudioRelaySession({ this }, { sent += it; true }, { peerClosed.set(true) },
            { workers.execute(it) }, ::schedule, { now })
        init { fixtures += this; session.begin() }
        override fun start(): Boolean { spawned.set(true); return startWorks }
        override fun accept(): AudioCaptureConnection {
            attached.set(true)
            if (!connects) {
                while (!closed.get()) Thread.sleep(2)
                error("Listener closed")
            }
            return object : AudioCaptureConnection {
                override val input = this@Fixture.input
                override val output = controls
                override fun close() { localClosed.set(true); producer.close(); input.close() }
            }
        }
        override fun close() { closed.set(true); producer.close() }
        fun startRelay() { session.receive(BusEnvelope(BusPaths.CORE_AUDIO_RELAY_START, payload = C.start())); await { attached.get() || peerClosed.get() } }
        fun ready() {
            startRelay()
            producer.write(token)
            LocalCaptureProtocol.write(producer, LocalCaptureProtocol.HEADER,
                C.ready(C.OPUS, List(3) { byteArrayOf(1) }).apply { remove("v") }.toString().toByteArray())
            await { session.state == GlassesAudioRelaySession.State.STREAMING }
        }
        fun errorCode() = sent.first { it.path == BusPaths.CORE_AUDIO_RELAY_ERROR }.payload.getString("code")
    }
    @After fun cleanup() {
        fixtures.forEach { it.session.close(); it.close() }
        workers.shutdownNow()
        assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
    }
    @Test fun `missing and invalid start never spawn capture`() {
        val missing = Fixture()
        advance(3000)
        assertTrue(missing.peerClosed.get()); assertFalse(missing.spawned.get())
        val bad = Fixture()
        bad.session.receive(BusEnvelope(BusPaths.CORE_AUDIO_RELAY_START, payload = C.start(listOf(C.PCM16))))
        await { bad.peerClosed.get() }
        assertEquals("UNSUPPORTED", bad.errorCode()); assertFalse(bad.spawned.get())
    }
    @Test fun `bridge failure and missing attachment report bridge unavailable and clean up`() {
        val failure = Fixture(startWorks = false)
        failure.startRelay()
        await { failure.peerClosed.get() }
        assertEquals("BRIDGE_UNAVAILABLE", failure.errorCode())
        assertTrue(failure.closed.get())
        val missing = Fixture(connects = false)
        missing.startRelay(); advance(5000)
        await { missing.peerClosed.get() }
        assertEquals("BRIDGE_UNAVAILABLE", missing.errorCode())
        assertTrue(missing.closed.get())
    }
    @Test fun `token deadline closes a blocked local reader and wrong tokens fail closed`() {
        val noToken = Fixture()
        noToken.startRelay(); advance(5000)
        await { noToken.peerClosed.get() }
        assertTrue(noToken.localClosed.get()); assertTrue(noToken.closed.get())
        val wrong = Fixture()
        wrong.startRelay(); wrong.producer.write(ByteArray(32))
        await { wrong.peerClosed.get() }
        assertTrue(wrong.localClosed.get())
    }
    @Test fun `header packets and silence are forwarded on the audio sender only`() {
        val fixture = Fixture(); fixture.ready()
        repeat(4) { LocalCaptureProtocol.write(fixture.producer, LocalCaptureProtocol.PACKET, byteArrayOf(it.toByte())) }
        LocalCaptureProtocol.write(fixture.producer, LocalCaptureProtocol.STATUS, JSONObject().put("silent", true).toString().toByteArray())
        await { fixture.sent.any { it.path == BusPaths.CORE_AUDIO_RELAY_STATUS } }
        val frames = fixture.sent.filter { it.path == BusPaths.CORE_AUDIO_RELAY_FRAME }
        assertEquals(listOf("ar-0", "ar-1"), frames.map { it.id })
        frames.forEach { assertTrue(C.validateFrame(it) is C.ValidationResult.Valid) }
        assertTrue(fixture.sent.first { it.path == BusPaths.CORE_AUDIO_RELAY_STATUS }.payload.getBoolean("silent"))
        advance(2000)
        await { fixture.controls.size() >= 3 }
        assertEquals(LocalCaptureProtocol.KEEPALIVE, fixture.controls.toByteArray()[0].toInt())
    }
    @Test fun `capture EOF reports capture failed`() {
        val fixture = Fixture(); fixture.ready(); fixture.producer.close()
        await { fixture.peerClosed.get() }
        assertEquals("CAPTURE_FAILED", fixture.errorCode())
    }
    @Test fun `scheduled peer timeout closes local while the producer read is blocked`() {
        val fixture = Fixture(); fixture.ready(); advance(6000)
        assertTrue(fixture.peerClosed.get()); assertTrue(fixture.localClosed.get()); assertTrue(fixture.closed.get())
    }
    @Test fun `phone close tears down capture immediately and a later session streams`() {
        val fixture = Fixture(); fixture.ready(); fixture.session.close()
        assertTrue(fixture.peerClosed.get()); assertTrue(fixture.localClosed.get()); assertTrue(fixture.closed.get())
        val next = Fixture(); next.ready()
        assertEquals(GlassesAudioRelaySession.State.STREAMING, next.session.state)
    }
}
