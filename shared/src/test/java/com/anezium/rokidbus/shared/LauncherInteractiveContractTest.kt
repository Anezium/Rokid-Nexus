package com.anezium.rokidbus.shared

import java.lang.reflect.Modifier
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherInteractiveContractTest {
    @Test
    fun `the bundle takes bit 14 and leaves bit 13 unallocated`() {
        assertEquals(1 shl 14, BusCapabilityBits.LAUNCHER_INTERACTIVE)
        val bits = BusCapabilityBits::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .map { it.isAccessible = true; it.getInt(null) }
        assertFalse("1 shl 13 is reserved for the unshipped widget branch", (1 shl 13) in bits)
        assertEquals("every feature bit is distinct", bits.size, bits.toSet().size)
    }

    @Test
    fun `negotiation runs the lower version only when both announce one`() {
        assertEquals(1, LauncherInteractiveContract.negotiated(1, 1))
        assertEquals(1, LauncherInteractiveContract.negotiated(1, 4))
        assertEquals(2, LauncherInteractiveContract.negotiated(3, 2))
        assertEquals(0, LauncherInteractiveContract.negotiated(1, 0))
        assertEquals(0, LauncherInteractiveContract.negotiated(0, 1))
        assertEquals(0, LauncherInteractiveContract.negotiated(-1, 3))
        assertTrue(LauncherInteractiveContract.isActive(1))
        assertFalse(LauncherInteractiveContract.isActive(0))
    }

    @Test
    fun `announced versions read malformed values as absent`() {
        assertEquals(1, LauncherInteractiveContract.parseVersion(1))
        assertEquals(7, LauncherInteractiveContract.parseVersion(7L))
        listOf(null, "1", -2, 1.5, Long.MAX_VALUE, JSONObject.NULL).forEach {
            assertEquals("$it", 0, LauncherInteractiveContract.parseVersion(it))
        }
    }

    @Test
    fun `glasses announce the version and need the bit for it to count`() {
        fun glasses(features: Int, version: Int) = GlassesHubCapabilitiesContract.parse(
            GlassesHubCapabilitiesContract.toJson(
                GlassesHubCapabilitiesContract.create(
                    features = features,
                    imageSurfaceVersion = 0,
                    maxImageBytes = 0,
                    versionName = null,
                    launcherInteractiveVersion = version,
                ),
            ),
        )
        val capable = glasses(BusCapabilityBits.LAUNCHER_INTERACTIVE, 1)
        assertEquals(1, capable.launcherInteractiveVersion)
        assertEquals(1, GlassesHubCapabilitiesContract.negotiatedLauncherInteractiveVersion(capable))
        assertTrue(GlassesHubCapabilitiesContract.supportsLauncherInteractive(capable))

        // A newer glasses build runs this phone's version, never more.
        val newer = glasses(BusCapabilityBits.LAUNCHER_INTERACTIVE, 3)
        assertEquals(1, GlassesHubCapabilitiesContract.negotiatedLauncherInteractiveVersion(newer))

        assertFalse(GlassesHubCapabilitiesContract.supportsLauncherInteractive(glasses(0, 1)))
        assertFalse(GlassesHubCapabilitiesContract.supportsLauncherInteractive(glasses(BusCapabilityBits.LAUNCHER_INTERACTIVE, 0)))

        // An old glasses payload has no field at all, and the exact-match versions are untouched.
        val old = GlassesHubCapabilitiesContract.parse(JSONObject().put("version", 1).put("pageSessionVersion", 1))
        assertEquals(0, old.launcherInteractiveVersion)
        assertEquals(1, old.pageSessionVersion)
        assertFalse(GlassesHubCapabilitiesContract.supportsLauncherInteractive(old))
        assertEquals(0, GlassesHubCapabilitiesContract.create(features = 0, imageSurfaceVersion = 0, maxImageBytes = 0, versionName = null, launcherInteractiveVersion = -4).launcherInteractiveVersion)
    }

    @Test
    fun `phone announces the version and an explicit backend choice only when made`() {
        val phone = PhoneHubCapabilitiesContract.create(
            features = 0,
            cameraConsumerName = null,
            pageSessionVersion = 1,
            launcherInteractiveVersion = 1,
            launcherBackend = LauncherInteractiveContract.BACKEND_SESSION,
        )
        val payload = PhoneHubCapabilitiesContract.toJson(phone)
        assertEquals(1, payload.getInt(LauncherInteractiveContract.CAPABILITY_FIELD_VERSION))
        assertEquals("session", payload.getString(LauncherInteractiveContract.CAPABILITY_FIELD_LAUNCHER_BACKEND))
        val parsed = PhoneHubCapabilitiesContract.parse(payload)
        assertEquals(phone, parsed)
        assertTrue(PhoneHubCapabilitiesContract.supportsLauncherInteractive(parsed))

        val noOpinion = PhoneHubCapabilitiesContract.toJson(PhoneHubCapabilitiesContract.create(features = 0, cameraConsumerName = null))
        assertFalse(noOpinion.has(LauncherInteractiveContract.CAPABILITY_FIELD_LAUNCHER_BACKEND))
        assertEquals(0, noOpinion.getInt(LauncherInteractiveContract.CAPABILITY_FIELD_VERSION))
        assertNull(PhoneHubCapabilitiesContract.parse(noOpinion).launcherBackend)
        assertFalse(PhoneHubCapabilitiesContract.supportsLauncherInteractive(PhoneHubCapabilitiesContract.parse(noOpinion)))

        // An unknown backend is no opinion, never a guess.
        val unknown = JSONObject(payload.toString()).put(LauncherInteractiveContract.CAPABILITY_FIELD_LAUNCHER_BACKEND, "fancy")
        assertNull(PhoneHubCapabilitiesContract.parse(unknown).launcherBackend)
        assertNull(PhoneHubCapabilitiesContract.create(features = 0, cameraConsumerName = null, launcherBackend = "fancy").launcherBackend)
        assertEquals("legacy", PhoneHubCapabilitiesContract.parse(JSONObject().put("launcherBackend", "legacy")).launcherBackend)
    }

    @Test
    fun `collection capacity follows the negotiated bundle`() {
        assertEquals(8, ActivitySurfaceContract.effectiveCollectionCapacity(1))
        assertEquals(8, ActivitySurfaceContract.effectiveCollectionCapacity(2))
        assertEquals(2, ActivitySurfaceContract.effectiveCollectionCapacity(0))
        assertEquals(ActivitySurfaceContract.MAX_ACTIVE_ACTIVITIES, ActivitySurfaceContract.MAX_AMBIENT_ACTIVITIES)
    }
}
