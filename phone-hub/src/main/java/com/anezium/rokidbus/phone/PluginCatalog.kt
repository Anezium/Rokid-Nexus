package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.plugin.NexusPlugin
import com.anezium.rokidbus.shared.plugin.PluginCapability

/** Plain-data settings target; callers build the ComponentName at launch time. */
data class PluginSettingsTarget(
    val packageName: String,
    val className: String,
)

data class BuiltInPluginCatalogSpec(
    val id: String,
    val displayName: String,
    val launchable: Boolean,
    val settingsTarget: PluginSettingsTarget? = null,
    val runtime: NexusPlugin? = null,
)

enum class PluginCatalogState {
    BUILT_IN,
    PENDING,
    ENABLED,
    DISABLED,
    DENIED,
    INVALID,
    MISSING_CAPABILITY,
}

enum class PluginProvenance {
    REGISTRY,
    LOCAL,
}

data class PluginCatalogEntry(
    val catalogKey: String,
    val id: String?,
    val displayName: String,
    val state: PluginCatalogState,
    val launchable: Boolean,
    val iconKey: String? = null,
    val iconDrawableResId: Int? = null,
    val builtIn: NexusPlugin? = null,
    val principal: PhonePluginPrincipal? = null,
    val settingsComponent: PluginSettingsTarget? = null,
    val detail: String? = null,
    val provenance: PluginProvenance = PluginProvenance.LOCAL,
    val registryAuthor: String? = null,
)

data class PluginCatalog(val entries: List<PluginCatalogEntry>) {
    val launchableEntries: List<PluginCatalogEntry>
        get() = entries.filter { it.launchable && it.state in setOf(PluginCatalogState.BUILT_IN, PluginCatalogState.ENABLED) }

    fun entry(id: String): PluginCatalogEntry? = entries.singleOrNull { it.id == id }

    companion object {
        fun build(
            builtIns: Collection<NexusPlugin>,
            candidates: Collection<PhonePluginCandidate>,
            builtInSpecs: Collection<BuiltInPluginCatalogSpec> = builtIns.map { plugin ->
                BuiltInPluginCatalogSpec(
                    id = plugin.id,
                    displayName = plugin.displayName,
                    launchable = plugin.launchable,
                    runtime = plugin,
                )
            },
            registryFeed: RegistryFeed = RegistryFeed(RegistryClient.SUPPORTED_VERSION, emptyList()),
            registryLogger: (String) -> Unit = {},
            grantState: (PhonePluginPrincipal) -> PluginGrantState,
        ): PluginCatalog {
            val entries = mutableListOf<PluginCatalogEntry>()
            val builtInIds = builtInSpecs.map(BuiltInPluginCatalogSpec::id).toSet()
            builtInSpecs.forEach { spec ->
                entries += PluginCatalogEntry(
                    catalogKey = "builtin:${spec.id}",
                    id = spec.id,
                    displayName = spec.displayName,
                    state = PluginCatalogState.BUILT_IN,
                    launchable = spec.launchable,
                    builtIn = spec.runtime,
                    settingsComponent = spec.settingsTarget,
                )
            }
            candidates.forEach { candidate ->
                when (candidate) {
                    is PhonePluginCandidate.Invalid -> entries += PluginCatalogEntry(
                        catalogKey = "invalid:${candidate.packageName}:${candidate.serviceComponent}",
                        id = null,
                        displayName = candidate.displayName,
                        state = PluginCatalogState.INVALID,
                        launchable = false,
                        detail = candidate.reason,
                    )
                    is PhonePluginCandidate.Valid -> {
                        val principal = candidate.principal
                        if (principal.descriptor.id in builtInIds) return@forEach
                        val grant = grantState(principal)
                        val state = when (grant) {
                            PluginGrantState.Pending -> PluginCatalogState.PENDING
                            PluginGrantState.Denied -> PluginCatalogState.DENIED
                            PluginGrantState.Disabled -> PluginCatalogState.DISABLED
                            is PluginGrantState.Approved -> if (
                                principal.descriptor.launchable &&
                                PluginCapability.SURFACES !in grant.capabilities
                            ) {
                                PluginCatalogState.MISSING_CAPABILITY
                            } else {
                                PluginCatalogState.ENABLED
                            }
                        }
                        val settings = principal.descriptor.settingsActivity?.let { className ->
                            PluginSettingsTarget(principal.packageName, resolveClassName(principal.packageName, className))
                        }
                        entries += PluginCatalogEntry(
                            catalogKey = "external:${principal.packageName}:${principal.descriptor.id}",
                            id = principal.descriptor.id,
                            displayName = principal.descriptor.displayName,
                            state = state,
                            launchable = principal.descriptor.launchable && state == PluginCatalogState.ENABLED,
                            iconKey = principal.descriptor.iconKey,
                            iconDrawableResId = principal.descriptor.iconDrawableResId,
                            principal = principal,
                            settingsComponent = settings,
                        )
                    }
                }
            }

            val duplicateIds = entries.mapNotNull(PluginCatalogEntry::id)
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            val resolved = entries.map { entry ->
                if (entry.id in duplicateIds) {
                    entry.copy(state = PluginCatalogState.INVALID, launchable = false, detail = "DUPLICATE_PLUGIN_ID")
                } else {
                    entry
                }
            }.sortedWith(compareBy({ it.displayName.lowercase() }, { it.catalogKey }))
            val matches = RegistryPluginMatcher.match(
                feed = registryFeed,
                localCatalog = PluginCatalog(resolved),
                logger = registryLogger,
            ).matches
            val registryByCatalogKey = matches.mapNotNull { match ->
                match.localEntry?.catalogKey?.let { it to match.plugin }
            }.toMap()
            return PluginCatalog(
                resolved.map { entry ->
                    val registryPlugin = registryByCatalogKey[entry.catalogKey] ?: return@map entry
                    entry.copy(
                        provenance = PluginProvenance.REGISTRY,
                        registryAuthor = registryPlugin.author,
                    )
                },
            )
        }

        private fun resolveClassName(packageName: String, className: String): String = when {
            className.startsWith('.') -> packageName + className
            '.' !in className -> "$packageName.$className"
            else -> className
        }
    }
}
