package com.anezium.rokidbus.plugin.t3code

internal data class T3ModelOption(
    val id: String,
    val label: String,
    val isDefault: Boolean,
)

internal data class T3OptionDescriptor(
    val id: String,
    val label: String,
    val type: String,
    val options: List<T3ModelOption>,
    val currentValue: String?,
) {
    fun defaultOptionIndex(): Int {
        val current = currentValue?.let { value -> options.indexOfFirst { it.id == value } } ?: -1
        if (current >= 0) return current
        val marked = options.indexOfFirst(T3ModelOption::isDefault)
        return if (marked >= 0) marked else 0
    }
}

internal data class T3Model(
    val slug: String,
    val name: String,
    val optionDescriptors: List<T3OptionDescriptor>,
)

internal data class T3Provider(
    val instanceId: String,
    val driver: String,
    val displayName: String,
    val enabled: Boolean,
    val installed: Boolean,
    val models: List<T3Model>,
)

internal data class T3ServerConfig(
    val environmentLabel: String?,
    val providers: List<T3Provider>,
) {
    val availableProviders: List<T3Provider>
        get() = providers.filter { it.enabled && it.installed }
}

internal data class T3ModelOptionValue(val id: String, val value: String)

internal data class T3ModelSelection(
    val instanceId: String,
    val model: String,
    val options: List<T3ModelOptionValue> = emptyList(),
)

internal data class T3Project(
    val id: String,
    val title: String,
    val workspaceRoot: String,
    val defaultModelSelection: T3ModelSelection?,
)

internal data class T3Session(
    val status: String,
    val providerName: String?,
    val providerInstanceId: String?,
    val activeTurnId: String?,
    val lastError: String?,
    val updatedAt: String?,
)

internal data class T3BoardThread(
    val id: String,
    val projectId: String,
    val title: String,
    val modelSelection: T3ModelSelection,
    val updatedAt: String,
    val archivedAt: String?,
    val deletedAt: String?,
    val hasPendingApprovals: Boolean,
    val hasPendingUserInput: Boolean,
    val session: T3Session?,
)

internal data class T3Message(
    val id: String,
    val role: String,
    val text: String,
    val turnId: String?,
    val streaming: Boolean,
    val createdAt: String,
    val updatedAt: String?,
)

internal data class T3ThreadDetail(
    val id: String,
    val projectId: String,
    val title: String,
    val modelSelection: T3ModelSelection,
    val messages: List<T3Message>,
    val session: T3Session?,
)

internal sealed interface T3ShellEvent {
    data class Snapshot(
        val projects: List<T3Project>,
        val threads: List<T3BoardThread>,
    ) : T3ShellEvent

    data object Synchronized : T3ShellEvent
    data class ProjectUpserted(val project: T3Project) : T3ShellEvent
    data class ProjectRemoved(val projectId: String) : T3ShellEvent
    data class ThreadUpserted(val thread: T3BoardThread) : T3ShellEvent
    data class ThreadRemoved(val threadId: String) : T3ShellEvent
}

internal sealed interface T3ThreadStreamEvent {
    data class Snapshot(val thread: T3ThreadDetail) : T3ThreadStreamEvent
    data object Synchronized : T3ThreadStreamEvent
    data class Event(val type: String, val payload: org.json.JSONObject) : T3ThreadStreamEvent
}
