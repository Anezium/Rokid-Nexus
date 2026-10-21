package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesError
import com.anezium.rokidbus.shared.GlassesFilesLane
import com.anezium.rokidbus.shared.GlassesFilesResult
import com.anezium.rokidbus.shared.GlassesFilesSendDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class GlassesFilesFallbackTest {
    @get:Rule val temp = TemporaryFolder()
    private val io = Executors.newCachedThreadPool()
    private val id = "files_0123456789abcdef"

    @After
    fun tearDown() {
        io.shutdownNow()
    }

    @Test
    fun `upload lane cut after handshake before activation resumes its bus snapshot`() {
        val source = ByteArray(200_017) { (it * 17).toByte() }
        val root = temp.newFolder()
        val upload = GlassesFileStore(root, JvmFsPrimitives()) { Long.MAX_VALUE / 4 }
            .beginUpload("", "pending.bin", source.size.toLong(), id).getOrThrow()
        val key = GlassesFilesLane.newKey()
        val lane = lane(key)
        val active = GlassesFilesUpload(id, upload, lane, 0)
        assertNull(active.write(0, source.copyOfRange(0, 65_536), viaLane = false))
        val accept = io.submit<Boolean> { lane.accept() }
        connect(lane, key).close()
        assertTrue(accept.get(5, TimeUnit.SECONDS))
        lane.close()
        assertTrue(lane.isProven)
        assertFalse(active.resync.laneOpened)
        assertEquals(65_536L, active.beginResync(1))
        assertNull(active.upgradeToLane())
        assertTrue(active.resync.confirm(65_536))
        assertNull(active.write(65_536, source.copyOfRange(65_536, source.size), viaLane = false))
        assertNull(upload.finish(sha(source)))
        assertArrayEquals(source, upload.finalFile().readBytes())
    }

    @Test
    fun `download lane cut after handshake before activation finishes on bus with the right bytes`() {
        val source = ByteArray(200_017) { (it * 31).toByte() }
        val file = temp.newFile().apply { writeBytes(source) }
        val key = GlassesFilesLane.newKey()
        val lane = lane(key)
        val receiver = ByteArrayOutputStream()
        val results = java.util.concurrent.CopyOnWriteArrayList<GlassesFilesResult>()
        var injected = false
        lateinit var transfer: GlassesFilesDownload
        transfer = GlassesFilesDownload(id, file, null, io, {
            results.add(it)
            if (it is GlassesFilesResult.LaneResync) transfer.onResume(id, 16_384)
            true
        }, { offset, bytes ->
            assertEquals(receiver.size().toLong(), offset)
            if (!injected) {
                injected = true
                receiver.write(bytes, 0, 16_384)
                assertTrue(transfer.offerHotspotLane(lane))
                connect(lane, key).use {
                    lane.close()
                    transfer.upgradeToLane(id, 16_384)
                }
            } else {
                receiver.write(bytes)
                transfer.onAck(id, receiver.size().toLong())
            }
            true
        }, {}, ::now)
        val worker = io.submit { transfer.stream() }
        try {
            worker.get(5, TimeUnit.SECONDS)
            assertTrue(injected)
            assertTrue(results.any { it == GlassesFilesResult.BusResume(id, 16_384) })
            assertFalse(results.any { it is GlassesFilesResult.TransferFailed || it is GlassesFilesResult.LaneResume })
            assertArrayEquals(source, receiver.toByteArray())
            assertEquals(sha(source), results.filterIsInstance<GlassesFilesResult.DownloadEnd>().single().sha256)
        } finally {
            transfer.cancelAll()
        }
    }

    @Test
    fun `upload resumes at staging beyond its last ack and ignores a queued late lane frame`() {
        val root = temp.newFolder()
        val source = ByteArray(900_007) { (it * 17).toByte() }
        val store = GlassesFileStore(root, JvmFsPrimitives()) { Long.MAX_VALUE / 4 }
        val upload = store.beginUpload("", "resume.bin", source.size.toLong(), id).getOrThrow()
        val active = GlassesFilesUpload(id, upload, null, 0)
        active.resync.opened()
        val staged = 262_144
        assertNull(active.write(0, source.copyOfRange(0, staged), viaLane = true))
        val sender = GlassesFilesSendDigest()
        sender.record(source, staged * 2)

        assertEquals(staged.toLong(), active.beginResync(1_000))
        assertNull(active.write(staged.toLong(), byteArrayOf(99, 98), viaLane = true))
        assertEquals(staged.toLong(), upload.stagedBytes)
        assertEquals(staged.toLong(), active.beginResync(2_000))
        assertTrue(active.resync.confirm(staged.toLong()))
        sender.rewind(staged.toLong())
        while (sender.position < source.size) {
            val count = minOf(sender.readLimit(GlassesFilesContract.CHUNK_BYTES), source.size - sender.position.toInt())
            val bytes = source.copyOfRange(sender.position.toInt(), sender.position.toInt() + count)
            if (!sender.replaying) assertNull(active.write(sender.position, bytes, viaLane = false))
            sender.record(bytes, count)
        }

        assertNull(upload.finish(sender.finish()))
        assertArrayEquals(source, upload.finalFile().readBytes())
        assertFalse(root.listFiles()!!.any { it.name.endsWith(GlassesFilesContract.PARTIAL_SUFFIX) })
    }

    @Test
    fun `bus upload upgrades at completed staging and finishes with the original digest`() {
        val source = ByteArray(900_007) { (it * 17).toByte() }
        val root = temp.newFolder()
        val store = GlassesFileStore(root, JvmFsPrimitives()) { Long.MAX_VALUE / 4 }
        val upload = store.beginUpload("", "upgrade.bin", source.size.toLong(), id).getOrThrow()
        val active = GlassesFilesUpload(id, upload, null, 0)
        val staged = 65_536
        assertNull(active.write(0, source.copyOfRange(0, staged), viaLane = false))
        assertEquals(staged.toLong(), active.upgradeToLane())
        assertNull(active.write(staged.toLong(), byteArrayOf(99, 98), viaLane = false))
        assertEquals(staged.toLong(), upload.stagedBytes)
        assertEquals(staged.toLong(), active.upgradeToLane())
        assertFalse(active.confirmLane(staged + 1L))
        assertTrue(active.confirmLane(staged.toLong()))
        val sender = GlassesFilesSendDigest()
        sender.record(source, staged + 100)
        sender.rewind(staged.toLong())
        while (sender.position < source.size) {
            val count = minOf(sender.readLimit(GlassesFilesLane.CHUNK_BYTES), source.size - sender.position.toInt())
            val bytes = source.copyOfRange(sender.position.toInt(), sender.position.toInt() + count)
            if (!sender.replaying) assertNull(active.write(sender.position, bytes, viaLane = true))
            sender.record(bytes, count)
        }
        assertNull(upload.finish(sender.finish()))
        assertArrayEquals(source, upload.finalFile().readBytes())
    }

    @Test
    fun `unconfirmed upload expires without duplicate extension and retirement removes staging`() {
        val root = temp.newFolder()
        val store = GlassesFileStore(root, JvmFsPrimitives()) { Long.MAX_VALUE / 4 }
        val upload = store.beginUpload("", "timeout.bin", 12, id).getOrThrow()
        val active = GlassesFilesUpload(id, upload, null, 0)
        active.resync.opened()
        active.write(0, byteArrayOf(1, 2), viaLane = true)
        active.beginResync(100)
        active.beginResync(29_000)
        assertTrue(active.resync.expired(30_100))
        active.retire()
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun `resumed upload still rejects a changed source digest`() {
        val root = temp.newFolder()
        val store = GlassesFileStore(root, JvmFsPrimitives()) { Long.MAX_VALUE / 4 }
        val upload = store.beginUpload("", "changed.bin", 4, id).getOrThrow()
        val active = GlassesFilesUpload(id, upload, null, 0)
        active.resync.opened()
        active.write(0, byteArrayOf(1, 2), viaLane = true)
        active.beginResync(1)
        active.resync.confirm(2)
        active.write(2, byteArrayOf(3, 4), viaLane = false)
        assertEquals(GlassesFilesError.INTEGRITY, upload.finish(sha(byteArrayOf(9, 2, 3, 4))))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun `download loses in-flight lane bytes then rewinds to the phone snapshot and finishes over bus`() {
        val source = ByteArray(6 * 1024 * 1024 + 17) { (it * 31).toByte() }
        val file = temp.newFile().apply { writeBytes(source) }
        val key = GlassesFilesLane.newKey()
        val lane = lane(key)
        val results = LinkedBlockingQueue<GlassesFilesResult>()
        val receiver = ByteArrayOutputStream()
        val resumed = AtomicLong(-1)
        val summaries = java.util.concurrent.CopyOnWriteArrayList<String>()
        lateinit var transfer: GlassesFilesDownload
        transfer = GlassesFilesDownload(id, file, lane, io, { results.add(it); true }, { offset, bytes ->
            assertEquals(receiver.size().toLong(), offset)
            assertTrue(bytes.size <= GlassesFilesContract.CHUNK_BYTES)
            if (resumed.compareAndSet(-1, offset)) {
                // A duplicate snapshot must not rewind the source a second time.
                transfer.onResume(id, offset)
                transfer.onAck(id, source.size.toLong(), viaLane = true)
            }
            receiver.write(bytes)
            transfer.onAck(id, receiver.size().toLong())
            true
        }, {}, ::now, summaries::add)
        val worker = io.submit { transfer.stream() }
        assertTrue(results.poll(5, TimeUnit.SECONDS) is GlassesFilesResult.DownloadReady)
        connect(lane, key).use { phone ->
            val first = phone.read() as GlassesFilesLane.Frame.Data
            receiver.write(first.bytes)
            assertTrue(phone.read() is GlassesFilesLane.Frame.Data)
        }
        assertTrue(results.poll(5, TimeUnit.SECONDS) is GlassesFilesResult.LaneResync)
        val staged = receiver.size().toLong()
        assertFalse(worker.isDone)
        transfer.onResume(id, staged)
        worker.get(10, TimeUnit.SECONDS)
        val messages = results.toList()
        assertEquals(staged, resumed.get())
        assertArrayEquals(source, receiver.toByteArray())
        assertEquals(sha(source), messages.filterIsInstance<GlassesFilesResult.DownloadEnd>().single().sha256)
        assertTrue(messages.any { it == GlassesFilesResult.BusResume(id, staged) })
        assertFalse(messages.any { it is GlassesFilesResult.TransferFailed })
        assertTrue(summaries.contains("files lane connected"))
        assertTrue(summaries.contains("files download bus resume staged=$staged"))
        assertTrue(summaries.contains("files download done size=${source.size}"))
    }

    @Test
    fun `running bus download upgrades to a proven lane at the receiver snapshot`() {
        val source = ByteArray(6 * 1024 * 1024 + 17) { (it * 31).toByte() }
        val file = temp.newFile().apply { writeBytes(source) }
        val results = LinkedBlockingQueue<GlassesFilesResult>()
        val receiver = ByteArrayOutputStream()
        val receiverLock = Any()
        val firstBus = java.util.concurrent.CountDownLatch(1)
        val resumeSender = java.util.concurrent.CountDownLatch(1)
        var frozen = false
        lateinit var transfer: GlassesFilesDownload
        transfer = GlassesFilesDownload(id, file, null, io, { results.add(it); true }, { offset, bytes ->
            synchronized(receiverLock) {
                if (!frozen) {
                    assertEquals(receiver.size().toLong(), offset)
                    receiver.write(bytes)
                    transfer.onAck(id, receiver.size().toLong())
                    firstBus.countDown()
                }
            }
            if (offset == 0L) assertTrue(resumeSender.await(5, TimeUnit.SECONDS))
            true
        }, {}, ::now)
        val worker = io.submit { transfer.stream() }
        assertTrue(firstBus.await(5, TimeUnit.SECONDS))
        val key = GlassesFilesLane.newKey()
        val lane = lane(key)
        assertTrue(transfer.offerHotspotLane(lane))
        connect(lane, key).use { phone ->
            val staged = synchronized(receiverLock) { frozen = true; receiver.size().toLong() }
            assertTrue(staged in 1 until source.size.toLong())
            transfer.upgradeToLane(id, staged)
            resumeSender.countDown()
            var laneBytes = 0L
            while (receiver.size() < source.size) {
                val frame = phone.read() as GlassesFilesLane.Frame.Data
                assertEquals(receiver.size().toLong(), frame.offset)
                receiver.write(frame.bytes)
                laneBytes += frame.bytes.size
                phone.ack(receiver.size().toLong())
            }
            worker.get(10, TimeUnit.SECONDS)
            assertEquals(source.size.toLong() - staged, laneBytes)
            assertTrue(results.contains(GlassesFilesResult.LaneResume(id, staged)))
            assertTrue(results.contains(GlassesFilesResult.DownloadEnd(id, sha(source))))
            assertFalse(results.any { it is GlassesFilesResult.TransferFailed })
            assertArrayEquals(source, receiver.toByteArray())
        }
    }

    @Test
    fun `download resync expires when the phone never states its offset`() {
        val file = temp.newFile().apply { writeBytes(ByteArray(5 * 1024 * 1024)) }
        val key = GlassesFilesLane.newKey()
        val lane = lane(key)
        val clock = AtomicLong(1_000)
        val results = LinkedBlockingQueue<GlassesFilesResult>()
        val transfer = GlassesFilesDownload(id, file, lane, io, { results.add(it); true }, { _, _ -> false }, {}, clock::get)
        val worker = io.submit { transfer.stream() }
        results.poll(5, TimeUnit.SECONDS)
        connect(lane, key).use { it.read() }
        assertTrue(results.poll(5, TimeUnit.SECONDS) is GlassesFilesResult.LaneResync)
        clock.addAndGet(GlassesFilesContract.IDLE_TIMEOUT_MS)
        worker.get(3, TimeUnit.SECONDS)
        assertEquals(GlassesFilesResult.TransferFailed(id, GlassesFilesError.TIMEOUT), results.poll(1, TimeUnit.SECONDS))
    }

    @Test
    fun `download lane EOF after the full acknowledgement needs no resync`() {
        val source = ByteArray(100_003) { it.toByte() }
        val file = temp.newFile().apply { writeBytes(source) }
        val key = GlassesFilesLane.newKey()
        val lane = lane(key)
        val results = LinkedBlockingQueue<GlassesFilesResult>()
        val transfer = GlassesFilesDownload(id, file, lane, io, { results.add(it); true }, { _, _ -> error("unexpected bus data") }, {}, ::now)
        val worker = io.submit { transfer.stream() }
        results.poll(5, TimeUnit.SECONDS)
        connect(lane, key).use { phone ->
            val frame = phone.read() as GlassesFilesLane.Frame.Data
            assertArrayEquals(source, frame.bytes)
            phone.ack(source.size.toLong())
        }
        worker.get(5, TimeUnit.SECONDS)
        assertEquals(listOf(GlassesFilesResult.DownloadEnd(id, sha(source))), results.toList())
    }

    @Test
    fun `cancel during download resync closes the worker and ignores a late offset`() {
        val file = temp.newFile().apply { writeBytes(ByteArray(5 * 1024 * 1024)) }
        val key = GlassesFilesLane.newKey()
        val lane = lane(key)
        val results = LinkedBlockingQueue<GlassesFilesResult>()
        val transfer = GlassesFilesDownload(id, file, lane, io, { results.add(it); true }, { _, _ -> error("cancelled") }, {}, ::now)
        val worker = io.submit { transfer.stream() }
        results.poll(5, TimeUnit.SECONDS)
        connect(lane, key).use { it.read() }
        assertTrue(results.poll(5, TimeUnit.SECONDS) is GlassesFilesResult.LaneResync)
        transfer.cancelAll()
        transfer.onResume(id, 0)
        worker.get(3, TimeUnit.SECONDS)
        assertTrue(results.isEmpty())
    }

    private fun lane(key: ByteArray) = GlassesFilesLaneServer(id,
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()), key, null, acceptTimeoutMs = 5_000)

    private fun connect(lane: GlassesFilesLaneServer, key: ByteArray): Phone {
        val socket = Socket(InetAddress.getLoopbackAddress(), lane.endpoint.port)
        socket.soTimeout = 5_000
        val phone = Phone(socket, key)
        GlassesFilesLane.writeHello(phone.output, id, key)
        assertEquals(GlassesFilesLane.ANSWER, phone.read())
        return phone
    }

    private inner class Phone(val socket: Socket, key: ByteArray) : AutoCloseable {
        val output = DataOutputStream(socket.getOutputStream())
        private val input = DataInputStream(socket.getInputStream())
        private val opener = GlassesFilesLane.Opener(key, id, GlassesFilesLane.Direction.GLASSES_TO_PHONE)
        private val sealer = GlassesFilesLane.Sealer(key, id, GlassesFilesLane.Direction.PHONE_TO_GLASSES)
        fun read() = opener.read(input)
        fun ack(staged: Long) = sealer.write(output, GlassesFilesLane.Frame.Ack(staged))
        override fun close() = socket.close()
    }

    private fun sha(bytes: ByteArray) = GlassesFilesContract.hex(GlassesFilesContract.newDigest().apply { update(bytes) })
    private fun now() = System.nanoTime() / 1_000_000
}
