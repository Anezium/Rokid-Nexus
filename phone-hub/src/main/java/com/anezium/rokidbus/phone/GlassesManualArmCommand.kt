package com.anezium.rokidbus.phone

import java.security.MessageDigest
import java.util.Base64

/**
 * Phone-module mirror of glasses-hub SelfArmSessionCommand. Keep command output byte-identical;
 * SelfArmCommandParityTest pins both builders to the same digest because app modules cannot share
 * an internal implementation without adding SDK surface.
 */
internal object GlassesManualArmCommand {
    private const val PREPARE_SENTINEL = "ROKID_NEXUS_PREPARE_RESULT"
    private const val RESTART_SENTINEL = "ROKID_NEXUS_ADBD_RESTART_RESULT"
    private const val ARM_SENTINEL = "ROKID_NEXUS_ARM_RESULT"

    data class RestartRequest(val oldAdbdPid: String)

    fun buildPrepare(watchdogScript: String, bridgeScript: String): String {
        val watchdogBytes = watchdogScript.toByteArray()
        val encodedWatchdog = Base64.getEncoder().encodeToString(watchdogBytes)
        val watchdogHash = MessageDigest.getInstance("SHA-256")
            .digest(watchdogBytes)
            .joinToString("") { "%02x".format(it) }
        val bridgeBytes = bridgeScript.toByteArray()
        val encodedBridge = Base64.getEncoder().encodeToString(bridgeBytes)
        val bridgeHash = MessageDigest.getInstance("SHA-256")
            .digest(bridgeBytes)
            .joinToString("") { "%02x".format(it) }
        return buildString {
            appendCommonVariables()
            appendLine("pm grant \"\$PKG\" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true")
            appendAccessibilityRepair()
            appendLine("printf '%s' '$encodedWatchdog' | base64 -d > \"\$WATCHDOG\"")
            appendLine("chmod 700 \"\$WATCHDOG\"")
            appendLine("SCRIPT_HASH=\"\$(sha256sum \"\$WATCHDOG\" 2>/dev/null | cut -d' ' -f1)\"")
            appendLine("[ \"\$SCRIPT_HASH\" = '$watchdogHash' ] && SCRIPT_READY=1 || SCRIPT_READY=0")
            appendLine("printf '%s' '$encodedBridge' | base64 -d > \"\$BRIDGE\"")
            appendLine("chmod 700 \"\$BRIDGE\"")
            appendLine("BRIDGE_HASH=\"\$(sha256sum \"\$BRIDGE\" 2>/dev/null | cut -d' ' -f1)\"")
            appendLine("[ \"\$BRIDGE_HASH\" = '$bridgeHash' ] && BRIDGE_SCRIPT_READY=1 || BRIDGE_SCRIPT_READY=0")
            appendLine("setprop persist.adb.tcp.port -1")
            appendLine("setprop service.adb.tcp.port -1")
            appendVerification(includeWatchdog = false)
            appendLine(
                "echo '$PREPARE_SENTINEL grant='\"\$GRANTED\"" +
                    "' a11y='\"\$A11Y_ENABLED\"' service='\"\$SERVICE_PRESENT\"" +
                    "' script='\"\$SCRIPT_READY\"' bridge_script='\"\$BRIDGE_SCRIPT_READY\"" +
                    "' persist='\"\$PERSIST_PORT\"" +
                    "' service_port='\"\$SERVICE_PORT\"' legacy_tcp_disabled='\"\$LEGACY_TCP_DISABLED\"",
            )
            appendLine("[ \"\$GRANTED\" != \"0\" ] || exit 1")
            appendLine("[ \"\$A11Y_ENABLED\" = \"1\" ] || exit 2")
            appendLine("[ \"\$SERVICE_PRESENT\" = \"1\" ] || exit 3")
            appendLine("[ \"\$SCRIPT_READY\" = \"1\" ] || exit 4")
            appendLine("[ \"\$LEGACY_TCP_DISABLED\" = \"1\" ] || exit 5")
        }
    }

    fun buildRestartAdbd(): String = buildString {
        appendLine("OLD_ADBD_PID=\"\$(pidof adbd 2>/dev/null | tr ' ' ',')\"")
        appendLine("[ -n \"\$OLD_ADBD_PID\" ] || exit 1")
        appendLine("echo '$RESTART_SENTINEL scheduled=1 old_adbd_pid='\"\$OLD_ADBD_PID\"")
        appendLine("nohup sh -c 'sleep 2; setprop ctl.restart adbd' >/dev/null 2>&1 </dev/null &")
    }

    fun buildArm(restartWatchdog: Boolean): String = buildString {
        appendCommonVariables()
        appendLine("sh \"\$WATCHDOG\" ${if (restartWatchdog) "restart" else "start"}")
        appendLine("sh \"\$WATCHDOG\" repair")
        appendLine("sh \"\$BRIDGE\" ${if (restartWatchdog) "restart" else "start"}")
        appendLine("sleep 1")
        appendVerification(includeWatchdog = true)
        appendLine(
            "echo '$ARM_SENTINEL grant='\"\$GRANTED\"" +
                "' a11y='\"\$A11Y_ENABLED\"' service='\"\$SERVICE_PRESENT\"" +
                "' watchdog='\"\$WATCHDOG_RUNNING\"' bridge='\"\$BRIDGE_RUNNING\"" +
                "' persist='\"\$PERSIST_PORT\"" +
                "' service_port='\"\$SERVICE_PORT\"' legacy_tcp_disabled='\"\$LEGACY_TCP_DISABLED\"",
        )
        appendLine("[ \"\$GRANTED\" != \"0\" ] || exit 1")
        appendLine("[ \"\$A11Y_ENABLED\" = \"1\" ] || exit 2")
        appendLine("[ \"\$SERVICE_PRESENT\" = \"1\" ] || exit 3")
        appendLine("[ \"\$WATCHDOG_RUNNING\" = \"1\" ] || exit 4")
        appendLine("[ \"\$LEGACY_TCP_DISABLED\" = \"1\" ] || exit 5")
    }

    fun prepareSucceeded(output: String): Boolean {
        val values = sentinelValues(output, PREPARE_SENTINEL) ?: return false
        return commonSucceeded(values) && values["script"] == "1"
    }

    fun restartRequest(output: String): RestartRequest? {
        val values = sentinelValues(output, RESTART_SENTINEL) ?: return null
        val oldAdbdPid = values["old_adbd_pid"].orEmpty()
        if (values["scheduled"] != "1" || oldAdbdPid.isBlank()) return null
        return RestartRequest(oldAdbdPid)
    }

    fun armSucceeded(output: String): Boolean {
        val values = sentinelValues(output, ARM_SENTINEL) ?: return false
        return commonSucceeded(values) && values["watchdog"] == "1"
    }

    private fun StringBuilder.appendCommonVariables() {
        appendLine("PKG='$CLIENT_PACKAGE'")
        appendLine("SVC='$ACCESSIBILITY_SERVICE'")
        appendLine("SVC_SHORT='$ACCESSIBILITY_SERVICE_SHORT'")
        appendLine("WATCHDOG='$WATCHDOG_REMOTE_PATH'")
        appendLine("BRIDGE='$BRIDGE_REMOTE_PATH'")
    }

    private fun StringBuilder.appendAccessibilityRepair() {
        appendLine("CURRENT=\"\$(settings get secure enabled_accessibility_services 2>/dev/null)\"")
        appendLine("case \"\$CURRENT\" in")
        appendLine("  \"\"|\"null\") NEXT_SERVICES=\"\$SVC\" ;;")
        appendLine("  *)")
        appendLine("    case \":\$CURRENT:\" in")
        appendLine("      *\":\$SVC:\"*|*\":\$SVC_SHORT:\"*) NEXT_SERVICES=\"\$CURRENT\" ;;")
        appendLine("      *) NEXT_SERVICES=\"\$CURRENT:\$SVC\" ;;")
        appendLine("    esac")
        appendLine("    ;;")
        appendLine("esac")
        appendLine("settings put secure enabled_accessibility_services \"\$NEXT_SERVICES\"")
        appendLine("settings put secure accessibility_enabled 1")
    }

    private fun StringBuilder.appendVerification(includeWatchdog: Boolean) {
        appendLine(
            "GRANTED=\$(dumpsys package \"\$PKG\" 2>/dev/null " +
                "| grep -A3 android.permission.WRITE_SECURE_SETTINGS | grep -c 'granted=true')",
        )
        appendLine("A11Y_ENABLED=\"\$(settings get secure accessibility_enabled 2>/dev/null)\"")
        appendLine("SERVICES=\"\$(settings get secure enabled_accessibility_services 2>/dev/null)\"")
        appendLine("case \":\$SERVICES:\" in")
        appendLine("  *\":\$SVC:\"*|*\":\$SVC_SHORT:\"*) SERVICE_PRESENT=1 ;;")
        appendLine("  *) SERVICE_PRESENT=0 ;;")
        appendLine("esac")
        if (includeWatchdog) {
            appendLine("WATCHDOG_STATUS=\"\$(sh \"\$WATCHDOG\" status 2>/dev/null || true)\"")
            appendLine("case \"\$WATCHDOG_STATUS\" in")
            appendLine("  *\"running=yes\"*) WATCHDOG_RUNNING=1 ;;")
            appendLine("  *) WATCHDOG_RUNNING=0 ;;")
            appendLine("esac")
            appendLine("BRIDGE_STATUS=\"\$(sh \"\$BRIDGE\" status 2>/dev/null || true)\"")
            appendLine("case \"\$BRIDGE_STATUS\" in")
            appendLine("  *\"running=yes\"*) BRIDGE_RUNNING=1 ;;")
            appendLine("  *) BRIDGE_RUNNING=0 ;;")
            appendLine("esac")
        }
        appendLine("PERSIST_PORT=\"\$(getprop persist.adb.tcp.port)\"")
        appendLine("SERVICE_PORT=\"\$(getprop service.adb.tcp.port)\"")
        appendLine("case \"\$PERSIST_PORT:\$SERVICE_PORT\" in")
        appendLine("  -1:-1) LEGACY_TCP_DISABLED=1 ;;")
        appendLine("  *) LEGACY_TCP_DISABLED=0 ;;")
        appendLine("esac")
    }

    private fun commonSucceeded(values: Map<String, String>): Boolean =
        values["grant"]?.toIntOrNull()?.let { it > 0 } == true &&
            values["a11y"] == "1" &&
            values["service"] == "1" &&
            values["persist"] == "-1" &&
            values["service_port"] == "-1" &&
            values["legacy_tcp_disabled"] == "1"

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

    private const val CLIENT_PACKAGE = "com.anezium.rokidbus.glasses"
    private const val ACCESSIBILITY_SERVICE =
        "com.anezium.rokidbus.glasses/com.anezium.rokidbus.glasses.RokidBusAccessibilityService"
    private const val ACCESSIBILITY_SERVICE_SHORT =
        "com.anezium.rokidbus.glasses/.RokidBusAccessibilityService"
    private const val WATCHDOG_REMOTE_PATH = "/data/local/tmp/rokid-nexus-a11y-watchdog.sh"
    private const val BRIDGE_REMOTE_PATH = "/data/local/tmp/rokid-nexus-cmd-bridge.sh"
}
