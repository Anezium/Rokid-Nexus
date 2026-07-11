package com.anezium.rokidbus.glasses

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPrivateCrtKeySpec
import javax.crypto.Cipher

internal class AdbLoopbackClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 5555,
    private val timeoutMs: Int = 5_000,
) {
    data class ShellResult(
        val connected: Boolean,
        val authenticated: Boolean,
        val commandSent: Boolean,
        val output: String,
    )

    fun runShell(command: String, keyMaterial: AdbKeyMaterial): ShellResult {
        val socket = Socket()
        return runCatching {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            send(output, CMD_CNXN, ADB_VERSION, MAX_DATA, "host::\u0000".toByteArray(Charsets.UTF_8))
            if (!authenticate(input, output, keyMaterial.privateKey)) {
                ShellResult(
                    connected = true,
                    authenticated = false,
                    commandSent = false,
                    output = "ADB auth rejected or timed out",
                )
            } else {
                ShellResult(
                    connected = true,
                    authenticated = true,
                    commandSent = true,
                    output = openShell(input, output, command),
                )
            }
        }.getOrElse {
            ShellResult(
                connected = false,
                authenticated = false,
                commandSent = false,
                output = it.message.orEmpty().ifBlank { it::class.java.simpleName },
            )
        }.also {
            runCatching { socket.close() }
        }
    }

    private fun authenticate(input: InputStream, output: java.io.OutputStream, privateKey: PrivateKey): Boolean {
        var attempts = 0
        while (attempts < 3) {
            val message = readMessage(input) ?: return false
            when (message.command) {
                CMD_CNXN -> return true
                CMD_AUTH -> {
                    if (message.arg0 != AUTH_TOKEN || message.data.isEmpty()) return false
                    val signature = when (attempts) {
                        0 -> AdbKeyMaterial.signSha1WithRsa(message.data, privateKey)
                        else -> AdbKeyMaterial.signAdbDigest(message.data, privateKey)
                    } ?: return false
                    attempts += 1
                    send(output, CMD_AUTH, AUTH_SIGNATURE, 0, signature)
                }
            }
        }
        return false
    }

    private fun openShell(input: InputStream, output: java.io.OutputStream, command: String): String {
        val localId = 1
        var remoteId = 0
        send(output, CMD_OPEN, localId, 0, "shell:sh\u0000".toByteArray(Charsets.UTF_8))
        val collected = ByteArrayOutputStream()
        while (remoteId == 0) {
            val message = try {
                readMessage(input)
            } catch (_: SocketTimeoutException) {
                return collected.toString(Charsets.UTF_8.name())
            } ?: return collected.toString(Charsets.UTF_8.name())
            when (message.command) {
                CMD_OKAY -> remoteId = message.arg0
                CMD_WRTE -> {
                    if (message.data.isNotEmpty()) collected.write(message.data)
                    send(output, CMD_OKAY, localId, message.arg0, ByteArray(0))
                }
                CMD_CLSE -> return collected.toString(Charsets.UTF_8.name())
            }
        }

        val script = "${command.trimEnd()}\nexit\n".toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < script.size) {
            val size = (script.size - offset).coerceAtMost(WRITE_CHUNK_SIZE)
            send(output, CMD_WRTE, localId, remoteId, script.copyOfRange(offset, offset + size))
            offset += size
            if (!waitForWriteAck(input, output, localId, remoteId, collected)) {
                return collected.toString(Charsets.UTF_8.name())
            }
        }

        while (true) {
            val message = try {
                readMessage(input)
            } catch (_: SocketTimeoutException) {
                return collected.toString(Charsets.UTF_8.name())
            } ?: return collected.toString(Charsets.UTF_8.name())
            when (message.command) {
                CMD_OKAY -> if (remoteId == 0) remoteId = message.arg0
                CMD_WRTE -> {
                    if (remoteId == 0) remoteId = message.arg0
                    if (message.data.isNotEmpty()) collected.write(message.data)
                    send(output, CMD_OKAY, localId, remoteId, ByteArray(0))
                }
                CMD_CLSE -> {
                    if (remoteId != 0) send(output, CMD_CLSE, localId, remoteId, ByteArray(0))
                    return collected.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    private fun waitForWriteAck(
        input: InputStream,
        output: java.io.OutputStream,
        localId: Int,
        remoteId: Int,
        collected: ByteArrayOutputStream,
    ): Boolean {
        while (true) {
            val message = try {
                readMessage(input)
            } catch (_: SocketTimeoutException) {
                return false
            } ?: return false
            when (message.command) {
                CMD_OKAY -> return true
                CMD_WRTE -> {
                    if (message.data.isNotEmpty()) collected.write(message.data)
                    send(output, CMD_OKAY, localId, remoteId, ByteArray(0))
                }
                CMD_CLSE -> return false
            }
        }
    }

    private fun send(
        output: java.io.OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        data: ByteArray,
    ) {
        val header = ByteBuffer.allocate(ADB_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(data.size)
            .putInt(data.checksum())
            .putInt(command xor -1)
            .array()
        output.write(header)
        if (data.isNotEmpty()) output.write(data)
        output.flush()
    }

    private fun readMessage(input: InputStream): Message? {
        val header = input.readExactly(ADB_HEADER_SIZE) ?: return null
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val length = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        if (magic != (command xor -1) || length < 0 || length > MAX_DATA) return null
        val data = if (length > 0) input.readExactly(length) ?: return null else ByteArray(0)
        if (checksum != data.checksum()) return null
        return Message(command, arg0, arg1, data)
    }

    private data class Message(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray,
    )

    companion object {
        private const val ADB_HEADER_SIZE = 24
        private const val ADB_VERSION = 0x01000000
        private const val MAX_DATA = 4096
        private const val WRITE_CHUNK_SIZE = 2048
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2

        private val CMD_CNXN = command("CNXN")
        private val CMD_AUTH = command("AUTH")
        private val CMD_OPEN = command("OPEN")
        private val CMD_OKAY = command("OKAY")
        private val CMD_CLSE = command("CLSE")
        private val CMD_WRTE = command("WRTE")

        private fun command(value: String): Int =
            value[0].code or
                (value[1].code shl 8) or
                (value[2].code shl 16) or
                (value[3].code shl 24)
    }
}

internal data class AdbKeyMaterial(
    val privateKey: PrivateKey,
    val publicKey: String,
) {
    companion object {
        fun parse(privateKeyPem: String, publicKey: String): AdbKeyMaterial? =
            parsePrivateKey(privateKeyPem)?.let { AdbKeyMaterial(it, publicKey) }

        fun parsePrivateKey(privateKeyPem: String): PrivateKey? =
            AdbPrivateKeyParser.parse(privateKeyPem)

        fun signSha1WithRsa(token: ByteArray, privateKey: PrivateKey): ByteArray? =
            runCatching {
                Signature.getInstance("SHA1withRSA").apply {
                    initSign(privateKey)
                    update(token)
                }.sign()
            }.getOrNull()

        fun signAdbDigest(token: ByteArray, privateKey: PrivateKey): ByteArray? =
            runCatching {
                val digestInfo = SHA1_DIGEST_INFO_PREFIX + token
                Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
                    init(Cipher.ENCRYPT_MODE, privateKey)
                }.doFinal(digestInfo)
            }.getOrNull()

        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }
}

private object AdbPrivateKeyParser {
    fun parse(text: String): PrivateKey? {
        val factory = KeyFactory.getInstance("RSA")
        pemBytes(text, "PRIVATE KEY")?.let { bytes ->
            runCatching { return factory.generatePrivate(PKCS8EncodedKeySpec(bytes)) }
        }
        pemBytes(text, "RSA PRIVATE KEY")?.let { bytes ->
            runCatching { return factory.generatePrivate(pkcs1Spec(bytes)) }
        }
        return null
    }

    private fun pkcs1Spec(bytes: ByteArray): RSAPrivateCrtKeySpec {
        val sequence = DerReader(bytes).readSequence()
        sequence.readInteger()
        val modulus = sequence.readInteger()
        val publicExponent = sequence.readInteger()
        val privateExponent = sequence.readInteger()
        val primeP = sequence.readInteger()
        val primeQ = sequence.readInteger()
        val primeExponentP = sequence.readInteger()
        val primeExponentQ = sequence.readInteger()
        val crtCoefficient = sequence.readInteger()
        return RSAPrivateCrtKeySpec(
            modulus,
            publicExponent,
            privateExponent,
            primeP,
            primeQ,
            primeExponentP,
            primeExponentQ,
            crtCoefficient,
        )
    }

    private fun pemBytes(text: String, label: String): ByteArray? {
        val begin = "-----BEGIN $label-----"
        val end = "-----END $label-----"
        val start = text.indexOf(begin)
        val stop = text.indexOf(end)
        if (start < 0 || stop < 0 || stop <= start) return null
        val base64 = text.substring(start + begin.length, stop)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("")
        return android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
    }
}

private class DerReader(private val data: ByteArray) {
    private var offset = 0

    fun readSequence(): DerReader {
        expect(0x30)
        val length = readLength()
        val start = offset
        offset += length
        return DerReader(data.copyOfRange(start, start + length))
    }

    fun readInteger(): BigInteger {
        expect(0x02)
        val length = readLength()
        val start = offset
        offset += length
        return BigInteger(data.copyOfRange(start, start + length))
    }

    private fun expect(expected: Int) {
        val actual = data[offset].toInt() and 0xff
        if (actual != expected) error("DER tag mismatch")
        offset += 1
    }

    private fun readLength(): Int {
        val first = data[offset++].toInt() and 0xff
        if ((first and 0x80) == 0) return first
        val count = first and 0x7f
        var length = 0
        repeat(count) {
            length = (length shl 8) or (data[offset++].toInt() and 0xff)
        }
        return length
    }
}

private fun ByteArray.checksum(): Int =
    fold(0) { sum, byte -> sum + (byte.toInt() and 0xff) }

private fun InputStream.readExactly(length: Int): ByteArray? {
    val data = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(data, offset, length - offset)
        if (read < 0) return null
        offset += read
    }
    return data
}
