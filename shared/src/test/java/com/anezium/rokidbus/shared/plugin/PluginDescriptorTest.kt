package com.anezium.rokidbus.shared.plugin

import com.anezium.rokidbus.shared.BusConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginDescriptorTest {
    private fun validMetadata() = linkedMapOf<String, String?>(
        BusConstants.META_PLUGIN_ID to "hello.plugin",
        BusConstants.META_PLUGIN_DISPLAY_NAME to "Hello",
        BusConstants.META_PLUGIN_API_VERSION to "3",
        BusConstants.META_PLUGIN_CAPABILITIES to "surfaces,http_proxy",
        BusConstants.META_PLUGIN_RECEIVE_PREFIXES to
            "/system/plugin,/plugin/hello.plugin,/http/request/reply",
    )

    @Test
    fun `valid descriptor is immutable and normalized`() {
        val result = PluginDescriptorParser.parse(validMetadata())
        assertTrue(result is PluginDescriptorParseResult.Valid)
        val descriptor = (result as PluginDescriptorParseResult.Valid).descriptor
        assertEquals("hello.plugin", descriptor.id)
        assertEquals(listOf("/http/request/reply", "/plugin/hello.plugin", "/system/plugin"), descriptor.receivePrefixes)
    }

    @Test
    fun `plugin IDs follow protocol grammar`() {
        listOf("abc", "a.b-c_d", "z12").forEach { assertTrue(PluginDescriptor.isValidId(it)) }
        listOf("ab", "2bad", "Bad", "a/b", "a b").forEach { id ->
            assertEquals(
                PluginDescriptorParseResult.Invalid("INVALID_PLUGIN_ID"),
                PluginDescriptorParser.parse(validMetadata() + (BusConstants.META_PLUGIN_ID to id)),
            )
        }
    }

    @Test
    fun `unknown capability and foreign prefix are rejected`() {
        assertEquals(
            PluginDescriptorParseResult.Invalid("UNKNOWN_CAPABILITY"),
            PluginDescriptorParser.parse(validMetadata() + (BusConstants.META_PLUGIN_CAPABILITIES to "surfaces,admin")),
        )
        assertEquals(
            PluginDescriptorParseResult.Invalid("RECEIVE_PREFIX_OUTSIDE_NAMESPACE"),
            PluginDescriptorParser.parse(validMetadata() + (BusConstants.META_PLUGIN_RECEIVE_PREFIXES to "/other")),
        )
    }

    @Test
    fun `conflicting duplicate metadata is rejected`() {
        val entries = validMetadata().entries.map { it.key to it.value } +
            (BusConstants.META_PLUGIN_ID to "other")
        assertEquals(
            PluginDescriptorParseResult.Invalid("CONFLICTING_METADATA"),
            PluginDescriptorParser.parse(entries),
        )
    }
}
