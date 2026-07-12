package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.json.JSONObject

data class PluginGrantTarget(
    val packageName: String,
    val pluginId: String,
) {
    fun matches(principal: PhonePluginPrincipal): Boolean =
        principal.packageName == packageName && principal.descriptor.id == pluginId
}

sealed interface PluginPostInstallResult {
    data class Ready(
        val target: PluginGrantTarget,
        val principal: PhonePluginPrincipal,
        val grantState: PluginGrantState,
    ) : PluginPostInstallResult

    data class Failure(val reason: String) : PluginPostInstallResult
}

class PluginPostInstallCoordinator(
    private val discoverPackage: (packageName: String) -> List<PhonePluginCandidate>,
    private val grantState: (PhonePluginPrincipal) -> PluginGrantState,
    private val refreshCatalog: () -> Unit,
) {
    fun onInstalled(packageName: String, pluginId: String): PluginPostInstallResult {
        val target = PluginGrantTarget(packageName, pluginId)
        val matching = discoverPackage(packageName)
            .mapNotNull { (it as? PhonePluginCandidate.Valid)?.principal }
            .filter(target::matches)
        refreshCatalog()
        val principal = matching.singleOrNull()
            ?: return PluginPostInstallResult.Failure("Installed plugin identity could not be discovered")
        return PluginPostInstallResult.Ready(target, principal, grantState(principal))
    }
}

object PluginPackageChangePolicy {
    fun shouldReconcile(action: String?, replacing: Boolean): Boolean =
        action != Intent.ACTION_PACKAGE_REMOVED || !replacing
}

sealed interface RecoveredPluginInstall {
    data class Success(val target: PluginGrantTarget) : RecoveredPluginInstall
    data object Cancelled : RecoveredPluginInstall
    data class Failure(val message: String) : RecoveredPluginInstall
}

class PluginInstallRecoveryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(result: RecoveredPluginInstall) {
        val value = when (result) {
            is RecoveredPluginInstall.Success -> JSONObject()
                .put("type", "success")
                .put("package", result.target.packageName)
                .put("pluginId", result.target.pluginId)
            RecoveredPluginInstall.Cancelled -> JSONObject().put("type", "cancelled")
            is RecoveredPluginInstall.Failure -> JSONObject()
                .put("type", "failure")
                .put("message", result.message)
        }
        preferences.edit().putString(KEY_RESULT, value.toString()).commit()
    }

    fun peek(): RecoveredPluginInstall? = runCatching {
        val value = preferences.getString(KEY_RESULT, null) ?: return null
        val json = JSONObject(value)
        when (json.optString("type")) {
            "success" -> {
                val packageName = json.optString("package")
                val pluginId = json.optString("pluginId")
                if (packageName.isBlank() || pluginId.isBlank()) null
                else RecoveredPluginInstall.Success(PluginGrantTarget(packageName, pluginId))
            }
            "cancelled" -> RecoveredPluginInstall.Cancelled
            "failure" -> RecoveredPluginInstall.Failure(
                json.optString("message").takeIf(String::isNotBlank) ?: "Plugin installation failed",
            )
            else -> null
        }
    }.getOrNull()

    fun clear() {
        preferences.edit().remove(KEY_RESULT).commit()
    }

    fun clearSuccess(target: PluginGrantTarget) {
        if ((peek() as? RecoveredPluginInstall.Success)?.target == target) clear()
    }

    companion object {
        private const val PREFERENCES = "nexus_plugin_install_recovery"
        private const val KEY_RESULT = "last_result"
    }
}

fun Activity.resumeRecoveredPluginInstall() {
    val store = PluginInstallRecoveryStore(applicationContext)
    when (val result = store.peek()) {
        is RecoveredPluginInstall.Success -> {
            startActivity(PluginPermissionsActivity.intent(this, result.target))
        }
        RecoveredPluginInstall.Cancelled -> {
            store.clear()
            Toast.makeText(this, "Installation cancelled", Toast.LENGTH_SHORT).show()
        }
        is RecoveredPluginInstall.Failure -> {
            store.clear()
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        }
        null -> Unit
    }
}
