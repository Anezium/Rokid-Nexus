package com.anezium.rokidbus.plugin.t3code

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

internal data class T3StartCommand(val threadId: String, val payload: JSONObject)

internal object T3CommandFactory {
    fun threadTurnStart(
        projectId: String,
        modelSelection: T3ModelSelection,
        prompt: String,
        now: Instant = Instant.now(),
        nextId: () -> String = { UUID.randomUUID().toString() },
    ): T3StartCommand {
        val normalizedPrompt = collapseWhitespace(prompt)
        require(normalizedPrompt.isNotEmpty())
        val commandId = nextId()
        val threadId = nextId()
        val messageId = nextId()
        val createdAt = now.toString()
        val selection = modelSelectionJson(modelSelection)
        val createThread = JSONObject()
            .put("projectId", projectId)
            .put("title", normalizedPrompt.take(MAX_TITLE_CHARS))
            .put("modelSelection", JSONObject(selection.toString()))
            .put("runtimeMode", "full-access")
            .put("interactionMode", "default")
            .put("branch", JSONObject.NULL)
            .put("worktreePath", JSONObject.NULL)
            .put("createdAt", createdAt)
        val command = JSONObject()
            .put("type", "thread.turn.start")
            .put("commandId", commandId)
            .put("threadId", threadId)
            .put(
                "message",
                JSONObject()
                    .put("messageId", messageId)
                    .put("role", "user")
                    .put("text", normalizedPrompt)
                    .put("attachments", JSONArray()),
            )
            .put("modelSelection", selection)
            .put("runtimeMode", "full-access")
            .put("interactionMode", "default")
            .put("bootstrap", JSONObject().put("createThread", createThread))
            .put("createdAt", createdAt)
        return T3StartCommand(threadId, command)
    }

    fun modelSelectionJson(selection: T3ModelSelection): JSONObject = JSONObject()
        .put("instanceId", selection.instanceId)
        .put("model", selection.model)
        .put(
            "options",
            JSONArray().apply {
                selection.options.forEach { option ->
                    put(JSONObject().put("id", option.id).put("value", option.value))
                }
            },
        )

    const val MAX_TITLE_CHARS = 60
}
