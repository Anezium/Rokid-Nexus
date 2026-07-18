package com.anezium.rokidbus.glasses

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

internal object SelfArmCommandBridgeProtocol {
    const val WIFI_ENABLE = "wifi_enable"
    const val WIFI_DISABLE = "wifi_disable"
    const val MAX_REQUEST_BYTES = 160
    private val secretRegex = Regex("[0-9a-f]{64}")
    private val nonceRegex = Regex("[0-9a-f]{32}")
    private val tokenRegex = Regex("[0-9a-f]{64}")
    private val allowedCommands = setOf(WIFI_ENABLE, WIFI_DISABLE)

    sealed interface Verification {
        data class Accepted(val command: String, val nonce: String) : Verification
        data class Rejected(val reason: String) : Verification
    }

    fun token(secretHex: String, command: String, nonce: String): String {
        require(secretRegex.matches(secretHex)) { "Secret must be 32-byte lowercase hex" }
        require(command in allowedCommands) { "Command is not allowed" }
        require(nonceRegex.matches(nonce)) { "Nonce must be 16-byte lowercase hex" }
        // Android 11+ scoped storage already prevents another app from writing this app's
        // external-files channel. The prefix-keyed digest is defense-in-depth for that threat.
        // SHA-256 length extension cannot yield an accepted request: the parser requires the
        // exact fixed command:nonce form, only two literal commands exist, and an attacker cannot
        // write the channel directory in the first place.
        val input = "$secretHex:$command:$nonce".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(input)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    fun request(secretHex: String, command: String, nonce: String): String =
        "$command:$nonce:${token(secretHex, command, nonce)}\n"

    fun verify(request: String, secretHex: String, seenNonces: Set<String>): Verification {
        if (request.toByteArray(Charsets.UTF_8).size !in 1..MAX_REQUEST_BYTES) {
            return Verification.Rejected("size")
        }
        if (!request.endsWith('\n') || request.count { it == '\n' } != 1) {
            return Verification.Rejected("format")
        }
        if (!secretRegex.matches(secretHex)) return Verification.Rejected("secret")
        val fields = request.dropLast(1).split(':')
        if (fields.size != 3) return Verification.Rejected("format")
        val (command, nonce, suppliedToken) = fields
        if (command !in allowedCommands) return Verification.Rejected("command")
        if (!nonceRegex.matches(nonce) || !tokenRegex.matches(suppliedToken)) {
            return Verification.Rejected("format")
        }
        if (nonce in seenNonces) return Verification.Rejected("replay")
        val expectedToken = token(secretHex, command, nonce)
        if (!MessageDigest.isEqual(expectedToken.toByteArray(), suppliedToken.toByteArray())) {
            return Verification.Rejected("auth")
        }
        return Verification.Accepted(command, nonce)
    }

    fun isValidSecret(secretHex: String): Boolean = secretRegex.matches(secretHex)
}

internal object SelfArmCommandBridgeClient {
    private const val CHANNEL_NAME = "cmd_bridge"
    private const val DOORBELL_NAME = "doorbell"
    private const val SECRET_FILE_NAME = "rokid-nexus-cmd-bridge.secret"
    // svc wifi enable blocks in the bridge until the framework brings Wi-Fi up, and the bridge may
    // wait up to its poll interval before it even sees the request, so the response can land a
    // couple of seconds out. Wait long enough to read it instead of racing to the accessibility
    // fallback (which would defeat the point of the silent bridge); a genuinely dead bridge still
    // falls back within this window, and CameraLink waits longer still.
    private const val DEFAULT_TIMEOUT_MS = 6_000L
    private const val RESPONSE_POLL_MS = 50L
    const val SECRET_PLACEHOLDER = "__ROKID_NEXUS_BRIDGE_SECRET_HEX__"
    private val secureRandom = SecureRandom()
    private val secretLock = Any()

    fun setWifiEnabled(context: Context, enabled: Boolean, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val command = if (enabled) {
            SelfArmCommandBridgeProtocol.WIFI_ENABLE
        } else {
            SelfArmCommandBridgeProtocol.WIFI_DISABLE
        }
        return execute(context.applicationContext, command, timeoutMs)
    }

    internal fun ensureSecretHex(context: Context): String = synchronized(secretLock) {
        loadSecretHex(context)?.let { return@synchronized it }
        val file = secretFile(context)
        val dir = file.parentFile ?: error("Bridge secret has no parent directory")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            error("Could not create self-arm directory")
        }
        val secret = randomHex(32)
        val temp = File(dir, ".$SECRET_FILE_NAME.${UUID.randomUUID()}.tmp")
        temp.writeText("$secret\n")
        makeOwnerOnly(temp)
        runCatching {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
        makeOwnerOnly(file)
        secret
    }

    internal fun loadSecretHex(context: Context): String? = runCatching {
        val value = secretFile(context).readText().trim()
        value.takeIf(SelfArmCommandBridgeProtocol::isValidSecret)
    }.getOrNull()

    internal fun renderBridgeScript(assetScript: String, secretHex: String): String {
        require(SelfArmCommandBridgeProtocol.isValidSecret(secretHex)) { "Invalid bridge secret" }
        require(assetScript.windowed(SECRET_PLACEHOLDER.length).count { it == SECRET_PLACEHOLDER } == 1) {
            "Bridge asset must contain exactly one secret placeholder"
        }
        return assetScript.replace(SECRET_PLACEHOLDER, secretHex)
    }

    /**
     * The channel directory must be owned by the app so that both the app (requests) and the
     * shell-uid bridge (responses/FIFO, via the shared ext_data_rw group) can write into it.
     * Create it here, from the app, before the bridge is ever spawned — if the bridge created it
     * first it would be shell-owned and the app could not drop requests into it.
     */
    internal fun ensureChannelDir(context: Context): File? {
        val externalFiles = context.applicationContext.getExternalFilesDir(null) ?: return null
        val channel = File(externalFiles, CHANNEL_NAME)
        if (!channel.isDirectory && !channel.mkdirs() && !channel.isDirectory) return null
        return channel
    }

    private fun execute(context: Context, command: String, timeoutMs: Long): Boolean {
        if (timeoutMs <= 0L) return false
        val secret = loadSecretHex(context) ?: return false
        val channel = ensureChannelDir(context) ?: return false
        val nonce = randomHex(16)
        val requestFile = File(channel, "$nonce.request")
        val responseFile = File(channel, "$nonce.response")
        val tempFile = File(channel, ".$nonce.request.${UUID.randomUUID()}.tmp")
        return try {
            responseFile.delete()
            tempFile.writeText(SelfArmCommandBridgeProtocol.request(secret, command, nonce))
            if (!tempFile.renameTo(requestFile)) return false
            ringDoorbell(File(channel, DOORBELL_NAME), nonce)
            awaitResponse(responseFile, nonce, timeoutMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        } finally {
            tempFile.delete()
            requestFile.delete()
            responseFile.delete()
        }
    }

    private fun ringDoorbell(doorbell: File, nonce: String) {
        val descriptor = runCatching {
            Os.open(
                doorbell.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC,
                0,
            )
        }.getOrNull() ?: return
        try {
            val bytes = "$nonce\n".toByteArray(Charsets.US_ASCII)
            Os.write(descriptor, bytes, 0, bytes.size)
        } finally {
            runCatching { Os.close(descriptor) }
        }
    }

    @Throws(InterruptedException::class)
    private fun awaitResponse(responseFile: File, nonce: String, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (responseFile.isFile) {
                val size = responseFile.length()
                if (size !in 1..96) return false
                val fields = responseFile.readText().trimEnd('\n').split(':')
                return fields.size == 2 && fields[0] == nonce && fields[1] == "ok"
            }
            Thread.sleep(RESPONSE_POLL_MS)
        }
        return false
    }

    private fun secretFile(context: Context): File =
        File(File(context.applicationContext.filesDir, "self-arm"), SECRET_FILE_NAME)

    private fun randomHex(byteCount: Int): String = ByteArray(byteCount)
        .also(secureRandom::nextBytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun makeOwnerOnly(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}
