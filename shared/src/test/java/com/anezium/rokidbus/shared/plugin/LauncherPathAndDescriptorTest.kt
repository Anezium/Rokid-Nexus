package com.anezium.rokidbus.shared.plugin

import com.anezium.rokidbus.shared.AmbientPreferencesContract
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ComposeContract
import com.anezium.rokidbus.shared.QuickActionFamily
import com.anezium.rokidbus.shared.QuickActionPolicyContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherPathAndDescriptorTest {
    private val pluginPaths = listOf(
        BusPaths.ACTIVITY_RESULT,
        BusPaths.ACTIVITY_GEOMETRY,
        BusPaths.ACTIVITY_HEADING,
        BusPaths.NOTICE_WITHDRAW,
    )

    private val corePaths = listOf(
        BusPaths.CORE_NOTICE_PURGE,
        BusPaths.CORE_POLICY_QUICK_ACTIONS,
        BusPaths.CORE_COMPOSE_DICTATE_START,
        BusPaths.CORE_COMPOSE_DICTATE_PARTIAL,
        BusPaths.CORE_COMPOSE_DICTATE_FINAL,
        BusPaths.CORE_COMPOSE_DICTATE_STOP,
        BusPaths.CORE_COMPOSE_TYPE_START,
        BusPaths.CORE_COMPOSE_TYPE_TEXT,
        BusPaths.CORE_COMPOSE_TYPE_DONE,
        BusPaths.CORE_COMPOSE_TYPE_FINISH,
        BusPaths.CORE_COMPOSE_TYPE_CANCEL,
        BusPaths.CORE_AMBIENT_SIZES_SET,
        BusPaths.CORE_AMBIENT_SIZES,
        BusPaths.CORE_AMBIENT_POLICY,
    )

    @Test
    fun `the plugin paths of the bundle need surfaces and are owner stamped`() {
        pluginPaths.forEach { path ->
            assertEquals(path, PluginCapability.SURFACES, PathRules.requiredCapability(path))
            assertTrue(path, PathRules.isLauncherOwnerPath(path))
            assertFalse(path, PathRules.isReserved(path))
            // Nobody subscribes to them: they are not deliveries, and no prefix may claim them.
            assertFalse(path, PathRules.isDirectReply(path))
            assertFalse(path, PathRules.isAllowedReceivePrefix(path, "todo", PluginCapability.entries.toSet()))
        }
        assertFalse(PathRules.isLauncherOwnerPath(BusPaths.ACTIVITY_START))
        assertFalse(PathRules.isLauncherOwnerPath(BusPaths.ACTIVITY_ACTION))
        // The command the owner receives keeps its existing owner-scoped direct reply.
        assertTrue(PathRules.isDirectReply(BusPaths.ACTIVITY_ACTION))
        assertTrue(PathRules.isOwnerScoped(BusPaths.ACTIVITY_ACTION))
    }

    @Test
    fun `every new core route stays reserved and unlockable by no capability`() {
        val declared = corePaths.toSet() + ComposeContract.glassesToPhonePaths + ComposeContract.phoneToGlassesPaths +
            setOf(AmbientPreferencesContract.SIZES_SET_PATH, AmbientPreferencesContract.SIZES_PATH, AmbientPreferencesContract.POLICY_PATH)
        assertEquals(corePaths.toSet(), declared)
        corePaths.forEach { path ->
            assertTrue(path, path.startsWith("/core/"))
            assertTrue(path, PathRules.isReserved(path))
            assertNull(path, PathRules.requiredCapability(path))
            assertNull(path, PathRules.requiredCapabilityForReceivePrefix(path))
            assertFalse(path, PathRules.isAllowedReceivePrefix(path, "todo", PluginCapability.entries.toSet()))
            assertFalse(path, PathRules.isDirectReply(path))
            assertFalse(path, PathRules.isOwnerScoped(path))
        }
        assertFalse(PathRules.isAllowedReceivePrefix("/core", "todo", PluginCapability.entries.toSet()))
    }

    @Test
    fun `quick action metadata declares families without touching capabilities`() {
        fun descriptor(vararg extra: Pair<String, String?>): PluginDescriptor {
            val result = PluginDescriptorParser.parse(validMetadata() + extra.toList())
            assertTrue("$result", result is PluginDescriptorParseResult.Valid)
            return (result as PluginDescriptorParseResult.Valid).descriptor
        }
        assertTrue(descriptor().quickActionFamilies.isEmpty())
        val declared = descriptor(QuickActionPolicyContract.META_PLUGIN_QUICK_ACTIONS to "checklist, media")
        assertEquals(setOf(QuickActionFamily.CHECKLIST, QuickActionFamily.MEDIA), declared.quickActionFamilies)
        assertEquals(setOf(PluginCapability.SURFACES), declared.requestedCapabilities)
        assertEquals(setOf(QuickActionFamily.MEDIA), descriptor(QuickActionPolicyContract.META_PLUGIN_QUICK_ACTIONS to "media,unknown").quickActionFamilies)
        assertTrue(descriptor(QuickActionPolicyContract.META_PLUGIN_QUICK_ACTIONS to "everything").quickActionFamilies.isEmpty())
        // Conflicting declarations fail closed to none rather than invalidating the plugin.
        assertTrue(
            descriptor(
                QuickActionPolicyContract.META_PLUGIN_QUICK_ACTIONS to "media",
                QuickActionPolicyContract.META_PLUGIN_QUICK_ACTIONS to "checklist",
            ).quickActionFamilies.isEmpty(),
        )
    }

    private fun validMetadata(): List<Pair<String, String?>> = listOf(
        BusConstants.META_PLUGIN_ID to "todo",
        BusConstants.META_PLUGIN_DISPLAY_NAME to "Todo",
        BusConstants.META_PLUGIN_API_VERSION to "3",
        BusConstants.META_PLUGIN_CAPABILITIES to "surfaces",
        BusConstants.META_PLUGIN_RECEIVE_PREFIXES to "/system/plugin,/plugin/todo",
    )
}
