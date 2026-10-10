package com.anezium.rokidbus.media.session

import com.anezium.rokidbus.shared.skills.SkillLimits
import java.util.UUID

internal class MediaSkillReferences<T>(private val capacity: Int = 32) {
    private data class Reference<T>(val token: T, var touchedAt: Long)
    private val references = LinkedHashMap<String, Reference<T>>()

    @Synchronized
    fun forTokens(tokens: List<T>, now: Long): List<String> {
        check(tokens.size <= capacity)
        references.entries.removeAll { (_, ref) ->
            now - ref.touchedAt > SkillLimits.REFERENCE_IDLE_MS || ref.token !in tokens
        }
        return tokens.map { token ->
            val key = references.entries.find { it.value.token == token }?.key
                ?: UUID.randomUUID().toString().also { references[it] = Reference(token, now) }
            references.getValue(key).touchedAt = now
            key
        }
    }

    @Synchronized
    fun token(reference: String): T? = references[reference]?.token
}
