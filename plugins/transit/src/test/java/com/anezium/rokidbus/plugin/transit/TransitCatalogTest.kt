package com.anezium.rokidbus.plugin.transit

import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.skills.SkillEffect
import com.anezium.rokidbus.shared.skills.SkillSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitCatalogTest {
    private fun input(op: String) = requireNotNull(transitCatalog.operation(op)).input
    private fun output(op: String) = requireNotNull(transitCatalog.operation(op)).output

    private fun SkillSchema.ObjectType.string(name: String) = properties.getValue(name) as SkillSchema.StringType
    private fun SkillSchema.ObjectType.array(name: String) = properties.getValue(name) as SkillSchema.ArrayType

    @Test
    fun `the catalog declares exactly Transit's operations`() {
        assertEquals(
            listOf(
                TransitSkillContract.LIST_FAVORITES,
                TransitSkillContract.SEARCH_STOPS,
                TransitSkillContract.GET_DEPARTURES,
                TransitSkillContract.START_JOURNEY,
                TransitSkillContract.JOURNEY_STATUS,
                TransitSkillContract.STOP_JOURNEY,
            ),
            transitCatalog.operations.map { it.id },
        )
    }

    @Test
    fun `declared limits follow the contract constants`() {
        assertEquals(TransitSkillContract.FAVORITES_PAGE_SIZE, output(TransitSkillContract.LIST_FAVORITES).array("stops").maxItems)
        assertEquals(TransitSkillContract.MAX_FILTER_CHARS, input(TransitSkillContract.LIST_FAVORITES).string("filter").maxLength)
        assertEquals(TransitSkillContract.MAX_CURSOR_CHARS, input(TransitSkillContract.LIST_FAVORITES).string("cursor").maxLength)
        assertEquals(TransitSkillContract.MAX_QUERY_CHARS, input(TransitSkillContract.SEARCH_STOPS).string("query").maxLength)
        assertEquals(TransitSkillContract.MAX_SEARCH_RESULTS, output(TransitSkillContract.SEARCH_STOPS).array("stops").maxItems)
        assertEquals(TransitSkillContract.MAX_BOARD_DEPARTURES, output(TransitSkillContract.GET_DEPARTURES).array("departures").maxItems)
        assertEquals(TransitSkillContract.MAX_LINE_CHARS, input(TransitSkillContract.GET_DEPARTURES).string("line").maxLength)
        assertEquals(TransitSkillContract.MAX_DIRECTION_CHARS, input(TransitSkillContract.GET_DEPARTURES).string("direction").maxLength)
    }

    @Test
    fun `stops, departures, and journeys are entity references, never plain identifiers`() {
        assertEquals("stop", input(TransitSkillContract.GET_DEPARTURES).string("stop").referenceType)
        assertEquals("departure", input(TransitSkillContract.GET_DEPARTURES).string("after").referenceType)
        assertEquals("journey", input(TransitSkillContract.STOP_JOURNEY).string("journey").referenceType)
        assertEquals("journey", output(TransitSkillContract.START_JOURNEY).string("journey").referenceType)
    }

    @Test
    fun `only the journey operations act, and they declare the activity they drive`() {
        val actions = transitCatalog.operations.filter { it.effect == SkillEffect.ACTION }.map { it.id }
        assertEquals(listOf(TransitSkillContract.START_JOURNEY, TransitSkillContract.STOP_JOURNEY), actions)
        transitCatalog.operations.forEach { op ->
            assertEquals(op.id, op.effect == SkillEffect.ACTION, PluginCapability.SURFACES in op.requires)
        }
    }

    @Test
    fun `no operation can return a coordinate`() {
        fun names(schema: SkillSchema): List<String> = when (schema) {
            is SkillSchema.ObjectType -> schema.properties.keys.toList() + schema.properties.values.flatMap(::names)
            is SkillSchema.ArrayType -> names(schema.items)
            else -> emptyList()
        }
        val forbidden = setOf("lat", "lon", "latitude", "longitude", "position", "coordinates", "location")
        transitCatalog.operations.forEach { op ->
            assertTrue(op.id, names(op.output).none { it in forbidden })
        }
    }
}
