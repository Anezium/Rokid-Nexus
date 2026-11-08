package com.anezium.rokidbus.plugin.meta

internal fun whatsappConnectionStatus(
    link: String,
    connected: Boolean,
    loggedIn: Boolean,
    blocker: String,
): String = when {
    blocker == "connect_failed" -> "Connection unavailable · check network, then Refresh status"
    blocker == "connect_rejected" -> "Connection rejected · reopen settings or check linked devices"
    blocker == "temporary_ban" -> "Account temporarily restricted · check WhatsApp"
    blocker == "stream_replaced" -> "Session replaced · check linked devices"
    blocker == "client_outdated" -> "WhatsApp client outdated · update plugin"
    blocker.isNotEmpty() -> "Not authenticated · check linked devices"
    link == "linked" && connected && loggedIn -> "linked · authenticated"
    link == "linked" -> "Stored device · not authenticated"
    link == "pairing" -> "pairing · not authenticated"
    connected || loggedIn -> "unlinked · not authenticated"
    else -> "unlinked · offline"
}
