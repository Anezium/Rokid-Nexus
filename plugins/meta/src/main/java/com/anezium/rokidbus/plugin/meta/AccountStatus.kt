package com.anezium.rokidbus.plugin.meta

internal data class AccountStatus(
    val service: MetaAccount,
    val account: Boolean,
    val username: String,
    val login: String,
    val connected: Boolean,
    val link: String,
) {
    val loginBusy: Boolean get() = login == "running" || login == "approval_required" || login == "code_required"
    val canLogIn: Boolean get() = !account && !loginBusy
    val canSend: Boolean get() = account && connected
    val wantsConnection: Boolean get() = account && !connected && link == "disconnected" && !loginBusy
    /** True while something is changing on its own: the screen polls quickly only then. */
    val inFlight: Boolean get() = loginBusy || link == "connecting"

    enum class Tone { IDLE, BUSY, GOOD, BAD }

    val tone: Tone get() = when {
        loginBusy || link == "connecting" -> Tone.BUSY
        !account -> if (login.startsWith("failed")) Tone.BAD else Tone.IDLE
        connected -> Tone.GOOD
        link.startsWith("failed") -> Tone.BAD
        else -> Tone.IDLE
    }

    /** The one-line status under the header; [describe] carries the full explanation. */
    fun headline(): String = when {
        login == "running" -> "Logging in…"
        login == "approval_required" -> "Waiting for approval"
        login == "code_required" -> "Verification code needed"
        !account -> when {
            login == "cancelled" -> "Login cancelled"
            login.startsWith("failed") -> "Login failed"
            else -> "Not signed in"
        }
        connected -> "Connected"
        link == "connecting" -> "Connecting…"
        link == "failed_authentication" || link == "failed_identity" -> "Session refused"
        link.startsWith("failed") -> "Connection failed"
        else -> "Not connected"
    }

    fun describe(): String = when {
        login == "running" -> "Logging in…"
        login == "approval_required" ->
            "Approve this login in the ${service.title} app within 3 minutes, then come back here."
        login == "code_required" -> "Enter the verification code ${service.title} sent you."
        !account -> when (login) {
            "cancelled" -> "Login cancelled. Nothing was saved."
            "failed_account_mismatch" -> "${service.title} returned a different account. Nothing was saved."
            "failed_rate_limited" -> "${service.title} is limiting login attempts. Try again later."
            "failed_verification_in_instagram" -> "${service.title} wants to check something in its own app first. Open it, complete the check, then try again."
            "failed_timeout" -> "Login timed out. Nothing was saved."
            "failed_unsupported_step" -> "${service.title} asked for a sign-in step this app does not support yet. Nothing was saved."
            "failed_too_many_steps", "failed_storage", "failed" -> "Login failed. Nothing was saved."
            else -> "Not signed in."
        }
        connected -> "Connected as ${service.handle(username)}."
        link == "connecting" -> "Connecting as ${service.handle(username)}…"
        link == "failed_authentication" || link == "failed_identity" ->
            "${service.title} refused the saved session for ${service.handle(username)}. Forget it and log in again."
        link.startsWith("failed") -> "Could not connect as ${service.handle(username)}. Check the network, then tap Reconnect."
        else -> "Saved session for ${service.handle(username)}. Not connected."
    }
}
