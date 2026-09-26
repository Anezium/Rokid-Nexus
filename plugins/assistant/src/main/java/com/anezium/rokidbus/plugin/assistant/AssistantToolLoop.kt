package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.shared.skills.SkillLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** One model pass as the runner sees it, whatever the provider's wire format. */
internal data class AssistantLoopPass(
    val text: String,
    val toolCalls: List<AssistantToolCall> = emptyList(),
    /** A text-bridge backend asked for a tool in a form Nexus could not read. */
    val malformedToolRequest: Boolean = false,
)

/**
 * What a provider adapter translates: tool declarations and messages in its own format. It owns
 * no authorization, retry, or loop policy; [AssistantToolLoop] owns those for every provider.
 */
internal interface AssistantLoopAdapter {
    /** Tool rounds this backend can complete; the runner uses the lower of this and its budget. */
    val maxToolRounds: Int

    /**
     * Streams one pass. [tools] is empty when no further tool call may be made: the adapter then
     * asks for the tool-free synthesis and must not execute or offer anything.
     */
    suspend fun pass(tools: List<AssistantToolDefinition>, round: Int): AssistantLoopPass

    /** Appends a pass's calls and their results to the transcript for the next pass. */
    fun appendToolResults(pass: AssistantLoopPass, results: List<Pair<AssistantToolCall, AssistantToolResult>>)

    /** Appends the note a malformed text-bridge request earns instead of results. */
    fun appendMalformedToolRequest() = Unit

    /** Retires text already streamed for a pass whose tool calls are about to run. */
    suspend fun resetVisibleText()
}

/**
 * The shared execute, result, and continue loop behind every Assistant provider.
 *
 * Budgets are the plan's provisional ones from [SkillLimits]: a bounded number of tool rounds
 * and executed calls, run one at a time, inside one deadline for the whole turn including model
 * latency. One [AssistantToolExecutionPhase] serves the entire turn, so repeated rounds never
 * recreate execution state or reset the built-in mutation guards (calendar deletion among them).
 * Repeated rounds of nothing but invalid calls, an exhausted budget, or cancellation end the
 * loop; the synthesis that follows offers no tools.
 */
internal class AssistantToolLoop(
    private val phase: AssistantToolExecutionPhase,
    private val maxRounds: Int = SkillLimits.ASSISTANT_MAX_TOOL_ROUNDS,
    private val turnDeadlineMs: Long = SkillLimits.ASSISTANT_TURN_DEADLINE_MS,
) {
    /** The final answer, or [TURN_TIMEOUT_MESSAGE] when the deadline ran out first. */
    suspend fun run(adapter: AssistantLoopAdapter): String {
        val timedOut = AtomicBoolean(false)
        return try {
            coroutineScope {
                val turn = this
                // Wall-clock, not the caller's dispatcher clock: the deadline is about the wearer
                // waiting, and the loop itself must stay in the caller's coroutine to stream.
                val watchdog = launch(Dispatchers.Default) {
                    delay(turnDeadlineMs)
                    timedOut.set(true)
                    turn.cancel()
                }
                try {
                    loop(adapter)
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (cancelled: CancellationException) {
            if (!timedOut.get() || !currentCoroutineContext().isActive) throw cancelled
            adapter.resetVisibleText()
            TURN_TIMEOUT_MESSAGE
        }
    }

    private suspend fun loop(adapter: AssistantLoopAdapter): String {
        val rounds = minOf(maxRounds, adapter.maxToolRounds)
        var round = 0
        var invalidRounds = 0
        while (true) {
            val offerTools = round < rounds && !phase.budgetExhausted && invalidRounds < MAX_INVALID_ROUNDS
            val tools = if (offerTools) phase.availableDefinitions else emptyList()
            val pass = adapter.pass(tools, round)
            currentCoroutineContext().ensureActive()
            if (!offerTools || tools.isEmpty()) return pass.text
            if (pass.malformedToolRequest) {
                adapter.resetVisibleText()
                adapter.appendMalformedToolRequest()
                round += 1
                continue
            }
            if (pass.toolCalls.isEmpty()) return pass.text
            adapter.resetVisibleText()
            val results = pass.toolCalls.map { call ->
                val result = phase.execute(call)
                currentCoroutineContext().ensureActive()
                call to result
            }
            adapter.appendToolResults(pass, results)
            invalidRounds = if (results.all { (_, result) -> result.isInvalidCall() }) invalidRounds + 1 else 0
            round += 1
        }
    }

    private fun AssistantToolResult.isInvalidCall(): Boolean =
        this is AssistantToolResult.Error && code == TOOL_ERROR_INVALID_CALL

    companion object {
        const val MAX_INVALID_ROUNDS = 2
        const val TURN_TIMEOUT_MESSAGE = "That took too long, so I stopped before finishing. Please ask again."
    }
}
