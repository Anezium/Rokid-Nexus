package com.anezium.rokidbus.plugin.relay

import android.app.Notification

internal data class RemoteInputShape(val allowsFreeForm: Boolean)

internal data class NotificationActionShape(
    val hasActionIntent: Boolean,
    val remoteInputs: List<RemoteInputShape>,
)

internal object NotificationAdmission {
    fun isRepliableAction(action: NotificationActionShape): Boolean =
        action.hasActionIntent && action.remoteInputs.any(RemoteInputShape::allowsFreeForm)

    fun firstRepliableActionIndex(actions: List<NotificationActionShape>): Int? =
        actions.indexOfFirst(::isRepliableAction).takeIf { it >= 0 }
}

internal fun Notification.findRepliableAction(): Notification.Action? =
    actions?.firstOrNull { action ->
        NotificationAdmission.isRepliableAction(
            NotificationActionShape(
                hasActionIntent = action.actionIntent != null,
                remoteInputs = action.remoteInputs.orEmpty().map { input ->
                    RemoteInputShape(input.allowFreeFormInput)
                },
            ),
        )
    }
