package com.anezium.rokidbus.phone

import android.content.pm.ProviderInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import com.anezium.rokidbus.glasses.GlassesFileStore
import com.anezium.rokidbus.glasses.GlassesFilesDownload
import com.anezium.rokidbus.glasses.GlassesFilesLaneServer
import com.anezium.rokidbus.glasses.GlassesFilesUpload
import com.anezium.rokidbus.glasses.JvmFsPrimitives
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.GlassesFilesChunk
import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesError
import com.anezium.rokidbus.shared.GlassesFilesLane
import com.anezium.rokidbus.shared.GlassesFilesLaneEndpoint
import com.anezium.rokidbus.shared.GlassesFilesRequest
import com.anezium.rokidbus.shared.GlassesFilesResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.time.Duration

/**
 * Drives the real phone controller workers and the real glasses streamer, receiver and lane
 * server through a table of schedules: where the lane is cut, which side notices first, which
 * control event lands at which moment, and whether resync messages repeat. Every schedule must
 * end the way it demands, within a bound far below the idle budget, with exact bytes and digest,
 * and with nothing left open. The lane runs through a loopback proxy that opens every frame.
 * Time is the paused Robolectric clock shared by both ends: it moves only when a replay schedule
 * charges the sender's prefix rehash, so every idle budget is crossed on purpose or not at all.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [32], shadows = [GlassesFilesAnswerLossTest.WifiSocketNetwork::class])
class GlassesFilesScheduleTest(private val schedule: Schedule) {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `the transfer ends as scheduled with exact bytes and nothing left open`() = Harness(schedule, temp).run()

    enum class Direction { UPLOAD, DOWNLOAD }
    enum class LaneKind { NONE, SHARED, HOTSPOT }
    enum class Handshake { OK, DROP, TRUNCATE }

    /** Which end observes the cut first; the proxy holds the other end until the first bus signal has crossed. */
    enum class Detector { PHONE_FIRST, GLASSES_FIRST, BOTH }
    enum class Trigger { NONE, CAMERA, DEADLINE, CANCEL, LINK_DOWN }

    /**
     * Moments of a transfer. As a cut, a lane frame point drops that frame and everything after
     * it; as a trigger moment, the event fires once that frame has been forwarded. The handshake
     * point cuts after the answer has reached the phone, so both ends hold a proven lane.
     */
    enum class Point {
        HANDSHAKE, SWITCH_ASKED, SWITCH_ANSWERED, SWITCH_ECHOED,
        LANE_FRAME_0, LANE_FRAME_1, LANE_FRAME_3, FINAL_ACK, BUS_CHUNK_3, SNAPSHOT,
    }

    data class Schedule(
        val direction: Direction,
        val lane: LaneKind,
        val handshake: Handshake = Handshake.OK,
        val cut: Point? = null,
        val detector: Detector = Detector.BOTH,
        val trigger: Trigger = Trigger.NONE,
        val at: Point? = null,
        val dup: Boolean = false,
        val offerAfterChunk: Int = 1,
        /** The sender's prefix rehash after each rewind outlasts the peer's idle budget. */
        val replay: Boolean = false,
    ) {
        override fun toString(): String = buildString {
            append(direction.name.lowercase()).append(' ').append(lane.name.lowercase())
            if (handshake != Handshake.OK) append(" answer-").append(handshake.name.lowercase())
            if (cut != null) append(" cut@").append(cut.name.lowercase()).append(' ').append(detector.name.lowercase())
            if (trigger != Trigger.NONE) append(' ').append(trigger.name.lowercase()).append('@').append(at!!.name.lowercase())
            if (dup) append(" dup")
            if (offerAfterChunk != 1) append(" offer@").append(offerAfterChunk)
            if (replay) append(" replay")
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun schedules(): List<Array<Any>> = all().map { arrayOf<Any>(it) }

        fun all(): List<Schedule> = buildList {
            for (direction in Direction.values()) {
                add(Schedule(direction, LaneKind.NONE))
                add(Schedule(direction, LaneKind.NONE, trigger = Trigger.CANCEL, at = Point.BUS_CHUNK_3))
                add(Schedule(direction, LaneKind.NONE, trigger = Trigger.LINK_DOWN, at = Point.BUS_CHUNK_3))
                for (lane in listOf(LaneKind.SHARED, LaneKind.HOTSPOT)) {
                    add(Schedule(direction, lane))
                    val cuts = mutableListOf(Point.HANDSHAKE, Point.LANE_FRAME_0, Point.LANE_FRAME_1, Point.LANE_FRAME_3, Point.FINAL_ACK)
                    if (lane == LaneKind.HOTSPOT) {
                        cuts += Point.SWITCH_ASKED
                        cuts += Point.SWITCH_ANSWERED
                        if (direction == Direction.UPLOAD) cuts += Point.SWITCH_ECHOED
                    }
                    for (cut in cuts) for (detector in Detector.values()) add(Schedule(direction, lane, cut = cut, detector = detector))
                    add(Schedule(direction, lane, cut = Point.HANDSHAKE, dup = true))
                    add(Schedule(direction, lane, cut = Point.LANE_FRAME_1, dup = true))
                    for (handshake in listOf(Handshake.DROP, Handshake.TRUNCATE)) {
                        add(Schedule(direction, lane, handshake))
                        add(Schedule(direction, lane, handshake, dup = true))
                    }
                    for (trigger in listOf(Trigger.CANCEL, Trigger.LINK_DOWN)) {
                        add(Schedule(direction, lane, trigger = trigger, at = Point.HANDSHAKE))
                        add(Schedule(direction, lane, trigger = trigger, at = Point.LANE_FRAME_1))
                        add(Schedule(direction, lane, cut = Point.LANE_FRAME_1, trigger = trigger, at = Point.SNAPSHOT))
                        if (lane == LaneKind.HOTSPOT) add(Schedule(direction, lane, trigger = trigger, at = Point.SWITCH_ASKED))
                    }
                    if (lane == LaneKind.HOTSPOT) {
                        add(Schedule(direction, lane, trigger = Trigger.CANCEL, at = Point.SWITCH_ANSWERED))
                        for (at in listOf(Point.HANDSHAKE, Point.SWITCH_ASKED, Point.SWITCH_ANSWERED, Point.LANE_FRAME_1, Point.FINAL_ACK)) {
                            add(Schedule(direction, lane, trigger = Trigger.CAMERA, at = at))
                        }
                        for (at in listOf(Point.HANDSHAKE, Point.SWITCH_ANSWERED, Point.LANE_FRAME_1, Point.FINAL_ACK)) {
                            add(Schedule(direction, lane, trigger = Trigger.DEADLINE, at = at))
                        }
                        add(Schedule(direction, lane, cut = Point.LANE_FRAME_1, trigger = Trigger.CAMERA, at = Point.SNAPSHOT))
                        add(Schedule(direction, lane, cut = Point.LANE_FRAME_1, trigger = Trigger.DEADLINE, at = Point.SNAPSHOT))
                        add(Schedule(direction, lane, cut = Point.LANE_FRAME_1, offerAfterChunk = 5))
                    }
                }
                // Every rewind kind with a prefix whose rehash crosses the 30 s idle budget: a
                // lost shared or hotspot lane, the hotspot upgrade alone, and the upgrade followed
                // by a lost lane, which rewinds twice.
                add(Schedule(direction, LaneKind.SHARED, cut = Point.LANE_FRAME_1, replay = true))
                add(Schedule(direction, LaneKind.SHARED, cut = Point.LANE_FRAME_3, replay = true))
                add(Schedule(direction, LaneKind.HOTSPOT, cut = Point.LANE_FRAME_1, replay = true))
                add(Schedule(direction, LaneKind.HOTSPOT, cut = Point.LANE_FRAME_1, dup = true, replay = true))
                add(Schedule(direction, LaneKind.HOTSPOT, offerAfterChunk = 5, replay = true))
                add(Schedule(direction, LaneKind.HOTSPOT, cut = Point.LANE_FRAME_1, offerAfterChunk = 5, replay = true))
            }
        }

        /** Four full lane chunks and a tail: enough frames to cut between, and 34 bus chunks. */
        private const val SOURCE_BYTES = 1_100_003

        /** Loopback is not Bluetooth: paced so a lane can be set up while bytes still flow. */
        private const val BUS_CHUNK_MS = 40L
        private const val RELEASE_FALLBACK_MS = 1_500L

        /** A replayed prefix is read in pieces this big, each costing this much clock: a slow source that never stalls. */
        private const val REPLAY_STEP_BYTES = 16 * 1024
        private const val REPLAY_STEP_MS = 4_000L
        private const val REPLAY_STEP_REAL_MS = 50L
        private const val GLASSES_TICK_MS = 1_000L
    }

    private class Harness(private val schedule: Schedule, private val temp: TemporaryFolder) {
        private val id = AtomicReference<String>()
        private val source = ByteArray(SOURCE_BYTES) { (it * 31 + 7).toByte() }
        private val sourceFile: File = temp.newFile().apply { writeBytes(source) }
        private val output: File = temp.newFile()
        private val io: ExecutorService = Executors.newCachedThreadPool { Thread(it, "schedule-io").apply { isDaemon = true } }
        private val control: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "schedule-glasses").apply { isDaemon = true } }
        private val toPhone: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "schedule-bus").apply { isDaemon = true } }
        private val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "schedule-timer").apply { isDaemon = true } }
        private val failure = AtomicReference<Throwable>()
        private val results = CopyOnWriteArrayList<GlassesFilesResult>()
        private val requests = CopyOnWriteArrayList<GlassesFilesRequest>()
        private val declined = CountDownLatch(1)
        private val cancelSeen = CountDownLatch(1)
        private val downloadFinished = CountDownLatch(1)
        private val servers = CopyOnWriteArrayList<GlassesFilesLaneServer>()
        private val triggered = AtomicBoolean(false)
        /** The highest snapshot either receiver froze: the sender rehashes everything below it. */
        private val replayBelow = AtomicLong(0)
        @Volatile private var proxy: LaneProxy? = null
        @Volatile private var storeRoot: File? = null
        @Volatile private var upload: GlassesFilesUpload? = null
        @Volatile private var download: GlassesFilesDownload? = null
        @Volatile private var laneOffered = false
        private lateinit var controller: GlassesFilesController
        private lateinit var active: Any

        fun run() {
            val context = RuntimeEnvironment.getApplication()
            val connectivity = shadowOf(context.getSystemService(ConnectivityManager::class.java))
            val network = ShadowNetwork.newInstance(71)
            connectivity.addNetwork(network, null)
            val capabilities = NetworkCapabilities()
            shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            connectivity.setNetworkCapabilities(network, capabilities)
            val provider = GlassesFilesAnswerLossTest.DownloadProvider().apply {
                destination = output
                attachInfo(context, ProviderInfo().apply { authority = "media" })
            }
            ShadowContentResolver.registerProviderInternal("media", provider)
            controller = GlassesFilesController(context, ::fromPhone, openUpload = { _, _ -> openSource(sourceFile) })
            controller.onLinkStateChanged(controlUp = true, dataUp = true)
            controller.onCapabilities(glassesFilesSupported = true, laneSupported = true)
            val isUpload = schedule.direction == Direction.UPLOAD
            val item = GlassesTransferItem("row", if (isUpload) GlassesTransferDirection.SEND else GlassesTransferDirection.COPY,
                "schedule.bin", "", if (isUpload) Uri.fromFile(sourceFile).toString() else "schedule.bin", source.size.toLong(),
                state = GlassesTransferState.RUNNING)
            val type = GlassesFilesController::class.java.declaredClasses.single { it.simpleName == if (isUpload) "ActiveSend" else "ActiveCopy" }
            active = type.declaredConstructors.single { it.parameterCount == 2 }.apply { isAccessible = true }.newInstance(controller, item)
            setField(controller, "active", active)
            @Suppress("UNCHECKED_CAST")
            (field(controller, "transfers") as MutableList<GlassesTransferItem>).add(item)
            id.set(field(active, "wireId") as String)
            val worker = FutureTask {
                try { call(active, "run") } catch (error: java.lang.reflect.InvocationTargetException) {
                    failure.compareAndSet(null, error.targetException)
                    throw error
                }
            }
            val startedAt = SystemClock.elapsedRealtime()
            Thread(worker, "schedule-phone").apply { isDaemon = true; start() }
            timers.scheduleWithFixedDelay({ control.execute { checked(::tick) } }, GLASSES_TICK_MS, GLASSES_TICK_MS, TimeUnit.MILLISECONDS)
            try {
                val outcome = checkNotNull(worker.get(12, TimeUnit.SECONDS))
                failure.get()?.let { throw it }
                when (schedule.trigger) {
                    Trigger.CANCEL -> assertEquals(outcome.toString(), "Cancelled", outcome.javaClass.simpleName)
                    Trigger.LINK_DOWN -> {
                        assertEquals(outcome.toString(), "Stopped", outcome.javaClass.simpleName)
                        assertEquals(GlassesTransferStop.LINK_LOST, field(outcome, "reason"))
                    }
                    else -> {
                        assertEquals(outcome.toString(), "Done", outcome.javaClass.simpleName)
                        assertFalse(results.toString(), results.any { it is GlassesFilesResult.TransferFailed })
                        val received = if (isUpload) {
                            val files = storeRoot!!.walkTopDown().filter { it.isFile }.toList()
                            assertEquals(files.toString(), 1, files.size)
                            files.single().readBytes()
                        } else {
                            assertTrue(downloadFinished.await(5, TimeUnit.SECONDS))
                            assertEquals(sha(source), results.filterIsInstance<GlassesFilesResult.DownloadEnd>().single().sha256)
                            output.readBytes()
                        }
                        assertArrayEquals(received.size.toString(), source, received)
                        if (schedule.lane != LaneKind.NONE && schedule.handshake == Handshake.OK && schedule.cut == null &&
                            schedule.trigger == Trigger.NONE) {
                            assertTrue("the lane carried bytes", proxy!!.dataFrames.get() > 0)
                        }
                        // A truncated hotspot answer leaves both ends on the bus: the glasses may
                        // process the decline before their lane EOF and have nothing to resync.
                        val resyncNeeded = schedule.cut != null || schedule.handshake == Handshake.DROP ||
                            (schedule.handshake == Handshake.TRUNCATE && schedule.lane == LaneKind.SHARED)
                        if (resyncNeeded) {
                            assertTrue("the lane loss was resynced over the bus: $requests $results",
                                requests.any { it is GlassesFilesRequest.BusResume } || results.any { it is GlassesFilesResult.BusResume })
                        }
                    }
                }
                assertFalse(results.toString(), results.any { it is GlassesFilesResult.TransferFailed && it.error == GlassesFilesError.TIMEOUT })
                assertFalse(results.toString(), results.any { it is GlassesFilesResult.TransferFailed && it.error == GlassesFilesError.INVALID_REQUEST })
                if (schedule.replay) {
                    // The rehash really outlasted the idle budget, and the sender's echo repeated through it.
                    assertTrue("the clock crossed the idle budget", SystemClock.elapsedRealtime() - startedAt > GlassesFilesContract.IDLE_TIMEOUT_MS)
                    val echoes = if (isUpload) requests.count { it is GlassesFilesRequest.BusResume || it is GlassesFilesRequest.LaneResume }
                        else results.count { it is GlassesFilesResult.BusResume || it is GlassesFilesResult.LaneResume }
                    assertTrue("the sender repeated its echo: $requests $results", echoes >= 3)
                }
                // The receiver freezes one snapshot per transfer; repeats must carry the same offset.
                val snapshots = if (isUpload) results.filterIsInstance<GlassesFilesResult.BusResume>().map { it.stagedBytes }
                    else requests.filterIsInstance<GlassesFilesRequest.BusResume>().map { it.stagedBytes }
                assertTrue(snapshots.toString(), snapshots.distinct().size <= 1)
                // The phone's worker ends on its local cancel; the glasses only learn of it from the bus.
                if (schedule.trigger == Trigger.CANCEL) assertTrue("glasses saw the cancel", cancelSeen.await(5, TimeUnit.SECONDS))
                control.submit {}.get(5, TimeUnit.SECONDS)
                if (!isUpload) assertTrue("glasses streamer ended", downloadFinished.await(5, TimeUnit.SECONDS))
                assertTrue("phone lane closed", (field(active, "lane") as GlassesFilesLaneClient?)?.isClosed != false)
                servers.forEach { assertFalse("glasses lane closed", it.isConnected()) }
                if (isUpload && (schedule.trigger == Trigger.CANCEL || schedule.trigger == Trigger.LINK_DOWN)) {
                    val left = storeRoot!!.walkTopDown().filter { it.isFile }.toList()
                    assertTrue("staging removed: $left", left.isEmpty())
                }
            } finally {
                controller.close()
                worker.cancel(true)
                download?.cancelAll()
                this.upload?.retire()
                proxy?.close()
                servers.forEach { runCatching { it.close() } }
                io.shutdownNow()
                control.shutdownNow()
                toPhone.shutdownNow()
                timers.shutdownNow()
            }
        }

        // ---- the bus: phone -> glasses -------------------------------------------------------

        private fun fromPhone(envelope: BusEnvelope): String? {
            val bytes = envelope.binary
            if (bytes != null) {
                val chunk = GlassesFilesContract.parseChunk(envelope.payload, bytes)!!
                Thread.sleep(BUS_CHUNK_MS)
                control.execute { checked { onUploadChunk(chunk, bytes, viaLane = false) } }
                return null
            }
            val request = GlassesFilesContract.parseRequest(envelope.payload) ?: return null
            requests += request
            when (request) {
                is GlassesFilesRequest.BusResume -> snapshot(request.stagedBytes)
                is GlassesFilesRequest.LaneResume -> snapshot(request.stagedBytes)
                else -> Unit
            }
            val twice = schedule.dup && (request is GlassesFilesRequest.LaneResync || request is GlassesFilesRequest.BusResume)
            repeat(if (twice) 2 else 1) { control.execute { checked { onRequest(request) } } }
            return null
        }

        private fun onRequest(request: GlassesFilesRequest) {
            val now = SystemClock.elapsedRealtime()
            when (request) {
                is GlassesFilesRequest.UploadBegin -> {
                    val root = temp.newFolder().also { storeRoot = it }
                    val store = GlassesFileStore(root, JvmFsPrimitives()) { Long.MAX_VALUE / 4 }
                    val staged = store.beginUpload("", request.name, request.sizeBytes, request.transferId).getOrThrow()
                    val lane = if (schedule.lane == LaneKind.SHARED) openLane() else null
                    val active = GlassesFilesUpload(request.transferId, staged, lane, now)
                    upload = active
                    deliver(GlassesFilesResult.UploadReady(request.transferId, staged.name, lane?.let { proxy!!.endpoint }))
                    if (lane != null) io.execute { checked { serveUploadLane(active, lane, upgrade = false) } }
                }
                is GlassesFilesRequest.DownloadBegin -> {
                    val lane = if (schedule.lane == LaneKind.SHARED) openLane() else null
                    val active = GlassesFilesDownload(request.transferId, sourceFile, lane, io, { result ->
                        deliver(if (result is GlassesFilesResult.DownloadReady && lane != null) result.copy(lane = proxy!!.endpoint) else result)
                        true
                    }, { offset, data, epoch ->
                        Thread.sleep(BUS_CHUNK_MS)
                        toPhone.execute { checked {
                            check(controller.handleRemote(BusEnvelope(path = GlassesFilesContract.DATA_PATH,
                                payload = GlassesFilesContract.chunkMeta(GlassesFilesChunk(request.transferId, offset, epoch)), binary = data)))
                            drainInbox()
                            onBusChunk(offset)
                        } }
                        true
                    }, { downloadFinished.countDown() }, SystemClock::elapsedRealtime, openSource = ::openSource)
                    download = active
                    io.execute { checked { active.stream() } }
                }
                is GlassesFilesRequest.LaneSwitch -> {
                    upload?.onLaneSwitch(now)?.let(::signal)
                    reached(Point.SWITCH_ASKED)
                }
                is GlassesFilesRequest.LaneResume -> {
                    upload?.let { if (!it.onLaneResume(request.stagedBytes, now)) failUpload(it, GlassesFilesError.INVALID_REQUEST) }
                    download?.upgradeToLane(request.transferId, request.stagedBytes)
                    reached(if (schedule.direction == Direction.UPLOAD) Point.SWITCH_ECHOED else Point.SWITCH_ASKED)
                }
                is GlassesFilesRequest.LaneDeclined -> {
                    upload?.onLaneDeclined()
                    download?.declineLane(request.transferId)
                    declined.countDown()
                    proxy?.releaseGlasses()
                }
                is GlassesFilesRequest.LaneResync -> {
                    upload?.onResyncRequest(now)?.let(::signal)
                    download?.requestResync(request.transferId)
                    proxy?.releaseGlasses()
                }
                is GlassesFilesRequest.BusResume -> {
                    upload?.let { if (!it.onBusResume(request.stagedBytes, now)) failUpload(it, GlassesFilesError.INVALID_REQUEST) }
                    download?.onResume(request.transferId, request.stagedBytes)
                    proxy?.releaseGlasses()
                    if (schedule.direction == Direction.DOWNLOAD) reached(Point.SNAPSHOT)
                }
                is GlassesFilesRequest.DownloadProgress -> download?.onAck(request.transferId, request.stagedBytes)
                is GlassesFilesRequest.UploadEnd -> {
                    val active = upload ?: return
                    upload = null
                    val error = active.finish(request.sha256)
                    deliver(if (error != null) GlassesFilesResult.TransferFailed(request.transferId, error)
                        else GlassesFilesResult.UploadDone(request.transferId, active.upload.name))
                }
                is GlassesFilesRequest.Cancel -> {
                    upload?.retire()
                    upload = null
                    download?.cancel(request.transferId)
                    cancelSeen.countDown()
                }
                else -> Unit
            }
        }

        private fun onUploadChunk(chunk: GlassesFilesChunk, bytes: ByteArray, viaLane: Boolean, owner: GlassesFilesUpload? = null) {
            val active = upload ?: return
            if (owner != null && owner !== active) return
            when (val outcome = active.onChunk(chunk.offset, bytes, viaLane, chunk.busEpoch, SystemClock.elapsedRealtime())) {
                GlassesFilesUpload.Chunk.Ignored -> Unit
                is GlassesFilesUpload.Chunk.Failed -> failUpload(active, outcome.error)
                is GlassesFilesUpload.Chunk.Staged -> {
                    val staged = outcome.ack
                    if (staged != null) {
                        val acked = (outcome.laneAck?.write(GlassesFilesLane.Frame.Ack(staged)) == true) ||
                            deliver(GlassesFilesResult.UploadProgress(active.transferId, staged))
                        if (acked) active.ackSent(staged)
                    }
                    if (!viaLane) onBusChunk(chunk.offset)
                }
            }
        }

        private fun onBusChunk(offset: Long) {
            val index = (offset / GlassesFilesContract.CHUNK_BYTES).toInt()
            if (index == 3) reached(Point.BUS_CHUNK_3)
            if (schedule.lane == LaneKind.HOTSPOT && index == schedule.offerAfterChunk) control.execute { checked(::offerHotspot) }
        }

        private fun signal(signal: GlassesFilesUpload.Signal) {
            val active = upload ?: return
            when (signal) {
                is GlassesFilesUpload.Signal.LaneResume -> deliver(GlassesFilesResult.LaneResume(active.transferId, signal.staged))
                is GlassesFilesUpload.Signal.BusResume -> deliver(GlassesFilesResult.BusResume(active.transferId, signal.staged))
            }
        }

        private fun failUpload(active: GlassesFilesUpload, error: GlassesFilesError) {
            if (upload === active) upload = null
            active.retire()
            deliver(GlassesFilesResult.TransferFailed(active.transferId, error))
        }

        /** The service's maintenance tick: every clock step runs it, and real time once a second. */
        private fun tick() {
            val active = upload ?: return
            when (val tick = active.onTick(SystemClock.elapsedRealtime())) {
                GlassesFilesUpload.Tick.Idle -> Unit
                is GlassesFilesUpload.Tick.Resume -> signal(tick.signal)
                GlassesFilesUpload.Tick.Expired -> failUpload(active, GlassesFilesError.TIMEOUT)
            }
        }

        // ---- the sources and the clock -----------------------------------------------------

        private fun snapshot(staged: Long) {
            replayBelow.updateAndGet { maxOf(it, staged) }
        }

        /**
         * A replay schedule charges every piece of a rehashed prefix to the shared clock, then
         * lets both peers look at it: the glasses' tick and the phone's wait run before the next
         * piece, as a slow provider would let them.
         */
        private fun openSource(file: File): InputStream {
            val plain = file.inputStream()
            if (!schedule.replay) return plain
            return SlowPrefix(plain, replayBelow::get, REPLAY_STEP_BYTES) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(REPLAY_STEP_MS))
                control.execute { checked(::tick) }
                val signal = field(active, "signal")!!
                synchronized(signal) { (signal as Object).notifyAll() }
                Thread.sleep(REPLAY_STEP_REAL_MS)
            }
        }

        private fun serveUploadLane(active: GlassesFilesUpload, lane: GlassesFilesLaneServer, upgrade: Boolean) {
            if (!lane.accept()) {
                lane.close()
                return
            }
            control.execute {
                if (!upgrade && upload === active && !active.resync.laneOpened && !active.resync.switched) active.resync.opened()
            }
            try {
                while (true) {
                    val frame = lane.read()
                    if (frame is GlassesFilesLane.Frame.Data) {
                        control.execute { checked { onUploadChunk(GlassesFilesChunk(active.transferId, frame.offset), frame.bytes, viaLane = true, owner = active) } }
                    }
                }
            } catch (_: Exception) {
                control.execute { checked { if (upload === active) active.onLaneEof(SystemClock.elapsedRealtime())?.let(::signal) } }
            } finally {
                lane.close()
            }
        }

        private fun offerHotspot() {
            if (laneOffered) return
            laneOffered = true
            val lane = openLane()
            val sending = download
            val receiving = upload
            if (receiving != null) {
                receiving.lane = lane
                io.execute { checked { serveUploadLane(receiving, lane, upgrade = true) } }
            } else if (sending?.offerHotspotLane(lane) != true) {
                lane.close()
                return
            }
            setField(active, "hotspotDeadline", SystemClock.elapsedRealtime() + 25_000L)
            deliver(GlassesFilesResult.HotspotReady(id.get(), proxy!!.endpoint, "127.0.0.1"))
        }

        private fun openLane(): GlassesFilesLaneServer {
            check(proxy == null) { "one lane per transfer" }
            val key = GlassesFilesLane.newKey()
            val lane = GlassesFilesLaneServer(id.get(), ServerSocket(0, 4, InetAddress.getLoopbackAddress()), key, null)
            servers += lane
            proxy = LaneProxy(id.get(), key, lane, source.size.toLong(), io, timers, schedule.handshake,
                before = ::beforeForwarding, after = ::afterForwarding).also { it.start() }
            return lane
        }

        // ---- the bus: glasses -> phone -------------------------------------------------------

        private fun deliver(result: GlassesFilesResult): Boolean {
            results += result
            when (result) {
                is GlassesFilesResult.BusResume -> snapshot(result.stagedBytes)
                is GlassesFilesResult.LaneResume -> snapshot(result.stagedBytes)
                else -> Unit
            }
            val resync = result is GlassesFilesResult.LaneResync || result is GlassesFilesResult.BusResume
            val twice = schedule.dup && resync
            repeat(if (twice) 2 else 1) {
                toPhone.execute { checked {
                    // A truncated answer fails the open first; the peer's snapshot then meets a declined lane.
                    if (resync && schedule.handshake == Handshake.TRUNCATE) check(declined.await(5, TimeUnit.SECONDS))
                    check(controller.handleRemote(BusEnvelope(path = GlassesFilesContract.RESULT_PATH, payload = GlassesFilesContract.encode(result))))
                    drainInbox()
                    if (resync) proxy?.releasePhone()
                    if (result is GlassesFilesResult.LaneResume) reached(Point.SWITCH_ANSWERED)
                    if (result is GlassesFilesResult.BusResume && schedule.direction == Direction.UPLOAD) reached(Point.SNAPSHOT)
                } }
            }
            return true
        }

        private fun drainInbox() {
            (field(controller, "inbox") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
        }

        // ---- schedule points -----------------------------------------------------------------

        private fun pointOf(event: LaneEvent): Point? = when (event) {
            LaneEvent.Answer -> Point.HANDSHAKE
            is LaneEvent.Data -> when (event.index) {
                0 -> Point.LANE_FRAME_0
                1 -> Point.LANE_FRAME_1
                3 -> Point.LANE_FRAME_3
                else -> null
            }
            LaneEvent.FinalAck -> Point.FINAL_ACK
        }

        /** False drops the frame and cuts the lane there. */
        private fun beforeForwarding(event: LaneEvent): Boolean {
            val point = pointOf(event) ?: return true
            if (schedule.cut != point) return true
            proxy!!.cut(schedule.detector)
            return false
        }

        private fun afterForwarding(event: LaneEvent) {
            pointOf(event)?.let(::reached)
        }

        private fun reached(point: Point) {
            if (schedule.cut == point) proxy?.cut(schedule.detector)
            if (schedule.at == point) fire()
        }

        private fun fire() {
            if (!triggered.compareAndSet(false, true)) return
            when (schedule.trigger) {
                Trigger.CAMERA -> control.execute { checked {
                    upload?.onCameraTakeover(SystemClock.elapsedRealtime())?.let(::signal)
                    download?.onCameraTakeover()
                    deliver(GlassesFilesResult.HotspotFailed(id.get()))
                } }
                Trigger.DEADLINE -> (field(controller, "inbox") as ExecutorService).execute { checked { call(active, "hotspotFailed") } }
                // Both land before the next frame or message moves, as a cancel at that moment would.
                Trigger.CANCEL -> controller.cancel("row")
                Trigger.LINK_DOWN -> {
                    // Each hub notices the data plane drop on its own. The glasses side is queued
                    // first: the phone's worker returns as soon as its side fails, and the checks
                    // that follow drain the glasses executor, so they must not get ahead of it.
                    control.execute { checked {
                        upload?.retire()
                        upload = null
                        download?.cancelAll()
                    } }
                    controller.onLinkStateChanged(controlUp = true, dataUp = false)
                }
                Trigger.NONE -> Unit
            }
        }

        private fun checked(action: () -> Unit) {
            try { action() } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
    }

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

    sealed interface LaneEvent {
        data object Answer : LaneEvent
        data class Data(val index: Int) : LaneEvent
        data object FinalAck : LaneEvent
    }

    /**
     * Loopback proxy between the phone's lane client and the glasses' lane server. It opens each
     * sealed frame with its own copy of the key, so the schedule can name frames, and it can close
     * either end first while holding the other open until the first bus signal has crossed.
     */
    private class LaneProxy(
        private val id: String,
        private val key: ByteArray,
        private val server: GlassesFilesLaneServer,
        private val sizeBytes: Long,
        private val io: ExecutorService,
        private val timers: ScheduledExecutorService,
        private val handshake: Handshake,
        private val before: (LaneEvent) -> Boolean,
        private val after: (LaneEvent) -> Unit,
    ) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val endpoint: GlassesFilesLaneEndpoint = server.endpoint.copy(port = listener.localPort)
        val dataFrames = AtomicInteger()
        @Volatile private var phone: Socket? = null
        @Volatile private var glasses: Socket? = null
        private val phoneHeld = AtomicBoolean(false)
        private val glassesHeld = AtomicBoolean(false)
        private val cutDone = AtomicBoolean(false)
        private val helloBytes = ByteArrayOutputStream().also { GlassesFilesLane.writeHello(DataOutputStream(it), id, key) }.size()

        fun start() {
            io.execute {
                runCatching {
                    val phone = listener.accept().also { this.phone = it; it.tcpNoDelay = true; it.soTimeout = 30_000 }
                    val glasses = Socket(InetAddress.getLoopbackAddress(), server.endpoint.port)
                        .also { this.glasses = it; it.tcpNoDelay = true; it.soTimeout = 30_000 }
                    io.execute { pump(phone, glasses, toGlasses = true) }
                    io.execute { pump(glasses, phone, toGlasses = false) }
                }.onFailure { runCatching { listener.close() } }
            }
        }

        private fun pump(from: Socket, to: Socket, toGlasses: Boolean) {
            val input = DataInputStream(from.getInputStream())
            val output = DataOutputStream(to.getOutputStream())
            val opener = GlassesFilesLane.Opener(key, id,
                if (toGlasses) GlassesFilesLane.Direction.PHONE_TO_GLASSES else GlassesFilesLane.Direction.GLASSES_TO_PHONE)
            var index = 0
            var data = 0
            try {
                if (toGlasses) {
                    val hello = ByteArray(helloBytes).also(input::readFully)
                    output.write(hello)
                    output.flush()
                }
                while (!cutDone.get()) {
                    val type = input.readByte()
                    val length = input.readInt()
                    val sealed = ByteArray(length).also(input::readFully)
                    val raw = ByteArrayOutputStream().also {
                        DataOutputStream(it).apply { writeByte(type.toInt()); writeInt(length); write(sealed) }
                    }.toByteArray()
                    val frame = opener.read(DataInputStream(raw.inputStream()))
                    val event: LaneEvent? = when {
                        !toGlasses && index == 0 -> LaneEvent.Answer
                        frame is GlassesFilesLane.Frame.Data -> LaneEvent.Data(data++)
                        frame is GlassesFilesLane.Frame.Ack && frame.stagedBytes >= sizeBytes -> LaneEvent.FinalAck
                        else -> null
                    }
                    index += 1
                    if (event == LaneEvent.Answer) {
                        when (handshake) {
                            Handshake.OK -> {
                                output.write(raw)
                                output.flush()
                                after(event)
                            }
                            Handshake.TRUNCATE -> {
                                output.write(raw, 0, raw.size - 1)
                                output.flush()
                                closeBoth()
                                return
                            }
                            // The glasses go first: their snapshot reaches the phone while connect() still waits.
                            Handshake.DROP -> {
                                cut(Detector.GLASSES_FIRST)
                                return
                            }
                        }
                        continue
                    }
                    if (event != null && !before(event)) return
                    if (cutDone.get()) return
                    output.write(raw)
                    output.flush()
                    if (frame is GlassesFilesLane.Frame.Data) dataFrames.incrementAndGet()
                    if (event != null) after(event)
                }
            } catch (_: Exception) {
                if (!cutDone.get()) closeBoth()
            }
        }

        fun cut(detector: Detector) {
            if (!cutDone.compareAndSet(false, true)) return
            when (detector) {
                Detector.PHONE_FIRST -> { glassesHeld.set(true); runCatching { phone?.close() } }
                Detector.GLASSES_FIRST -> { phoneHeld.set(true); runCatching { glasses?.close() } }
                Detector.BOTH -> closeBoth()
            }
            // The held end is released by the first bus signal that crosses, or by this fallback.
            timers.schedule({ releasePhone(); releaseGlasses() }, RELEASE_FALLBACK_MS, TimeUnit.MILLISECONDS)
        }

        fun releasePhone() {
            if (phoneHeld.compareAndSet(true, false)) runCatching { phone?.close() }
        }

        fun releaseGlasses() {
            if (glassesHeld.compareAndSet(true, false)) runCatching { glasses?.close() }
        }

        private fun closeBoth() {
            runCatching { phone?.close() }
            runCatching { glasses?.close() }
        }

        override fun close() {
            cutDone.set(true)
            phoneHeld.set(false)
            glassesHeld.set(false)
            closeBoth()
            runCatching { listener.close() }
        }
    }
}

private fun sha(bytes: ByteArray) = GlassesFilesContract.hex(GlassesFilesContract.newDigest().apply { update(bytes) })

private fun member(target: Any, name: String): java.lang.reflect.Field =
    generateSequence(target.javaClass as Class<*>?) { it.superclass }
        .mapNotNull { type -> type.declaredFields.firstOrNull { it.name == name } }.first().apply { isAccessible = true }

private fun field(target: Any, name: String): Any? = member(target, name).get(target)
private fun setField(target: Any, name: String, value: Any?) = member(target, name).set(target, value)
private fun call(target: Any, name: String): Any? =
    generateSequence(target.javaClass as Class<*>?) { it.superclass }
        .flatMap { it.declaredMethods.asSequence() }
        .first { it.name == name && it.parameterCount == 0 }.apply { isAccessible = true }.invoke(target)
