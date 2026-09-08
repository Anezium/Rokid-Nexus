package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusSdkResult

internal fun assistantPlainCard(
    lines: List<String>,
    offerQuestionInput: Boolean,
    supportsEditableSurface: Boolean,
): NexusCard = NexusCard(
    title = "Assistant",
    lines = lines,
    handlesBack = true,
    // Blank metadata inherits on older hubs; a new content key clears the editor's instructions.
    contentKey = if (offerQuestionInput) "assistant-plain-ready" else "assistant-plain-busy",
    footer = when {
        !offerQuestionInput -> ""
        supportsEditableSurface -> "Tap to write a question · Back to close"
        else -> "Write in phone settings · Back to close"
    },
)

internal fun replaceAssistantSurface(
    showReplacement: () -> NexusSdkResult,
    retirePrevious: () -> Unit,
): NexusSdkResult {
    val result = showReplacement()
    // Retiring the last registered id first would self-close the plugin before its replacement.
    if (result == NexusSdkResult.SENT) retirePrevious()
    return result
}

/** Speech normalizes at capture; typed formatting must survive this shared pipeline boundary. */
internal fun dispatchAssistantQuestion(question: String, launch: (String) -> Unit): Boolean {
    if (question.isBlank()) return false
    launch(question)
    return true
}
