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
}

data class StoreCatalog(val entries: List<StoreEntry>) {
    val categories: List<String>
        get() = entries.map(StoreEntry::category).filter(String::isNotBlank).distinct().sorted()

    fun entry(id: String): StoreEntry? = entries.singleOrNull { it.id == id }

    companion object {
        fun build(
            feed: RegistryFeed,
            localCatalog: PluginCatalog,
            installedVersionCodes: Map<String, Long>,
            hostVersionCode: Long,
            logger: (String) -> Unit = {},
        ): StoreCatalog {
            val localRemaining = localCatalog.entries.toMutableList()
            val duplicateIds = feed.plugins
                .groupingBy { it.nexus.pluginId }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            duplicateIds.sorted().forEach { pluginId ->
                logger("Dropping duplicate registry plugin id=$pluginId")
            }

            val acceptedRemote = feed.plugins.filter { plugin ->
                when {
                    plugin.nexus.pluginId in duplicateIds -> false
                    plugin.id != plugin.nexus.pluginId -> {
                        logger("Dropping registry plugin with mismatched descriptor id=${plugin.id}")
                        false
                    }
                    plugin.artifact.target != "phone" -> {
                        logger("Dropping registry plugin with non-phone artifact id=${plugin.id}")
                        false
                    }
                    else -> true
                }
            }

            val remoteEntries = acceptedRemote.mapNotNull { plugin ->
                val sameId = localRemaining.singleOrNull { it.id == plugin.nexus.pluginId }
                val samePackage = localRemaining.singleOrNull {
                    it.installedPackageName() == plugin.artifact.packageName
                }
                val local = sameId ?: samePackage
                if (local != null && (
                        local.id != plugin.nexus.pluginId ||
                            local.installedPackageName() != plugin.artifact.packageName
                        )
                ) {
                    logger("Dropping registry identity mismatch id=${plugin.nexus.pluginId}")
                    return@mapNotNull null
                }
                if (local != null) localRemaining.remove(local)
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

            val localEntries = localRemaining.map { local ->
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

        private fun PluginCatalogEntry.installedPackageName(): String? =
            principal?.packageName ?: settingsComponent?.packageName
    }
}
