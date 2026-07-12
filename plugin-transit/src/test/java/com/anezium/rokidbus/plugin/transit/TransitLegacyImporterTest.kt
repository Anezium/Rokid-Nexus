package com.anezium.rokidbus.plugin.transit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitLegacyImporterTest {
    private class FakeStorage(
        private val stops: String? = null,
        private val mode: String? = null,
        private val exists: Boolean = true,
    ) : TransitLegacyStorage {
        var complete = false
        override fun hasLegacyState(): Boolean = exists
        override fun readStops(): String? = stops
        override fun readLastMode(): String? = mode
        override fun isComplete(): Boolean = complete
        override fun markComplete() { complete = true }
    }

    @Test
    fun `direct import filters legacy state and runs once`() {
        val legacy = JSONArray()
            .put(JSONObject().put("id", "a").put("name", "Alpha").put("lat", 1.0).put("lon", 2.0))
            .put(JSONObject().put("id", "a").put("name", "Duplicate").put("lat", 3.0).put("lon", 4.0))
            .put(JSONObject().put("id", "bad").put("name", "Missing"))
        val storage = FakeStorage(legacy.toString(), "favorites")
        val imported = mutableListOf<TransitStop>()
        var importedMode: TransitMode? = null
        val favorites = { stops: List<TransitStop>, mode: TransitMode? ->
            imported += stops
            importedMode = mode
        }
        val importer = TransitLegacyImporter(storage)

        assertTrue(importer.importIfNeeded(favorites))
        assertFalse(importer.importIfNeeded(favorites))
        assertEquals(listOf("a"), imported.map(TransitStop::id))
        assertEquals(TransitMode.FAVORITES, importedMode)
    }

    @Test
    fun `missing legacy preferences remain eligible`() {
        val storage = FakeStorage(exists = false)
        assertFalse(TransitLegacyImporter(storage).importIfNeeded { _, _ -> })
        assertFalse(storage.complete)
    }
}
