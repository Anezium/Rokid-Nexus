package com.anezium.rokidbus.glasses

import android.content.Context
import android.net.wifi.WifiManager
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

internal object SelfArmCommandBridgeProtocol {
    const val WIFI_ENABLE = "wifi_enable"
    const val WIFI_DISABLE = "wifi_disable"
    const val WIFI_CONNECT = "wifi_connect"
    const val MAX_REQUEST_BYTES = 512
    private val secretRegex = Regex("[0-9a-f]{64}")
    private val nonceRegex = Regex("[0-9a-f]{32}")
    private val tokenRegex = Regex("[0-9a-f]{64}")
    private val encodedArgumentRegex = Regex("[A-Za-z0-9+/]+={0,2}")
    private val allowedCommands = setOf(WIFI_ENABLE, WIFI_DISABLE, WIFI_CONNECT)

    sealed interface Verification {
        data class Accepted(
            val command: String,
            val nonce: String,
            val arguments: List<String> = emptyList(),
        ) : Verification
        data class Rejected(val reason: String) : Verification
    }

    fun token(
        secretHex: String,
        command: String,
        nonce: String,
        arguments: List<String> = emptyList(),
    ): String {
        require(secretRegex.matches(secretHex)) { "Secret must be 32-byte lowercase hex" }
        require(command in allowedCommands) { "Command is not allowed" }
        require(nonceRegex.matches(nonce)) { "Nonce must be 16-byte lowercase hex" }
        // Android 11+ scoped storage already prevents another app from writing this app's
        // external-files channel. The prefix-keyed digest is defense-in-depth for that threat.
        // SHA-256 length extension cannot yield an accepted request: the parser requires the
        // exact command shape and authenticated arguments, and an attacker cannot write the
        // channel directory in the first place.
        require(arguments.none { ':' in it || '\n' in it }) { "Invalid command argument" }
        val input = buildList {
            add(secretHex)
            add(command)
            add(nonce)
            addAll(arguments)
        }.joinToString(":").toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(input)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    fun request(
        secretHex: String,
        command: String,
        nonce: String,
        arguments: List<String> = emptyList(),
    ): String = buildList {
        add(command)
        add(nonce)
        addAll(arguments)
        add(token(secretHex, command, nonce, arguments))
    }.joinToString(":", postfix = "\n")

    fun verify(request: String, secretHex: String, seenNonces: Set<String>): Verification {
        if (request.toByteArray(Charsets.UTF_8).size !in 1..MAX_REQUEST_BYTES) {
            return Verification.Rejected("size")
        }
        if (!request.endsWith('\n') || request.count { it == '\n' } != 1) {
            return Verification.Rejected("format")
        }
        if (!secretRegex.matches(secretHex)) return Verification.Rejected("secret")
        val fields = request.dropLast(1).split(':')
        if (fields.size < 3) return Verification.Rejected("format")
        val command = fields.first()
        val nonce = fields.getOrNull(1).orEmpty()
        val suppliedToken = fields.last()
        if (command !in allowedCommands) return Verification.Rejected("command")
        val arguments = fields.subList(2, fields.lastIndex)
        val validShape = when (command) {
            WIFI_ENABLE, WIFI_DISABLE -> arguments.isEmpty()
            WIFI_CONNECT -> arguments.size == 2 && arguments.all {
                it.length in 1..172 && encodedArgumentRegex.matches(it)
            }
            else -> false
        }
        if (!validShape) return Verification.Rejected("format")
        if (!nonceRegex.matches(nonce) || !tokenRegex.matches(suppliedToken)) {
            return Verification.Rejected("format")
        }
        if (nonce in seenNonces) return Verification.Rejected("replay")
        val expectedToken = token(secretHex, command, nonce, arguments)
        if (!MessageDigest.isEqual(expectedToken.toByteArray(), suppliedToken.toByteArray())) {
            return Verification.Rejected("auth")
        }
        return Verification.Accepted(command, nonce, arguments)
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
        return execute(context.applicationContext, command, enabled, timeoutMs)
    }

    fun connectWifiNetwork(
        context: Context,
        ssid: String,
        passphrase: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean {
        if (ssid.isBlank() || ssid.length > 128 || passphrase.length !in 8..128 || timeoutMs <= 0L) {
            return false
        }
        val arguments = listOf(
            Base64.encodeToString(ssid.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
            Base64.encodeToString(passphrase.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
        )
        if (arguments.any { it.length !in 1..172 }) return false
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return false
        if (isConnectedTo(wifiManager, ssid)) return true
        return submit(
            context.applicationContext,
            SelfArmCommandBridgeProtocol.WIFI_CONNECT,
            arguments,
            timeoutMs,
        ) { awaitWifiNetwork(wifiManager, ssid, timeoutMs) }
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

    private fun execute(context: Context, command: String, targetEnabled: Boolean, timeoutMs: Long): Boolean {
        if (timeoutMs <= 0L) return false
        val wifiManager = context.getSystemService(WifiManager::class.java) ?: return false
        // Success is confirmed by observing the Wi-Fi state in-process rather than by reading a
        // response file the shell bridge writes: a file created by the bridge's (shell) uid can be
        // hidden from the app's uid for seconds by the FUSE negative-dentry cache, which made every
        // request look like it failed. Watching WifiManager avoids the cross-uid channel entirely.
        if (isWifiEnabledSafe(wifiManager) == targetEnabled) return true
        return submit(context, command, emptyList(), timeoutMs) {
            awaitWifiState(wifiManager, targetEnabled, timeoutMs)
        }
    }

    private fun submit(
        context: Context,
        command: String,
        arguments: List<String>,
        timeoutMs: Long,
        awaitResult: () -> Boolean,
    ): Boolean {
        val secret = loadSecretHex(context) ?: return false
        val channel = ensureChannelDir(context) ?: return false
        val nonce = randomHex(16)
        val requestFile = File(channel, "$nonce.request")
        val tempFile = File(channel, ".$nonce.request.${UUID.randomUUID()}.tmp")
        return try {
            val request = SelfArmCommandBridgeProtocol.request(secret, command, nonce, arguments)
            if (request.toByteArray(Charsets.UTF_8).size > SelfArmCommandBridgeProtocol.MAX_REQUEST_BYTES) {
                return false
            }
            tempFile.writeText(request)
            if (!tempFile.renameTo(requestFile)) return false
            ringDoorbell(File(channel, DOORBELL_NAME), nonce)
            awaitResult()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        } finally {
            tempFile.delete()
            requestFile.delete()
        }
    }

    private fun isWifiEnabledSafe(wifiManager: WifiManager): Boolean =
        runCatching { wifiManager.isWifiEnabled }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun isConnectedTo(wifiManager: WifiManager, targetSsid: String): Boolean =
        runCatching {
            LohsGatewayResolver.normalizeSsid(wifiManager.connectionInfo?.ssid) == targetSsid
        }.getOrDefault(false)

    @Throws(InterruptedException::class)
    private fun awaitWifiState(wifiManager: WifiManager, target: Boolean, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (isWifiEnabledSafe(wifiManager) == target) return true
            Thread.sleep(RESPONSE_POLL_MS)
        }
        return false
    }

    @Suppress("DEPRECATION")
    @Throws(InterruptedException::class)
    private fun awaitWifiNetwork(
        wifiManager: WifiManager,
        targetSsid: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (isConnectedTo(wifiManager, targetSsid)) return true
            Thread.sleep(RESPONSE_POLL_MS)
        }
        return false
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
