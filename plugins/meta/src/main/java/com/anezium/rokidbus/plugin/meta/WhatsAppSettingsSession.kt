package com.anezium.rokidbus.plugin.meta

internal data class WhatsAppConnection(
    val link: String,
    val connected: Boolean,
    val loggedIn: Boolean,
    val blocker: String = "",
) {
    val authenticated get() = link == "linked" && connected && loggedIn && blocker.isEmpty()
    val label get() = whatsappConnectionStatus(link, connected, loggedIn, blocker)
}

internal data class WhatsAppPairing(val session: String, val state: String, val code: String = "")
internal data class WhatsAppSettingsView(
    val text: String,
    val code: String = "",
    val busy: Boolean = false,
    val canLink: Boolean = false,
    val keepScreenOn: Boolean = false,
)

internal interface WhatsAppSettingsBackend {
    fun acquire()
    fun release()
    fun query(timeoutMs: Long, done: (Result<WhatsAppConnection>) -> Unit)
    fun startLink(number: String, done: (Result<WhatsAppPairing>) -> Unit)
    fun link(session: String, done: (Result<WhatsAppPairing>) -> Unit)
    fun logout(done: (Result<Unit>) -> Unit)
}

internal fun interface SettingsScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

/**
 * Retained only in memory; configuration handoff is bounded. Ordinary backgrounding releases
 * ownership, except that a pending pairing may be held until it settles or its own deadline
 * passes, so the owner can enter the code in WhatsApp on this same phone.
 */
internal class WhatsAppSettingsSession(
    private val backend: WhatsAppSettingsBackend,
    private val scheduler: SettingsScheduler,
    private val now: () -> Long,
    private val holdInBackground: () -> Boolean = { false },
    private val endBackgroundHold: () -> Unit = {},
) {
    private enum class Phase { IDLE, CHECKING, STARTING, PAIRING, FINISHING }
    private var phase = Phase.IDLE
    private var visible = false
    private var held = false
    private val active get() = visible || held
    private val pending get() = phase == Phase.STARTING || phase == Phase.PAIRING || phase == Phase.FINISHING
    private var version = 0L
    private var listener: ((WhatsAppSettingsView) -> Unit)? = null
    private var cancelWork: (() -> Unit)? = null
    private var cancelHandoff: (() -> Unit)? = null
    private var inFlight = false
    private var pairing = ""
    private var until = 0L
    private var finishUntil = 0L
    private var connection = WhatsAppConnection("unknown", false, false)
    var view = WhatsAppSettingsView("Checking linked device…", busy = true)
        private set

    fun start(render: (WhatsAppSettingsView) -> Unit) {
        cancelHandoff?.invoke()
        cancelHandoff = null
        if (held) {
            held = false
            endBackgroundHold()
        }
        visible = true
        listener = render
        backend.acquire()
        emit()
        if (!inFlight) when (phase) {
            Phase.PAIRING -> schedule(0)
            Phase.FINISHING -> awaitLogin()
            Phase.STARTING -> Unit
            else -> query()
        }
    }

    fun stop(changingConfiguration: Boolean) {
        visible = false
        listener = null
        if (!changingConfiguration && pending && holdInBackground()) {
            held = true
            return
        }
        cancelWork?.invoke()
        cancelWork = null
        if (changingConfiguration) {
            cancelHandoff?.invoke()
            cancelHandoff = scheduler.schedule(5_000) { if (!visible) close() }
        } else close()
    }

    private fun close() {
        if (held) {
            held = false
            endBackgroundHold()
        }
        version++
        cancelHandoff?.invoke()
        cancelHandoff = null
        pairing = ""
        until = 0
        finishUntil = 0
        inFlight = false
        phase = Phase.IDLE
        view = WhatsAppSettingsView("Checking linked device…", busy = true)
        backend.release()
    }

    private fun emit() { if (visible) listener?.invoke(view) }
    private fun update(text: String, code: String = "") {
        view = WhatsAppSettingsView(text, code, phase != Phase.IDLE,
            phase == Phase.IDLE && connection.link == "unlinked",
            phase == Phase.STARTING || phase == Phase.PAIRING || phase == Phase.FINISHING)
        emit()
        if (held && !pending) close()
    }

    fun refresh() { if (phase == Phase.IDLE && visible) query() }

    private fun query() {
        phase = Phase.CHECKING
        inFlight = true
        val request = version
        update("Checking linked device…")
        backend.query(8_000) { result ->
            if (request != version) return@query
            inFlight = false
            phase = Phase.IDLE
            result.onSuccess { connection = it; update(it.label) }.onFailure {
                connection = WhatsAppConnection("unknown", false, false)
                update("WhatsApp unavailable")
            }
        }
    }

    fun startLink(number: String) {
        if (!visible || phase != Phase.IDLE || !view.canLink) return
        phase = Phase.STARTING
        inFlight = true
        until = now() + 180_000
        val request = version
        update("Preparing TEST pairing…")
        backend.startLink(number) { result ->
            if (request != version) return@startLink
            inFlight = false
            result.onSuccess {
                pairing = it.session
                phase = Phase.PAIRING
                update("Pairing TEST account…")
                if (active) schedule(0)
            }.onFailure { phase = Phase.IDLE; update("Pairing unavailable; check linked devices") }
        }
    }

    private fun schedule(delay: Long) {
        cancelWork?.invoke()
        cancelWork = scheduler.schedule(delay) {
            cancelWork = null
            if (active && !inFlight) {
                if (phase == Phase.PAIRING) pollPairing() else if (phase == Phase.FINISHING) awaitLogin()
            }
        }
    }

    private fun pollPairing() {
        if (now() >= until) { phase = Phase.IDLE; pairing = ""; update("Pairing timed out"); return }
        inFlight = true
        val request = version
        backend.link(pairing) { result ->
            if (request != version) return@link
            inFlight = false
            result.onSuccess {
                when (it.state) {
                    "pairing" -> { update("Pairing TEST account…", it.code); if (active) schedule(1_500) }
                    "success" -> {
                        pairing = ""
                        phase = Phase.FINISHING
                        finishUntil = minOf(until, now() + 30_000)
                        update("Finishing link…")
                        if (active) awaitLogin()
                    }
                    else -> {
                        pairing = ""
                        phase = Phase.IDLE
                        update(when (it.state) {
                            "passkey_required" -> "Passkey required · this build cannot complete pairing"
                            "timeout" -> "Pairing timed out"
                            else -> "Pairing failed · check WhatsApp"
                        })
                    }
                }
            }.onFailure { pairing = ""; phase = Phase.IDLE; update("Pairing unavailable") }
        }
    }

    private fun awaitLogin() {
        val remaining = finishUntil - now()
        if (remaining <= 0) { phase = Phase.IDLE; update("Link not authenticated · check WhatsApp"); return }
        inFlight = true
        val request = version
        backend.query(minOf(8_000, remaining)) { result ->
            if (request != version) return@query
            inFlight = false
            result.onSuccess {
                connection = it
                if (it.authenticated || it.blocker.isNotEmpty()) { phase = Phase.IDLE; update(it.label) }
                else if (now() >= finishUntil) { phase = Phase.IDLE; update("Link not authenticated · check WhatsApp") }
                else { update("Finishing link…"); if (active) schedule(100) }
            }.onFailure { phase = Phase.IDLE; update("WhatsApp unavailable") }
        }
    }

    fun unlink() {
        if (!visible || phase != Phase.IDLE) return
        inFlight = true
        phase = Phase.CHECKING
        val request = version
        update("Unlinking…")
        backend.logout { result ->
            if (request != version) return@logout
            inFlight = false
            phase = Phase.IDLE
            if (result.isSuccess) { connection = WhatsAppConnection("unlinked", false, false); update("Unlinked") }
            else update("Unlink refused; use WhatsApp linked devices")
        }
    }
}
