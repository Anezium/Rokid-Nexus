package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.NexusPlugin
import com.anezium.rokidbus.shared.plugin.NexusPluginHost
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCatalogTest {
    private class BuiltIn(override val id: String) : NexusPlugin {
        override val displayName: String = id
        override fun onRegister(host: NexusPluginHost) = Unit
        override fun onOpen() = Unit
        override fun onClose() = Unit
        override fun onInput(event: NexusInputEvent) = Unit
    }

    private fun principal(id: String = "hello") = PhonePluginPrincipal(
        packageName = "dev.example.$id",
        serviceComponent = ComponentName("dev.example.$id", "dev.example.$id.Service"),
        uid = 10,
        signingDigestSha256 = "digest-$id",
        descriptor = PluginDescriptor(
            id = id,
            displayName = id.replaceFirstChar(Char::uppercase),
            apiVersion = 3,
            requestedCapabilities = setOf(PluginCapability.SURFACES),
            receivePrefixes = listOf("/plugin/$id", "/system/plugin"),
            settingsActivity = ".$id.SettingsActivity",
            launchable = true,
        ),
    )

    @Test
    fun `catalog merges built-in and approved external deterministically`() {
        val external = principal()
        val catalog = PluginCatalog.build(
            listOf(BuiltIn("lyrics")),
            listOf(PhonePluginCandidate.Valid(external)),
        ) { PluginGrantState.Approved(setOf(PluginCapability.SURFACES)) }
        assertEquals(listOf("hello", "lyrics"), catalog.launchableEntries.mapNotNull { it.id })
        assertEquals("dev.example.hello.hello.SettingsActivity", catalog.entry("hello")?.settingsComponent?.className)
    }

    @Test
    fun `pending disabled and missing surface grant are not launchable`() {
        val candidate = PhonePluginCandidate.Valid(principal())
        listOf<PluginGrantState>(
            PluginGrantState.Pending,
            PluginGrantState.Disabled,
            PluginGrantState.Approved(emptySet()),
        ).forEach { state ->
            assertTrue(PluginCatalog.build(emptyList(), listOf(candidate)) { state }.launchableEntries.isEmpty())
        }
    }

    @Test
    fun `built-in wins over stale external plugin with the same ID`() {
        val catalog = PluginCatalog.build(
            listOf(BuiltIn("hello")),
            listOf(PhonePluginCandidate.Valid(principal("hello"))),
        ) { PluginGrantState.Approved(setOf(PluginCapability.SURFACES)) }
        assertEquals(listOf("hello"), catalog.launchableEntries.mapNotNull { it.id })
        assertEquals(1, catalog.entries.size)
        assertEquals(PluginCatalogState.BUILT_IN, catalog.entry("hello")?.state)
    }

    @Test
    fun `transit remains built in when stale APK is installed`() {
        val withoutTransitApk = PluginCatalog.build(listOf(BuiltIn("lyrics"), BuiltIn("transit")), emptyList()) {
            PluginGrantState.Pending
        }
        assertTrue(withoutTransitApk.launchableEntries.any { it.id == "transit" })

        val transit = PhonePluginCandidate.Valid(principal("transit"))
        val installed = PluginCatalog.build(listOf(BuiltIn("lyrics"), BuiltIn("transit")), listOf(transit)) {
            PluginGrantState.Approved(setOf(PluginCapability.SURFACES))
        }
        assertEquals(1, installed.entries.count { it.id == "transit" })
        assertEquals(PluginCatalogState.BUILT_IN, installed.entry("transit")?.state)
    }
}
