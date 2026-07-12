package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreCatalogTest {
    @Test
    fun `feed-only plugin is available`() {
        val catalog = build(remote = listOf(plugin()))

        assertEquals(StoreEntryState.AVAILABLE, catalog.entry("feeds")?.state)
    }

    @Test
    fun `newer registry version is update available`() {
        val catalog = build(
            remote = listOf(plugin(versionCode = 8)),
            local = listOf(local()),
            versions = mapOf(PACKAGE to 7L),
        )

        assertEquals(StoreEntryState.UPDATE_AVAILABLE, catalog.entry("feeds")?.state)
        assertEquals(PluginCatalogState.ENABLED, catalog.entry("feeds")?.localGrantState)
    }

    @Test
    fun `equal or older registry version is installed and carries every grant state`() {
        val grantStates = listOf(
            PluginCatalogState.ENABLED,
            PluginCatalogState.PENDING,
            PluginCatalogState.DISABLED,
            PluginCatalogState.DENIED,
        )
        grantStates.forEach { grantState ->
            val catalog = build(
                remote = listOf(plugin(versionCode = 7)),
                local = listOf(local(state = grantState)),
                versions = mapOf(PACKAGE to 7L),
            )
            assertEquals(StoreEntryState.INSTALLED, catalog.entry("feeds")?.state)
            assertEquals(grantState, catalog.entry("feeds")?.localGrantState)
        }
    }

    @Test
    fun `local-only plugin is sideloaded`() {
        val catalog = build(local = listOf(local()), versions = mapOf(PACKAGE to 7L))

        assertEquals(StoreEntryState.SIDELOADED, catalog.entry("feeds")?.state)
        assertEquals(7L, catalog.entry("feeds")?.installedVersionCode)
    }

    @Test
    fun `feed-only host requirement disables install`() {
        val catalog = build(
            remote = listOf(plugin(minHostVersionCode = 7)),
            hostVersionCode = 6,
        )

        assertEquals(StoreEntryState.REQUIRES_HOST, catalog.entry("feeds")?.state)
    }

    @Test
    fun `host-incompatible update keeps installed plugin accessible`() {
        val catalog = build(
            remote = listOf(plugin(versionCode = 8, minHostVersionCode = 7)),
            local = listOf(local()),
            versions = mapOf(PACKAGE to 7L),
            hostVersionCode = 6,
        )

        assertEquals(StoreEntryState.INSTALLED, catalog.entry("feeds")?.state)
        assertEquals(true, catalog.entry("feeds")?.updateBlockedByHost)
        assertEquals(PluginCatalogState.ENABLED, catalog.entry("feeds")?.localGrantState)
    }

    @Test
    fun `built-in plugin remains installed rather than sideloaded`() {
        val builtIn = PluginCatalogEntry(
            catalogKey = "builtin:lens",
            id = "lens",
            displayName = "Lens",
            state = PluginCatalogState.BUILT_IN,
            launchable = false,
            settingsComponent = PluginSettingsTarget("com.anezium.rokidbus.phone", "com.anezium.rokidbus.phone.LensSettingsActivity"),
        )

        val catalog = build(local = listOf(builtIn))

        assertEquals(StoreEntryState.INSTALLED, catalog.entry("lens")?.state)
        assertEquals(PluginCatalogState.BUILT_IN, catalog.entry("lens")?.localGrantState)
    }

    @Test
    fun `duplicate registry plugin ids are all dropped and logged`() {
        val logs = mutableListOf<String>()
        val catalog = build(
            remote = listOf(plugin(), plugin(id = "other", packageName = "dev.other")),
            logger = logs::add,
        )

        assertTrue(catalog.entries.isEmpty())
        assertEquals(listOf("Dropping duplicate registry plugin id=feeds"), logs)
    }

    @Test
    fun `registry id and package must both match local identity`() {
        val logs = mutableListOf<String>()
        val local = local(packageName = "dev.sideloaded.feeds")
        val catalog = build(remote = listOf(plugin()), local = listOf(local), logger = logs::add)

        assertEquals(StoreEntryState.SIDELOADED, catalog.entry("feeds")?.state)
        assertNull(catalog.entry("feeds")?.registryPlugin)
        assertEquals(listOf("Dropping registry identity mismatch id=feeds"), logs)
    }

    @Test
    fun `descriptor id mismatch and non-phone artifacts are dropped`() {
        val logs = mutableListOf<String>()
        val mismatchedId = plugin(id = "wrong")
        val nonPhone = plugin(pluginId = "transit", id = "transit", target = "glasses", packageName = "dev.transit")

        val catalog = build(remote = listOf(mismatchedId, nonPhone), logger = logs::add)

        assertTrue(catalog.entries.isEmpty())
        assertEquals(2, logs.size)
    }

    private fun build(
        remote: List<RegistryPlugin> = emptyList(),
        local: List<PluginCatalogEntry> = emptyList(),
        versions: Map<String, Long> = emptyMap(),
        hostVersionCode: Long = 6,
        logger: (String) -> Unit = {},
    ) = StoreCatalog.build(
        feed = RegistryFeed(1, remote),
        localCatalog = PluginCatalog(local),
        installedVersionCodes = versions,
        hostVersionCode = hostVersionCode,
        logger = logger,
    )

    private fun plugin(
        pluginId: String = "feeds",
        id: String = pluginId,
        packageName: String = PACKAGE,
        versionCode: Long = 7,
        minHostVersionCode: Long = 6,
        target: String = "phone",
    ) = RegistryPlugin(
        id = id,
        name = "Feeds",
        category = "Social",
        summary = "Read feeds.",
        description = "Read social feeds on your glasses.",
        author = "Anezium",
        sourceUrl = "https://github.com/Anezium/Rokid-Nexus",
        publishedAt = "2026-07-12",
        iconAsset = "feeds.png",
        screenshotAssets = emptyList(),
        listingDescriptionMarkdown = "Feeds",
        releases = emptyList(),
        nexus = RegistryNexus(pluginId, 3, listOf("surfaces"), true, null, minHostVersionCode),
        artifact = RegistryArtifact(
            target = target,
            url = "https://github.com/Anezium/Rokid-Nexus/releases/download/feeds-v0.1.0/feeds-phone-release.apk",
            sha256 = "ab".repeat(32),
            sizeBytes = 123L,
            packageName = packageName,
            versionCode = versionCode,
            versionName = "0.1.0",
        ),
    )

    private fun local(
        packageName: String = PACKAGE,
        state: PluginCatalogState = PluginCatalogState.ENABLED,
    ): PluginCatalogEntry {
        val descriptor = PluginDescriptor(
            id = "feeds",
            displayName = "Feeds local",
            apiVersion = 3,
            requestedCapabilities = setOf(PluginCapability.SURFACES),
            receivePrefixes = listOf("/plugin/feeds"),
            settingsActivity = null,
            launchable = true,
        )
        val principal = PhonePluginPrincipal(
            packageName = packageName,
            serviceComponent = ComponentName(packageName, "$packageName.Service"),
            uid = 10001,
            signingDigestSha256 = "digest",
            descriptor = descriptor,
        )
        return PluginCatalogEntry(
            catalogKey = "external:$packageName:feeds",
            id = "feeds",
            displayName = "Feeds local",
            state = state,
            launchable = state == PluginCatalogState.ENABLED,
            principal = principal,
        )
    }

    companion object {
        private const val PACKAGE = "com.anezium.rokidbus.plugin.feeds"
    }
}
