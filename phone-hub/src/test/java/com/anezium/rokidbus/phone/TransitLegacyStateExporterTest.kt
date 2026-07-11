package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitLegacyStateExporterTest {
    private class FakeStorage(
        var stops: String? = null,
        var mode: String? = null,
    ) : TransitLegacyStateStorage {
        val complete = mutableSetOf<String>()
        var clearCount = 0
        override fun readStops(): String? = stops
        override fun readLastMode(): String? = mode
        override fun clearLegacy() { clearCount += 1; stops = null; mode = null }
        override fun isComplete(key: String): Boolean = key in complete
        override fun markComplete(key: String) { complete += key }
    }

    private fun principal(id: String = "transit") = PhonePluginPrincipal(
        packageName = "dev.example.$id",
        serviceComponent = ComponentName("dev.example.$id", "dev.example.$id.Service"),
        uid = 42,
        signingDigestSha256 = "digest-$id",
        descriptor = PluginDescriptor(
            id = id,
            displayName = id,
            apiVersion = 3,
            requestedCapabilities = setOf(PluginCapability.SURFACES),
            receivePrefixes = listOf("/plugin/$id", "/system/plugin"),
            settingsActivity = null,
            launchable = true,
        ),
    )

    private val approved = PluginGrantState.Approved(setOf(PluginCapability.SURFACES))

    @Test
    fun `wrong principal and unapproved principal receive nothing`() {
        val exporter = TransitLegacyStateExporter(FakeStorage("[]", "near_me"))
        assertNull(exporter.prepare(principal("other"), approved))
        assertNull(exporter.prepare(principal(), PluginGrantState.Pending))
    }

    @Test
    fun `corrupt and duplicate entries are filtered`() {
        val stops = JSONArray()
            .put(JSONObject().put("id", "a").put("name", "Alpha").put("lat", 1).put("lon", 2))
            .put(JSONObject().put("id", "a").put("name", "Duplicate").put("lat", 3).put("lon", 4))
            .put(JSONObject().put("id", "bad").put("name", "Missing"))
        val pending = TransitLegacyStateExporter(FakeStorage(stops.toString(), "near_me"))
            .prepare(principal(), approved)!!
        assertEquals(1, pending.payload.getInt("count"))
    }

    @Test
    fun `invalid acknowledgement keeps state and valid acknowledgement clears once`() {
        val storage = FakeStorage(
            JSONArray().put(JSONObject().put("id", "a").put("name", "Alpha").put("lat", 1).put("lon", 2)).toString(),
            "favorites",
        )
        val exporter = TransitLegacyStateExporter(storage)
        val pending = exporter.prepare(principal(), approved)!!
        assertFalse(exporter.acknowledge(principal(), approved, JSONObject().put("id", "wrong")))
        assertEquals(0, storage.clearCount)
        val ack = JSONObject()
            .put("version", 1)
            .put("type", "transit_legacy_ack")
            .put("pluginId", "transit")
            .put("id", pending.eventId)
            .put("count", pending.payload.getInt("count"))
            .put("checksum", pending.payload.getString("checksum"))
        assertTrue(exporter.acknowledge(principal(), approved, ack))
        assertEquals(1, storage.clearCount)
        assertNull(exporter.prepare(principal(), approved))
    }

    @Test
    fun `empty legacy state is completed without payload`() {
        val storage = FakeStorage("[]", null)
        val exporter = TransitLegacyStateExporter(storage)
        assertNull(exporter.prepare(principal(), approved))
        assertTrue(storage.complete.isNotEmpty())
    }
}
