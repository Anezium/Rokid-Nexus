package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SppAuthProtocolTest {
    private val key = ByteArray(32) { it.toByte() }
    private val phoneNonce = ByteArray(32) { (it + 32).toByte() }
    private val glassesNonce = ByteArray(32) { (it + 64).toByte() }
    private val domain = "RokidBus-SPP-v1\u0000".toByteArray()

    @Test fun mutualHandshakeAndJsonAndBinaryRoundTrips() {
        val (phone, glasses) = handshake()
        val json = BusEnvelope("/test", "json-id", JSONObject().put("text", "Привет"))
        val binary = BusEnvelope("/binary", "binary-id", JSONObject().put("part", 3), byteArrayOf(0, 1, -1))
        for (envelope in listOf(json, binary, json.copy(id = "next"))) {
            val received = glasses.read(ByteArrayInputStream(encode(phone, envelope)))!!
            assertEquals(envelope.path, received.path)
            assertEquals(envelope.id, received.id)
            assertEquals(envelope.payload.toString(), received.payload.toString())
            assertArrayEquals(envelope.binary, received.binary)
        }
        assertEquals(json.id, phone.read(ByteArrayInputStream(encode(glasses, json)))!!.id)
    }

    @Test fun fixedTranscriptAndHkdfWireVector() {
        val output = ByteArrayOutputStream()
        val challenge = record(2, glassesNonce + proof("glasses-proof")) + record(4, proof("glasses-ready"))
        val phone = SppAuthProtocol.connect(ByteArrayInputStream(challenge), output, key, SppAuthProtocol.RecentNonces()) { phoneNonce }
        assertArrayEquals(record(1, phoneNonce) + record(3, proof("phone-proof")), output.toByteArray())
        val envelope = BusEnvelope("/x", "id")
        val wire = encode(phone, envelope)
        val extracted = mac(phoneNonce + glassesNonce, key)
        val directionalKey = mac(extracted, domain + "phone-to-glasses".toByteArray() + byteArrayOf(1))
        assertEquals(2, wire[4].toInt())
        assertEquals(0L, ByteBuffer.wrap(wire, 5, 8).long)
        assertArrayEquals(mac(directionalKey, domain + wire.dropLast(32).toByteArray()), wire.takeLast(32).toByteArray())
        // Independent Python hashlib/hmac vector for the fixed handshake transcript.
        assertEquals("6d24a47f1a8a336567bfdcc7268951cccc3720250961223894786bec5c390b95", proof("glasses-proof").joinToString("") { "%02x".format(it) })
    }

    @Test fun rawPairKeyProofIsNotAcceptedAsHandshakeAuthenticationKey() {
        val oldProof = mac(key, domain + "glasses-proof".toByteArray() + phoneNonce + glassesNonce)
        assertThrows(IOException::class.java) {
            SppAuthProtocol.connect(
                ByteArrayInputStream(record(2, glassesNonce + oldProof)), ByteArrayOutputStream(),
                key, SppAuthProtocol.RecentNonces(),
            ) { phoneNonce }
        }
    }

    @Test fun wrongKeyCannotAuthenticateEitherSide() {
        assertThrows(IOException::class.java) { handshake(serverKey = ByteArray(32) { 99 }) }
        val forgedFinish = record(1, phoneNonce) + record(3, ByteArray(32))
        assertThrows(IOException::class.java) {
            SppAuthProtocol.accept(ByteArrayInputStream(forgedFinish), ByteArrayOutputStream(), key, SppAuthProtocol.RecentNonces()) { glassesNonce }
        }
    }

    @Test fun repeatedPhoneNonceAndOldTranscriptAreRejected() {
        val nonces = SppAuthProtocol.RecentNonces()
        val transcript = record(1, phoneNonce) + record(3, proof("phone-proof"))
        SppAuthProtocol.accept(ByteArrayInputStream(transcript), ByteArrayOutputStream(), key, nonces) { glassesNonce }
        assertThrows(IOException::class.java) {
            SppAuthProtocol.accept(ByteArrayInputStream(transcript), ByteArrayOutputStream(), key, nonces) { ByteArray(32) { 90 } }
        }
        // Even with an empty replay cache, a new local challenge defeats an old phone proof.
        assertThrows(IOException::class.java) {
            SppAuthProtocol.accept(ByteArrayInputStream(transcript), ByteArrayOutputStream(), key, SppAuthProtocol.RecentNonces()) { ByteArray(32) { 90 } }
        }
    }

    @Test fun repeatedGlassesNonceAndReflectedProofAreRejected() {
        val nonces = SppAuthProtocol.RecentNonces()
        val transcript = record(2, glassesNonce + proof("glasses-proof")) + record(4, proof("glasses-ready"))
        SppAuthProtocol.connect(ByteArrayInputStream(transcript), ByteArrayOutputStream(), key, nonces) { phoneNonce }
        assertThrows(IOException::class.java) {
            SppAuthProtocol.connect(ByteArrayInputStream(transcript), ByteArrayOutputStream(), key, nonces) { phoneNonce }
        }
        assertThrows(IOException::class.java) {
            SppAuthProtocol.connect(ByteArrayInputStream(record(2, glassesNonce + proof("phone-proof"))), ByteArrayOutputStream(), key, SppAuthProtocol.RecentNonces()) { phoneNonce }
        }
    }

    @Test fun missingReadyAndLegacyOrUnknownVersionAreRejected() {
        for (input in listOf(
            record(2, glassesNonce + proof("glasses-proof")),
            record(2, glassesNonce + proof("glasses-proof")) + record(4, ByteArray(32)),
            record(2, glassesNonce + proof("glasses-proof")).also { it[4] = 2 },
            ByteArrayOutputStream().also { FrameProtocol.write(it, BusEnvelope("/legacy")) }.toByteArray(),
        )) {
            assertThrows(IOException::class.java) {
                SppAuthProtocol.connect(ByteArrayInputStream(input), ByteArrayOutputStream(), key, SppAuthProtocol.RecentNonces()) { phoneNonce }
            }
        }
    }

    @Test fun replayedAndSkippedFramesCloseSession() {
        val (phone, glasses) = handshake()
        val frame = encode(phone)
        assertNotNull(glasses.read(ByteArrayInputStream(frame)))
        assertThrows(IOException::class.java) { glasses.read(ByteArrayInputStream(frame)) }
        assertThrows(IOException::class.java) { glasses.read(ByteArrayInputStream(encode(phone))) }
        assertThrows(IOException::class.java) { glasses.write(ByteArrayOutputStream(), BusEnvelope("/after-failure")) }
        val (otherPhone, otherGlasses) = handshake()
        encode(otherPhone)
        assertThrows(IOException::class.java) { otherGlasses.read(ByteArrayInputStream(encode(otherPhone))) }
    }

    @Test fun tamperedHeaderCounterBodyAndMacAreRejectedBeforeParsing() {
        for (position in listOf(0, 4, 5, 13, -1)) {
            val (phone, glasses) = handshake()
            val wire = encode(phone)
            val index = if (position == -1) wire.lastIndex else position
            wire[index] = (wire[index].toInt() xor 1).toByte()
            assertThrows(IOException::class.java) { glasses.read(ByteArrayInputStream(wire)) }
        }
    }

    @Test fun reflectedAndCrossSessionFramesAreRejected() {
        val (phone, glasses) = handshake()
        assertThrows(IOException::class.java) { phone.read(ByteArrayInputStream(encode(phone))) }
        val (newPhone, _) = handshake(serverNonce = ByteArray(32) { 80 })
        assertThrows(IOException::class.java) { glasses.read(ByteArrayInputStream(encode(newPhone))) }
    }

    @Test fun truncatedOversizedAndLegacyFramesAreRejected() {
        val legacy = ByteArrayOutputStream().also { FrameProtocol.write(it, BusEnvelope("/legacy")) }.toByteArray()
        val invalid = listOf(byteArrayOf(0), ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array(), legacy)
        for (bytes in invalid) {
            val (_, glasses) = handshake()
            assertThrows(IOException::class.java) { glasses.read(ByteArrayInputStream(bytes)) }
        }
        val (phone, glasses) = handshake()
        assertThrows(IOException::class.java) { glasses.read(ByteArrayInputStream(encode(phone).dropLast(1).toByteArray())) }
    }

    @Test fun binaryBodyLimitIsPreservedByWrapper() {
        val (phone, glasses) = handshake()
        val base = BusEnvelope("/large", "fixed", binary = ByteArray(0))
        val overhead = FrameProtocol.toFrameBody(base).size
        val envelope = base.copy(binary = ByteArray(FrameProtocol.MAX_FRAME_BYTES - overhead))
        val wire = encode(phone, envelope)
        assertEquals(FrameProtocol.MAX_FRAME_BYTES + 45, wire.size)
        assertArrayEquals(envelope.binary, glasses.read(ByteArrayInputStream(wire))!!.binary)
        assertThrows(IOException::class.java) {
            encode(phone, envelope.copy(binary = ByteArray(FrameProtocol.MAX_FRAME_BYTES - overhead + 1)))
        }
    }

    private fun handshake(serverKey: ByteArray = key, serverNonce: ByteArray = glassesNonce): Pair<SppAuthProtocol.Session, SppAuthProtocol.Session> {
        val serverInput = PipedInputStream(1024)
        val clientOutput = PipedOutputStream(serverInput)
        val clientInput = PipedInputStream(1024)
        val serverOutput = PipedOutputStream(clientInput)
        val executor = Executors.newSingleThreadExecutor()
        val server = executor.submit<SppAuthProtocol.Session> {
            SppAuthProtocol.accept(serverInput, serverOutput, serverKey, SppAuthProtocol.RecentNonces()) { serverNonce }
        }
        return try {
            val phone = SppAuthProtocol.connect(clientInput, clientOutput, key, SppAuthProtocol.RecentNonces()) { phoneNonce }
            phone to server.get(3, TimeUnit.SECONDS)
        } finally {
            clientOutput.close()
            serverOutput.close()
            clientInput.close()
            serverInput.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private fun encode(session: SppAuthProtocol.Session, envelope: BusEnvelope = BusEnvelope("/test", "fixed")): ByteArray =
        ByteArrayOutputStream().also { session.write(it, envelope) }.toByteArray()

    private fun record(type: Int, payload: ByteArray): ByteArray = byteArrayOf(0x4e, 0x58, 0x53, 0x50, 1, type.toByte()) + payload
    private fun proof(label: String): ByteArray {
        val authKey = mac(mac(domain, key), domain + "handshake-auth".toByteArray() + byteArrayOf(1))
        return mac(authKey, domain + label.toByteArray() + phoneNonce + glassesNonce)
    }
    private fun mac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }
}
