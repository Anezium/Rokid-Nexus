package com.anezium.rokidbus.glasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

internal object SelfArmController {
    private const val ADB_PORT = 5555
    private const val WIFI_ENABLE_COMMAND = "svc wifi enable"
    private const val WIFI_DISABLE_COMMAND = "svc wifi disable"
    private const val STOP_SENTINEL = "ROKID_NEXUS_STOP_RESULT"
    private val operationRunning = AtomicBoolean(false)
    private val idleCallbacksLock = Any()
    private val idleCallbacks = mutableListOf<() -> Unit>()

    fun ensureWatchdog(
        context: Context,
        reason: String,
        onComplete: ((SelfArmWatchdogEnsureResult) -> Unit)? = null,
    ): Boolean = runAsync(context, reason) { appContext ->
        val result = runCatching {
            runSelfArm(appContext, reason, restartWatchdog = true)
        }.onFailure {
            logError("Self-arm failed reason=$reason", it)
        }.getOrDefault(SelfArmWatchdogEnsureResult.FAILED)
        onComplete?.invoke(result)
    }

    internal fun runWhenIdle(callback: () -> Unit) {
        val runNow = synchronized(idleCallbacksLock) {
            if (operationRunning.get()) {
                idleCallbacks += callback
                false
            } else {
                true
            }
        }
        if (runNow) callback()
    }

    fun repairNow(context: Context, reason: String) {
        val appContext = context.applicationContext
        if (!accessibilityRepairNeeded(appContext)) return
        runAsync(appContext, reason) { runSelfArm(it, reason, restartWatchdog = false) }
    }

    fun setWifiEnabled(context: Context, enabled: Boolean): Boolean {
        val appContext = context.applicationContext
        val wirelessResult = WirelessAdbShell.setWifiEnabled(appContext, enabled)
        if (wirelessResult.success) return true
        log(
            "Wireless ADB Wi-Fi request unavailable; falling back to classic loopback: " +
                wirelessResult.error.take(160),
        )
        return setWifiEnabled(
            enabled = enabled,
            seam = WifiControlSeam(
                loadKey = { AdbKeyStore.loadExisting(appContext) },
                loopbackListening = ::adbLoopbackListening,
                runShell = { command, key ->
                    AdbLoopbackClient(port = ADB_PORT).runShell(command, key)
                },
            ),
        )
    }

    fun bootstrapWirelessManually(
        context: Context,
        pairPort: Int,
        code: String,
        connectPort: Int,
    ): Boolean = runCatching {
        SelfArmLocalAdbBootstrapper(context.applicationContext).bootstrap(
            pairPort = pairPort,
            pairingCode = code,
            connectPort = connectPort,
        )
        true
    }.onFailure {
        logError("Manual Wireless Debugging bootstrap failed", it)
    }.getOrDefault(false)

    internal fun setWifiEnabled(enabled: Boolean, seam: WifiControlSeam): Boolean {
        val key = seam.loadKey()
        if (key == null) {
            seam.log("Wi-Fi request no-op: ADB key unavailable")
            return false
        }
        if (!seam.loopbackListening()) {
            seam.log("Wi-Fi request no-op: loopback ADB $ADB_PORT unavailable")
            return false
        }
        val command = if (enabled) WIFI_ENABLE_COMMAND else WIFI_DISABLE_COMMAND
        val result = seam.runShell(command, key)
        return result.authenticated && result.commandSent
    }

    internal fun stopWatchdog(context: Context, reason: String, onComplete: ((Boolean) -> Unit)? = null) {
        runAsync(context, reason, onComplete = null) { appContext ->
            val key = AdbKeyStore.loadExisting(appContext)
            if (key == null || !adbLoopbackListening()) {
                log("Self-arm stop no-op reason=$reason: ADB key or loopback unavailable")
                onComplete?.invoke(false)
                return@runAsync
            }
            val result = AdbLoopbackClient(port = ADB_PORT).runShell(buildStopCommand(), key)
            val stopped = result.authenticated && result.commandSent && stopCommandSucceeded(result.output)
            log("Self-arm stop reason=$reason connected=${result.connected} auth=${result.authenticated} stopped=$stopped")
            onComplete?.invoke(stopped)
        }
    }

    internal fun servicesWithNexusService(current: String?): String {
        val clean = current?.trim().orEmpty()
        if (clean.isBlank() || clean == "null") return SelfArmConstants.ACCESSIBILITY_SERVICE
        val services = clean.split(':').filter { it.isNotBlank() }
        if (services.any(::isNexusAccessibilityService)) return clean
        return (services + SelfArmConstants.ACCESSIBILITY_SERVICE).joinToString(":")
    }

    internal fun accessibilityRepairNeeded(current: String?, accessibilityEnabled: Int): Boolean =
        accessibilityEnabled != 1 ||
            current.orEmpty().split(':').none(::isNexusAccessibilityService)

    private fun isNexusAccessibilityService(service: String): Boolean =
        service == SelfArmConstants.ACCESSIBILITY_SERVICE ||
            service == SelfArmConstants.ACCESSIBILITY_SERVICE_SHORT

    internal fun buildPrepareCommand(watchdogScript: String, bridgeScript: String): String =
        SelfArmSessionCommand.buildPrepare(watchdogScript, bridgeScript)

    internal fun buildArmCommand(restartWatchdog: Boolean): String =
        SelfArmSessionCommand.buildArm(restartWatchdog)

    internal fun buildStopCommand(): String = buildString {
        appendLine("sh '${SelfArmConstants.WATCHDOG_REMOTE_PATH}' stop >/dev/null 2>&1 || true")
        appendLine("sleep 1")
        appendLine("WATCHDOG_STATUS=\"\$(sh '${SelfArmConstants.WATCHDOG_REMOTE_PATH}' status 2>/dev/null || true)\"")
        appendLine("case \"\$WATCHDOG_STATUS\" in")
        appendLine("  *\"running=no\"*) WATCHDOG_STOPPED=1 ;;")
        appendLine("  *)")
        appendLine("    WATCHDOG_PID=\"\$(cat '${SelfArmConstants.WATCHDOG_PIDFILE}' 2>/dev/null)\"")
        appendLine("    if [ -z \"\$WATCHDOG_PID\" ] || ! kill -0 \"\$WATCHDOG_PID\" 2>/dev/null; then")
        appendLine("      WATCHDOG_STOPPED=1")
        appendLine("    else")
        appendLine("      WATCHDOG_STOPPED=0")
        appendLine("    fi")
        appendLine("    ;;")
        appendLine("esac")
        appendLine("echo '$STOP_SENTINEL watchdog='\"\$WATCHDOG_STOPPED\"")
    }

    internal fun stopCommandSucceeded(output: String): Boolean =
        sentinelValues(output, STOP_SENTINEL)?.get("watchdog") == "1"

    private fun runAsync(
        context: Context,
        reason: String,
        onComplete: (() -> Unit)? = null,
        operation: (Context) -> Unit,
    ): Boolean {
        val appContext = context.applicationContext
        val accepted = synchronized(idleCallbacksLock) {
            operationRunning.compareAndSet(false, true)
        }
        if (!accepted) {
            log("Self-arm coalesced reason=$reason: operation already running")
            onComplete?.invoke()
            return false
        }
        Thread {
            try {
                runCatching { operation(appContext) }
                    .onFailure { logError("Self-arm failed reason=$reason", it) }
            } finally {
                val callbacks = synchronized(idleCallbacksLock) {
                    operationRunning.set(false)
                    idleCallbacks.toList().also { idleCallbacks.clear() }
                }
                onComplete?.invoke()
                callbacks.forEach { callback ->
                    runCatching { callback() }
                        .onFailure { logError("Self-arm idle callback failed", it) }
                }
            }
        }.apply {
            name = "RokidNexusSelfArm"
            isDaemon = true
            start()
        }
        return true
    }

    private fun runSelfArm(
        context: Context,
        reason: String,
        restartWatchdog: Boolean,
    ): SelfArmWatchdogEnsureResult {
        val scriptFile = ensureInternalWatchdog(context)
        val bridgeFile = ensureInternalCommandBridge(context)
        val repairedDirectly = repairAccessibilityDirect(context)
        val watchdogScript = scriptFile.readText()
        val bridgeScript = bridgeFile.readText()
        val bootstrapComplete = SelfArmLocalAdbBootstrapper.isBootstrapComplete(context)
        var tlsSessionUnreachable = false

        if (bootstrapComplete) {
            SelfArmWirelessAdbController.ensureEnabledForMaintenance(context)
            val initialTlsSession = runCatching {
                SelfArmLocalAdbBootstrapper.openPairedSession(context)
            }.onFailure {
                tlsSessionUnreachable = it.hasSessionUnavailableCause()
                logError("Self-arm TLS session unavailable reason=$reason", it)
            }.getOrNull()
            if (initialTlsSession != null) {
                return runSequence(
                    context = context,
                    reason = reason,
                    repairedDirectly = repairedDirectly,
                    initialSession = initialTlsSession,
                    watchdogScript = watchdogScript,
                    bridgeScript = bridgeScript,
                    restartWatchdog = restartWatchdog,
                )
            }
        }

        val key = AdbKeyStore.loadExisting(context)
        if (!bootstrapComplete || key == null || !adbLoopbackListening()) {
            log(
                "Self-arm no-op reason=$reason directRepair=$repairedDirectly " +
                    "bootstrapComplete=$bootstrapComplete classicKey=${key != null}",
            )
            return if (tlsSessionUnreachable) {
                SelfArmWatchdogEnsureResult.SESSION_UNREACHABLE
            } else {
                SelfArmWatchdogEnsureResult.FAILED
            }
        }

        val classicSession = ClassicAdbShellSession(key)
        return runSequence(
            context = context,
            reason = reason,
            repairedDirectly = repairedDirectly,
            initialSession = classicSession,
            watchdogScript = watchdogScript,
            bridgeScript = bridgeScript,
            restartWatchdog = restartWatchdog,
        )
    }

    private fun runSequence(
        context: Context,
        reason: String,
        repairedDirectly: Boolean,
        initialSession: SelfArmShellSession,
        watchdogScript: String,
        bridgeScript: String,
        restartWatchdog: Boolean,
    ): SelfArmWatchdogEnsureResult = runCatching {
        val result = SelfArmLocalAdbBootstrapper.runSequence(
            context = context,
            initialSession = initialSession,
            watchdogScript = watchdogScript,
            bridgeScript = bridgeScript,
            restartWatchdog = restartWatchdog,
        )
        SelfArmOnboardingStore.recordNetworkPosture(context, result.posture)
        log(
            "Self-arm ready reason=$reason directRepair=$repairedDirectly " +
                "port=${result.port} restartedAdbd=${result.restartedAdbd}",
        )
        SelfArmWatchdogEnsureResult.READY
    }.onFailure {
        logError("Self-arm sequence failed reason=$reason directRepair=$repairedDirectly", it)
    }.fold(
        onSuccess = { it },
        onFailure = {
            if (it.hasSessionUnavailableCause()) {
                SelfArmWatchdogEnsureResult.SESSION_UNREACHABLE
            } else {
                SelfArmWatchdogEnsureResult.FAILED
            }
        },
    )

    private fun Throwable.hasSessionUnavailableCause(): Boolean {
        val seen = HashSet<Throwable>()
        var current: Throwable? = this
        while (current != null && seen.add(current)) {
            if (current is SelfArmSessionUnavailableException) return true
            current = current.cause
        }
        return false
    }

    private fun accessibilityRepairNeeded(context: Context): Boolean =
        runCatching {
            val resolver = context.contentResolver
            accessibilityRepairNeeded(
                Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0),
            )
        }.onFailure {
            logError("Self-arm accessibility state read failed", it)
        }.getOrDefault(true)

    private fun repairAccessibilityDirect(context: Context): Boolean {
        if (!accessibilityRepairNeeded(context)) return true
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            log("Self-arm direct repair unavailable: WRITE_SECURE_SETTINGS not granted")
            return false
        }
        return runCatching {
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                servicesWithNexusService(current),
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            log("Self-arm direct accessibility repair completed")
            true
        }.onFailure {
            logError("Self-arm direct accessibility repair failed", it)
        }.getOrDefault(false)
    }

    private val watchdogVersionRegex = Regex("^VERSION=\"([^\"]+)\"$")
    private val bridgeVersionRegex = Regex("^VERSION=\"([^\"]+)\"$")

    private fun ensureInternalWatchdog(context: Context): File {
        val file = File(File(context.filesDir, "self-arm"), SelfArmConstants.WATCHDOG_ASSET)
        val currentVersion = if (file.exists()) watchdogScriptVersion(file) else null
        if (currentVersion == SelfArmConstants.WATCHDOG_VERSION) return file
        val script = context.assets.open(SelfArmConstants.WATCHDOG_ASSET).bufferedReader().use { it.readText() }
            .replace("\r\n", "\n").replace("\r", "\n")
        val dir = file.parentFile ?: error("Watchdog has no parent directory")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) error("Could not create self-arm directory")
        file.writeText(script)
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(true, true)
        return file
    }

    private fun watchdogScriptVersion(file: File): String? = runCatching {
        file.useLines { lines ->
            lines.firstNotNullOfOrNull { watchdogVersionRegex.find(it)?.groupValues?.get(1) }
        }
    }.getOrNull()

    internal fun ensureInternalCommandBridge(context: Context): File {
        val secretHex = SelfArmCommandBridgeClient.ensureSecretHex(context)
        val file = File(File(context.filesDir, "self-arm"), SelfArmConstants.BRIDGE_ASSET)
        val currentVersion = if (file.exists()) bridgeScriptVersion(file) else null
        val expectedSecretAssignment = "SECRET=\"$secretHex\""
        if (currentVersion == SelfArmConstants.BRIDGE_VERSION &&
            runCatching { file.useLines { lines -> lines.any { it == expectedSecretAssignment } } }.getOrDefault(false)
        ) {
            return file
        }
        val assetScript = context.assets.open(SelfArmConstants.BRIDGE_ASSET).bufferedReader().use { it.readText() }
            .replace("\r\n", "\n").replace("\r", "\n")
        val script = SelfArmCommandBridgeClient.renderBridgeScript(assetScript, secretHex)
        val dir = file.parentFile ?: error("Bridge has no parent directory")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) error("Could not create self-arm directory")
        file.writeText(script)
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(true, true)
        return file
    }

    private fun bridgeScriptVersion(file: File): String? = runCatching {
        file.useLines { lines ->
            lines.firstNotNullOfOrNull { bridgeVersionRegex.find(it)?.groupValues?.get(1) }
        }
    }.getOrNull()

    private fun adbLoopbackListening(): Boolean {
        val socket = Socket()
        return runCatching {
            socket.connect(InetSocketAddress("127.0.0.1", ADB_PORT), 500)
            true
        }.getOrDefault(false).also {
            runCatching { socket.close() }
        }
    }

    private fun sentinelValues(output: String, sentinel: String): Map<String, String>? {
        val line = output.lineSequence().lastOrNull { it.contains(sentinel) } ?: return null
        return line.substringAfter(sentinel)
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull { part ->
                val key = part.substringBefore("=", missingDelimiterValue = "")
                val value = part.substringAfter("=", missingDelimiterValue = "")
                if (key.isBlank()) null else key to value
            }
            .toMap()
    }
}

internal data class WifiControlSeam(
    val loadKey: () -> AdbKeyMaterial?,
    val loopbackListening: () -> Boolean,
    val runShell: (String, AdbKeyMaterial) -> AdbLoopbackClient.ShellResult,
    val log: (String) -> Unit = ::log,
)
