package com.anezium.rokidbus.glasses

import android.content.Context
import android.net.wifi.WifiManager
import android.system.Os
import android.system.OsConstants
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.anezium.rokidbus.shared.MediaSyncCatalogContract
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

internal enum class WifiConnectSecurity(val commandKeyword: String) {
    OPEN("open"),
    WPA2("wpa2"),
    WPA3("wpa3"),
    ;

    fun isValidPassphrase(passphrase: String): Boolean = when (this) {
        OPEN -> passphrase.isEmpty()
        WPA2, WPA3 -> passphrase.length in 8..128
    }

    companion object {
        fun fromCommandKeyword(value: String?): WifiConnectSecurity? = entries.firstOrNull {
            it.commandKeyword == value
        }
    }
}

internal object SelfArmCommandBridgeProtocol {
    const val WIFI_ENABLE = "wifi_enable"
    const val WIFI_DISABLE = "wifi_disable"
    const val WIFI_CONNECT = "wifi_connect"
    const val WIFI_CONNECT_FILES = "wifi_connect_files"
    const val WIFI_FORGET = "wifi_forget"
    const val WIFI_CLEANUP_FILES = "wifi_cleanup_files"
    const val ADB_WIFI_ENABLE = "adb_wifi_enable"
    const val ADB_WIFI_DISABLE = "adb_wifi_disable"
    const val MAX_REQUEST_BYTES = 512
    private val secretRegex = Regex("[0-9a-f]{64}")
    private val nonceRegex = Regex("[0-9a-f]{32}")
    private val tokenRegex = Regex("[0-9a-f]{64}")
    private val encodedArgumentRegex = Regex("[A-Za-z0-9+/]+={0,2}")
    const val DELETE_CAPTURE = "delete_capture"
    private val allowedCommands = setOf(
        WIFI_ENABLE,
        WIFI_DISABLE,
        WIFI_CONNECT,
        WIFI_CONNECT_FILES,
        WIFI_FORGET,
        WIFI_CLEANUP_FILES,
        DELETE_CAPTURE,
        ADB_WIFI_ENABLE,
        ADB_WIFI_DISABLE,
    )

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
            WIFI_ENABLE, WIFI_DISABLE, ADB_WIFI_ENABLE, ADB_WIFI_DISABLE, WIFI_CLEANUP_FILES -> arguments.isEmpty()
            WIFI_CONNECT, WIFI_CONNECT_FILES -> arguments.size == 3 &&
                arguments[0].length in 1..172 && encodedArgumentRegex.matches(arguments[0]) &&
                when (WifiConnectSecurity.fromCommandKeyword(arguments[2])) {
                    WifiConnectSecurity.OPEN -> arguments[1].isEmpty()
                    WifiConnectSecurity.WPA2,
                    WifiConnectSecurity.WPA3,
                    -> arguments[1].length in 1..172 && encodedArgumentRegex.matches(arguments[1])
                    null -> false
                }
            DELETE_CAPTURE -> arguments.size == 1 &&
                arguments[0].length in 1..344 && encodedArgumentRegex.matches(arguments[0])
            WIFI_FORGET -> arguments.size == 1 &&
                arguments[0].length in 1..44 && encodedArgumentRegex.matches(arguments[0])
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
    private val channelLock = Any()

    fun setWifiEnabled(context: Context, enabled: Boolean, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val command = if (enabled) {
            SelfArmCommandBridgeProtocol.WIFI_ENABLE
        } else {
            SelfArmCommandBridgeProtocol.WIFI_DISABLE
        }
        return execute(context.applicationContext, command, enabled, timeoutMs)
    }

    fun setWirelessAdbEnabled(
        context: Context,
        enabled: Boolean,
        timeoutMs: Long = WIRELESS_ADB_TIMEOUT_MS,
    ): Boolean {
        if (timeoutMs <= 0L) return false
        val appContext = context.applicationContext
        if (enabled && !SelfArmWirelessAdbController.isWifiNetworkReady(appContext)) return false
        val currentPort = SelfArmWirelessAdbController.readWirelessPort()
        if (enabled && currentPort > 0) return true
        if (!enabled && !SelfArmWirelessAdbController.isEnabled(appContext) && currentPort <= 0) return true
        val command = if (enabled) {
            SelfArmCommandBridgeProtocol.ADB_WIFI_ENABLE
        } else {
            SelfArmCommandBridgeProtocol.ADB_WIFI_DISABLE
        }
        return submit(appContext, command, emptyList(), timeoutMs) {
            awaitWirelessAdbState(appContext, enabled, timeoutMs)
        }
    }

    fun connectWifiNetwork(
        context: Context,
        ssid: String,
        passphrase: String,
        security: WifiConnectSecurity = WifiConnectSecurity.WPA2,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        joined: (() -> Boolean)? = null,
        onSubmitted: () -> Unit = {},
        requireTargetConfirmation: Boolean = false,
    ): Boolean {
        if (requireTargetConfirmation && android.os.Build.VERSION.SDK_INT != 32) return false
        if (ssid.isBlank() || ssid.length > 128 || !security.isValidPassphrase(passphrase) ||
            timeoutMs <= 0L
        ) {
            return false
        }
        val arguments = listOf(
            Base64.encodeToString(ssid.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
            Base64.encodeToString(passphrase.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
            security.commandKeyword,
        )
        if (arguments[0].length !in 1..172 || arguments[1].length > 172) return false
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return false
        if (!requireTargetConfirmation && (joined?.invoke() ?: isConnectedTo(wifiManager, ssid))) return true
        return submit(
            context.applicationContext,
            if (requireTargetConfirmation) SelfArmCommandBridgeProtocol.WIFI_CONNECT_FILES else SelfArmCommandBridgeProtocol.WIFI_CONNECT,
            arguments,
            timeoutMs,
            onSubmitted = onSubmitted,
        ) { receipt ->
            if (requireTargetConfirmation) {
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (receipt?.readText()?.trim() == "ok" && (joined?.invoke() ?: isConnectedTo(wifiManager, ssid))) {
                        return@submit true
                    }
                    Thread.sleep(RESPONSE_POLL_MS)
                }
                false
            } else if (joined == null) awaitWifiNetwork(wifiManager, ssid, timeoutMs)
            else {
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (joined()) return@submit true
                    Thread.sleep(RESPONSE_POLL_MS)
                }
                joined()
            }
        }
    }

    fun cleanupFilesNetworks(context: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        if (android.os.Build.VERSION.SDK_INT != 32 || timeoutMs <= 0L) return false
        return submit(context.applicationContext, SelfArmCommandBridgeProtocol.WIFI_CLEANUP_FILES,
            emptyList(), timeoutMs) { receipt ->
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                when (runCatching { receipt?.readText() }.getOrNull()) {
                    "ok\n" -> return@submit true
                    "error\n" -> return@submit false
                }
                Thread.sleep(RESPONSE_POLL_MS)
            }
            false
        }
    }

    fun forgetWifiNetwork(context: Context, ssid: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val bytes = ssid.toByteArray(Charsets.UTF_8)
        if (android.os.Build.VERSION.SDK_INT != 32 || ssid.isBlank() || bytes.size !in 1..32 ||
            ssid.any { it.isISOControl() } || timeoutMs <= 0L
        ) return false
        return submit(context.applicationContext, SelfArmCommandBridgeProtocol.WIFI_FORGET,
            listOf(Base64.encodeToString(bytes, Base64.NO_WRAP)), timeoutMs) { receipt ->
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                val result = runCatching { receipt?.readText() }.getOrNull()
                if (result == "ok\n") return@submit true
                if (result == "error\n") return@submit false
                Thread.sleep(RESPONSE_POLL_MS)
            }
            false
        }
    }

    /**
     * Removes one capture from the glasses' camera directory through the shell-uid bridge.
     *
     * The hub cannot do this itself: the file belongs to the camera app, and scoped storage only
     * yields to an all-files grant or an interactive consent dialog the headless hub has no screen
     * for. The bridge has neither problem, so delete-after-sync becomes a promise photo sync can
     * actually keep. Success is read from the directory rather than from the bridge's response
     * file: a file written by the bridge's uid can stay invisible to this one for seconds behind
     * the FUSE cache, so the effect is what gets checked.
     */
    fun deleteCapture(context: Context, name: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        if (timeoutMs <= 0L || !MediaSyncCatalogContract.isSafeName(name)) return false
        val appContext = context.applicationContext
        val directory = File(MediaCatalog.DEFAULT_DIRECTORY)
        if (!isPresent(directory, name)) return true
        val encoded = Base64.encodeToString(name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        if (encoded.length !in 1..344) return false
        return submit(
            appContext,
            SelfArmCommandBridgeProtocol.DELETE_CAPTURE,
            listOf(encoded),
            timeoutMs,
        ) { awaitCaptureGone(directory, name, timeoutMs) }
    }

    private fun awaitCaptureGone(directory: File, name: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isPresent(directory, name)) return true
            Thread.sleep(RESPONSE_POLL_MS)
        }
        return !isPresent(directory, name)
    }

    /** Listing the directory forces a fresh read; a stat can answer from a cached entry. */
    private fun isPresent(directory: File, name: String): Boolean =
        runCatching { directory.list()?.contains(name) }.getOrNull() ?: File(directory, name).exists()

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
     *
     * The repair below is best-effort and measured to fail against exactly that case: under FUSE
     * the app cannot write into, nor delete, a directory another uid created inside its own data
     * dir. Clearing a foreign channel belongs to the bridge script, which has the only uid that
     * can. What this detection buys is the log line saying so, instead of a delete that reports
     * `not_permitted` forever with nothing to point at.
     */
    internal fun ensureChannelDir(context: Context): File? {
        val externalFiles = context.applicationContext.getExternalFilesDir(null) ?: return null
        val result = synchronized(channelLock) {
            ensureChannelDirLocked(externalFiles, ::isChannelWritable, ::removeChannelDirectory)
        }
        result.repairReason?.let { reason ->
            val outcome = if (result.directory != null) "succeeded" else "failed"
            Log.w(TAG, "command bridge channel repair reason=$reason result=$outcome")
        }
        return result.directory
    }

    internal fun ensureChannelDir(
        externalFiles: File,
        writableProbe: (File) -> Boolean = ::isChannelWritable,
        removeChannel: (File) -> Boolean = ::removeChannelDirectory,
    ): File? = synchronized(channelLock) {
        ensureChannelDirLocked(externalFiles, writableProbe, removeChannel).directory
    }

    private fun ensureChannelDirLocked(
        externalFiles: File,
        writableProbe: (File) -> Boolean,
        removeChannel: (File) -> Boolean,
    ): ChannelDirectoryResult {
        val channel = File(externalFiles, CHANNEL_NAME)
        val path = channel.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!channel.mkdirs() && !channel.isDirectory) return ChannelDirectoryResult(null)
        }

        val reason = channelProblem(channel, writableProbe) ?: return ChannelDirectoryResult(channel)
        repeat(CHANNEL_REPAIR_ATTEMPTS) {
            if (removeChannel(channel) &&
                (channel.mkdirs() || channel.isDirectory) &&
                channelProblem(channel, writableProbe) == null
            ) {
                return ChannelDirectoryResult(channel, reason)
            }
        }
        return ChannelDirectoryResult(null, reason)
    }

    private fun channelProblem(channel: File, writableProbe: (File) -> Boolean): String? = when {
        Files.isSymbolicLink(channel.toPath()) -> "symbolic_link"
        !channel.isDirectory -> "not_directory"
        !writableProbe(channel) -> "not_writable"
        else -> null
    }

    private fun isChannelWritable(channel: File): Boolean {
        var probe: File? = null
        return try {
            probe = File.createTempFile(".write-probe-", ".tmp", channel)
            probe.delete()
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            probe?.delete()
        }
    }

    private fun removeChannelDirectory(channel: File): Boolean = runCatching {
        val channelPath = channel.toPath()
        if (!Files.exists(channelPath, LinkOption.NOFOLLOW_LINKS)) return@runCatching true
        // The default walk does not follow links, so cleanup cannot escape the fixed channel path.
        Files.walkFileTree(
            channelPath,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
                    if (exception != null) throw exception
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        !Files.exists(channelPath, LinkOption.NOFOLLOW_LINKS)
    }.getOrDefault(false)

    private data class ChannelDirectoryResult(
        val directory: File?,
        val repairReason: String? = null,
    )

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
        onSubmitted: () -> Unit = {},
        awaitResult: (File?) -> Boolean,
    ): Boolean {
        val secret = loadSecretHex(context) ?: return false
        val channel = ensureChannelDir(context) ?: return false
        val nonce = randomHex(16)
        val requestFile = File(channel, "$nonce.request")
        val tempFile = File(channel, ".$nonce.request.${UUID.randomUUID()}.tmp")
        // Pre-create this app-owned inode; unlike shell-created responses it has no negative
        // FUSE dentry. A stale read can only report failure, never a false successful command.
        val receipt = when (command) {
            SelfArmCommandBridgeProtocol.WIFI_FORGET -> File(channel, "$nonce.forget-result")
            SelfArmCommandBridgeProtocol.WIFI_CONNECT_FILES -> File(channel, "$nonce.connect-result")
            SelfArmCommandBridgeProtocol.WIFI_CLEANUP_FILES -> File(channel, "$nonce.files-result")
            else -> null
        }
        return try {
            val request = SelfArmCommandBridgeProtocol.request(secret, command, nonce, arguments)
            if (request.toByteArray(Charsets.UTF_8).size > SelfArmCommandBridgeProtocol.MAX_REQUEST_BYTES) {
                return false
            }
            receipt?.writeText("pending\n")
            tempFile.writeText(request)
            if (!tempFile.renameTo(requestFile)) return false
            onSubmitted()
            ringDoorbell(File(channel, DOORBELL_NAME), nonce)
            awaitResult(receipt)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        } finally {
            tempFile.delete()
            requestFile.delete()
            receipt?.delete()
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

    @Throws(InterruptedException::class)
    private fun awaitWirelessAdbState(context: Context, enabled: Boolean, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val port = SelfArmWirelessAdbController.readWirelessPort()
            val settingEnabled = SelfArmWirelessAdbController.isEnabled(context)
            if (enabled && settingEnabled && port > 0) return true
            if (!enabled && !settingEnabled && port <= 0) return true
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

    private const val TAG = "NexusCommandBridge"
    private const val CHANNEL_REPAIR_ATTEMPTS = 3
    private const val WIRELESS_ADB_TIMEOUT_MS = 12_000L
}
