package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.NativeAppEntry

internal sealed interface LauncherMenuEntry {
    val stableId: String
    val displayName: String

    data class Plugin(
        val entry: GlassesHub.LauncherEntry,
    ) : LauncherMenuEntry {
        override val stableId: String = "plugin:${entry.id}"
        override val displayName: String = entry.displayName
    }

    data class NativeApp(
        val entry: NativeAppEntry,
    ) : LauncherMenuEntry {
        override val stableId: String = "app:${entry.packageName}"
        override val displayName: String = entry.label
    }
}

internal object LauncherMenuCatalog {
    fun merge(
        plugins: List<GlassesHub.LauncherEntry>,
        nativeApps: List<NativeAppEntry>,
    ): List<LauncherMenuEntry> = buildList(plugins.size + nativeApps.size) {
        plugins.forEach { add(LauncherMenuEntry.Plugin(it)) }
        nativeApps.forEach { add(LauncherMenuEntry.NativeApp(it)) }
    }
}
