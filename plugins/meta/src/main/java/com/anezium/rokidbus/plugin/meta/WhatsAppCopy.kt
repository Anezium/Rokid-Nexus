package com.anezium.rokidbus.plugin.meta

/**
 * The words the WhatsApp page shows for each state the session reports. The session
 * speaks in short technical labels that its tests pin down; this is the one place they
 * are turned into consumer copy, so the page and the hub never word a state twice.
 */
internal object WhatsAppCopy {
    /** The status line under the header. */
    fun headline(view: WhatsAppSettingsView): String = when (view.text) {
        "Checking linked device…" -> "Checking your link…"
        "Preparing TEST pairing…" -> "Getting your code…"
        "Pairing TEST account…" -> if (view.code.isEmpty()) "Getting your code…" else "Waiting for the code in WhatsApp"
        "Finishing link…" -> "Finishing up…"
        "Unlinking…" -> "Unlinking…"
        "Unlinked" -> "Unlinked"
        "Pairing timed out" -> "Code expired"
        "Pairing unavailable", "Pairing unavailable; check linked devices" -> "Could not start linking"
        "Pairing failed · check WhatsApp" -> "Linking failed"
        "Passkey required · this build cannot complete pairing" -> "Passkey accounts not supported"
        "Link not authenticated · check WhatsApp" -> "Link not confirmed"
        "WhatsApp unavailable" -> "WhatsApp unavailable"
        "linked · authenticated" -> "Connected"
        "Stored device · not authenticated" -> "Linked, not connected"
        "pairing · not authenticated" -> "Linking in progress"
        "unlinked · not authenticated", "unlinked · offline" -> "Not linked"
        "Connection unavailable · check network, then Refresh status" -> "No connection"
        "Connection rejected · reopen settings or check linked devices" -> "Connection refused"
        "Account temporarily restricted · check WhatsApp" -> "Account temporarily restricted"
        "Session replaced · check linked devices" -> "Signed out by WhatsApp"
        "WhatsApp client outdated · update plugin" -> "Update needed"
        "Not authenticated · check linked devices" -> "Not signed in"
        else -> view.text
    }

    /** The explanation under the card title for a settled, linked-or-failed state. */
    fun explain(view: WhatsAppSettingsView): String = when (view.text) {
        "linked · authenticated" -> "This phone is one of your WhatsApp linked devices. Nexus can read and send messages for your glasses."
        "Stored device · not authenticated" -> "This phone is linked, but WhatsApp has not confirmed the session yet. Tap Refresh, or open WhatsApp › Linked devices."
        "Pairing timed out" -> "The code was not entered in time. Get a new one to try again."
        "Pairing failed · check WhatsApp" -> "WhatsApp did not accept the link. Check Linked devices in WhatsApp, then try again."
        "Passkey required · this build cannot complete pairing" -> "This account signs in with a passkey, which Nexus cannot complete yet."
        "Link not authenticated · check WhatsApp" -> "The code was accepted but WhatsApp did not confirm the link. Open WhatsApp › Linked devices to check."
        "WhatsApp unavailable" -> "Nexus could not reach its WhatsApp engine. Leave this page and come back."
        "Connection unavailable · check network, then Refresh status" -> "Nexus cannot reach WhatsApp right now. Check the network, then tap Refresh."
        "Connection rejected · reopen settings or check linked devices" -> "WhatsApp refused the connection. Leave this page and come back, or check Linked devices in WhatsApp."
        "Account temporarily restricted · check WhatsApp" -> "WhatsApp has temporarily restricted this account. Open WhatsApp for details."
        "Session replaced · check linked devices" -> "WhatsApp signed this device out, usually because it was removed from Linked devices. Link it again when you are ready."
        "WhatsApp client outdated · update plugin" -> "WhatsApp no longer accepts this version. Update Meta Messages from the Nexus store."
        "Not authenticated · check linked devices" -> "WhatsApp has not confirmed this device. Check Linked devices in WhatsApp."
        "Unlinked" -> "This phone is no longer a WhatsApp linked device."
        "Pairing unavailable", "Pairing unavailable; check linked devices" -> "Nexus could not start linking. Check the number and the network, then try again."
        else -> ""
    }

    /** What the hub row says once this page has learned something. */
    fun presence(view: WhatsAppSettingsView): String =
        if (view.text == "linked · authenticated") "Linked · connected" else "Linked · ${headline(view).lowercase()}"
}
