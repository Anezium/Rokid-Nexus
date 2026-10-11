package com.anezium.rokidbus.shared.skills

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillSchemaTest {
    private fun parse(json: String): SkillSchema.ObjectType =
        (SkillSchemaParser.parseRoot(JSONObject(json)) as SkillSchemaParseResult.Valid).schema

    private fun reason(json: String): String =
        (SkillSchemaParser.parseRoot(JSONObject(json)) as SkillSchemaParseResult.Invalid).reason

    private val stopQuery = """
        {"type":"object","properties":{
          "query":{"type":"string","minLength":1,"maxLength":120,"description":"Stop name"},
          "limit":{"type":"integer","minimum":1,"maximum":8},
          "mode":{"type":"string","enum":["bus","tram"]},
          "stop":{"type":"string","nexusRef":"stop"},
          "tags":{"type":"array","items":{"type":"string","maxLength":8},"maxItems":3}
        },"required":["query"],"additionalProperties":false}
    """.trimIndent()

    @Test
    fun `the subset parses and round-trips`() {
        val schema = parse(stopQuery)

        assertEquals(setOf("query"), schema.required)
        assertEquals("stop", (schema.properties["stop"] as SkillSchema.StringType).referenceType)
        assertEquals(schema, parse(schema.toJson().toString()))
    }

    @Test
    fun `keywords outside the subset reject the schema`() {
        assertEquals(
            "SCHEMA_KEYWORD_UNSUPPORTED",
            reason("""{"type":"object","properties":{"a":{"type":"string","maxLength":4,"pattern":"x"}}}"""),
        )
        assertEquals("SCHEMA_KEYWORD_UNSUPPORTED", reason("""{"type":"object","${'$'}ref":"#/x"}"""))
        assertEquals("SCHEMA_KEYWORD_UNSUPPORTED", reason("""{"type":"object","oneOf":[]}"""))
        assertEquals("SCHEMA_TYPE_UNSUPPORTED", reason("""{"type":["object","null"]}"""))
        assertEquals("SCHEMA_ADDITIONAL_PROPERTIES", reason("""{"type":"object","additionalProperties":true}"""))
        assertEquals("ROOT_NOT_OBJECT", reason("""{"type":"string","maxLength":3}"""))
    }

    @Test
    fun `strings arrays and numbers must be bounded`() {
        assertEquals("SCHEMA_BOUND_MISSING", reason("""{"type":"object","properties":{"a":{"type":"string"}}}"""))
        assertEquals(
            "SCHEMA_BOUND_MISSING",
            reason("""{"type":"object","properties":{"a":{"type":"array","items":{"type":"boolean"}}}}"""),
        )
        assertEquals("SCHEMA_RANGE_MISSING", reason("""{"type":"object","properties":{"a":{"type":"integer"}}}"""))
        assertEquals(
            "SCHEMA_BOUND_INVALID",
            reason(
                """{"type":"object","properties":{"a":{"type":"string","maxLength":${SkillLimits.MAX_STRING_LENGTH + 1}}}}""",
            ),
        )
    }

    @Test
    fun `depth and size are capped`() {
        var nested = """{"type":"boolean"}"""
        repeat(SkillLimits.MAX_SCHEMA_DEPTH) { nested = """{"type":"object","properties":{"a":$nested}}""" }
        assertEquals("SCHEMA_TOO_DEEP", reason(nested))
    }

    @Test
    fun `references cannot carry their own constraints`() {
        assertEquals(
            "SCHEMA_REFERENCE_INVALID",
            reason("""{"type":"object","properties":{"a":{"type":"string","nexusRef":"stop","maxLength":9}}}"""),
        )
        assertEquals(
            "SCHEMA_REFERENCE_INVALID",
            reason("""{"type":"object","properties":{"a":{"type":"string","nexusRef":"Stop!"}}}"""),
        )
    }

    @Test
    fun `validation rejects unknown missing mistyped and out-of-range values`() {
        val schema = parse(stopQuery)
        fun check(json: String) = SkillSchemaValidator.validate(JSONObject(json), schema)

        assertEquals(SkillValidation.Valid, check("""{"query":"Central","limit":3,"tags":["a"]}"""))
        assertEquals("UNKNOWN_PROPERTY", (check("""{"query":"a","extra":1}""") as SkillValidation.Invalid).reason)
        assertEquals("REQUIRED", (check("""{}""") as SkillValidation.Invalid).reason)
        assertEquals("LENGTH", (check("""{"query":""}""") as SkillValidation.Invalid).reason)
        assertEquals("RANGE", (check("""{"query":"a","limit":9}""") as SkillValidation.Invalid).reason)
        assertEquals("TYPE", (check("""{"query":"a","limit":1.5}""") as SkillValidation.Invalid).reason)
        assertEquals("ENUM", (check("""{"query":"a","mode":"ferry"}""") as SkillValidation.Invalid).reason)
        assertEquals("NULL", (check("""{"query":null}""") as SkillValidation.Invalid).reason)
        assertEquals("LENGTH", (check("""{"query":"a","tags":["1","2","3","4"]}""") as SkillValidation.Invalid).reason)
    }

    @Test
    fun `references are rewritten deep and a single refusal rejects the instance`() {
        val schema = parse(
            """{"type":"object","properties":{
                 "stops":{"type":"array","maxItems":4,"items":{"type":"object","properties":{
                   "stop":{"type":"string","nexusRef":"stop"},"name":{"type":"string","maxLength":40}}}}}}""",
        )
        val instance = JSONObject().put(
            "stops",
            JSONArray()
                .put(JSONObject().put("stop", "A").put("name", "Alpha"))
                .put(JSONObject().put("stop", "B").put("name", "Beta")),
        )

        val mapped = SkillSchemaValidator.mapReferences(instance, schema) { type, value -> "$type:$value" }
        val copy = (mapped as SkillSchemaValidator.ReferenceMapping.Mapped).instance
        assertEquals("stop:B", copy.getJSONArray("stops").getJSONObject(1).getString("stop"))
        assertEquals("Beta", copy.getJSONArray("stops").getJSONObject(1).getString("name"))
        assertEquals("A", instance.getJSONArray("stops").getJSONObject(0).getString("stop"))

        val rejected = SkillSchemaValidator.mapReferences(instance, schema) { _, value -> value.takeIf { it == "A" } }
        assertEquals("$.stops[1].stop", (rejected as SkillSchemaValidator.ReferenceMapping.Rejected).path)
    }

    @Test
    fun `the model projection hides the reference keyword and explains it`() {
        val model = parse(stopQuery).toModelJson()
        val stop = model.getJSONObject("properties").getJSONObject("stop")

        assertFalse(stop.has(SkillSchema.KEY_REFERENCE))
        assertTrue(stop.getString("description").contains("never make one up"))
        assertEquals(false, model.get("additionalProperties"))
    }
}
