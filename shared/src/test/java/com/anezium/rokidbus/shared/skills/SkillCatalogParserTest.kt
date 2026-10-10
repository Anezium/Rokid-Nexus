package com.anezium.rokidbus.shared.skills

import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCatalogParserTest {
    private fun operation(id: String = "search_stops") = JSONObject()
        .put("id", id)
        .put("version", 1)
        .put("label", "Search stops")
        .put("description", "Finds stops by name.")
        .put("examples", JSONArray().put("Departures from Central"))
        .put("effect", "read")
        .put("cancellable", true)
        .put("deduplicates", true)
        .put("prerequisites", JSONArray().put("network"))
        .put("data", JSONArray().put("place_names"))
        .put(
            "input",
            JSONObject("""{"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":120}},"required":["query"]}"""),
        )
        .put(
            "output",
            JSONObject("""{"type":"object","properties":{"stop":{"type":"string","nexusRef":"stop"}}}"""),
        )

    private fun catalog(vararg operations: JSONObject) =
        JSONObject().put("version", 1).put("operations", JSONArray(operations.toList())).toString()

    private fun reason(text: String) = (SkillCatalogParser.parse(text) as SkillCatalogParseResult.Invalid).reason

    @Test
    fun `a valid catalog exposes typed operations`() {
        val result = SkillCatalogParser.parse(catalog(operation()))
        val op = (result as SkillCatalogParseResult.Valid).catalog.operations.single()

        assertEquals("search_stops", op.id)
        assertEquals(SkillEffect.READ, op.effect)
        assertEquals(setOf(SkillPrerequisite.NETWORK), op.prerequisites)
        assertEquals(setOf("stop"), op.outputReferenceTypes)
        assertEquals(64, op.digest.length)
    }

    @Test
    fun `the digest follows every approved field`() {
        fun digest(op: JSONObject) =
            ((SkillCatalogParser.parse(catalog(op)) as SkillCatalogParseResult.Valid).catalog.operations.single()).digest

        val base = digest(operation())
        assertEquals(base, digest(operation()))
        assertNotEquals(base, digest(operation().put("description", "Finds stops by their name.")))
        assertNotEquals(base, digest(operation().put("effect", "action")))
        assertNotEquals(base, digest(operation().put("data", JSONArray().put("place_names").put("schedules"))))
    }

    @Test
    fun `limits reject the whole catalog`() {
        val tooMany = (0..SkillLimits.MAX_OPERATIONS_PER_PROVIDER).map { operation("op_$it") }.toTypedArray()
        assertEquals("CATALOG_TOO_MANY_OPERATIONS", reason(catalog(*tooMany)))
        assertEquals(
            "OPERATION_DESCRIPTION",
            reason(catalog(operation().put("description", "é".repeat(SkillLimits.MAX_DESCRIPTION_BYTES / 2 + 1)))),
        )
        val huge = "x".repeat(SkillLimits.MAX_CATALOG_BYTES)
        assertEquals("CATALOG_TOO_LARGE", reason(catalog(operation().put("description", huge))))
        assertEquals("OPERATION_ID_DUPLICATE", reason(catalog(operation(), operation())))
    }

    @Test
    fun `unknown vocabulary and keys fail closed`() {
        assertEquals("OPERATION_DATA", reason(catalog(operation().put("data", JSONArray().put("location")))))
        assertEquals("OPERATION_KEY_UNSUPPORTED", reason(catalog(operation().put("route", "/x"))))
        assertEquals("OPERATION_REQUIRES", reason(catalog(operation().put("requires", JSONArray().put("camera")))))
        assertEquals("OPERATION_EFFECT", reason(catalog(operation().put("effect", "write"))))
        assertEquals("CATALOG_VERSION", reason("""{"version":2,"operations":[]}"""))
        assertEquals("CATALOG_NOT_JSON", reason("<xml/>"))
        assertEquals("INPUT_SCHEMA_MISSING", reason(catalog(operation().apply { remove("input") })))
    }

    @Test
    fun `an operation may require the surfaces grant and nothing else`() {
        val op = operation().put("requires", JSONArray().put("surfaces"))
        val parsed = (SkillCatalogParser.parse(catalog(op)) as SkillCatalogParseResult.Valid).catalog

        assertEquals(setOf(PluginCapability.SURFACES), parsed.operations.single().requires)
    }

    @Test
    fun `invalid utf8 is rejected`() {
        val result = SkillCatalogParser.parse(byteArrayOf(0x7b, 0xff.toByte(), 0x7d))
        assertTrue(result is SkillCatalogParseResult.Invalid)
    }

    @Test
    fun `canonical json does not depend on key order`() {
        assertEquals(
            CanonicalJson.write(JSONObject("""{"b":1,"a":[true,"x"]}""")),
            CanonicalJson.write(JSONObject("""{"a":[true,"x"],"b":1}""")),
        )
    }
}
