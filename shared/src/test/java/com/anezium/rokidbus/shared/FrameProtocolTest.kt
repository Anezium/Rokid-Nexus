package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FrameProtocolTest {
    @Test
    fun jsonEnvelope_roundTripsThroughStreams() {
        val envelope = BusEnvelope(
            path = "/probe/ping",
            id = "json-id-1",
            payload = JSONObject()
                .put("message", "hello")
                .put("count", 3)
                .put("ok", true),
            v = 7,
        )
        val output = ByteArrayOutputStream()

        FrameProtocol.write(output, envelope)
        val decoded = FrameProtocol.read(ByteArrayInputStream(output.toByteArray()))

        assertNotNull(decoded)
        assertEquals("/probe/ping", decoded!!.path)
        assertEquals("json-id-1", decoded.id)
        assertEquals(7, decoded.v)
        assertEquals("hello", decoded.payload.getString("message"))
        assertEquals(3, decoded.payload.getInt("count"))
        assertTrue(decoded.payload.getBoolean("ok"))
        assertNull(decoded.binary)
    }

    @Test
    fun binaryEnvelope_roundTripsMetaAndDataExactly() {
        val data = byteArrayOf(0x00, 0x01, 0x7B, 0x7F, 0x80.toByte(), 0xFF.toByte())
        val envelope = BusEnvelope(
            path = "/audio/chunk",
            id = "binary-id-1",
            payload = JSONObject()
                .put("status", 206)
                .put("done", false)
                .put("label", "pcm"),
            binary = data,
            v = 4,
        )
        val output = ByteArrayOutputStream()

        FrameProtocol.write(output, envelope)
        val decoded = FrameProtocol.read(ByteArrayInputStream(output.toByteArray()))

        assertNotNull(decoded)
        assertEquals("/audio/chunk", decoded!!.path)
        assertEquals("binary-id-1", decoded.id)
        assertEquals(4, decoded.v)
        assertEquals(206, decoded.payload.getInt("status"))
        assertFalse(decoded.payload.getBoolean("done"))
        assertEquals("pcm", decoded.payload.getString("label"))
        assertArrayEquals(data, decoded.binary)
    }

    @Test
    fun binaryEnvelope_withEmptyMetaOmitsMetaHeaderAndReadsEmptyPayload() {
        val envelope = BusEnvelope(
            path = "/stream/empty-meta",
            id = "binary-empty-meta",
            payload = JSONObject(),
            binary = byteArrayOf(0x11, 0x22),
        )
        val output = ByteArrayOutputStream()

        FrameProtocol.write(output, envelope)
        val wire = output.toByteArray()
        val body = wire.copyOfRange(4, wire.size)
        val header = JSONObject(String(body, 3, binaryHeaderLength(body), Charsets.UTF_8))
        val decoded = FrameProtocol.read(ByteArrayInputStream(wire))

        assertFalse(header.has("meta"))
        assertNotNull(decoded)
        assertEquals(0, decoded!!.payload.length())
        assertArrayEquals(byteArrayOf(0x11, 0x22), decoded.binary)
    }

    @Test
    fun binaryEnvelope_withEmptyDataRoundTrips() {
        val envelope = BusEnvelope(
            path = "/stream/empty-data",
            id = "binary-empty-data",
            payload = JSONObject().put("done", true),
            binary = ByteArray(0),
        )
        val output = ByteArrayOutputStream()

        FrameProtocol.write(output, envelope)
        val decoded = FrameProtocol.read(ByteArrayInputStream(output.toByteArray()))

        assertNotNull(decoded)
        assertEquals("/stream/empty-data", decoded!!.path)
        assertEquals("binary-empty-data", decoded.id)
        assertTrue(decoded.payload.getBoolean("done"))
        assertArrayEquals(ByteArray(0), decoded.binary)
    }

    @Test
    fun jsonWireFormat_startsWithJsonObjectAndLengthPrefixMatchesBodySize() {
        val output = ByteArrayOutputStream()

        FrameProtocol.write(
            output,
            BusEnvelope(
                path = "/json/wire",
                id = "json-wire-id",
                payload = JSONObject().put("kind", "json"),
            ),
        )
        val wire = output.toByteArray()
        val body = wire.copyOfRange(4, wire.size)

        assertEquals(body.size, frameLength(wire))
        assertEquals(0x7B.toByte(), body.first())
        assertEquals("/json/wire", JSONObject(String(body, Charsets.UTF_8)).getString("path"))
    }

    @Test
    fun binaryWireFormat_usesFormatHeaderLengthHeaderAndDataLayout() {
        val data = byteArrayOf(0x45, 0x23, 0x01, 0x00)
        val output = ByteArrayOutputStream()

        FrameProtocol.write(
            output,
            BusEnvelope(
                path = "/binary/wire",
                id = "binary-wire-id",
                payload = JSONObject().put("codec", "raw"),
                binary = data,
            ),
        )
        val wire = output.toByteArray()
        val body = wire.copyOfRange(4, wire.size)
        val headerLength = binaryHeaderLength(body)
        val headerBytes = body.copyOfRange(3, 3 + headerLength)
        val decodedData = body.copyOfRange(3 + headerLength, body.size)
        val header = JSONObject(String(headerBytes, Charsets.UTF_8))

        assertEquals(body.size, frameLength(wire))
        assertEquals(0x01.toByte(), body[0])
        assertEquals(headerBytes.size, headerLength)
        assertEquals("/binary/wire", header.getString("path"))
        assertEquals("binary-wire-id", header.getString("id"))
        assertEquals(1, header.getInt("v"))
        assertEquals("raw", header.getJSONObject("meta").getString("codec"))
        assertArrayEquals(data, decodedData)
    }

    @Test
    fun write_rejectsFrameBodyOverTwoMiB() {
        val tooLarge = ByteArray(MAX_FRAME_BODY_BYTES)

        assertThrows(IllegalArgumentException::class.java) {
            FrameProtocol.write(
                ByteArrayOutputStream(),
                BusEnvelope(
                    path = "/binary/too-large",
                    id = "too-large",
                    binary = tooLarge,
                ),
            )
        }
    }

    @Test
    fun write_rejectsBinaryHeaderOverU16MaxBytes() {
        val hugePath = "p".repeat(U16_MAX + 1)

        assertThrows(IllegalArgumentException::class.java) {
            FrameProtocol.write(
                ByteArrayOutputStream(),
                BusEnvelope(
                    path = hugePath,
                    id = "huge-header",
                    binary = ByteArray(0),
                ),
            )
        }
    }

    @Test
    fun read_declaredLengthZeroThrows() {
        val input = ByteArrayInputStream(intBytes(0))

        assertThrows(IllegalArgumentException::class.java) {
            FrameProtocol.read(input)
        }
    }

    @Test
    fun read_declaredLengthOverTwoMiBThrows() {
        val input = ByteArrayInputStream(intBytes(MAX_FRAME_BODY_BYTES + 1))

        assertThrows(IllegalArgumentException::class.java) {
            FrameProtocol.read(input)
        }
    }

    @Test
    fun read_unknownFormatByteThrows() {
        val input = ByteArrayInputStream(frame(byteArrayOf(0x02)))

        assertThrows(IllegalArgumentException::class.java) {
            FrameProtocol.read(input)
        }
    }

    @Test
    fun read_truncatedBodyThrowsEofException() {
        val input = ByteArrayInputStream(intBytes(5) + byteArrayOf(0x7B, 0x7D))

        assertThrows(EOFException::class.java) {
            FrameProtocol.read(input)
        }
    }

    @Test
    fun read_truncatedHeaderThrowsEofException() {
        val input = ByteArrayInputStream(byteArrayOf(0x00, 0x00))

        assertThrows(EOFException::class.java) {
            FrameProtocol.read(input)
        }
    }

    @Test
    fun read_binaryHeaderLengthPastBodyEndThrows() {
        val body = byteArrayOf(0x01, 0x00, 0x05, 0x7B, 0x7D)
        val input = ByteArrayInputStream(frame(body))

        assertThrows(IllegalArgumentException::class.java) {
            FrameProtocol.read(input)
        }
    }

    @Test
    fun read_binaryHeaderThatIsNotJsonThrows() {
        val body = binaryBody(header = "not-json".toByteArray(Charsets.UTF_8), data = ByteArray(0))
        val input = ByteArrayInputStream(frame(body))

        assertThrows(Exception::class.java) {
            FrameProtocol.read(input)
        }
    }

    @Test
    fun read_twoFramesBackToBackThenCleanEof() {
        val output = ByteArrayOutputStream()
        FrameProtocol.write(
            output,
            BusEnvelope(
                path = "/sequence/json",
                id = "sequence-json",
                payload = JSONObject().put("index", 1),
            ),
        )
        FrameProtocol.write(
            output,
            BusEnvelope(
                path = "/sequence/binary",
                id = "sequence-binary",
                payload = JSONObject().put("index", 2),
                binary = byteArrayOf(0x10, 0x20),
            ),
        )

        val input = ByteArrayInputStream(output.toByteArray())
        val first = FrameProtocol.read(input)
        val second = FrameProtocol.read(input)
        val end = FrameProtocol.read(input)

        assertNotNull(first)
        assertEquals("/sequence/json", first!!.path)
        assertEquals(1, first.payload.getInt("index"))
        assertNull(first.binary)
        assertNotNull(second)
        assertEquals("/sequence/binary", second!!.path)
        assertEquals(2, second.payload.getInt("index"))
        assertArrayEquals(byteArrayOf(0x10, 0x20), second.binary)
        assertNull(end)
    }

    @Test
    fun toJsonBytesAndFromJsonBytes_areSymmetricForJsonEnvelopeFields() {
        val envelope = BusEnvelope(
            path = "/json/symmetry",
            id = "json-symmetry-id",
            payload = JSONObject()
                .put("nested", JSONObject().put("answer", 42))
                .put("name", "symmetry"),
            v = 9,
        )

        val decoded = FrameProtocol.fromJsonBytes(FrameProtocol.toJsonBytes(envelope))

        assertEquals("/json/symmetry", decoded.path)
        assertEquals("json-symmetry-id", decoded.id)
        assertEquals(9, decoded.v)
        assertEquals(42, decoded.payload.getJSONObject("nested").getInt("answer"))
        assertEquals("symmetry", decoded.payload.getString("name"))
        assertNull(decoded.binary)
    }

    @Test
    fun fromJsonBytes_defaultsVToOneAndGeneratesIdWhenIdIsBlank() {
        val json = JSONObject()
            .put("path", "/json/defaults")
            .put("id", "")
            .put("payload", JSONObject().put("ok", true))

        val decoded = FrameProtocol.fromJsonBytes(json.toString().toByteArray(Charsets.UTF_8))

        assertEquals(1, decoded.v)
        assertEquals("/json/defaults", decoded.path)
        assertTrue(decoded.payload.getBoolean("ok"))
        assertTrue(decoded.id.isNotBlank())
        assertNotEquals("", decoded.id)
        assertNull(decoded.binary)
    }

    @Test
    fun fromBinaryBodyDefaultsVToOneAndGeneratesIdWhenHeaderIdIsBlank() {
        val header = JSONObject()
            .put("path", "/binary/defaults")
            .put("id", "")
            .toString()
            .toByteArray(Charsets.UTF_8)
        val input = ByteArrayInputStream(frame(binaryBody(header = header, data = byteArrayOf(0x55))))

        val decoded = FrameProtocol.read(input)

        assertNotNull(decoded)
        assertEquals(1, decoded!!.v)
        assertEquals("/binary/defaults", decoded.path)
        assertTrue(decoded.id.isNotBlank())
        assertNotEquals("", decoded.id)
        assertEquals(0, decoded.payload.length())
        assertArrayEquals(byteArrayOf(0x55), decoded.binary)
    }

    private fun frameLength(wire: ByteArray): Int =
        ByteBuffer.wrap(wire, 0, 4).order(ByteOrder.BIG_ENDIAN).int

    private fun binaryHeaderLength(body: ByteArray): Int =
        ByteBuffer.wrap(body, 1, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and U16_MAX

    private fun frame(body: ByteArray): ByteArray =
        intBytes(body.size) + body

    private fun intBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    private fun binaryBody(header: ByteArray, data: ByteArray): ByteArray =
        ByteBuffer.allocate(1 + 2 + header.size + data.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(0x01.toByte())
            .putShort(header.size.toShort())
            .put(header)
            .put(data)
            .array()

    private companion object {
        private const val MAX_FRAME_BODY_BYTES = 2 * 1024 * 1024
        private const val U16_MAX = 0xffff
    }
}
