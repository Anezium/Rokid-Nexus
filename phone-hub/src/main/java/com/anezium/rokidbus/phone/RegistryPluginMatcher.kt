package com.anezium.rokidbus.phone

internal data class RegistryPluginMatch(
    val plugin: RegistryPlugin,
    val localEntry: PluginCatalogEntry?,
)

internal data class RegistryPluginMatchResult(
    val matches: List<RegistryPluginMatch>,
    val localRemaining: List<PluginCatalogEntry>,
)

internal object RegistryPluginMatcher {
    fun match(
        feed: RegistryFeed,
        localCatalog: PluginCatalog,
        logger: (String) -> Unit = {},
    ): RegistryPluginMatchResult {
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

        val matches = acceptedRemote.mapNotNull { plugin ->
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
            RegistryPluginMatch(plugin, local)
        }

        return RegistryPluginMatchResult(matches, localRemaining)
    }
}

internal fun PluginCatalogEntry.installedPackageName(): String? =
    principal?.packageName ?: settingsComponent?.packageName
