package com.anezium.rokidbus.glasses.audio

import com.anezium.rokidbus.glasses.SppPeer
import com.anezium.rokidbus.glasses.AuthenticatedSppServer
import com.anezium.rokidbus.shared.SppAuthProtocol
import com.anezium.rokidbus.shared.SppPairingKeyStore
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.GlassesAudioRelayContract as C
import org.junit.Assert.*
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.ByteArrayOutputStream

class GlassesAudioRelayServerTest {
    @Test fun `a key read that finishes after rotation cannot authenticate with the old enrollment`() {
        val workers = Executors.newCachedThreadPool()
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connected = AtomicBoolean()
        val peer = Peer()
        val persisted = AtomicReference(ByteArray(32) { 42 })
        val loads = AtomicInteger()
        val server = GlassesAudioRelayServer(object : SppPairingKeyStore {
            override fun load(): ByteArray {
                val snapshot = persisted.get()
                if (loads.incrementAndGet() == 1) {
                    loading.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
                return snapshot
            }
            override fun save(key: ByteArray) = false
        }, { workers.execute(it) }, { _, _ -> ({}) }, { 0L }) { _, _ ->
            connected.set(true)
            error("Stale key must not connect")
        }
        try {
            workers.execute { server.accept(peer) }
            assertTrue(loading.await(2, TimeUnit.SECONDS))
            val replacement = ByteArray(32) { 9 }
            persisted.set(replacement)
            server.installKey(replacement)
            release.countDown()
            assertTrue(peer.closed.await(2, TimeUnit.SECONDS))
            assertFalse(connected.get())
        } finally {
            release.countDown(); peer.close(); workers.shutdownNow()
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun `watchdog closes authenticated pipe with peer reader blocked and permits another session`() {
        val workers = Executors.newCachedThreadPool()
        val outstanding = AtomicInteger()
        val key = ByteArray(32) { 42 }
        val now = AtomicLong()
        val ticks = CopyOnWriteArrayList<() -> Unit>()
        val attachments = CopyOnWriteArrayList<AudioCaptureAttachment>()
        val localClosed = AtomicBoolean()
        val server = GlassesAudioRelayServer(object : SppPairingKeyStore {
            override fun load() = key
            override fun save(key: ByteArray) = false
        }, { task ->
            outstanding.incrementAndGet()
            workers.execute { try { task() } finally { outstanding.decrementAndGet() } }
        }, { _, _ -> ({}) }, now::get) { send, close ->
            GlassesAudioRelaySession({
                val pipe = PipedInputStream(8192)
                val producer = PipedOutputStream(pipe)
                object : AudioCaptureAttachment {
                    override val token = ByteArray(32)
                    override fun start(): Boolean {
                        producer.write(token)
                        LocalCaptureProtocol.write(producer, LocalCaptureProtocol.HEADER,
                            C.ready(C.OPUS, List(3) { byteArrayOf(1) }).apply { remove("v") }.toString().toByteArray())
                        return true
                    }
                    override fun accept() = object : AudioCaptureConnection {
                        override val input = pipe
                        override val output = ByteArrayOutputStream()
                        override fun close() { localClosed.set(true); producer.close(); pipe.close() }
                    }
                    override fun close() { producer.close() }
                }.also { attachments += it }
            }, send, close, { workers.execute(it) }, { _, task -> ticks += task; ({ ticks.remove(task); Unit }) }, now::get)
        }
        val first = Peer(); val second = Peer()
        try {
            server.accept(first)
            val phone = SppAuthProtocol.connect(first.phoneInput, first.phoneOutput, key, SppAuthProtocol.RecentNonces())
            phone.write(first.phoneOutput, BusEnvelope(BusPaths.CORE_AUDIO_RELAY_START, payload = C.start()))
            assertEquals(BusPaths.CORE_AUDIO_RELAY_READY, phone.read(first.phoneInput)!!.path)
            now.set(6000)
            ticks.toList().forEach { it() }
            assertTrue(first.closed.await(2, TimeUnit.SECONDS)); assertTrue(localClosed.get())
            // A subsequent admission can proceed once the old reader's teardown completes.
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (outstanding.get() != 0 && System.nanoTime() < end) Thread.sleep(2)
            assertEquals(0, outstanding.get())
            server.accept(second)
            SppAuthProtocol.connect(second.phoneInput, second.phoneOutput, key, SppAuthProtocol.RecentNonces())
        } finally {
            first.close(); second.close(); attachments.forEach { it.close() }
            workers.shutdownNow(); assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private class Peer : SppPeer {
        val phoneInput = PipedInputStream(8192)
        override val output = PipedOutputStream(phoneInput)
        override val input = PipedInputStream(8192)
        val phoneOutput = PipedOutputStream(input)
        override val bonded = true
        val closed = CountDownLatch(1)
        override fun close() { phoneOutput.close(); output.close(); input.close(); phoneInput.close(); closed.countDown() }
    }
    @Test fun `audio authentication refuses a second peer and has no bus callbacks`() {
        val key = ByteArray(32) { 42 }
        val persisted = AtomicReference(key)
        val workers = Executors.newCachedThreadPool()
        val connected = CountDownLatch(1)
        val first = Peer(); val second = Peer()
        val server = GlassesAudioRelayServer(object : SppPairingKeyStore {
            override fun load() = persisted.get()
            override fun save(key: ByteArray): Boolean = error("Audio must not persist a pairing key")
        }, { workers.execute(it) }, { _, _ -> ({}) }, { 0L }) { send, close ->
            connected.countDown()
            GlassesAudioRelaySession({ error("No start received") }, send, close, { workers.execute(it) }, { _, _ -> ({}) }, { 0L })
        }
        try {
            server.accept(first)
            SppAuthProtocol.connect(first.phoneInput, first.phoneOutput, key, SppAuthProtocol.RecentNonces())
            assertTrue(connected.await(2, TimeUnit.SECONDS))
            server.accept(second)
            assertTrue(second.closed.await(1, TimeUnit.SECONDS))
            assertEquals(1L, first.closed.count)
            server.installKey(key)
            assertEquals(1L, first.closed.count)
            val replacement = ByteArray(32) { 9 }
            persisted.set(replacement)
            server.installKey(replacement)
            assertTrue(first.closed.await(1, TimeUnit.SECONDS))
        } finally {
            first.close(); second.close(); workers.shutdownNow()
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun `failed bus persistence preserves audio authentication with the retained key`() {
        val workers = Executors.newCachedThreadPool()
        val retained = ByteArray(32) { 42 }
        val replacement = ByteArray(32) { 9 }
        val saves = AtomicInteger()
        val connected = CountDownLatch(1)
        val peer = Peer()
        val keys = object : SppPairingKeyStore {
            override fun load() = retained
            override fun save(key: ByteArray): Boolean { saves.incrementAndGet(); return false }
        }
        val audio = GlassesAudioRelayServer(keys, { workers.execute(it) }, { _, _ -> ({}) }, { 0L }) { send, close ->
            connected.countDown()
            GlassesAudioRelaySession({ error("No start received") }, send, close, { workers.execute(it) }, { _, _ -> ({}) }, { 0L })
        }
        val bus = AuthenticatedSppServer(keys, { workers.execute(it) }, { _, _ -> ({}) }, { 0L }, {}, {})
        try {
            bus.installKey(replacement)
            assertEquals(1, saves.get())
            audio.installKey(replacement)
            audio.accept(peer)
            SppAuthProtocol.connect(peer.phoneInput, peer.phoneOutput, retained, SppAuthProtocol.RecentNonces())
            assertTrue(connected.await(2, TimeUnit.SECONDS))
            // A repeated failed offer must also leave an already authenticated audio peer alive.
            audio.installKey(replacement)
            assertEquals(1L, peer.closed.count)
            assertArrayEquals(retained, keys.load())
        } finally {
            peer.close(); workers.shutdownNow()
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}
