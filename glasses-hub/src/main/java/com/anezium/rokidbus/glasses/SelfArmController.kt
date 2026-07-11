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
    private const val INSTALL_SENTINEL = "ROKID_NEXUS_INSTALL_RESULT"
    private const val STOP_SENTINEL = "ROKID_NEXUS_STOP_RESULT"
    private val operationRunning = AtomicBoolean(false)

    fun ensureWatchdog(context: Context, reason: String, onComplete: (() -> Unit)? = null) {
        runAsync(context, reason, onComplete) { appContext ->
            runSelfArm(appContext, reason, restartWatchdog = true)
        }
    }

    fun repairNow(context: Context, reason: String) {
        val appContext = context.applicationContext
        if (!accessibilityRepairNeeded(appContext)) return
        runAsync(appContext, reason) { runSelfArm(it, reason, restartWatchdog = false) }
    }

    fun setWifiEnabled(context: Context, enabled: Boolean): Boolean =
        setWifiEnabled(
            enabled = enabled,
            seam = WifiControlSeam(
                loadKey = { AdbKeyStore.loadOrCreate(context.applicationContext) },
                loopbackListening = ::adbLoopbackListening,
                runShell = { command, key ->
                    AdbLoopbackClient(port = ADB_PORT).runShell(command, key)
                },
            ),
        )

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
            val key = AdbKeyStore.loadOrCreate(appContext)
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
        if (SelfArmConstants.ACCESSIBILITY_SERVICE in services) return clean
        return (services + SelfArmConstants.ACCESSIBILITY_SERVICE).joinToString(":")
    }

    internal fun accessibilityRepairNeeded(current: String?, accessibilityEnabled: Int): Boolean =
        accessibilityEnabled != 1 ||
            current.orEmpty().split(':').none { it == SelfArmConstants.ACCESSIBILITY_SERVICE }

    internal fun buildInstallCommand(
        script: String,
        restartWatchdog: Boolean,
    ): String = buildString {
        appendLine("setprop persist.adb.tcp.port $ADB_PORT")
        appendLine("setprop service.adb.tcp.port $ADB_PORT")
        appendLine("cat > '${SelfArmConstants.WATCHDOG_REMOTE_PATH}' <<'ROKID_NEXUS_WATCHDOG'")
        append(script.trimEnd())
        appendLine()
        appendLine("ROKID_NEXUS_WATCHDOG")
        appendLine("chmod 700 '${SelfArmConstants.WATCHDOG_REMOTE_PATH}'")
        appendLine(
            "sh '${SelfArmConstants.WATCHDOG_REMOTE_PATH}' " +
                if (restartWatchdog) "restart" else "start",
        )
        appendLine("sh '${SelfArmConstants.WATCHDOG_REMOTE_PATH}' repair")
        appendLine("sleep 1")
        appendLine("WATCHDOG_STATUS=\"\$(sh '${SelfArmConstants.WATCHDOG_REMOTE_PATH}' status 2>/dev/null || true)\"")
        appendLine("case \"\$WATCHDOG_STATUS\" in")
        appendLine("  *\"running=yes\"*) WATCHDOG_RUNNING=1 ;;")
        appendLine("  *) WATCHDOG_RUNNING=0 ;;")
        appendLine("esac")
        appendLine("PERSIST_PORT=\"\$(getprop persist.adb.tcp.port)\"")
        appendLine("SERVICE_PORT=\"\$(getprop service.adb.tcp.port)\"")
        appendLine(
            "echo '$INSTALL_SENTINEL watchdog='\"\$WATCHDOG_RUNNING\"" +
                "' persist='\"\$PERSIST_PORT\"' service='\"\$SERVICE_PORT\"",
        )
    }

    internal fun installCommandSucceeded(output: String): Boolean {
        val values = sentinelValues(output, INSTALL_SENTINEL) ?: return false
        val port = ADB_PORT.toString()
        return values["watchdog"] == "1" && values["persist"] == port && values["service"] == port
    }

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
    ) {
        val appContext = context.applicationContext
        if (!operationRunning.compareAndSet(false, true)) {
            log("Self-arm coalesced reason=$reason: operation already running")
            onComplete?.invoke()
            return
        }
        Thread {
            try {
                runCatching { operation(appContext) }
                    .onFailure { logError("Self-arm failed reason=$reason", it) }
            } finally {
                operationRunning.set(false)
                onComplete?.invoke()
            }
        }.apply {
            name = "RokidNexusSelfArm"
            isDaemon = true
            start()
        }
    }

    private fun runSelfArm(context: Context, reason: String, restartWatchdog: Boolean): Boolean {
        val scriptFile = ensureInternalWatchdog(context)
        val repairedDirectly = repairAccessibilityDirect(context)
        val key = AdbKeyStore.loadOrCreate(context)
        if (key == null) {
            log("Self-arm no-op reason=$reason directRepair=$repairedDirectly: ADB key unavailable")
            return repairedDirectly
        }
        if (!adbLoopbackListening()) {
            log("Self-arm no-op reason=$reason directRepair=$repairedDirectly: loopback ADB 5555 unavailable")
            return repairedDirectly
        }

        val result = AdbLoopbackClient(port = ADB_PORT).runShell(
            buildInstallCommand(scriptFile.readText(), restartWatchdog),
            key,
        )
        val started = result.authenticated && result.commandSent && installCommandSucceeded(result.output)
        if (started) {
            log("Self-arm ready reason=$reason directRepair=$repairedDirectly")
        } else {
            log(
                "Self-arm no-op reason=$reason directRepair=$repairedDirectly " +
                    "connected=${result.connected} auth=${result.authenticated} sent=${result.commandSent} " +
                    "output=${result.output.take(160)}",
            )
        }
        return repairedDirectly || started
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

    private fun ensureInternalWatchdog(context: Context): File {
        val file = File(File(context.filesDir, "self-arm"), SelfArmConstants.WATCHDOG_ASSET)
        val currentVersion = if (file.exists()) watchdogScriptVersion(file) else null
        if (currentVersion == SelfArmConstants.WATCHDOG_VERSION) return file
        val script = context.assets.open(SelfArmConstants.WATCHDOG_ASSET).bufferedReader().use { it.readText() }
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
