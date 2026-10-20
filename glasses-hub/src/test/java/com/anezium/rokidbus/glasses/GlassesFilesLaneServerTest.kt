package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesFilesLane
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GlassesFilesLaneServerTest {
    private val id = "files_0123456789abcdef"
    private val io = Executors.newCachedThreadPool()

    @After
    fun tearDown() {
        io.shutdownNow()
    }

    private fun server(key: ByteArray, acceptTimeoutMs: Int = 600) = GlassesFilesLaneServer(
        transferId = id,
        server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()),
        key = key,
        wifiLock = null,
        acceptTimeoutMs = acceptTimeoutMs,
    )

    @Test
    fun `the phone with the key is accepted and frames flow`() {
        val key = GlassesFilesLane.newKey()
        val lane = server(key, acceptTimeoutMs = 5_000)
        val accepted = io.submit<Boolean> { lane.accept() }
        Socket(InetAddress.getLoopbackAddress(), lane.endpoint.port).use { socket ->
            val output = DataOutputStream(socket.getOutputStream())
            GlassesFilesLane.writeHello(output, id, key)
            assertTrue(accepted.get(5, TimeUnit.SECONDS))
            GlassesFilesLane.Sealer(key, id, GlassesFilesLane.Direction.PHONE_TO_GLASSES)
                .write(output, GlassesFilesLane.Frame.Data(0, byteArrayOf(1, 2, 3)))
            assertEquals(GlassesFilesLane.Frame.Data(0, byteArrayOf(1, 2, 3)).offset, (lane.read() as GlassesFilesLane.Frame.Data).offset)
        }
        lane.close()
    }

    @Test
    fun `a hello trickled byte by byte cannot outlast the accept budget`() {
        val key = GlassesFilesLane.newKey()
        val lane = server(key, acceptTimeoutMs = 600)
        val started = System.nanoTime()
        val accepted = io.submit<Boolean> { lane.accept() }
        val hello = ByteArrayOutputStream().also { GlassesFilesLane.writeHello(DataOutputStream(it), id, key) }.toByteArray()
        Socket(InetAddress.getLoopbackAddress(), lane.endpoint.port).use { socket ->
            io.execute {
                runCatching {
                    // Each byte comes well inside a per-read timeout, but the whole hello takes far longer.
                    hello.forEach { byte ->
                        socket.getOutputStream().write(byte.toInt())
                        socket.getOutputStream().flush()
                        Thread.sleep(150)
                    }
                }
            }
            assertFalse(accepted.get(5, TimeUnit.SECONDS))
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_500)
        lane.close()
    }

    @Test
    fun `closing the lane interrupts a pending hello`() {
        val key = GlassesFilesLane.newKey()
        val lane = server(key, acceptTimeoutMs = 30_000)
        val accepted = io.submit<Boolean> { lane.accept() }
        Socket(InetAddress.getLoopbackAddress(), lane.endpoint.port).use { socket ->
            socket.getOutputStream().write(byteArrayOf(0x4E, 0x58))
            socket.getOutputStream().flush()
            Thread.sleep(200)
            val started = System.nanoTime()
            lane.close()
            assertFalse(accepted.get(5, TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        }
    }

    @Test
    fun `a wrong key is refused and the port is not offered again`() {
        val lane = server(GlassesFilesLane.newKey(), acceptTimeoutMs = 5_000)
        val accepted = io.submit<Boolean> { lane.accept() }
        Socket(InetAddress.getLoopbackAddress(), lane.endpoint.port).use { socket ->
            GlassesFilesLane.writeHello(DataOutputStream(socket.getOutputStream()), id, GlassesFilesLane.newKey())
            assertFalse(accepted.get(5, TimeUnit.SECONDS))
        }
        assertFalse(runCatching { Socket(InetAddress.getLoopbackAddress(), lane.endpoint.port).close() }.isSuccess)
        lane.close()
    }
}
