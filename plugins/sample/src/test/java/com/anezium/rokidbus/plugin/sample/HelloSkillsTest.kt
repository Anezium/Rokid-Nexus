package com.anezium.rokidbus.plugin.sample

import com.anezium.rokidbus.shared.skills.SkillCatalogParseResult
import com.anezium.rokidbus.shared.skills.SkillCatalogParser
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import com.anezium.rokidbus.shared.skills.SkillSchemaValidator
import com.anezium.rokidbus.shared.skills.SkillValidation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HelloSkillsTest {
    private val catalog = (
        SkillCatalogParser.parse(File("src/main/res/raw/nexus_skills.json").readText(Charsets.UTF_8))
            as SkillCatalogParseResult.Valid
        ).catalog

    @Test
    fun `the catalog is valid and declares the handled operation`() {
        assertEquals(listOf(HelloSkills.COUNT_WORDS), catalog.operations.map { it.id })
    }

    @Test
    fun `count_words answers within its declared output`() {
        val arguments = JSONObject().put("text", "  How many words are here?  ")
        assertEquals(SkillValidation.Valid, SkillSchemaValidator.validate(arguments, catalog.operations.single().input))

        val answer = HelloSkills.answer(HelloSkills.COUNT_WORDS, arguments) as HelloSkills.Answer.Completed

        assertEquals(5, answer.data.getInt("words"))
        assertEquals(28, answer.data.getInt("characters"))
        assertEquals(SkillValidation.Valid, SkillSchemaValidator.validate(answer.data, catalog.operations.single().output))
    }

    @Test
    fun `an operation the plugin does not know is refused as unsupported`() {
        assertEquals(
            HelloSkills.Answer.Failed(SkillErrorCodes.UNSUPPORTED_OPERATION),
            HelloSkills.answer("delete_everything", JSONObject()),
        )
    }
}
