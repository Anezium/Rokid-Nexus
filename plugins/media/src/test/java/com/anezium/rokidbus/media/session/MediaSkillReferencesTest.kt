package com.anezium.rokidbus.media.session

import com.anezium.rokidbus.shared.skills.SkillLimits
import org.junit.Assert.*
import org.junit.Test

class MediaSkillReferencesTest {
    @Test fun referenceSurvivesRecreatedClientsAndControllerOrderChanges() {
        val references = MediaSkillReferences<String>()
        val firstClient = references.forTokens(listOf("a", "b"), 0)
        val nextClient = references.forTokens(listOf("b", "a"), 1)
        assertEquals(firstClient[0], nextClient[1])
        assertEquals(firstClient[1], nextClient[0])
        assertEquals("a", references.token(firstClient[0]))
    }

    @Test fun aReplacementSessionCannotReuseTheOldReference() {
        val references = MediaSkillReferences<String>()
        val old = references.forTokens(listOf("old"), 0).single()
        val replacement = references.forTokens(listOf("new"), 1).single()
        assertNull(references.token(old))
        assertNotEquals(old, replacement)
        assertEquals("new", references.token(replacement))
    }

    @Test fun idleReferencesExpireWithoutRetargeting() {
        val references = MediaSkillReferences<String>()
        val old = references.forTokens(listOf("a"), 0).single()
        val renewed = references.forTokens(listOf("a"), SkillLimits.REFERENCE_IDLE_MS + 1).single()
        assertNull(references.token(old))
        assertNotEquals(old, renewed)
    }

    @Test fun tooManySessionsFailWithoutInvalidatingExistingReferences() {
        val references = MediaSkillReferences<String>(2)
        val old = references.forTokens(listOf("a"), 0).single()
        assertThrows(IllegalStateException::class.java) {
            references.forTokens(listOf("a", "b", "c"), 1)
        }
        assertEquals("a", references.token(old))
    }
}
