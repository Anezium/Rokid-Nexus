package com.anezium.rokidbus.plugin.t3code

import org.json.JSONArray
import org.json.JSONObject

internal object T3Parsers {
    fun config(value: JSONObject): T3ServerConfig {
        val providers = value.optJSONArray("providers").objects().mapNotNull(::provider)
        return T3ServerConfig(
            environmentLabel = value.optJSONObject("environment")?.stringOrNull("label"),
            providers = providers,
        )
    }

    fun shellItem(value: JSONObject): T3ShellEvent? = when (value.optString("kind")) {
        "snapshot" -> {
            val snapshot = value.optJSONObject("snapshot") ?: return null
            T3ShellEvent.Snapshot(
                projects = snapshot.optJSONArray("projects").objects().mapNotNull(::project),
                threads = snapshot.optJSONArray("threads").objects().mapNotNull(::boardThread),
            )
        }
        "synchronized" -> T3ShellEvent.Synchronized
        "project-upserted" -> value.optJSONObject("project")?.let(::project)
            ?.let(T3ShellEvent::ProjectUpserted)
        "project-removed" -> value.stringOrNull("projectId")?.let(T3ShellEvent::ProjectRemoved)
        "thread-upserted" -> value.optJSONObject("thread")?.let(::boardThread)
            ?.let(T3ShellEvent::ThreadUpserted)
        "thread-removed" -> value.stringOrNull("threadId")?.let(T3ShellEvent::ThreadRemoved)
        else -> null
    }

    fun threadItem(value: JSONObject): T3ThreadStreamEvent? = when (value.optString("kind")) {
        "snapshot" -> value.optJSONObject("snapshot")
            ?.optJSONObject("thread")
            ?.let(::threadDetail)
            ?.let(T3ThreadStreamEvent::Snapshot)
        "synchronized" -> T3ThreadStreamEvent.Synchronized
        "event" -> {
            val event = value.optJSONObject("event") ?: return null
            val type = event.stringOrNull("type") ?: return null
            val payload = event.optJSONObject("payload") ?: JSONObject()
            T3ThreadStreamEvent.Event(type, payload)
        }
        else -> null
    }

    fun applyThreadEvent(
        detail: T3ThreadDetail,
        event: T3ThreadStreamEvent.Event,
    ): T3ThreadDetail? = when (event.type) {
        "thread.message-sent" -> {
            val message = message(event.payload) ?: return null
            val messages = detail.messages.toMutableList()
            val index = messages.indexOfFirst { it.id == message.id }
            if (index >= 0) messages[index] = message else messages += message
            detail.copy(messages = messages.sortedBy { it.createdAt })
        }
        "thread.session-set" -> {
            val session = event.payload.optJSONObject("session")?.let(::session) ?: return null
            detail.copy(session = session)
        }
        "thread.meta-updated" -> {
            val title = event.payload.stringOrNull("title") ?: return detail
            detail.copy(title = title)
        }
        else -> null
    }

    private fun provider(value: JSONObject): T3Provider? {
        val instanceId = value.stringOrNull("instanceId") ?: return null
        val models = value.optJSONArray("models").objects().mapNotNull(::model)
        return T3Provider(
            instanceId = instanceId,
            driver = value.stringOrNull("driver").orEmpty(),
            displayName = value.stringOrNull("displayName") ?: instanceId,
            enabled = value.optBoolean("enabled", false),
            installed = value.optBoolean("installed", false),
            models = models,
        )
    }

    private fun model(value: JSONObject): T3Model? {
        val slug = value.stringOrNull("slug") ?: return null
        val descriptors = value.optJSONObject("capabilities")
            ?.optJSONArray("optionDescriptors")
            .objects()
            .mapNotNull(::descriptor)
        return T3Model(
            slug = slug,
            name = value.stringOrNull("name") ?: slug,
            optionDescriptors = descriptors,
        )
    }

    private fun descriptor(value: JSONObject): T3OptionDescriptor? {
        val id = value.stringOrNull("id") ?: return null
        val type = value.stringOrNull("type") ?: return null
        return T3OptionDescriptor(
            id = id,
            label = value.stringOrNull("label") ?: id,
            type = type,
            options = value.optJSONArray("options").objects().mapNotNull { option ->
                val optionId = option.stringOrNull("id") ?: return@mapNotNull null
                T3ModelOption(
                    id = optionId,
                    label = option.stringOrNull("label") ?: optionId,
                    isDefault = option.optBoolean("isDefault", false),
                )
            },
            currentValue = value.stringOrNull("currentValue"),
        )
    }

    private fun project(value: JSONObject): T3Project? {
        val id = value.stringOrNull("id") ?: return null
        return T3Project(
            id = id,
            title = value.stringOrNull("title") ?: id,
            workspaceRoot = value.stringOrNull("workspaceRoot").orEmpty(),
            defaultModelSelection = value.optJSONObject("defaultModelSelection")?.let(::modelSelection),
        )
    }

    private fun boardThread(value: JSONObject): T3BoardThread? {
        val id = value.stringOrNull("id") ?: return null
        val selection = value.optJSONObject("modelSelection")?.let(::modelSelection) ?: return null
        return T3BoardThread(
            id = id,
            projectId = value.stringOrNull("projectId").orEmpty(),
            title = value.stringOrNull("title") ?: "Untitled thread",
            modelSelection = selection,
            updatedAt = value.stringOrNull("updatedAt").orEmpty(),
            archivedAt = value.stringOrNull("archivedAt"),
            deletedAt = value.stringOrNull("deletedAt"),
            hasPendingApprovals = value.optBoolean("hasPendingApprovals", false),
            hasPendingUserInput = value.optBoolean("hasPendingUserInput", false),
            session = value.optJSONObject("session")?.let(::session),
        )
    }

    private fun threadDetail(value: JSONObject): T3ThreadDetail? {
        val id = value.stringOrNull("id") ?: return null
        val selection = value.optJSONObject("modelSelection")?.let(::modelSelection) ?: return null
        return T3ThreadDetail(
            id = id,
            projectId = value.stringOrNull("projectId").orEmpty(),
            title = value.stringOrNull("title") ?: "Untitled thread",
            modelSelection = selection,
            messages = value.optJSONArray("messages").objects().mapNotNull(::message),
            session = value.optJSONObject("session")?.let(::session),
        )
    }

    private fun modelSelection(value: JSONObject): T3ModelSelection? {
        val instanceId = value.stringOrNull("instanceId") ?: value.stringOrNull("provider") ?: return null
        val model = value.stringOrNull("model") ?: return null
        return T3ModelSelection(
            instanceId = instanceId,
            model = model,
            options = value.optJSONArray("options").objects().mapNotNull { option ->
                val id = option.stringOrNull("id") ?: return@mapNotNull null
                val optionValue = option.stringOrNull("value") ?: return@mapNotNull null
                T3ModelOptionValue(id, optionValue)
            },
        )
    }

    private fun session(value: JSONObject): T3Session = T3Session(
        status = value.stringOrNull("status") ?: "idle",
        providerName = value.stringOrNull("providerName"),
        providerInstanceId = value.stringOrNull("providerInstanceId"),
        activeTurnId = value.stringOrNull("activeTurnId"),
        lastError = value.stringOrNull("lastError"),
        updatedAt = value.stringOrNull("updatedAt"),
    )

    private fun message(value: JSONObject): T3Message? {
        val id = value.stringOrNull("id") ?: value.stringOrNull("messageId") ?: return null
        val role = value.stringOrNull("role") ?: return null
        return T3Message(
            id = id,
            role = role,
            text = value.stringOrNull("text").orEmpty(),
            turnId = value.stringOrNull("turnId"),
            streaming = value.optBoolean("streaming", false),
            createdAt = value.stringOrNull("createdAt").orEmpty(),
            updatedAt = value.stringOrNull("updatedAt"),
        )
    }
}

internal fun JSONObject.stringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf(String::isNotEmpty)
}

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) optJSONObject(index)?.let(::add)
    }
}
