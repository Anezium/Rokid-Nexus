package com.anezium.rokidbus.glasses

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.anezium.rokidbus.shared.GlassesKeyboardContract
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the glasses on Nexus's keyboard while the owner's keep switch is on: at hub start, which
 * covers boot, and whenever the selected keyboard changes. It only ever takes the keyboard back
 * from Rokid's own, which the companion app selects without asking; a keyboard the owner installed
 * and chose is theirs, and is left alone even with the switch on.
 */
internal object GlassesKeyboardKeeper {
    private const val PREFS_NAME = "glasses_keyboard"
    private const val KEY_KEEP_NEXUS = "keep_nexus"
    private const val RECLAIM_LIMIT = 3
    private const val RECLAIM_WINDOW_MS = 10 * 60_000L

    private val registered = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    // Automatic takebacks only: if something keeps selecting Rokid's keyboard, fighting it forever
    // would flap the glasses' input; the phone's button and switch stay available past the limit.
    private val budget = GlassesKeyboardReclaimBudget(RECLAIM_LIMIT, RECLAIM_WINDOW_MS)

    fun start(context: Context) {
        val appContext = context.applicationContext
        post {
            RemoteInputImeProvisioner.ensureConfigured(appContext)
            reclaim(appContext, "hub_start", budgeted = true)
        }
        if (!registered.compareAndSet(false, true)) return
        appContext.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
            false,
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    reclaim(appContext, "selection_changed", budgeted = true)
                }
            },
        )
    }

    /**
     * Every keyboard write goes through the main looper, where the observer already runs: the
     * enabled list is a read-modify-write, and two of them racing would drop an entry.
     */
    fun post(block: () -> Unit) {
        handler.post(block)
    }

    fun isKeepEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_NEXUS, GlassesKeyboardContract.DEFAULT_KEEP_NEXUS)

    fun setKeepEnabled(context: Context, keep: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_NEXUS, keep).apply()
        log("keyboard keep switch=$keep")
        if (keep) reclaim(context.applicationContext, "switch_on", budgeted = false)
    }

    private fun reclaim(context: Context, trigger: String, budgeted: Boolean) {
        val selected = RemoteInputImeProvisioner.selectedMethod(context)
        val nexus = RemoteInputImeProvisioner.nexusComponent(context)
        if (!GlassesKeyboardKeepPolicy.shouldReclaim(isKeepEnabled(context), selected, nexus)) return
        val now = SystemClock.elapsedRealtime()
        if (budgeted && !budget.hasRoom(now)) {
            log("keyboard keep skipped trigger=$trigger reason=limit_reached")
            return
        }
        val ok = RemoteInputImeProvisioner.selectNexus(context)
        // Only a takeback that happened counts: failed writes (no permission yet) change nothing,
        // so they cannot flap anything, and spending the budget on them would leave none for
        // when setup grants the permission.
        if (ok && budgeted) budget.record(now)
        log(
            "keyboard keep reclaimed trigger=$trigger " +
                "from=${RemoteInputImeProvisioner.methodPackage(selected) ?: "none"} ok=$ok",
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

internal object GlassesKeyboardKeepPolicy {
    /** Rokid's own keyboards; the companion app selects them on its own. */
    private const val ROKID_PACKAGE_PREFIX = "com.rokid."

    fun shouldReclaim(keep: Boolean, selected: String?, nexusComponent: String): Boolean {
        if (!keep) return false
        if (selected.isNullOrBlank()) return true
        if (RemoteInputImeProvisioner.isNexus(selected, nexusComponent)) return false
        return RemoteInputImeProvisioner.methodPackage(selected)?.startsWith(ROKID_PACKAGE_PREFIX) == true
    }
}

internal class GlassesKeyboardReclaimBudget(private val limit: Int, private val windowMs: Long) {
    private val spent = ArrayDeque<Long>()

    fun hasRoom(nowMs: Long): Boolean {
        while (spent.isNotEmpty() && nowMs - spent.first() >= windowMs) spent.removeFirst()
        return spent.size < limit
    }

    fun record(nowMs: Long) {
        spent.addLast(nowMs)
    }
}
