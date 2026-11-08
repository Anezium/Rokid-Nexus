package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.plugin.PluginCapability

/** Plain-data settings target; callers build the ComponentName at launch time. */
data class PluginSettingsTarget(
    val packageName: String,
    val className: String,
)

enum class PluginCatalogState {
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
    val principal: PhonePluginPrincipal? = null,
    val settingsComponent: PluginSettingsTarget? = null,
    val detail: String? = null,
    val provenance: PluginProvenance = PluginProvenance.LOCAL,
    val registryAuthor: String? = null,
)

data class PluginCatalog(val entries: List<PluginCatalogEntry>) {
    val launchableEntries: List<PluginCatalogEntry>
        get() = entries.filter { it.launchable && it.state == PluginCatalogState.ENABLED }

    fun entry(id: String): PluginCatalogEntry? = entries.singleOrNull { it.id == id }

    companion object {
        fun build(
            candidates: Collection<PhonePluginCandidate>,
            registryFeed: RegistryFeed = RegistryFeed(RegistryClient.SUPPORTED_VERSION, emptyList()),
            registryLogger: (String) -> Unit = {},
            grantState: (PhonePluginPrincipal) -> PluginGrantState,
        ): PluginCatalog {
            val entries = mutableListOf<PluginCatalogEntry>()
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
                        val grant = grantState(principal)
                        val state = when (grant) {
                            PluginGrantState.Pending -> PluginCatalogState.PENDING
                            PluginGrantState.Denied -> PluginCatalogState.DENIED
                            PluginGrantState.Disabled -> PluginCatalogState.DISABLED
                            is PluginGrantState.Approved -> if (
                                principal.descriptor.launchable &&
                                PluginCapability.SURFACES !in grant.capabilities &&
                                PluginCapability.INK_SURFACE !in grant.capabilities
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
