package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesFilesLane
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
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

    private fun server(key: ByteArray, acceptTimeoutMs: Int = 600, helloTimeoutMs: Int = 300) = GlassesFilesLaneServer(
        transferId = id,
        server = ServerSocket(0, 4, InetAddress.getLoopbackAddress()),
        key = key,
        wifiLock = null,
        acceptTimeoutMs = acceptTimeoutMs,
        helloTimeoutMs = helloTimeoutMs,
    )

    /** What the phone does: hello, then the glasses' answer before anything else. */
    private fun connectAsPhone(port: Int, key: ByteArray): Pair<Socket, Boolean> {
        val socket = Socket(InetAddress.getLoopbackAddress(), port)
        socket.soTimeout = 3_000
        GlassesFilesLane.writeHello(DataOutputStream(socket.getOutputStream()), id, key)
        val opener = GlassesFilesLane.Opener(key, id, GlassesFilesLane.Direction.GLASSES_TO_PHONE)
        val answered = runCatching { opener.read(DataInputStream(socket.getInputStream())) == GlassesFilesLane.ANSWER }
            .getOrDefault(false)
        return socket to answered
    }

    @Test
    fun `the phone with the key is accepted and frames flow`() {
        val key = GlassesFilesLane.newKey()
        val lane = server(key, acceptTimeoutMs = 5_000)
        val accepted = io.submit<Boolean> { lane.accept() }
        val (socket, answered) = connectAsPhone(lane.endpoint.port, key)
        socket.use {
            val output = DataOutputStream(socket.getOutputStream())
            assertTrue(answered)
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
        val lane = server(key, acceptTimeoutMs = 30_000, helloTimeoutMs = 30_000)
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
    fun `a stranger with the wrong key does not cost the phone its turn`() {
        val key = GlassesFilesLane.newKey()
        val lane = server(key, acceptTimeoutMs = 5_000)
        val accepted = io.submit<Boolean> { lane.accept() }
        val (stranger, strangerAnswered) = connectAsPhone(lane.endpoint.port, GlassesFilesLane.newKey())
        stranger.close()
        assertFalse(strangerAnswered)
        val (phone, phoneAnswered) = connectAsPhone(lane.endpoint.port, key)
        phone.use {
            assertTrue(phoneAnswered)
            assertTrue(accepted.get(5, TimeUnit.SECONDS))
        }
        lane.close()
    }

    @Test
    fun `a silent stranger only delays the phone by the hello budget`() {
        val key = GlassesFilesLane.newKey()
        val lane = server(key, acceptTimeoutMs = 5_000, helloTimeoutMs = 300)
        val accepted = io.submit<Boolean> { lane.accept() }
        Socket(InetAddress.getLoopbackAddress(), lane.endpoint.port).use {
            val started = System.nanoTime()
            val (phone, phoneAnswered) = connectAsPhone(lane.endpoint.port, key)
            phone.use {
                assertTrue(phoneAnswered)
                assertTrue(accepted.get(5, TimeUnit.SECONDS))
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        }
        lane.close()
    }

    @Test
    fun `once the phone is in the port is not offered again`() {
        val key = GlassesFilesLane.newKey()
        val lane = server(key, acceptTimeoutMs = 5_000)
        val accepted = io.submit<Boolean> { lane.accept() }
        val (phone, answered) = connectAsPhone(lane.endpoint.port, key)
        phone.use {
            assertTrue(answered)
            assertTrue(accepted.get(5, TimeUnit.SECONDS))
            assertFalse(runCatching { Socket(InetAddress.getLoopbackAddress(), lane.endpoint.port).close() }.isSuccess)
        }
        lane.close()
    }

    @Test
    fun `declining ends a pending accept at once but spares a connected lane`() {
        val waiting = server(GlassesFilesLane.newKey(), acceptTimeoutMs = 30_000)
        val accepted = io.submit<Boolean> { waiting.accept() }
        Thread.sleep(100)
        val started = System.nanoTime()
        waiting.decline()
        assertFalse(accepted.get(5, TimeUnit.SECONDS))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)

        val key = GlassesFilesLane.newKey()
        val connected = server(key, acceptTimeoutMs = 5_000)
        val taken = io.submit<Boolean> { connected.accept() }
        val (phone, answered) = connectAsPhone(connected.endpoint.port, key)
        phone.use {
            assertTrue(answered && taken.get(5, TimeUnit.SECONDS))
            connected.decline()
            assertTrue(connected.isConnected())
        }
        connected.close()
    }
}
