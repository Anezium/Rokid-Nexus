package com.anezium.rokidbus.glasses

import java.util.Base64

/** Builds the complete privileged bootstrap performed by one authenticated ADB shell. */
internal object SelfArmSessionCommand {
    private const val INSTALL_SENTINEL = "ROKID_NEXUS_INSTALL_RESULT"

    fun build(
        watchdogScript: String,
        restartWatchdog: Boolean,
    ): String {
        val encodedWatchdog = Base64.getEncoder().encodeToString(watchdogScript.toByteArray())
        return buildString {
            appendLine("PKG='${SelfArmConstants.CLIENT_PACKAGE}'")
            appendLine("SVC='${SelfArmConstants.ACCESSIBILITY_SERVICE}'")
            appendLine("SVC_SHORT='${SelfArmConstants.ACCESSIBILITY_SERVICE_SHORT}'")
            appendLine("WATCHDOG='${SelfArmConstants.WATCHDOG_REMOTE_PATH}'")
            appendLine("pm grant \"\$PKG\" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true")
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
            appendLine("printf '%s' '$encodedWatchdog' | base64 -d > \"\$WATCHDOG\"")
            appendLine("chmod 700 \"\$WATCHDOG\"")
            appendLine(
                "sh \"\$WATCHDOG\" " + if (restartWatchdog) "restart" else "start",
            )
            appendLine("sh \"\$WATCHDOG\" repair")
            appendLine("sleep 1")
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
            appendLine("WATCHDOG_STATUS=\"\$(sh \"\$WATCHDOG\" status 2>/dev/null || true)\"")
            appendLine("case \"\$WATCHDOG_STATUS\" in")
            appendLine("  *\"running=yes\"*) WATCHDOG_RUNNING=1 ;;")
            appendLine("  *) WATCHDOG_RUNNING=0 ;;")
            appendLine("esac")
            appendLine("setprop persist.adb.tcp.port -1")
            appendLine("setprop service.adb.tcp.port -1")
            appendLine("PERSIST_PORT=\"\$(getprop persist.adb.tcp.port)\"")
            appendLine("SERVICE_PORT=\"\$(getprop service.adb.tcp.port)\"")
            appendLine("case \"\$PERSIST_PORT:\$SERVICE_PORT\" in")
            appendLine("  -1:-1) LEGACY_TCP_DISABLED=1 ;;")
            appendLine("  *) LEGACY_TCP_DISABLED=0 ;;")
            appendLine("esac")
            appendLine(
                "echo '$INSTALL_SENTINEL grant='\"\$GRANTED\"" +
                    "' a11y='\"\$A11Y_ENABLED\"' service='\"\$SERVICE_PRESENT\"" +
                    "' watchdog='\"\$WATCHDOG_RUNNING\"' persist='\"\$PERSIST_PORT\"" +
                    "' service_port='\"\$SERVICE_PORT\"' legacy_tcp_disabled='\"\$LEGACY_TCP_DISABLED\"",
            )
            appendLine("[ \"\$GRANTED\" != \"0\" ] || exit 1")
            appendLine("[ \"\$A11Y_ENABLED\" = \"1\" ] || exit 2")
            appendLine("[ \"\$SERVICE_PRESENT\" = \"1\" ] || exit 3")
            appendLine("[ \"\$WATCHDOG_RUNNING\" = \"1\" ] || exit 4")
            appendLine("[ \"\$LEGACY_TCP_DISABLED\" = \"1\" ] || exit 5")
            // Let both detached watchdog processes be re-parented before adbd is restarted.
            appendLine(
                "nohup sh -c 'sleep 2; setprop ctl.restart adbd' " +
                    ">/dev/null 2>&1 </dev/null &",
            )
        }
    }

    fun succeeded(output: String): Boolean {
        val values = sentinelValues(output) ?: return false
        return values["grant"]?.toIntOrNull()?.let { it > 0 } == true &&
            values["a11y"] == "1" &&
            values["service"] == "1" &&
            values["watchdog"] == "1" &&
            values["persist"] == "-1" &&
            values["service_port"] == "-1" &&
            values["legacy_tcp_disabled"] == "1"
    }

    private fun sentinelValues(output: String): Map<String, String>? {
        val line = output.lineSequence().lastOrNull { it.contains(INSTALL_SENTINEL) } ?: return null
        return line.substringAfter(INSTALL_SENTINEL)
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
