package com.flyfishxu.kadb.pair

import com.flyfish233.crypto.spake2.Spake2Context
import com.flyfish233.crypto.spake2.Spake2Role
import org.junit.Assert.*
import org.junit.Test

class PairingCompatibilityTest {
    private fun server(password: ByteArray) = PairingAuthCtx(
        Spake2Context(
            Spake2Role.Bob,
            "adb pair server\u0000".toByteArray(),
            "adb pair client\u0000".toByteArray(),
        ),
        password,
    )

    @Test fun samePasswordAuthenticatesAndTamperingIsRejected() {
        val password = "123456".toByteArray()
        val client = PairingAuthCtx.createAlice(password)!!
        val server = server(password)
        try {
            assertTrue(client.initCipher(server.msg))
            assertTrue(server.initCipher(client.msg))
            val message = "test pairing payload".toByteArray()
            assertArrayEquals(message, server.decrypt(client.encrypt(message)!!))
            val tampered = client.encrypt(message)!!
            tampered[0] = (tampered[0].toInt() xor 1).toByte()
            assertNull(server.decrypt(tampered))
        } finally {
            client.destroy()
            server.destroy()
        }
        assertTrue(client.isDestroyed)
        assertNull(client.encrypt(byteArrayOf(1)))
        assertFalse(client.initCipher(server.msg))
    }

    @Test fun wrongPasswordCannotAuthenticateEncryptedPayload() {
        val client = PairingAuthCtx.createAlice("123456".toByteArray())!!
        val server = server("654321".toByteArray())
        try {
            assertTrue(client.initCipher(server.msg))
            assertTrue(server.initCipher(client.msg))
            assertNull(server.decrypt(client.encrypt("payload".toByteArray())!!))
        } finally {
            client.destroy()
            server.destroy()
        }
    }
}
