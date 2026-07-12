package com.anezium.rokidbus.glasses

internal class LauncherReturnCoordinator {
    private var pendingPluginId: String? = null
    private var launcherReturnSurfaceId: String? = null

    @Synchronized
    fun recordLauncherOpen(pluginId: String) {
        pendingPluginId = pluginId.takeIf(String::isNotBlank)
        // Opening forward from the launcher supersedes any prior return contract;
        // otherwise the outgoing plugin's hide (sent before the new show) would
        // pop the launcher on top of the incoming surface.
        launcherReturnSurfaceId = null
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
