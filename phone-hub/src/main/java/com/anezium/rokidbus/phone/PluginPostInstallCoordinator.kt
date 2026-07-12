package com.anezium.rokidbus.phone

import android.content.Intent

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
