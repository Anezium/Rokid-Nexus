package com.anezium.rokidbus.phone

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import com.anezium.rokidbus.glasses.GlassesFileStore
import com.anezium.rokidbus.glasses.GlassesFilesDownload
import com.anezium.rokidbus.glasses.GlassesFilesLaneServer
import com.anezium.rokidbus.glasses.GlassesFilesUpload
import com.anezium.rokidbus.glasses.JvmFsPrimitives
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.GlassesFilesChunk
import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesLane
import com.anezium.rokidbus.shared.GlassesFilesLaneEndpoint
import com.anezium.rokidbus.shared.GlassesFilesRequest
import com.anezium.rokidbus.shared.GlassesFilesResult
import com.anezium.rokidbus.shared.GlassesFilesResync
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], shadows = [GlassesFilesAnswerLossTest.WifiSocketNetwork::class])
class GlassesFilesAnswerLossTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `upload shared lane dropped answer completes on Bluetooth`() = transfer(upload = true, hotspot = false, truncate = false)
    @Test fun `upload shared lane truncated answer completes on Bluetooth`() = transfer(upload = true, hotspot = false, truncate = true)
    @Test fun `upload hotspot lane dropped answer completes on Bluetooth`() = transfer(upload = true, hotspot = true, truncate = false)
    @Test fun `upload hotspot lane truncated answer completes on Bluetooth`() = transfer(upload = true, hotspot = true, truncate = true)
    @Test fun `download shared lane dropped answer completes on Bluetooth`() = transfer(upload = false, hotspot = false, truncate = false)
    @Test fun `download shared lane truncated answer completes on Bluetooth`() = transfer(upload = false, hotspot = false, truncate = true)
    @Test fun `download hotspot lane dropped answer completes on Bluetooth`() = transfer(upload = false, hotspot = true, truncate = false)
    @Test fun `download hotspot lane truncated answer completes on Bluetooth`() = transfer(upload = false, hotspot = true, truncate = true)
    @Test fun `download held old bus send after lost answer completes with resumed data before confirmation`() = transfer(upload = false, hotspot = true, truncate = false, holdOldSend = true)
    @Test fun `download held old bus send after truncated answer completes with resumed data before confirmation`() = transfer(upload = false, hotspot = true, truncate = true, holdOldSend = true)
    @Test fun `upload held old bus send after lost answer completes after snapshot confirmation`() = transfer(upload = true, hotspot = true, truncate = false, holdOldSend = true)
    @Test fun `upload held old bus send after truncated answer completes after snapshot confirmation`() = transfer(upload = true, hotspot = true, truncate = true, holdOldSend = true)
    @Test fun `upload resumed bus data overtakes snapshot confirmation after lost answer`() = transfer(upload = true, hotspot = true, truncate = false, holdOldSend = true, overtakeUploadConfirmation = true)

    @Test
    @Config(instrumentedPackages = ["com.anezium.rokidbus.phone.GlassesFilesController"],
        shadows = [UploadEpochBoundary::class, ResumeSnapshotBoundary::class])
    fun `upload buffered chunk waits for snapshot and rewind after lost answer`() =
        transfer(upload = true, hotspot = true, truncate = false, pauseBeforeEpoch = true)

    @Test
    @Config(instrumentedPackages = ["com.anezium.rokidbus.phone.GlassesFilesController"],
        shadows = [UploadEpochBoundary::class, ResumeSnapshotBoundary::class])
    fun `upload buffered chunk waits for snapshot and rewind after truncated answer`() =
        transfer(upload = true, hotspot = true, truncate = true, pauseBeforeEpoch = true)

    private fun transfer(upload: Boolean, hotspot: Boolean, truncate: Boolean, holdOldSend: Boolean = false,
        overtakeUploadConfirmation: Boolean = false, pauseBeforeEpoch: Boolean = false) {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        val network = ShadowNetwork.newInstance(71)
        connectivity.addNetwork(network, null)
        val capabilities = NetworkCapabilities()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        connectivity.setNetworkCapabilities(network, capabilities)
        val source = ByteArray(600_017) { (it * 31 + 7).toByte() }
        val input = temp.newFile().apply { writeBytes(source) }
        val output = temp.newFile()
        val provider = DownloadProvider().apply {
            destination = output
            attachInfo(context, ProviderInfo().apply { authority = "media" })
        }
        ShadowContentResolver.registerProviderInternal("media", provider)
        val io = Executors.newCachedThreadPool { Thread(it, "answer-loss-peer").apply { isDaemon = true } }
        val control = Executors.newSingleThreadExecutor { Thread(it, "answer-loss-bus").apply { isDaemon = true } }
        val failure = AtomicReference<Throwable>()
        val declined = CountDownLatch(1)
        val resyncDelivered = CountDownLatch(1)
        val confirmed = CountDownLatch(1)
        val corrupted = CountDownLatch(1)
        val resumedData = CountDownLatch(1)
        val confirmationDelivered = CountDownLatch(1)
        val resyncBegan = CountDownLatch(1)
        val installSnapshot = CountDownLatch(1)
        val pausedBeforeEpoch = AtomicBoolean(false)
        val heldUpload = AtomicReference<BusEnvelope>()
        var pendingUploadConfirmation: GlassesFilesRequest.BusResume? = null
        var oldDownloadDelivered = false
        val messages = CopyOnWriteArrayList<GlassesFilesResult>()
        val requests = CopyOnWriteArrayList<GlassesFilesRequest>()
        val sockets = CopyOnWriteArrayList<java.io.Closeable>()
        lateinit var controller: GlassesFilesController
        lateinit var active: Any
        lateinit var lane: GlassesFilesLaneServer
        lateinit var endpoint: GlassesFilesLaneEndpoint
        var peerUpload: GlassesFilesUpload? = null
        var peerDownload: GlassesFilesDownload? = null
        var offered = false

        fun deliver(result: GlassesFilesResult) {
            messages += result
            val resyncResult = result is GlassesFilesResult.LaneResync || result is GlassesFilesResult.BusResume
            val delivery = {
                if (truncate && resyncResult) check(declined.await(5, TimeUnit.SECONDS))
                assertTrue(controller.handleRemote(BusEnvelope(
                    path = GlassesFilesContract.RESULT_PATH, payload = GlassesFilesContract.encode(result))))
                if (resyncResult) {
                    (field(controller, "inbox") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
                    resyncDelivered.countDown()
                }
            }
            if (holdOldSend && !upload && result is GlassesFilesResult.BusResume) {
                io.execute { checked(failure) {
                    check(resumedData.await(5, TimeUnit.SECONDS))
                    delivery()
                    confirmationDelivered.countDown()
                } }
            } else if (pauseBeforeEpoch && resyncResult || truncate && resyncResult && declined.count > 0) {
                io.execute { checked(failure, delivery) }
            } else delivery()
        }

        fun openLane(id: String) {
            val key = GlassesFilesLane.newKey()
            val loopback = InetAddress.getByName("127.0.0.1")
            lane = GlassesFilesLaneServer(id, ServerSocket(0, 4, loopback), key, null)
            sockets += lane
            val proxy = ServerSocket(0, 1, loopback).also { sockets += it }
            endpoint = lane.endpoint.copy(port = proxy.localPort)
            io.execute { checked(failure) {
                proxy.accept().use { phone ->
                    Socket(loopback, lane.endpoint.port).use { glasses ->
                        sockets += phone
                        sockets += glasses
                        phone.soTimeout = 5_000
                        glasses.soTimeout = 5_000
                        val hello = ByteArray(7 + id.toByteArray(Charsets.UTF_8).size + 32)
                        DataInputStream(phone.getInputStream()).readFully(hello)
                        glasses.getOutputStream().apply { write(hello); flush() }
                        val answerInput = DataInputStream(glasses.getInputStream())
                        val type = answerInput.readByte()
                        val length = answerInput.readInt()
                        val sealed = ByteArray(length).also(answerInput::readFully)
                        val answer = java.io.ByteArrayOutputStream().also { bytes ->
                            DataOutputStream(bytes).apply { writeByte(type.toInt()); writeInt(length); write(sealed) }
                        }.toByteArray()
                        assertEquals(GlassesFilesLane.ANSWER, GlassesFilesLane.Opener(key, id,
                            GlassesFilesLane.Direction.GLASSES_TO_PHONE).read(DataInputStream(ByteArrayInputStream(answer))))
                        if (truncate) phone.getOutputStream().apply { write(answer, 0, answer.size - 1); flush() }
                        glasses.close()
                        // Drop exercises results deferred while connect() waits; truncation exercises
                        // results delivered only after the failed attempt has sent LaneDeclined.
                        if (!truncate) check(resyncDelivered.await(5, TimeUnit.SECONDS))
                        corrupted.countDown()
                    }
                }
            } }
        }

        fun resumeUpload(id: String) {
            peerUpload!!.beginResync(SystemClock.elapsedRealtime())?.let { deliver(GlassesFilesResult.BusResume(id, it)) }
        }

        fun serveUpload(upgrade: Boolean) {
            io.execute { checked(failure) {
                assertTrue(lane.accept())
                control.submit { if (!upgrade) peerUpload!!.resync.opened() }.get(5, TimeUnit.SECONDS)
                try {
                    while (true) lane.read()
                } catch (_: Exception) {
                    control.execute { checked(failure) { resumeUpload(peerUpload!!.transferId) } }
                }
            } }
        }

        fun offerHotspot(id: String) {
            if (!hotspot || offered) return
            offered = true
            openLane(id)
            if (upload) {
                peerUpload!!.lane = lane
                serveUpload(upgrade = true)
            } else assertTrue(peerDownload!!.offerHotspotLane(lane))
            setField(active, "hotspotDeadline", SystemClock.elapsedRealtime() + 25_000L)
            deliver(GlassesFilesResult.HotspotReady(id, endpoint, "127.0.0.1"))
        }

        controller = GlassesFilesController(context, { envelope ->
            if (envelope.binary == null && GlassesFilesContract.parseRequest(envelope.payload) is GlassesFilesRequest.LaneDeclined) declined.countDown()
            val sending = envelope.binary?.let { GlassesFilesContract.parseChunk(envelope.payload, it) }
            if (holdOldSend && sending?.offset == GlassesFilesContract.CHUNK_BYTES.toLong() && heldUpload.compareAndSet(null, envelope)) {
                control.execute { checked(failure) { offerHotspot(sending.transferId) } }
                check(corrupted.await(10, TimeUnit.SECONDS))
                check(resyncDelivered.await(5, TimeUnit.SECONDS))
                null
            } else {
                control.execute { checked(failure) {
                    val bytes = envelope.binary
                    if (bytes != null) {
                        val chunk = GlassesFilesContract.parseChunk(envelope.payload, bytes)!!
                        assertNull(peerUpload!!.write(chunk.offset, bytes, viaLane = false, busEpoch = chunk.busEpoch))
                        pendingUploadConfirmation?.let { confirmation ->
                            assertFalse(peerUpload!!.resync.confirmed)
                            assertEquals(chunk.offset + bytes.size, peerUpload!!.upload.stagedBytes)
                            assertTrue(peerUpload!!.resync.confirm(confirmation.stagedBytes))
                            confirmed.countDown()
                            pendingUploadConfirmation = null
                        }
                        deliver(GlassesFilesResult.UploadProgress(chunk.transferId, peerUpload!!.upload.stagedBytes))
                        if (!holdOldSend && !pauseBeforeEpoch) offerHotspot(chunk.transferId)
                    } else when (val request = GlassesFilesContract.parseRequest(envelope.payload)) {
                        is GlassesFilesRequest.UploadBegin -> {
                            if (!hotspot) openLane(request.transferId)
                            val store = GlassesFileStore(temp.newFolder(), JvmFsPrimitives()) { Long.MAX_VALUE / 4 }
                            peerUpload = GlassesFilesUpload(request.transferId,
                                store.beginUpload("", request.name, request.sizeBytes, request.transferId).getOrThrow(),
                                if (hotspot) null else lane, SystemClock.elapsedRealtime())
                            deliver(GlassesFilesResult.UploadReady(request.transferId, request.name, if (hotspot) null else endpoint))
                            if (!hotspot) serveUpload(upgrade = false)
                        }
                        is GlassesFilesRequest.DownloadBegin -> {
                            if (!hotspot) openLane(request.transferId)
                            peerDownload = GlassesFilesDownload(request.transferId, input, if (hotspot) null else lane, io,
                                { result ->
                                    deliver(if (result is GlassesFilesResult.DownloadReady && !hotspot) result.copy(lane = endpoint) else result)
                                    true
                                }, { offset, data, epoch ->
                                    val held = holdOldSend && !oldDownloadDelivered && offset > 0
                                    if (held) {
                                        control.execute { checked(failure) { offerHotspot(request.transferId) } }
                                        check(corrupted.await(10, TimeUnit.SECONDS))
                                        check(resyncDelivered.await(5, TimeUnit.SECONDS))
                                        check(confirmed.await(5, TimeUnit.SECONDS))
                                    }
                                    assertTrue(controller.handleRemote(BusEnvelope(path = GlassesFilesContract.DATA_PATH,
                                        payload = GlassesFilesContract.chunkMeta(GlassesFilesChunk(request.transferId, offset, epoch)), binary = data)))
                                    if (holdOldSend) {
                                        (field(controller, "inbox") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
                                        if (held) oldDownloadDelivered = true
                                        else if (offset > 0 && resumedData.count > 0) {
                                            assertFalse((field(active, "resync") as GlassesFilesResync).confirmed)
                                            resumedData.countDown()
                                            check(confirmationDelivered.await(5, TimeUnit.SECONDS))
                                        }
                                    } else control.execute { checked(failure) { offerHotspot(request.transferId) } }
                                    if (hotspot && !holdOldSend) {
                                        check(corrupted.await(10, TimeUnit.SECONDS))
                                        check(resyncDelivered.await(5, TimeUnit.SECONDS))
                                    }
                                    true
                                }, {}, SystemClock::elapsedRealtime)
                            io.execute { checked(failure) { peerDownload!!.stream() } }
                        }
                        is GlassesFilesRequest.LaneDeclined -> {
                            requests += request
                            peerUpload?.lane?.decline()
                            peerDownload?.declineLane(request.transferId)
                        }
                        is GlassesFilesRequest.LaneResync -> resumeUpload(request.transferId)
                        is GlassesFilesRequest.BusResume -> {
                            requests += request
                            if (upload) {
                                if (overtakeUploadConfirmation) pendingUploadConfirmation = request
                                else assertTrue(peerUpload!!.resync.confirm(request.stagedBytes))
                                heldUpload.get()?.let { old ->
                                    val bytes = old.binary!!
                                    val chunk = GlassesFilesContract.parseChunk(old.payload, bytes)!!
                                    assertNull(peerUpload!!.write(chunk.offset, bytes, viaLane = false, busEpoch = chunk.busEpoch))
                                    assertEquals("old bytes must not advance the confirmed snapshot", request.stagedBytes, peerUpload!!.upload.stagedBytes)
                                }
                                if (!overtakeUploadConfirmation) confirmed.countDown()
                            } else {
                                peerDownload!!.onResume(request.transferId, request.stagedBytes)
                                confirmed.countDown()
                            }
                        }
                        is GlassesFilesRequest.DownloadProgress -> peerDownload!!.onAck(request.transferId, request.stagedBytes)
                        is GlassesFilesRequest.UploadEnd -> {
                            val peer = peerUpload!!
                            assertTrue("upload needs receiver-confirmed resync", peer.resync.confirmed)
                            assertNull(peer.upload.finish(request.sha256))
                            assertEquals(sha(source), request.sha256)
                            deliver(GlassesFilesResult.UploadDone(request.transferId, request.transferId))
                        }
                        else -> Unit
                    }
                } }
                if (hotspot && envelope.binary != null && !holdOldSend && !pauseBeforeEpoch) {
                    check(corrupted.await(10, TimeUnit.SECONDS))
                    check(resyncDelivered.await(5, TimeUnit.SECONDS))
                }
                null
            }
        }, openUpload = { _, _ -> input.inputStream() })
        controller.onLinkStateChanged(controlUp = true, dataUp = true)
        controller.onCapabilities(glassesFilesSupported = true, laneSupported = true)
        val item = GlassesTransferItem("row", if (upload) GlassesTransferDirection.SEND else GlassesTransferDirection.COPY,
            "answer-loss.bin", "", if (upload) Uri.fromFile(input).toString() else "answer-loss.bin", source.size.toLong(),
            state = GlassesTransferState.RUNNING)
        val type = GlassesFilesController::class.java.declaredClasses.single { it.simpleName == if (upload) "ActiveSend" else "ActiveCopy" }
        active = type.declaredConstructors.single { it.parameterCount == 2 }.apply { isAccessible = true }.newInstance(controller, item)
        setField(controller, "active", active)
        @Suppress("UNCHECKED_CAST")
        (field(controller, "transfers") as MutableList<GlassesTransferItem>).add(item)
        if (pauseBeforeEpoch) {
            ResumeSnapshotBoundary.afterBegin = {
                resyncBegan.countDown()
                check(installSnapshot.await(5, TimeUnit.SECONDS))
            }
            UploadEpochBoundary.beforeCapture = { offset, sendChunk ->
                if (offset == GlassesFilesContract.CHUNK_BYTES.toLong() && pausedBeforeEpoch.compareAndSet(false, true)) {
                    // The real worker has passed needsResume(), but has not entered the epoch lock.
                    control.execute { checked(failure) { offerHotspot(field(active, "wireId") as String) } }
                    check(resyncBegan.await(10, TimeUnit.SECONDS))
                    try {
                        val resync = field(active, "resync") as GlassesFilesResync
                        assertTrue(resync.switched)
                        assertNull(resync.offset)
                        assertFalse(resync.confirmed)
                        assertNull(field(active, "resumePending"))
                        val sent = sendChunk()
                        control.submit {}.get(5, TimeUnit.SECONDS)
                        failure.get()?.let { throw it }
                        assertEquals("buffered original bytes must not acquire the resumed epoch",
                            offset, peerUpload!!.upload.stagedBytes)
                        assertFalse("sender must wait for the snapshot and worker rewind", sent)
                        sent
                    } finally {
                        installSnapshot.countDown()
                        check(resyncDelivered.await(5, TimeUnit.SECONDS))
                    }
                } else sendChunk()
            }
        }
        val worker = FutureTask {
            try { call(active, "run") } catch (error: java.lang.reflect.InvocationTargetException) {
                failure.compareAndSet(null, error.targetException)
                throw error
            }
        }
        Thread(worker, "answer-loss-phone").apply { isDaemon = true; start() }
        try {
            assertTrue("proxy must lose only the sealed answer", corrupted.await(10, TimeUnit.SECONDS))
            assertTrue("phone must decline its failed attempt", declined.await(5, TimeUnit.SECONDS))
            if (upload) {
                val didConfirm = confirmed.await(3, TimeUnit.SECONDS)
                failure.get()?.let { throw it }
                assertTrue("upload receiver snapshot was never confirmed", didConfirm)
            }
            val outcome = worker.get(5, TimeUnit.SECONDS)
            failure.get()?.let { throw it }
            assertEquals(outcome.toString(), "Done", outcome!!.javaClass.simpleName)
            val resync = field(active, "resync") as GlassesFilesResync
            assertTrue(resync.confirmed)
            assertFalse(resync.laneOpened)
            val snapshot = requests.filterIsInstance<GlassesFilesRequest.BusResume>().first().stagedBytes
            if (hotspot) assertTrue("hotspot must interrupt an already running bus transfer", snapshot > 0)
            else assertEquals(0L, snapshot)
            if (holdOldSend) {
                assertEquals(GlassesFilesContract.CHUNK_BYTES.toLong(), snapshot)
                if (upload) assertNotNull(heldUpload.get()) else assertTrue(oldDownloadDelivered)
            }
            if (pauseBeforeEpoch) {
                assertTrue(pausedBeforeEpoch.get())
                assertEquals(GlassesFilesContract.CHUNK_BYTES.toLong(), snapshot)
            }
            assertFalse(messages.any { it is GlassesFilesResult.TransferFailed })
            val received = if (upload) peerUpload!!.upload.finalFile().readBytes() else output.readBytes()
            assertArrayEquals(source, received)
            assertEquals(sha(source), sha(received))
            if (!upload) assertEquals(sha(source), messages.filterIsInstance<GlassesFilesResult.DownloadEnd>().single().sha256)
        } finally {
            installSnapshot.countDown()
            controller.close()
            worker.cancel(true)
            peerDownload?.cancelAll()
            sockets.forEach { runCatching { it.close() } }
            io.shutdownNow()
            control.shutdownNow()
            UploadEpochBoundary.beforeCapture = null
            ResumeSnapshotBoundary.afterBegin = null
        }
    }

    @Implements(className = "com.anezium.rokidbus.phone.GlassesFilesController\$ActiveSend", isInAndroidSdk = false)
    class UploadEpochBoundary : ResumeSnapshotBoundary() {
        @Implementation fun sendChunk(offset: Long, bytes: ByteArray, viaLane: Boolean): Boolean {
            val send = { Shadow.directlyOn<Boolean>(real, real.javaClass.name, "sendChunk",
                ClassParameter.from(Long::class.javaPrimitiveType!!, offset),
                ClassParameter.from(ByteArray::class.java, bytes),
                ClassParameter.from(Boolean::class.javaPrimitiveType!!, viaLane)) }
            return beforeCapture?.invoke(offset, send) ?: send()
        }

        companion object {
            var beforeCapture: ((Long, () -> Boolean) -> Boolean)? = null
        }
    }

    @Implements(className = "com.anezium.rokidbus.phone.GlassesFilesController\$ActiveTransfer", isInAndroidSdk = false)
    open class ResumeSnapshotBoundary {
        @RealObject lateinit var real: Any

        @Implementation fun beginResync(): Boolean {
            val started = Shadow.directlyOn<Boolean>(real, real.javaClass.superclass!!.name, "beginResync")
            if (started) afterBegin?.invoke()
            return started
        }

        companion object {
            var afterBegin: (() -> Unit)? = null
        }
    }

    @Implements(Network::class)
    class WifiSocketNetwork : ShadowNetwork() {
        @Implementation fun getSocketFactory(): SocketFactory = SocketFactory.getDefault()
    }

    class DownloadProvider : ContentProvider() {
        lateinit var destination: File
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?) = Uri.withAppendedPath(uri, "1")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 1
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 1
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
            MatrixCursor(arrayOf(MediaStore.Downloads.DISPLAY_NAME)).apply { addRow(arrayOf("answer-loss.bin")) }
        override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(destination,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
    }

    private fun checked(failure: AtomicReference<Throwable>, action: () -> Unit) {
        try { action() } catch (error: Throwable) { failure.compareAndSet(null, error) }
    }

    private fun sha(bytes: ByteArray) = GlassesFilesContract.hex(GlassesFilesContract.newDigest().apply { update(bytes) })
    private fun member(target: Any, name: String): java.lang.reflect.Field =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> type.declaredFields.firstOrNull { it.name == name } }.first().apply { isAccessible = true }
    private fun field(target: Any, name: String): Any? = member(target, name).get(target)
    private fun setField(target: Any, name: String, value: Any?) = member(target, name).set(target, value)
    private fun call(target: Any, name: String): Any? =
        target.javaClass.declaredMethods.first { it.name == name && it.parameterCount == 0 }.apply { isAccessible = true }.invoke(target)
}
