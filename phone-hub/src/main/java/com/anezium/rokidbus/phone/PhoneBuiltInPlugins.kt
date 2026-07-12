package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.lyrics.LyricsPlugin
import com.anezium.rokidbus.media.MediaDeckPlugin
import com.anezium.rokidbus.phone.lens.LensTranslationPlugin
import com.anezium.rokidbus.shared.plugin.NexusPlugin

/** The temporary in-process plugin set and its catalog presentation live in one place. */
object PhoneBuiltInPlugins {
    fun create(lens: LensTranslationPlugin): List<NexusPlugin> =
        listOf(LyricsPlugin(), MediaDeckPlugin(), lens)

    fun catalog(
        context: Context,
        runtimes: Collection<NexusPlugin> = emptyList(),
    ): PluginCatalog {
        val runtimeById = runtimes.associateBy(NexusPlugin::id)
        return PluginCatalog.build(
            builtIns = runtimes,
            candidates = PhonePluginDiscovery(context.packageManager).discover(),
            grantState = PluginGrantStore(context)::stateFor,
            builtInSpecs = specs(context.packageName, runtimeById),
        )
    }

    internal fun specs(
        packageName: String,
        runtimeById: Map<String, NexusPlugin>,
    ): List<BuiltInPluginCatalogSpec> = listOf(
        spec(packageName, "lyrics", "Lyrics", true, ".LyricsSettingsActivity", runtimeById),
        spec(packageName, "media", "Media Deck", true, ".MediaDeckSettingsActivity", runtimeById),
        spec(packageName, "lens", "Lens", false, ".LensSettingsActivity", runtimeById),
    )

    private fun spec(
        packageName: String,
        id: String,
        displayName: String,
        launchable: Boolean,
        settingsClass: String,
        runtimeById: Map<String, NexusPlugin>,
    ) = BuiltInPluginCatalogSpec(
        id = id,
        displayName = displayName,
        launchable = launchable,
        settingsTarget = PluginSettingsTarget(packageName, packageName + settingsClass),
        runtime = runtimeById[id],
    )
}
