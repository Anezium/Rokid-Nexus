package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.SppAuthProtocol
import com.anezium.rokidbus.shared.GlassesAudioRelayContract as C
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PhoneAudioRelayClientTest {
    private val workers = Executors.newCachedThreadPool()
    private val key = ByteArray(32) { 42 }
    private val now = AtomicLong()
    private data class Timer(val at: Long, val action: () -> Unit, val canceled: AtomicBoolean = AtomicBoolean())
    private val timers = CopyOnWriteArrayList<Timer>()
    private val received = CopyOnWriteArrayList<BusEnvelope>()
    private val statuses = CopyOnWriteArrayList<PhoneAudioRelayStatus>()
    private val retryDelays = CopyOnWriteArrayList<Long>()
    private val connected = AtomicInteger()
    private val players = AtomicInteger()
    private val offered = AtomicInteger()
    private val playbackClosed = AtomicBoolean()
    private val retry = Semaphore(0)
    private var wire: Wire? = null
    private var beforeFactoryReturns: () -> Unit = {}
    private var afterAuthentication: () -> Unit = {}
    private val authentications = AtomicInteger()
    private val client = PhoneAudioRelayClient(
        connection = { connected.incrementAndGet(); Wire().also { wire = it; beforeFactoryReturns() } },
        player = { _, _ ->
            players.incrementAndGet()
            object : AudioRelayPlayback {
                override fun offer(seq: Long, packets: List<ByteArray>) { offered.incrementAndGet() }
                override fun close() { playbackClosed.set(true) }
            }
        },
        status = statuses::add,
        schedule = { delay, action ->
            val timer = Timer(now.get() + delay, action); timers += timer
            ({ timer.canceled.set(true) })
        }, nowMs = now::get, launch = { workers.execute(it) },
        waitForRetry = { backoff, _ -> retryDelays += backoff.retryDelayMs(); retry.acquire() },
    )
    private inner class Wire : PhoneAudioRelayConnection {
        override val input = PipedInputStream(8192)
        private val serverOutput = PipedOutputStream(input)
        private val serverInput = PipedInputStream(8192)
        override val output = PipedOutputStream(serverInput)
        val closed = AtomicBoolean()
        @Volatile private var session: SppAuthProtocol.Session? = null
        init {
            workers.execute {
                try {
                    val authenticated = SppAuthProtocol.accept(serverInput, serverOutput, key, SppAuthProtocol.RecentNonces())
                    session = authenticated
                    while (!closed.get()) received += authenticated.read(serverInput) ?: break
                } catch (_: Exception) { }
            }
        }
        override fun authenticate(nonces: SppAuthProtocol.RecentNonces): SppAuthProtocol.Session {
            authentications.incrementAndGet()
            return SppAuthProtocol.connect(input, output, key, nonces).also { afterAuthentication() }
        }
        fun send(envelope: BusEnvelope) { await { session != null }; session!!.write(serverOutput, envelope) }
        override fun close() {
            closed.set(true); output.close(); serverOutput.close(); input.close(); serverInput.close()
        }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < end) Thread.sleep(2)
        assertTrue(condition())
    }
    private fun advance(ms: Long) {
        val end = now.get() + ms
        while (true) {
            val timer = timers.filter { !it.canceled.get() && it.at <= end }.minByOrNull { it.at } ?: break
            timers.remove(timer); now.set(timer.at); timer.action()
        }
        now.set(end)
    }
    private fun start() {
        client.setDesired(true)
        await { received.isNotEmpty() }
        assertEquals(BusPaths.CORE_AUDIO_RELAY_START, received.first().path)
        assertTrue(C.validateStart(received.first().payload) is C.ValidationResult.Valid)
    }
    private fun ready() {
        start()
        val csd = listOf("T3B1c0hlYWQBATgBgLsAAAAAAA==", "oC5jAAAAAAA=", "ALTEBAAAAAA=").map { Base64.getDecoder().decode(it) }
        wire!!.send(BusEnvelope(BusPaths.CORE_AUDIO_RELAY_READY, payload = C.ready(C.OPUS, csd)))
        await { PhoneAudioRelayStatus.ACTIVE in statuses }
    }
    @After fun cleanup() {
        client.close(); wire?.close(); workers.shutdownNow()
        assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
    }
    @Test fun `disabled client never connects or allocates playback`() {
        client.setDesired(false); advance(20000)
        assertEquals(0, connected.get()); assertEquals(0, players.get())
    }
    @Test fun `gate off during connection preparation prevents connect and authentication`() {
        val prepared = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        beforeFactoryReturns = { prepared.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        try {
            client.setDesired(true)
            assertTrue(prepared.await(2, TimeUnit.SECONDS))
            Thread { client.setDesired(false) }.apply { start(); join() }
            release.countDown()
            await { wire?.closed?.get() == true }
            assertEquals(0, authentications.get()); assertTrue(received.isEmpty())
        } finally { release.countDown() }
    }
    @Test fun `gate off while authentication returns prevents start and playback`() {
        val authenticated = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        afterAuthentication = { authenticated.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        try {
            client.setDesired(true)
            assertTrue(authenticated.await(2, TimeUnit.SECONDS))
            client.setDesired(false)
            release.countDown()
            await { wire!!.closed.get() }
            assertTrue(received.none { it.path == BusPaths.CORE_AUDIO_RELAY_START })
            assertEquals(0, players.get())
        } finally { release.countDown() }
    }
    @Test fun `start is first and no ready after three seconds closes without playback and backs off`() {
        start(); assertEquals(0, players.get()); advance(3000)
        assertTrue(wire!!.closed.get()); await { retryDelays.isNotEmpty() }
        assertEquals(listOf(1000L), retryDelays); assertEquals(1, connected.get())
    }
    @Test fun `keepalives have two second cadence and peer silence closes at six seconds`() {
        ready(); advance(1999)
        assertEquals(0, received.count { it.path == BusPaths.CORE_AUDIO_RELAY_KEEPALIVE })
        advance(1); await { received.count { it.path == BusPaths.CORE_AUDIO_RELAY_KEEPALIVE } == 1 }
        advance(2000); await { received.count { it.path == BusPaths.CORE_AUDIO_RELAY_KEEPALIVE } == 2 }
        advance(2000)
        assertTrue(wire!!.closed.get()); assertTrue(playbackClosed.get())
        assertTrue(received.all { BusPaths.isAudioRelayPath(it.path) })
    }
    @Test fun `gate off closes immediately while peer read blocks and closes playback`() {
        ready(); client.setDesired(false)
        assertTrue(wire!!.closed.get()); assertTrue(playbackClosed.get())
    }
    @Test fun `valid status resets the peer deadline`() {
        ready(); advance(4000)
        wire!!.send(BusEnvelope(BusPaths.CORE_AUDIO_RELAY_STATUS, payload = C.status(-1, true)))
        wire!!.send(C.frame(0, now.get(), listOf(byteArrayOf(1))))
        await { offered.get() == 1 }
        advance(2000); assertFalse(wire!!.closed.get())
        client.setDesired(false)
    }
    @Test fun `bridge unavailable before ready is visible and never configures a player`() {
        start()
        wire!!.send(BusEnvelope(BusPaths.CORE_AUDIO_RELAY_ERROR, payload = C.error("BRIDGE_UNAVAILABLE")))
        await { PhoneAudioRelayStatus.SETUP_NEEDED in statuses }
        assertEquals(0, players.get()); await { wire!!.closed.get() }
    }
}
