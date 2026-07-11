package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.plugin.PathRules

data class PluginWakeCandidate(
    val ownerKey: String,
    val uid: Int,
    val prefixes: List<String>,
    val approvedAndEnabled: Boolean,
)

sealed interface PluginWakeSelection {
    data object None : PluginWakeSelection
    data class Selected(val candidate: PluginWakeCandidate) : PluginWakeSelection
    data object Conflict : PluginWakeSelection
}

object PluginWakePolicy {
    fun select(
        path: String,
        candidates: Collection<PluginWakeCandidate>,
        excludeUid: Int? = null,
    ): PluginWakeSelection {
        val matches = candidates.filter { candidate ->
            candidate.approvedAndEnabled &&
                candidate.uid != excludeUid &&
                candidate.prefixes.any { PathRules.matchesPrefix(path, it) }
        }.distinctBy(PluginWakeCandidate::ownerKey)
        return when (matches.size) {
            0 -> PluginWakeSelection.None
            1 -> PluginWakeSelection.Selected(matches.single())
            else -> PluginWakeSelection.Conflict
        }
    }
}
