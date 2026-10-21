package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.GlassesFilesLane
import com.anezium.rokidbus.shared.GlassesFilesResync
import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesRequest
import com.anezium.rokidbus.shared.GlassesFilesResult
import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.ByteArrayOutputStream
import java.util.concurrent.FutureTask

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class GlassesFilesControllerTest {
    @Test
    fun `bus resume supersedes a pending lane resume before the upload worker continues`() {
        val source = ByteArray(200_017) { (it * 17).toByte() }
        val context = RuntimeEnvironment.getApplication()
        val file = java.io.File(context.cacheDir, "resume.bin").apply { writeBytes(source) }
        val received = ByteArrayOutputStream()
        val confirmations = mutableListOf<GlassesFilesRequest>()
        lateinit var active: Any
        var injected = false
        val controller = GlassesFilesController(context, { envelope ->
            val bytes = envelope.binary
            if (bytes != null) {
                val chunk = GlassesFilesContract.parseChunk(envelope.payload, bytes)!!
                if (!injected) {
                    injected = true
                    received.write(bytes, 0, 16_384)
                    call(active, "switchToLane")
                    call(active, "receiveResult", GlassesFilesResult.LaneResume(chunk.transferId, 16_384))
                    call(active, "onLaneLost")
                    call(active, "receiveResult", GlassesFilesResult.BusResume(chunk.transferId, 16_384))
                } else {
                    assertEquals(received.size().toLong(), chunk.offset)
                    received.write(bytes)
                    call(active, "receiveResult", GlassesFilesResult.UploadProgress(chunk.transferId, received.size().toLong()))
                }
            } else when (val request = GlassesFilesContract.parseRequest(envelope.payload)) {
                is GlassesFilesRequest.UploadBegin -> call(active, "receiveResult", GlassesFilesResult.UploadReady(request.transferId, request.name))
                is GlassesFilesRequest.BusResume, is GlassesFilesRequest.LaneResume -> confirmations.add(request)
                is GlassesFilesRequest.UploadEnd -> {
                    assertEquals(sha(source), request.sha256)
                    call(active, "receiveResult", GlassesFilesResult.UploadDone(request.transferId, "resume.bin"))
                }
                else -> Unit
            }
            null
        }, openUpload = { _, _ -> file.inputStream() })
        active = activeSend(controller, GlassesTransferItem("row", GlassesTransferDirection.SEND,
            "resume.bin", "", Uri.fromFile(file).toString(), source.size.toLong()))
        val worker = FutureTask { call(active, "run") }
        Thread(worker, "test-upload").apply { isDaemon = true; start() }
        try {
            val outcome = worker.get(5, TimeUnit.SECONDS)
            assertEquals(outcome.toString(), "Done", outcome!!.javaClass.simpleName)
            assertTrue(injected)
            assertEquals(listOf(GlassesFilesRequest.BusResume(field(active, "wireId") as String, 16_384)), confirmations)
            assertTrue((field(active, "resync") as GlassesFilesResync).confirmed)
            assertArrayEquals(source, received.toByteArray())
        } finally {
            controller.close()
            worker.cancel(true)
        }
    }

    @Test
    fun `lane ACK overlapping data-plane loss releases both controller monitors`() {
        val controller = GlassesFilesController(RuntimeEnvironment.getApplication(), { null })
        val active = activeSend(controller)
        (field(active, "resync") as GlassesFilesResync).opened()
        val controllerLock = field(controller, "lock")!!
        val ownsController = CountDownLatch(1)
        val loseLink = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val link = Thread {
            runCatching {
                synchronized(controllerLock) {
                    ownsController.countDown()
                    check(loseLink.await(5, TimeUnit.SECONDS))
                    controller.onLinkStateChanged(controlUp = true, dataUp = false)
                }
            }.onFailure { failure.set(it) }
        }.apply { isDaemon = true; start() }
        assertTrue(ownsController.await(5, TimeUnit.SECONDS))
        val ack = Thread {
            runCatching { call(active, "onLaneFrame", GlassesFilesLane.Frame.Ack(1)) }
                .onFailure { failure.set(it) }
        }.apply { isDaemon = true; start() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (ack.state != Thread.State.BLOCKED && ack.isAlive && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(Thread.State.BLOCKED, ack.state)
        } finally {
            loseLink.countDown()
        }
        link.join(2_000)
        ack.join(2_000)
        assertFalse("link callback deadlocked with lane ACK", link.isAlive)
        assertFalse("lane ACK deadlocked with link callback", ack.isAlive)
        failure.get()?.let { throw it }
        assertEquals(GlassesTransferStop.LINK_LOST, field(active, "stop"))
        controller.close()
    }

    private fun activeSend(controller: GlassesFilesController, item: GlassesTransferItem =
        GlassesTransferItem("row", GlassesTransferDirection.SEND, "test.bin", "", "file:///test.bin", 100)): Any {
        val type = GlassesFilesController::class.java.declaredClasses.single { it.simpleName == "ActiveSend" }
        val active = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(controller, item)
        setField(controller, "active", active)
        @Suppress("UNCHECKED_CAST")
        (field(controller, "transfers") as MutableList<GlassesTransferItem>).add(item)
        return active
    }

    private fun sha(bytes: ByteArray): String = GlassesFilesContract.newDigest().let {
        it.update(bytes)
        GlassesFilesContract.hex(it)
    }

    private fun member(target: Any, name: String): java.lang.reflect.Field =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> type.declaredFields.firstOrNull { it.name == name } }
            .first().apply { isAccessible = true }

    private fun field(target: Any, name: String): Any? = member(target, name).get(target)
    private fun setField(target: Any, name: String, value: Any?) = member(target, name).set(target, value)

    private fun call(target: Any, name: String, vararg args: Any): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .first { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(target, *args)
}
