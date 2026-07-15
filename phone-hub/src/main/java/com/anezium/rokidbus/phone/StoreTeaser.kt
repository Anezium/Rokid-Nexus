package com.anezium.rokidbus.phone

object StoreTeaser {
    fun newPluginCount(feed: RegistryFeed, installedPackageNames: Set<String>): Int =
        RegistryPluginMatcher.match(feed, PluginCatalog(emptyList())).matches.count { match ->
            match.plugin.artifact.packageName !in installedPackageNames
        }

    fun subtitle(feed: RegistryFeed, installedPackageNames: Set<String>): String =
        when (val count = newPluginCount(feed, installedPackageNames)) {
            0 -> "No new plugins"
            1 -> "1 new plugin"
            else -> "$count new plugins"
        }
}
