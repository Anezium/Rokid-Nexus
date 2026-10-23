package com.anezium.rokidbus.plugin.relay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.client.plugin.NexusSdkResult

/**
 * The glasses must forget what Relay showed them: a source app was turned Off, Relay was switched
 * off, or its notification access was revoked. One owner-scoped request covers it, so nothing has
 * to remember which events reached the glasses. It is owed on disk until the hub has taken it, and
 * the hub keeps it durably until the glasses have.
 */
internal object RelayHistoryForget {
    private const val PREFS = "relay_history"
    private const val KEY_OWED = "forget_owed"
    private const val HOLD_WINDOW_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())
    private var holdGeneration = 0

    fun request(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_OWED, true).apply()
        main.post { send(app, hold = true) }
    }

    /** A client registered or the link came back: what is still owed goes now. */
    fun flush(context: Context) {
        val app = context.applicationContext
        main.post { send(app, hold = false) }
    }

    private fun send(context: Context, hold: Boolean) {
        if (!prefs(context).getBoolean(KEY_OWED, false)) return RelayBus.releaseForget()
        val client = if (hold) holdForAWhile(context) else RelayBus.client
        if (client == null || !client.isApproved) return
        when (client.forgetNotices()) {
            // Without the surfaces grant the hub already forgets the owner on its own.
            NexusSdkResult.SENT, NexusSdkResult.CAPABILITY_NOT_GRANTED -> {
                prefs(context).edit().putBoolean(KEY_OWED, false).apply()
                RelayBus.releaseForget()
            }
            else -> Unit
        }
    }

    /** Binds the service for a bounded window; what is still owed after it waits for the next client. */
    private fun holdForAWhile(context: Context) = RelayBus.holdForForget(context).also {
        val generation = ++holdGeneration
        main.postDelayed({ if (holdGeneration == generation) RelayBus.releaseForget() }, HOLD_WINDOW_MS)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
