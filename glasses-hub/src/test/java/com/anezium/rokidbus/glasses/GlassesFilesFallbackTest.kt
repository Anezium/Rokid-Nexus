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
import java.io.InputStream
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
    fun `upload snapshot rejects old bus writes and admits resumed bytes before confirmation`() {
        val store = GlassesFileStore(temp.newFolder(), JvmFsPrimitives()) { Long.MAX_VALUE / 4 }
        val upload = store.beginUpload("", "snapshot.bin", 3, id).getOrThrow()
        val active = GlassesFilesUpload(id, upload, null, 0)
        active.resync.opened()
        assertNull(active.write(0, byteArrayOf(1), viaLane = true))
        assertEquals(1L, active.beginResync(1))
        assertNull(active.write(1, byteArrayOf(2, 3), viaLane = false))
        assertEquals(1L, upload.stagedBytes)
        assertNull(active.write(1, byteArrayOf(2, 3), viaLane = false, busEpoch = 1))
        assertEquals(3L, upload.stagedBytes)
        assertTrue(active.resync.confirm(1))
        assertNull(active.write(1, byteArrayOf(99), viaLane = false))
        assertEquals(3L, upload.stagedBytes)
        assertNull(upload.finish(sha(byteArrayOf(1, 2, 3))))
        assertArrayEquals(byteArrayOf(1, 2, 3), upload.finalFile().readBytes())
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
        assertNull(active.write(65_536, source.copyOfRange(65_536, source.size), viaLane = false, busEpoch = 1))
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
        }, { offset, bytes, _ ->
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
        sender.rewind(staged.toLong(), now = 0)
        while (sender.position < source.size) {
            val count = minOf(sender.readLimit(GlassesFilesContract.CHUNK_BYTES), source.size - sender.position.toInt())
            val bytes = source.copyOfRange(sender.position.toInt(), sender.position.toInt() + count)
            if (!sender.replaying) assertNull(active.write(sender.position, bytes, viaLane = false, busEpoch = 1))
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
        sender.rewind(staged.toLong(), now = 0)
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
        active.write(2, byteArrayOf(3, 4), viaLane = false, busEpoch = 1)
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
        transfer = GlassesFilesDownload(id, file, lane, io, { results.add(it); true }, { offset, bytes, _ ->
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
        transfer = GlassesFilesDownload(id, file, null, io, { results.add(it); true }, { offset, bytes, _ ->
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
    fun `camera takeover closes an unproven download hotspot lane and leaves the bus stream alone`() {
        val source = ByteArray(3 * GlassesFilesContract.CHUNK_BYTES + 11) { (it * 13).toByte() }
        val file = temp.newFile().apply { writeBytes(source) }
        val lane = lane(GlassesFilesLane.newKey())
        val results = java.util.concurrent.CopyOnWriteArrayList<GlassesFilesResult>()
        val receiver = ByteArrayOutputStream()
        val epochs = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val listeningAfterTakeover = java.util.concurrent.atomic.AtomicBoolean(true)
        lateinit var transfer: GlassesFilesDownload
        transfer = GlassesFilesDownload(id, file, null, io, { results.add(it); true }, { offset, bytes, epoch ->
            epochs += epoch
            if (offset == 0L) {
                assertTrue(transfer.offerHotspotLane(lane))
                transfer.onCameraTakeover()
                // Probed here: the stream's own exit closes every lane later anyway.
                listeningAfterTakeover.set(runCatching { Socket(InetAddress.getLoopbackAddress(), lane.endpoint.port).close() }.isSuccess)
            }
            assertEquals(receiver.size().toLong(), offset)
            receiver.write(bytes)
            transfer.onAck(id, receiver.size().toLong())
            true
        }, {}, ::now)
        io.submit { transfer.stream() }.get(5, TimeUnit.SECONDS)
        assertFalse("the hotspot lane kept listening after the camera took the Wi-Fi", listeningAfterTakeover.get())
        assertFalse(lane.isConnected())
        assertEquals(List(4) { 0 }, epochs.toList())
        assertEquals(listOf(GlassesFilesResult.DownloadEnd(id, sha(source))), results.filter { it !is GlassesFilesResult.DownloadReady })
        assertArrayEquals(source, receiver.toByteArray())
    }

    @Test
    fun `download bus submission failure terminates without resync`() = downloadBusFailure(resume = false)

    @Test
    fun `download bus submission failure stays terminal after resync`() = downloadBusFailure(resume = true)

    private fun downloadBusFailure(resume: Boolean) {
        val file = temp.newFile().apply { writeBytes(ByteArray(90_017)) }
        val key = GlassesFilesLane.newKey()
        val lane = if (resume) lane(key) else null
        val results = java.util.concurrent.CopyOnWriteArrayList<GlassesFilesResult>()
        val epochs = java.util.concurrent.CopyOnWriteArrayList<Int>()
        lateinit var transfer: GlassesFilesDownload
        transfer = GlassesFilesDownload(id, file, null, io, { results.add(it); true }, { _, _, epoch ->
            epochs += epoch
            if (lane != null && epochs.size == 1) {
                assertTrue(transfer.offerHotspotLane(lane))
                connect(lane, key).use {
                    lane.close()
                    transfer.requestResync(id)
                    transfer.onResume(id, 0)
                }
            }
            false
        }, {}, ::now)
        try {
            io.submit { transfer.stream() }.get(5, TimeUnit.SECONDS)
            assertEquals(if (resume) listOf(0, 0, 0, 1, 1, 1) else listOf(0, 0, 0), epochs.toList())
            assertEquals(listOf(GlassesFilesResult.TransferFailed(id, GlassesFilesError.LINK_LOST)),
                results.filterIsInstance<GlassesFilesResult.TransferFailed>())
            assertFalse(results.any { it is GlassesFilesResult.DownloadEnd })
        } finally {
            transfer.cancelAll()
        }
    }

    @Test
    fun `download resync expires when the phone never states its offset`() {
        val file = temp.newFile().apply { writeBytes(ByteArray(5 * 1024 * 1024)) }
        val key = GlassesFilesLane.newKey()
        val lane = lane(key)
        val clock = AtomicLong(1_000)
        val results = LinkedBlockingQueue<GlassesFilesResult>()
        val transfer = GlassesFilesDownload(id, file, lane, io, { results.add(it); true }, { _, _, _ -> false }, {}, clock::get)
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
        val transfer = GlassesFilesDownload(id, file, lane, io, { results.add(it); true }, { _, _, _ -> error("unexpected bus data") }, {}, ::now)
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
        val transfer = GlassesFilesDownload(id, file, lane, io, { results.add(it); true }, { _, _, _ -> error("cancelled") }, {}, ::now)
        val worker = io.submit { transfer.stream() }
        results.poll(5, TimeUnit.SECONDS)
        connect(lane, key).use { it.read() }
        assertTrue(results.poll(5, TimeUnit.SECONDS) is GlassesFilesResult.LaneResync)
        transfer.cancelAll()
        transfer.onResume(id, 0)
        worker.get(3, TimeUnit.SECONDS)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `download resync begun after the full acknowledgement sends the terminator without a snapshot`() {
        val source = ByteArray(100_003) { (it * 7).toByte() }
        val file = temp.newFile().apply { writeBytes(source) }
        val key = GlassesFilesLane.newKey()
        val lane = lane(key)
        val results = LinkedBlockingQueue<GlassesFilesResult>()
        val transfer = GlassesFilesDownload(id, file, lane, io, { results.add(it); true }, { _, _, _ -> error("unexpected bus data") }, {}, ::now)
        val worker = io.submit { transfer.stream() }
        results.poll(5, TimeUnit.SECONDS)
        connect(lane, key).use { phone ->
            assertArrayEquals(source, (phone.read() as GlassesFilesLane.Frame.Data).bytes)
            awaitTerminatorWait()
            // The camera takes the Wi-Fi in the same instant the last ack lands: both land under
            // the transfer lock before the worker can look.
            synchronized(field(transfer, "lock")!!) {
                transfer.onAck(id, source.size.toLong(), viaLane = true)
                transfer.requestResync(id)
            }
        }
        worker.get(5, TimeUnit.SECONDS)
        assertEquals(listOf(GlassesFilesResult.LaneResync(id), GlassesFilesResult.DownloadEnd(id, sha(source))), results.toList())
    }

    @Test
    fun `download snapshot at the full size after the final ack ends without resending`() {
        val source = ByteArray(100_003) { (it * 7).toByte() }
        val file = temp.newFile().apply { writeBytes(source) }
        val key = GlassesFilesLane.newKey()
        val lane = lane(key)
        val results = LinkedBlockingQueue<GlassesFilesResult>()
        val transfer = GlassesFilesDownload(id, file, lane, io, { results.add(it); true }, { _, _, _ -> error("unexpected bus data") }, {}, ::now)
        val worker = io.submit { transfer.stream() }
        results.poll(5, TimeUnit.SECONDS)
        connect(lane, key).use { phone ->
            assertArrayEquals(source, (phone.read() as GlassesFilesLane.Frame.Data).bytes)
            awaitTerminatorWait()
            synchronized(field(transfer, "lock")!!) {
                transfer.onAck(id, source.size.toLong(), viaLane = true)
                // The phone lost its lane first and already states that it holds every byte.
                transfer.onResume(id, source.size.toLong())
            }
        }
        worker.get(5, TimeUnit.SECONDS)
        assertEquals(listOf(GlassesFilesResult.LaneResync(id), GlassesFilesResult.BusResume(id, source.size.toLong()),
            GlassesFilesResult.DownloadEnd(id, sha(source))), results.toList())
    }

    @Test
    fun `cancel during a failing bus send ends the download quietly`() {
        val file = temp.newFile().apply { writeBytes(ByteArray(100_003)) }
        val results = LinkedBlockingQueue<GlassesFilesResult>()
        lateinit var transfer: GlassesFilesDownload
        transfer = GlassesFilesDownload(id, file, null, io, { results.add(it); true }, { _, _, _ ->
            transfer.cancelAll()
            false
        }, {}, ::now)
        io.submit { transfer.stream() }.get(5, TimeUnit.SECONDS)
        assertTrue(results.poll(1, TimeUnit.SECONDS) is GlassesFilesResult.DownloadReady)
        assertTrue(results.toList().toString(), results.isEmpty())
    }

    @Test
    fun `upload terminator after a camera takeover with every byte staged publishes`() {
        val source = ByteArray(70_001) { (it * 13).toByte() }
        val root = temp.newFolder()
        val upload = GlassesFileStore(root, JvmFsPrimitives()) { Long.MAX_VALUE / 4 }
            .beginUpload("", "camera.bin", source.size.toLong(), id).getOrThrow()
        val key = GlassesFilesLane.newKey()
        val lane = lane(key)
        val active = GlassesFilesUpload(id, upload, lane, 0)
        val accept = io.submit<Boolean> { lane.accept() }
        val phone = connect(lane, key)
        assertTrue(accept.get(5, TimeUnit.SECONDS))
        active.resync.opened()
        val staged = active.onChunk(0, source, viaLane = true, busEpoch = 0, now = 1) as GlassesFilesUpload.Chunk.Staged
        assertEquals(source.size.toLong(), staged.ack)
        active.ackSent(source.size.toLong())
        assertEquals(GlassesFilesUpload.Signal.BusResume(source.size.toLong(), first = true), active.onCameraTakeover(2))
        assertTrue(active.resync.switched)
        assertFalse(active.resync.confirmed)
        assertNull(active.finish(sha(source)))
        assertArrayEquals(source, upload.finalFile().readBytes())
        phone.close()
    }

    @Test
    fun `upload terminator with bytes missing still fails on integrity`() {
        val root = temp.newFolder()
        val upload = GlassesFileStore(root, JvmFsPrimitives()) { Long.MAX_VALUE / 4 }
            .beginUpload("", "short.bin", 4, id).getOrThrow()
        val active = GlassesFilesUpload(id, upload, null, 0)
        active.resync.opened()
        assertNull(active.write(0, byteArrayOf(1, 2), viaLane = true))
        assertEquals(2L, active.beginResync(1))
        assertEquals(GlassesFilesError.INTEGRITY, active.finish(sha(byteArrayOf(1, 2, 3, 4))))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun `the resync confirmation from the phone counts as upload activity`() {
        val upload = GlassesFileStore(temp.newFolder(), JvmFsPrimitives()) { Long.MAX_VALUE / 4 }
            .beginUpload("", "idle.bin", 12, id).getOrThrow()
        val active = GlassesFilesUpload(id, upload, null, 0)
        active.resync.opened()
        assertNull(active.write(0, byteArrayOf(1, 2), viaLane = true))
        assertEquals(2L, active.beginResync(1_000))
        assertTrue(active.onBusResume(2, 20_000))
        assertFalse(active.onBusResume(3, 20_000))
        assertEquals(GlassesFilesUpload.Tick.Idle, active.onTick(40_000))
        assertEquals(GlassesFilesUpload.Tick.Expired, active.onTick(50_001))
    }

    @Test
    fun `a repeated lane resume echo counts as upload activity while the phone rehashes`() {
        val upload = GlassesFileStore(temp.newFolder(), JvmFsPrimitives()) { Long.MAX_VALUE / 4 }
            .beginUpload("", "upgrade.bin", 1_000_000, id).getOrThrow()
        val key = GlassesFilesLane.newKey()
        val lane = lane(key)
        val active = GlassesFilesUpload(id, upload, lane, 0)
        assertNull(active.write(0, ByteArray(65_536), viaLane = false))
        val accept = io.submit<Boolean> { lane.accept() }
        connect(lane, key).use { phone ->
            assertTrue(accept.get(5, TimeUnit.SECONDS))
            assertEquals(GlassesFilesUpload.Signal.LaneResume(65_536), active.onLaneSwitch(1_000))
            // The phone echoes, then rehashes the prefix; it repeats the echo every 10 s meanwhile.
            assertTrue(active.onLaneResume(65_536, 2_000))
            assertTrue(active.onLaneResume(65_536, 12_000))
            assertTrue(active.onLaneResume(65_536, 22_000))
            assertTrue(active.onLaneResume(65_536, 32_000))
            assertEquals(GlassesFilesUpload.Tick.Idle, active.onTick(45_000))
            assertTrue(lane.isConnected())
            assertFalse(active.onLaneResume(65_537, 46_000))
            // Thirty seconds after the last echo the idle lane resyncs as before.
            assertTrue(active.onTick(62_000) is GlassesFilesUpload.Tick.Resume)
            phone.close()
        }
    }

    @Test
    fun `a progressing download prefix replay repeats the bus resume echo`() {
        val source = ByteArray(300_003) { (it * 7).toByte() }
        val file = temp.newFile().apply { writeBytes(source) }
        val key = GlassesFilesLane.newKey()
        val lane = lane(key)
        val clock = AtomicLong(1_000)
        val replayBelow = AtomicLong(0)
        val results = LinkedBlockingQueue<GlassesFilesResult>()
        // Every 16 KiB of the rehashed prefix costs 4 s of clock: a slow provider that never stalls.
        val transfer = GlassesFilesDownload(id, file, lane, io, { results.add(it); true }, { _, _, _ -> true }, {}, clock::get,
            openSource = { SlowPrefix(it.inputStream(), replayBelow::get, 16_384) { clock.addAndGet(4_000) } })
        val worker = io.submit { transfer.stream() }
        assertTrue(results.poll(5, TimeUnit.SECONDS) is GlassesFilesResult.DownloadReady)
        connect(lane, key).use { phone ->
            assertTrue(phone.read() is GlassesFilesLane.Frame.Data)
            assertTrue(phone.read() is GlassesFilesLane.Frame.Data)
            phone.ack(GlassesFilesLane.CHUNK_BYTES.toLong())
        }
        assertTrue(results.poll(5, TimeUnit.SECONDS) is GlassesFilesResult.LaneResync)
        replayBelow.set(GlassesFilesLane.CHUNK_BYTES.toLong())
        transfer.onResume(id, GlassesFilesLane.CHUNK_BYTES.toLong())
        // The 256 KiB prefix takes 64 s of clock: the echo, then a repeat at 12, 24, 36, 48 and 60 s.
        val echoes = generateSequence { results.poll(5, TimeUnit.SECONDS) }
            .takeWhile { it !is GlassesFilesResult.TransferFailed }
            .filterIsInstance<GlassesFilesResult.BusResume>()
            .take(6).toList()
        assertEquals(List(6) { GlassesFilesResult.BusResume(id, GlassesFilesLane.CHUNK_BYTES.toLong()) }, echoes)
        transfer.cancelAll()
        worker.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `an upload lane switch on a lane that is gone resumes on the bus`() {
        val upload = GlassesFileStore(temp.newFolder(), JvmFsPrimitives()) { Long.MAX_VALUE / 4 }
            .beginUpload("", "switch.bin", 100_000, id).getOrThrow()
        val key = GlassesFilesLane.newKey()
        val lane = lane(key)
        val active = GlassesFilesUpload(id, upload, lane, 0)
        assertNull(active.write(0, ByteArray(32_768), viaLane = false))
        val accept = io.submit<Boolean> { lane.accept() }
        connect(lane, key).close()
        assertTrue(accept.get(5, TimeUnit.SECONDS))
        lane.close()
        assertEquals(GlassesFilesUpload.Signal.BusResume(32_768, first = true), active.onLaneSwitch(5))
        assertEquals(GlassesFilesUpload.Signal.BusResume(32_768, first = false), active.onLaneSwitch(6))
        assertEquals(GlassesFilesUpload.Signal.BusResume(32_768, first = false), active.onLaneEof(7))
        assertEquals(GlassesFilesUpload.Signal.BusResume(32_768, first = false), active.onResyncRequest(8))
    }

    /** Spins until the stream worker is blocked waiting for the terminator's full acknowledgement. */
    private fun awaitTerminatorWait() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val waiting = Thread.getAllStackTraces().entries.any { (thread, frames) ->
                thread.state == Thread.State.TIMED_WAITING && frames.any { it.methodName == "awaitAck" } &&
                    frames.any { it.methodName == "stream" }
            }
            if (waiting) return
            Thread.sleep(1)
        }
        error("stream worker never reached its wait")
    }

    private fun field(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> type.declaredFields.firstOrNull { it.name == name } }
            .firstOrNull()?.apply { isAccessible = true }?.get(target)

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

    /** Reads below [below] come in [step] byte pieces and each costs [onStep]; the rest pass through. */
    private class SlowPrefix(
        private val delegate: InputStream,
        private val below: () -> Long,
        private val step: Int,
        private val onStep: () -> Unit,
    ) : InputStream() {
        private var position = 0L

        override fun read(): Int = delegate.read().also { if (it >= 0) position += 1 }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val slow = below() - position
            if (slow <= 0) return delegate.read(b, off, len).also { if (it > 0) position += it }
            val count = delegate.read(b, off, minOf(len.toLong(), step.toLong(), slow).toInt())
            if (count > 0) {
                position += count
                onStep()
            }
            return count
        }

        override fun close() = delegate.close()
    }
}
