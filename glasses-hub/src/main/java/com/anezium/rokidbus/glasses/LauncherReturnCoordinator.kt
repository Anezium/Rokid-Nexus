package com.anezium.rokidbus.glasses

internal class LauncherReturnCoordinator {
    private var pendingPluginId: String? = null
    private var launcherReturnSurfaceId: String? = null

    @Synchronized
    fun recordLauncherOpen(pluginId: String) {
        pendingPluginId = pluginId.takeIf(String::isNotBlank)
    }

    @Synchronized
    fun clearPendingLauncherOpen() {
        pendingPluginId = null
    }

    @Synchronized
    fun onSurfaceShown(surfaceId: String): Boolean {
        val pluginId = pendingPluginId ?: return false
        val matchesPlugin = surfaceId == pluginId || surfaceId.startsWith("$pluginId:")
        if (!matchesPlugin) return false
        pendingPluginId = null
        launcherReturnSurfaceId = surfaceId
        return true
    }

    @Synchronized
    fun consumeReturnOnHide(surfaceId: String): Boolean {
        if (launcherReturnSurfaceId != surfaceId) return false
        launcherReturnSurfaceId = null
        return true
    }
}

internal val launcherReturnCoordinator = LauncherReturnCoordinator()
