package com.anezium.rokidbus.shared.plugin

import com.anezium.rokidbus.shared.BusConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `icon key is optional normalized and unrestricted`() {
        fun iconKeyFor(value: String?): String? {
            val metadata = if (value == null) {
                validMetadata()
            } else {
                validMetadata() + (BusConstants.META_PLUGIN_ICON to value)
            }
            val result = PluginDescriptorParser.parse(metadata)
            assertTrue(result is PluginDescriptorParseResult.Valid)
            return (result as PluginDescriptorParseResult.Valid).descriptor.iconKey
        }

        assertNull(iconKeyFor(null))
        assertNull(iconKeyFor("   "))
        assertEquals("feed", iconKeyFor("feed"))
        assertEquals("music", iconKeyFor("  MUSIC  "))
        assertEquals("future-icon", iconKeyFor("future-icon"))
    }

    @Test
    fun `conflicting icon metadata cannot invalidate descriptor`() {
        val entries = validMetadata().entries.map { it.key to it.value } + listOf(
            BusConstants.META_PLUGIN_ICON to "music",
            BusConstants.META_PLUGIN_ICON to "star",
        )

        val result = PluginDescriptorParser.parse(entries)

        assertTrue(result is PluginDescriptorParseResult.Valid)
        assertEquals("star", (result as PluginDescriptorParseResult.Valid).descriptor.iconKey)
    }

    @Test
    fun `icon drawable resource id is optional and malformed values are ignored`() {
        fun iconDrawableResIdFor(value: String?): Int? {
            val metadata = if (value == null) {
                validMetadata()
            } else {
                validMetadata() + (BusConstants.META_PLUGIN_ICON_DRAWABLE to value)
            }
            val result = PluginDescriptorParser.parse(metadata)
            assertTrue(result is PluginDescriptorParseResult.Valid)
            return (result as PluginDescriptorParseResult.Valid).descriptor.iconDrawableResId
        }

        assertNull(iconDrawableResIdFor(null))
        assertEquals(2131230890, iconDrawableResIdFor("2131230890"))
        assertNull(iconDrawableResIdFor("0"))
        assertNull(iconDrawableResIdFor("not-a-resource-id"))
    }

    @Test
    fun `conflicting icon drawable metadata cannot invalidate descriptor`() {
        val entries = validMetadata().entries.map { it.key to it.value } + listOf(
            BusConstants.META_PLUGIN_ICON_DRAWABLE to "123",
            BusConstants.META_PLUGIN_ICON_DRAWABLE to "456",
        )

        val result = PluginDescriptorParser.parse(entries)

        assertTrue(result is PluginDescriptorParseResult.Valid)
        assertEquals(456, (result as PluginDescriptorParseResult.Valid).descriptor.iconDrawableResId)
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
    fun `camera receive prefixes require camera capability`() {
        val receive = "/system/plugin,/plugin/hello.plugin,/camera/session/state,/camera/link/offer"
        val withCamera = validMetadata() + mapOf(
            BusConstants.META_PLUGIN_CAPABILITIES to "camera",
            BusConstants.META_PLUGIN_RECEIVE_PREFIXES to receive,
        )
        val descriptor = PluginDescriptorParser.parse(withCamera)
        assertTrue(descriptor is PluginDescriptorParseResult.Valid)
        assertEquals(
            PluginDescriptorParseResult.Invalid("RECEIVE_PREFIX_OUTSIDE_NAMESPACE"),
            PluginDescriptorParser.parse(
                withCamera + (BusConstants.META_PLUGIN_CAPABILITIES to "surfaces"),
            ),
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
