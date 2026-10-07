package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.shared.skills.SkillLimits
import com.anezium.rokidbus.shared.skills.SkillsContract
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal data class AssistantProviderFeatures(
    val supportsTools: Boolean,
    val supportsVision: Boolean,
    val supportsWorkspaceSearch: Boolean = true,
)

internal data class AssistantToolSessionContext(
    val active: Boolean,
    val grantedCapabilities: Set<String> = emptySet(),
)

internal data class AssistantToolAvailabilityContext(
    val provider: AssistantProviderFeatures,
    val session: AssistantToolSessionContext,
)

internal data class AssistantToolJsonSchema(
    val text: String,
) {
    init {
        require(runCatching { JSONObject(text) }.isSuccess) {
            "Assistant tool parameter schema must be a JSON object."
        }
    }

    fun toJsonObject(): JSONObject = JSONObject(text)
}

internal sealed interface AssistantToolValidation {
    data class Valid(val arguments: JSONObject) : AssistantToolValidation
    data class Invalid(
        val error: AssistantToolResult.Error = AssistantToolResult.Error(
            TOOL_ERROR_INVALID_CALL,
        ),
    ) : AssistantToolValidation
}

internal interface AssistantToolDefinition {
    val name: String
    val description: String
    val parametersSchema: AssistantToolJsonSchema
    val sideEffecting: Boolean
    val maxExecutionsPerTurn: Int
        get() = Int.MAX_VALUE
    val progressLabel: String?
    val retiresProgressOnSuccess: Boolean
        get() = false
    val executionFailureCode: String
        get() = "${name}_failed"

    /**
     * Whether a side-effecting tool may run only once per turn. Built-in tools keep that guard;
     * plugin operations carry their own invocation identity and the hub's duplicate policy.
     */
    val oncePerTurn: Boolean
        get() = sideEffecting

    /** Whether the declared schema is complete enough for a provider's strict schema mode. */
    val strictSchema: Boolean
        get() = true

    fun isAvailable(context: AssistantToolAvailabilityContext): Boolean

    fun bindToTurn(workspaceVersion: Pair<Long, Long>?): AssistantToolDefinition = this

    fun validate(argumentsJson: String): AssistantToolValidation

    suspend fun execute(
        call: AssistantToolCall,
        arguments: JSONObject,
    ): AssistantToolResult
}

internal class AssistantToolRegistry(
    definitions: List<AssistantToolDefinition>,
    private val sessionContext: () -> AssistantToolSessionContext = {
        AssistantToolSessionContext(active = true)
    },
    private val progressReporter: (String) -> Unit = {},
    /** Plugin operations for the current turn, named by hub aliases under the reserved prefix. */
    private val dynamicDefinitions: () -> List<AssistantToolDefinition> = { emptyList() },
) {
    private val definitionsByName = definitions.associateBy(AssistantToolDefinition::name)

    init {
        require(definitionsByName.size == definitions.size) {
            "Assistant tool names must be unique."
        }
        definitions.forEach { definition ->
            require(definition.maxExecutionsPerTurn > 0)
            require(TOOL_NAME.matches(definition.name)) {
                "Assistant tool names must be stable lowercase identifiers."
            }
            require(!definition.name.startsWith(SkillsContract.ALIAS_PREFIX)) {
                "The ${SkillsContract.ALIAS_PREFIX} prefix is reserved for plugin operations."
            }
            require(definition.description.isNotBlank()) {
                "Assistant tool descriptions must not be blank."
            }
            definition.progressLabel?.let { label ->
                require(label.isNotBlank()) {
                    "Assistant tool progress labels must not be blank."
                }
            }
            AssistantToolResult.Error(definition.executionFailureCode)
        }
    }

    fun availableDefinitions(features: AssistantProviderFeatures,
        workspaceVersion: Pair<Long, Long>? = null): List<AssistantToolDefinition> {
        if (!features.supportsTools) return emptyList()
        val context = AssistantToolAvailabilityContext(features, sessionContext())
        val plugin = runCatching(dynamicDefinitions).getOrDefault(emptyList())
            .filter { it.name.startsWith(SkillsContract.ALIAS_PREFIX) && TOOL_NAME.matches(it.name) }
            .distinctBy(AssistantToolDefinition::name)
        return (definitionsByName.values + plugin).map { it.bindToTurn(workspaceVersion) }.filter { definition ->
            runCatching { definition.isAvailable(context) }.getOrDefault(false)
        }
    }

    fun newExecutionPhase(features: AssistantProviderFeatures,
        workspaceVersion: Pair<Long, Long>? = null): AssistantToolExecutionPhase =
        AssistantToolExecutionPhase(availableDefinitions(features, workspaceVersion), progressReporter)

    companion object {
        private val TOOL_NAME = Regex("[a-z][a-z0-9_]{0,63}")
        const val THINKING_LABEL = "Thinking…"
    }
}

internal class AssistantToolExecutionPhase(
    val availableDefinitions: List<AssistantToolDefinition>,
    private val progressReporter: (String) -> Unit,
) {
    private val definitionsByName = availableDefinitions.associateBy(AssistantToolDefinition::name)
    private val resultsByCallId = mutableMapOf<String, AssistantToolResult>()
    private val executedSideEffectingTools = mutableSetOf<String>()
    private val executionsByName = mutableMapOf<String, Int>()
    private var executedCalls = 0

    /** No further call can execute this turn; the runner stops offering tools. */
    val budgetExhausted: Boolean
        get() = executedCalls >= MAX_EXECUTED_CALLS

    suspend fun execute(call: AssistantToolCall): AssistantToolResult {
        var restoreProgress = true
        try {
            val result = executeCall(call)
            restoreProgress = result is AssistantToolResult.Error ||
                definitionsByName[call.name]?.retiresProgressOnSuccess != true
            return result
        } finally {
            if (restoreProgress) reportProgress(AssistantToolRegistry.THINKING_LABEL)
        }
    }

    private suspend fun executeCall(call: AssistantToolCall): AssistantToolResult {
        resultsByCallId[call.callId]?.let { result -> return result }

        val definition = definitionsByName[call.name]
            ?: return memoize(call, AssistantToolResult.Error(TOOL_ERROR_INVALID_CALL))
        val validation = try {
            definition.validate(call.argumentsJson)
        } catch (_: Throwable) {
            AssistantToolValidation.Invalid()
        }
        if (validation is AssistantToolValidation.Invalid) {
            return memoize(call, validation.error)
        }
        validation as AssistantToolValidation.Valid

        val guarded = definition.sideEffecting && definition.oncePerTurn
        if (
            executedCalls >= MAX_EXECUTED_CALLS ||
            (executionsByName[definition.name] ?: 0) >= definition.maxExecutionsPerTurn ||
            guarded && definition.name in executedSideEffectingTools
        ) {
            return memoize(call, AssistantToolResult.Error(TOOL_ERROR_ALREADY_USED))
        }

        executedCalls += 1
        executionsByName[definition.name] = (executionsByName[definition.name] ?: 0) + 1
        if (guarded) executedSideEffectingTools += definition.name
        val result = try {
            definition.progressLabel?.let(::reportProgress)
            definition.execute(call, validation.arguments)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AssistantToolResult.Error(definition.executionFailureCode)
        }
        return memoize(call, result)
    }

    private fun memoize(
        call: AssistantToolCall,
        result: AssistantToolResult,
    ): AssistantToolResult {
        resultsByCallId[call.callId] = result
        return result
    }

    private fun reportProgress(label: String) {
        runCatching { progressReporter(label) }
    }

    private companion object {
        const val MAX_EXECUTED_CALLS = SkillLimits.ASSISTANT_MAX_EXECUTED_CALLS
    }
}
