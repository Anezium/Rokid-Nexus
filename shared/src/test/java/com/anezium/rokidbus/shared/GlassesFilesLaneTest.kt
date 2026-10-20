package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class GlassesFilesLaneTest {
    private val id = "files_0123456789abcdef"
    private val key = GlassesFilesLane.newKey()

    @Test
    fun `hello proves the key for this transfer only`() {
        val bytes = ByteArrayOutputStream().also { GlassesFilesLane.writeHello(DataOutputStream(it), id, key) }.toByteArray()

        assertTrue(GlassesFilesLane.readHello(input(bytes), id, key))
        assertFalse(GlassesFilesLane.readHello(input(bytes), "files_another_transfer", key))
        assertFalse(GlassesFilesLane.readHello(input(bytes), id, GlassesFilesLane.newKey()))
        assertFalse(GlassesFilesLane.readHello(input(ByteArray(64)), id, key))
    }

    @Test
    fun `sealed frames round trip in order`() {
        val out = ByteArrayOutputStream()
        val sealer = GlassesFilesLane.Sealer(key, id, GlassesFilesLane.Direction.PHONE_TO_GLASSES)
        val chunk = ByteArray(GlassesFilesLane.CHUNK_BYTES) { (it % 251).toByte() }
        sealer.write(DataOutputStream(out), GlassesFilesLane.Frame.Data(0, chunk))
        sealer.write(DataOutputStream(out), GlassesFilesLane.Frame.Ack(42))

        val opener = GlassesFilesLane.Opener(key, id, GlassesFilesLane.Direction.PHONE_TO_GLASSES)
        val stream = input(out.toByteArray())
        val data = opener.read(stream) as GlassesFilesLane.Frame.Data
        assertEquals(0L, data.offset)
        assertArrayEquals(chunk, data.bytes)
        assertEquals(GlassesFilesLane.Frame.Ack(42), opener.read(stream))
    }

    @Test
    fun `a frame from the other direction, another key or out of order is refused`() {
        fun sealed(direction: GlassesFilesLane.Direction, sealingKey: ByteArray = key, frames: Int = 1): ByteArray {
            val out = ByteArrayOutputStream()
            val sealer = GlassesFilesLane.Sealer(sealingKey, id, direction)
            repeat(frames) { sealer.write(DataOutputStream(out), GlassesFilesLane.Frame.Ack(it.toLong())) }
            return out.toByteArray()
        }
        val opener = { GlassesFilesLane.Opener(key, id, GlassesFilesLane.Direction.PHONE_TO_GLASSES) }

        assertThrows(IOException::class.java) { opener().read(input(sealed(GlassesFilesLane.Direction.GLASSES_TO_PHONE))) }
        assertThrows(IOException::class.java) {
            opener().read(input(sealed(GlassesFilesLane.Direction.PHONE_TO_GLASSES, GlassesFilesLane.newKey())))
        }
        // Dropping the first frame shifts the nonce: the second can no longer be opened first.
        val two = sealed(GlassesFilesLane.Direction.PHONE_TO_GLASSES, frames = 2)
        val second = two.copyOfRange(two.size / 2, two.size)
        assertThrows(IOException::class.java) { opener().read(input(second)) }
    }

    @Test
    fun `a tampered byte fails authentication`() {
        val out = ByteArrayOutputStream()
        GlassesFilesLane.Sealer(key, id, GlassesFilesLane.Direction.GLASSES_TO_PHONE)
            .write(DataOutputStream(out), GlassesFilesLane.Frame.Data(7, byteArrayOf(1, 2, 3)))
        val bytes = out.toByteArray().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }

        assertThrows(IOException::class.java) {
            GlassesFilesLane.Opener(key, id, GlassesFilesLane.Direction.GLASSES_TO_PHONE).read(input(bytes))
        }
    }

    @Test
    fun `keys are 256-bit and encode losslessly`() {
        assertEquals(32, key.size)
        assertArrayEquals(key, GlassesFilesLane.decodeKey(GlassesFilesLane.encodeKey(key)))
        assertNull(GlassesFilesLane.decodeKey("c2hvcnQ="))
        assertNull(GlassesFilesLane.decodeKey("not base64!"))
    }

    @Test
    fun `lane requests and endpoints round trip and stay optional`() {
        val endpoint = GlassesFilesLaneEndpoint("192.168.1.84", 40123, GlassesFilesLane.encodeKey(key))
        listOf(
            GlassesFilesRequest.UploadBegin(id, "Download", "a.pdf", 3, lane = true),
            GlassesFilesRequest.DownloadBegin(id, "Download/a.pdf", lane = true),
        ).forEach { assertEquals(it, GlassesFilesContract.parseRequest(JSONObject(GlassesFilesContract.encode(it).toString()))) }
        listOf(
            GlassesFilesResult.UploadReady(id, "a.pdf", endpoint),
            GlassesFilesResult.DownloadReady(id, 3, 1, endpoint),
            GlassesFilesResult.UploadReady(id, "a.pdf"),
        ).forEach { assertEquals(it, GlassesFilesContract.parseResult(JSONObject(GlassesFilesContract.encode(it).toString()))) }
        assertFalse(endpoint.toString().contains(endpoint.key))
    }

    @Test
    fun `malformed lane fields invalidate the message`() {
        val ready = GlassesFilesContract.encode(GlassesFilesResult.UploadReady(id, "a.pdf"))
        fun withLane(host: Any, port: Any, laneKey: Any) =
            JSONObject(ready.toString()).put("lane", JSONObject().put("host", host).put("port", port).put("key", laneKey))
        val good = GlassesFilesLane.encodeKey(key)

        assertNull(GlassesFilesContract.parseResult(withLane("glasses.local", 40123, good)))
        assertNull(GlassesFilesContract.parseResult(withLane("192.168.1.256", 40123, good)))
        assertNull(GlassesFilesContract.parseResult(withLane("192.168.01.4", 40123, good)))
        assertNull(GlassesFilesContract.parseResult(withLane("192.168.1.4", 0, good)))
        assertNull(GlassesFilesContract.parseResult(withLane("192.168.1.4", 40123, "c2hvcnQ=")))
        assertNull(GlassesFilesContract.parseResult(JSONObject(ready.toString()).put("lane", true)))
        val begin = GlassesFilesContract.encode(GlassesFilesRequest.UploadBegin(id, "", "a.pdf", 1))
        assertNull(GlassesFilesContract.parseRequest(JSONObject(begin.toString()).put("lane", "yes")))
    }

    private fun input(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))
}
