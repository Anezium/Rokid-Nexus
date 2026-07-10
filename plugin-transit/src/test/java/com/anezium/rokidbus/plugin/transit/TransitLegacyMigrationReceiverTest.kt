package com.anezium.rokidbus.plugin.transit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

class TransitLegacyMigrationReceiverTest {
    private class FakeStorage : TransitMigrationStorage {
        val completed = mutableSetOf<String>()
        val imported = mutableListOf<List<TransitStop>>()
        var mode: TransitMode? = null
        override fun isComplete(id: String): Boolean = id in completed
        override fun import(stops: List<TransitStop>, mode: TransitMode?) {
            imported += stops
            this.mode = mode
        }
        override fun markComplete(id: String) { completed += id }
    }

    private fun payload(): JSONObject {
        val stops = JSONArray()
            .put(JSONObject().put("id", "a").put("name", "Alpha").put("lat", 1.0).put("lon", 2.0))
        val canonical = "favorites\n${JSONObject.quote("a")}|${JSONObject.quote("Alpha")}|1.0|2.0"
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return JSONObject()
            .put("version", 1)
            .put("type", "transit_legacy_state")
            .put("pluginId", "transit")
            .put("id", "migration-1")
            .put("count", 1)
            .put("checksum", checksum)
            .put("state", JSONObject().put("lastMode", "favorites").put("stops", stops))
    }

    @Test
    fun `valid state imports once and replay only acknowledges`() {
        val storage = FakeStorage()
        val receiver = TransitLegacyMigrationReceiver(storage)
        val first = receiver.receive(payload())
        val replay = receiver.receive(payload())
        assertNotNull(first)
        assertNotNull(replay)
        assertEquals(1, storage.imported.size)
        assertEquals("a", storage.imported.single().single().id)
        assertEquals(TransitMode.FAVORITES, storage.mode)
    }

    @Test
    fun `corrupt entry and checksum mismatch are rejected`() {
        val receiver = TransitLegacyMigrationReceiver(FakeStorage())
        assertNull(receiver.receive(payload().put("checksum", "wrong")))
        val corrupt = payload()
        corrupt.getJSONObject("state").getJSONArray("stops").getJSONObject(0).remove("lat")
        assertNull(receiver.receive(corrupt))
    }
}
