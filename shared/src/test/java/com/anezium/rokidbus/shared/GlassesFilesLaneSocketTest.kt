package com.anezium.rokidbus.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The lane codec over a real loopback TCP connection, both directions at once. */
class GlassesFilesLaneSocketTest {
    private val id = "files_0123456789abcdef"

    @Test
    fun `a file crosses the lane intact while acks flow back`() {
        val key = GlassesFilesLane.newKey()
        val payload = ByteArray(3 * GlassesFilesLane.CHUNK_BYTES + 1234) { (it * 31 % 251).toByte() }
        val io = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val received = io.submit<ByteArray> {
                server.accept().use { socket ->
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    check(GlassesFilesLane.readHello(input, id, key))
                    val opener = GlassesFilesLane.Opener(key, id, GlassesFilesLane.Direction.PHONE_TO_GLASSES)
                    val sealer = GlassesFilesLane.Sealer(key, id, GlassesFilesLane.Direction.GLASSES_TO_PHONE)
                    val out = java.io.ByteArrayOutputStream()
                    while (out.size() < payload.size) {
                        val frame = opener.read(input) as GlassesFilesLane.Frame.Data
                        check(frame.offset == out.size().toLong())
                        out.write(frame.bytes)
                        sealer.write(output, GlassesFilesLane.Frame.Ack(out.size().toLong()))
                    }
                    out.toByteArray()
                }
            }
            Socket(server.inetAddress, server.localPort).use { socket ->
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                GlassesFilesLane.writeHello(output, id, key)
                val sealer = GlassesFilesLane.Sealer(key, id, GlassesFilesLane.Direction.PHONE_TO_GLASSES)
                val opener = GlassesFilesLane.Opener(key, id, GlassesFilesLane.Direction.GLASSES_TO_PHONE)
                var offset = 0
                var lastAck = 0L
                while (offset < payload.size) {
                    val end = minOf(payload.size, offset + GlassesFilesLane.CHUNK_BYTES)
                    sealer.write(output, GlassesFilesLane.Frame.Data(offset.toLong(), payload.copyOfRange(offset, end)))
                    offset = end
                    lastAck = (opener.read(input) as GlassesFilesLane.Frame.Ack).stagedBytes
                }
                assertEquals(payload.size.toLong(), lastAck)
            }
            val bytes = received.get(10, TimeUnit.SECONDS)
            assertArrayEquals(digest(payload), digest(bytes))
        }
        io.shutdownNow()
    }

    @Test
    fun `a stranger without the key is turned away at hello`() {
        val key = GlassesFilesLane.newKey()
        val io = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val verdict = io.submit<Boolean> {
                server.accept().use { socket ->
                    GlassesFilesLane.readHello(DataInputStream(socket.getInputStream()), id, key)
                }
            }
            Socket(server.inetAddress, server.localPort).use { socket ->
                GlassesFilesLane.writeHello(DataOutputStream(socket.getOutputStream()), id, GlassesFilesLane.newKey())
            }
            assertFalse(verdict.get(10, TimeUnit.SECONDS))
        }
        io.shutdownNow()
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
