package com.anezium.rokidbus.plugin.relay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.client.plugin.NexusSdkResult

/**
 * The glasses must forget what Relay showed them: a source app was turned Off, Relay was switched
 * off, or its notification access was revoked. One owner-scoped request covers it, so nothing has
 * to remember which events reached the glasses. It is owed on disk until the hub says it holds it
 * (a sent request may still die in the client's reconnect queue), and the hub keeps it durably
 * until the glasses have.
 */
internal object RelayHistoryForget {
    private const val PREFS = "relay_history"
    private const val KEY_OWED = "forget_owed"
    private const val KEY_GENERATION = "forget_generation"
    private const val KEY_ADMITTED = "admitted_settings"
    private const val ENABLED_ENTRY = "enabled"
    private const val HOLD_WINDOW_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())
    private var holdGeneration = 0

    /** The request generation last handed to which client: that client still carries it. */
    private var inFlight: Pair<Int, Any>? = null

    fun request(context: Context) {
        val app = context.applicationContext
        val prefs = prefs(app)
        prefs.edit()
            .putBoolean(KEY_OWED, true)
            .putInt(KEY_GENERATION, prefs.getInt(KEY_GENERATION, 0) + 1)
            .apply()
        main.post { send(app, hold = true) }
    }

    /** A client registered or the link came back: what is still owed goes now. */
    fun flush(context: Context) {
        val app = context.applicationContext
        main.post { send(app, hold = false) }
    }

    /**
     * Whether [settings] admit less than when last looked at: Relay switched off, or a source
     * moved to a stricter policy. Decided from the settings alone, so it holds with the listener
     * detached, the inbox empty, or the process restarted since the content reached the glasses.
     * The first look has nothing to compare with and says no.
     */
    fun narrowed(context: Context, settings: RelaySettings): Boolean {
        val prefs = prefs(context)
        val before = prefs.getStringSet(KEY_ADMITTED, null)?.toSet()
        val now = admitted(settings)
        prefs.edit().putStringSet(KEY_ADMITTED, now).apply()
        before ?: return false
        if (ENABLED_ENTRY in before && ENABLED_ENTRY !in now) return true
        val was = policies(before)
        val nowPolicies = policies(now)
        return (was.keys + nowPolicies.keys).any { app ->
            (nowPolicies[app] ?: RelayAppPolicy.MESSAGES) < (was[app] ?: RelayAppPolicy.MESSAGES)
        }
    }

    /** Where [narrowed] starts from: what the settings admit before the user can change them. */
    fun baseline(context: Context) {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_ADMITTED)) prefs.edit().putStringSet(KEY_ADMITTED, admitted(RelaySettings(context))).apply()
    }

    private fun admitted(settings: RelaySettings): Set<String> =
        settings.configuredPackages().mapTo(mutableSetOf()) { "${settings.appPolicy(it).name}:$it" }
            .apply { if (settings.enabled()) add(ENABLED_ENTRY) }

    private fun policies(entries: Set<String>): Map<String, RelayAppPolicy> = entries
        .filter { ':' in it }
        .associate { it.substringAfter(':') to RelayAppPolicy.fromStored(it.substringBefore(':')) }

    private fun send(context: Context, hold: Boolean) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_OWED, false)) return RelayBus.releaseForget()
        val client = if (hold) holdForAWhile(context) else RelayBus.client
        if (client == null || !client.isApproved) return
        val generation = prefs.getInt(KEY_GENERATION, 0)
        if (inFlight?.first == generation && inFlight?.second === client) return
        // Without the surfaces grant the hub already forgets the owner on its own.
        when (client.forgetNotices { main.post { accepted(context, generation) } }) {
            NexusSdkResult.SENT -> inFlight = generation to client
            NexusSdkResult.CAPABILITY_NOT_GRANTED -> accepted(context, generation)
            else -> Unit
        }
    }

    /** A request made after the one the hub took is still owed: it may follow newer content. */
    private fun accepted(context: Context, generation: Int) {
        val prefs = prefs(context)
        if (prefs.getInt(KEY_GENERATION, 0) != generation) return
        prefs.edit().putBoolean(KEY_OWED, false).apply()
        RelayBus.releaseForget()
    }

    /** Binds the service for a bounded window; what is still owed after it waits for the next client. */
    private fun holdForAWhile(context: Context) = RelayBus.holdForForget(context).also {
        val generation = ++holdGeneration
        main.postDelayed({ if (holdGeneration == generation) RelayBus.releaseForget() }, HOLD_WINDOW_MS)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
