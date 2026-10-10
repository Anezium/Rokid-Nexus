package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.skills.SkillDispatch
import com.anezium.rokidbus.shared.skills.SkillError
import com.anezium.rokidbus.shared.skills.SkillProviderChoice
import com.anezium.rokidbus.shared.skills.SkillProviderInput
import com.anezium.rokidbus.shared.skills.SkillProviderResult
import com.anezium.rokidbus.shared.skills.SkillStatus
import org.json.JSONObject

/**
 * One skill invocation the hub delivered to this provider. Answer it exactly once, from any
 * thread, before [remainingMs] runs out; a second answer is refused. The hub has already
 * validated [arguments] against the operation's input schema and swapped every entity handle
 * for this provider's own identifier, which the provider must still validate before acting on.
 *
 * An invocation grants nothing else: it does not open the plugin, and while the hub holds the
 * provider only for skills it refuses surfaces, notices, pins, microphone, and camera traffic.
 */
class NexusSkillInvocation internal constructor(
    private val client: NexusPluginClient,
    val invocationId: String,
    val operationId: String,
    val contractVersion: Int,
    val arguments: JSONObject,
    private val deadlineAtMs: Long,
    private val clock: () -> Long,
) {
    /** Set when the hub cancels the call; stop remaining work where possible and answer anyway. */
    @Volatile
    var isCancelled: Boolean = false
        internal set

    /** Milliseconds left before the hub gives up on this call. Keep network timeouts inside it. */
    val remainingMs: Long
        get() = (deadlineAtMs - clock()).coerceAtLeast(0L)

    /** The read finished, or the requested postcondition was observed. An empty read is valid. */
    fun complete(data: JSONObject): NexusSdkResult =
        answer(SkillProviderResult(invocationId, SkillStatus.COMPLETED, data = data, observedAtMs = nowWall()))

    /** Dispatch is known to have happened but completion was not observed. */
    fun accepted(data: JSONObject? = null): NexusSdkResult =
        answer(SkillProviderResult(invocationId, SkillStatus.ACCEPTED, data = data, observedAtMs = nowWall()))

    /** Nothing happened; the caller must choose before invoking again. [reason] is a stable code. */
    fun needsInput(
        reason: String,
        prompt: String? = null,
        choices: List<SkillProviderChoice> = emptyList(),
    ): NexusSdkResult = answer(
        SkillProviderResult(
            invocationId,
            SkillStatus.NEEDS_INPUT,
            input = SkillProviderInput(reason, prompt, choices),
            observedAtMs = nowWall(),
        ),
    )

    /**
     * A known failure with a stable [code] (a [com.anezium.rokidbus.shared.skills.SkillErrorCodes]
     * value or a domain code) and what is known about dispatch. Never claim [SkillDispatch.NONE]
     * after an effect may have started.
     */
    fun fail(code: String, dispatch: SkillDispatch = SkillDispatch.NONE): NexusSdkResult = answer(
        SkillProviderResult(
            invocationId,
            SkillStatus.FAILED,
            error = SkillError(code, dispatch),
            observedAtMs = nowWall(),
        ),
    )

    /** Execution or completion cannot be established. */
    fun unknown(): NexusSdkResult =
        answer(SkillProviderResult(invocationId, SkillStatus.UNKNOWN, observedAtMs = nowWall()))

    private fun answer(result: SkillProviderResult): NexusSdkResult = client.sendSkillResult(this, result)

    private fun nowWall(): Long = System.currentTimeMillis()
}
