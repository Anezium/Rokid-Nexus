package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.GlassesFilesLane
import com.anezium.rokidbus.shared.GlassesFilesResync
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class GlassesFilesControllerTest {
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

    private fun activeSend(controller: GlassesFilesController): Any {
        val item = GlassesTransferItem("row", GlassesTransferDirection.SEND, "test.bin", "", "file:///test.bin", 100)
        val type = GlassesFilesController::class.java.declaredClasses.single { it.simpleName == "ActiveSend" }
        val active = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(controller, item)
        setField(controller, "active", active)
        @Suppress("UNCHECKED_CAST")
        (field(controller, "transfers") as MutableList<GlassesTransferItem>).add(item)
        return active
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
