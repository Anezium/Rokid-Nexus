package com.anezium.rokidbus.phone

enum class StoreEntryState {
    AVAILABLE,
    UPDATE_AVAILABLE,
    INSTALLED,
    SIDELOADED,
    REQUIRES_HOST,
}

data class StoreEntry(
    val id: String,
    val displayName: String,
    val category: String,
    val summary: String,
    val state: StoreEntryState,
    val registryPlugin: RegistryPlugin?,
    val localEntry: PluginCatalogEntry?,
    val installedVersionCode: Long?,
    val updateBlockedByHost: Boolean = false,
) {
    val localGrantState: PluginCatalogState?
        get() = localEntry?.state

    val provenance: PluginProvenance
        get() = if (registryPlugin != null) {
            PluginProvenance.REGISTRY
        } else {
            localEntry?.provenance ?: PluginProvenance.LOCAL
        }

    val registryAuthor: String?
        get() = registryPlugin?.author ?: localEntry?.registryAuthor
}

data class StoreCatalog(val entries: List<StoreEntry>) {
    val categories: List<String>
        get() = entries.map(StoreEntry::category).filter(String::isNotBlank).distinct().sorted()

    fun entry(id: String): StoreEntry? = entries.singleOrNull { it.id == id }

    internal fun availableUpdates(
        installedVersions: Map<String, InstalledPluginVersion>,
    ): List<PluginUpdateInfo> = entries.mapNotNull { entry ->
        if (entry.state != StoreEntryState.UPDATE_AVAILABLE) return@mapNotNull null
        val registryPlugin = entry.registryPlugin ?: return@mapNotNull null
        val installed = installedVersions[registryPlugin.artifact.packageName] ?: return@mapNotNull null
        PluginUpdateInfo(
            pluginId = entry.id,
            name = entry.displayName,
            installedVersionName = installed.versionName,
            availableVersionName = registryPlugin.artifact.versionName,
        )
    }

    companion object {
        fun build(
            feed: RegistryFeed,
            localCatalog: PluginCatalog,
            installedVersionCodes: Map<String, Long>,
            hostVersionCode: Long,
            logger: (String) -> Unit = {},
        ): StoreCatalog {
            val matchResult = RegistryPluginMatcher.match(feed, localCatalog, logger)
            val remoteEntries = matchResult.matches.map { match ->
                val plugin = match.plugin
                val local = match.localEntry
                val installedVersion = installedVersionCodes[plugin.artifact.packageName]
                val requiresNewerHost = plugin.nexus.minHostVersionCode > hostVersionCode
                val hasNewerRelease = installedVersion != null && plugin.artifact.versionCode > installedVersion
                val state = when {
                    local == null && requiresNewerHost -> StoreEntryState.REQUIRES_HOST
                    local == null -> StoreEntryState.AVAILABLE
                    hasNewerRelease && !requiresNewerHost ->
                        StoreEntryState.UPDATE_AVAILABLE
                    else -> StoreEntryState.INSTALLED
                }
                StoreEntry(
                    id = plugin.nexus.pluginId,
                    displayName = plugin.name,
                    category = plugin.category,
                    summary = plugin.summary,
                    state = state,
                    registryPlugin = plugin,
                    localEntry = local,
                    installedVersionCode = installedVersion,
                    updateBlockedByHost = local != null && hasNewerRelease && requiresNewerHost,
                )
            }

            val localEntries = matchResult.localRemaining.map { local ->
                val packageName = local.installedPackageName()
                StoreEntry(
                    id = local.id ?: local.catalogKey,
                    displayName = local.displayName,
                    category = LOCAL_CATEGORY,
                    summary = local.detail ?: "Installed outside the Nexus Store.",
                    state = if (local.state == PluginCatalogState.BUILT_IN) {
                        StoreEntryState.INSTALLED
                    } else {
                        StoreEntryState.SIDELOADED
                    },
                    registryPlugin = null,
                    localEntry = local,
                    installedVersionCode = packageName?.let(installedVersionCodes::get),
                )
            }

            return StoreCatalog(
                (remoteEntries + localEntries).sortedWith(
                    compareBy<StoreEntry>({ it.displayName.lowercase() }, { it.id }),
                ),
            )
        }

        private const val LOCAL_CATEGORY = "Local"

    }
}
