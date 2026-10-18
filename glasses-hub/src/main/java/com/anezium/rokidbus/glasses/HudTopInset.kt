package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt

internal object HudTopInset {
    private const val PREFS = "hud_position"
    private const val KEY_AUTO = "auto"
    private const val KEY_MANUAL_INSET_DP = "top_inset_dp"
    private const val KEY_AUTO_INSET_DP = "auto_inset_dp"

    /** Home-row centre with the ROM screen-position setting fully UP, calibrated 2026-08-06. */
    private const val TOP_MODE_ROW_CENTER_PX = 364f
    private const val AUTO_HYSTERESIS_DP = 2

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(Int) -> Unit>()
    @Volatile private var initialized = false
    @Volatile private var state = State()

    fun current(context: Context): Int = synchronized(lock) {
        ensureLoaded(context.applicationContext)
        state.effectiveDp
    }

    /** The ROM draws its home status row above accessibility windows; keep ambient maps clear. */
    fun ambientHeightPx(context: Context, top: Boolean, edgePx: Int): Int = synchronized(lock) {
        ensureLoaded(context.applicationContext)
        val metrics = context.resources.displayMetrics
        val row = TOP_MODE_ROW_CENTER_PX + state.autoDp * metrics.density
        val available = if (top) row - 24f - edgePx else metrics.heightPixels - edgePx - row - 24f
        available.toInt().coerceAtLeast(0)
    }

    fun set(context: Context, manualDp: Int, auto: Boolean) {
        val appContext = context.applicationContext
        val cleanManualDp = sanitize(manualDp)
        val nextEffectiveDp = synchronized(lock) {
            ensureLoaded(appContext)
            val previousEffectiveDp = state.effectiveDp
            state = state.copy(auto = auto, manualDp = cleanManualDp)
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO, auto)
                .putInt(KEY_MANUAL_INSET_DP, cleanManualDp)
                .apply()
            state.effectiveDp.takeIf { it != previousEffectiveDp }
        }
        nextEffectiveDp?.let(::notifyListeners)
    }

    fun onRomRowMeasured(context: Context, centerYpx: Int, density: Float) {
        if (density <= 0f || density.isNaN() || density.isInfinite()) return
        val measuredDp = sanitize(
            ((centerYpx - TOP_MODE_ROW_CENTER_PX) / density).roundToInt(),
        )
        val appContext = context.applicationContext
        val nextEffectiveDp = synchronized(lock) {
            ensureLoaded(appContext)
            if (abs(measuredDp - state.autoDp) < AUTO_HYSTERESIS_DP) {
                return@synchronized null
            }
            val previousEffectiveDp = state.effectiveDp
            state = state.copy(autoDp = measuredDp)
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_AUTO_INSET_DP, measuredDp)
                .apply()
            state.effectiveDp.takeIf { it != previousEffectiveDp }
        }
        nextEffectiveDp?.let(::notifyListeners)
    }

    fun restore(context: Context): Int {
        val next = readPersisted(context.applicationContext)
        val nextEffectiveDp = synchronized(lock) {
            val wasInitialized = initialized
            val previousEffectiveDp = state.effectiveDp
            state = next
            initialized = true
            next.effectiveDp.takeIf { wasInitialized && it != previousEffectiveDp }
        }
        nextEffectiveDp?.let(::notifyListeners)
        return next.effectiveDp
    }

    fun observe(context: Context, listener: (Int) -> Unit): () -> Unit {
        val initial = synchronized(lock) {
            ensureLoaded(context.applicationContext)
            listeners.add(listener)
            state.effectiveDp
        }
        dispatch { if (listener in listeners) listener(initial) }
        return { listeners.remove(listener) }
    }

    fun sanitize(value: Int): Int = PhoneHubCapabilitiesContract.sanitizeHudTopInsetDp(value)

    private fun ensureLoaded(context: Context) {
        if (initialized) return
        state = readPersisted(context)
        initialized = true
    }

    private fun readPersisted(context: Context): State {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedAuto = runCatching {
            preferences.getBoolean(
                KEY_AUTO,
                PhoneHubCapabilitiesContract.DEFAULT_HUD_POSITION_AUTO,
            )
        }.getOrNull()
        val storedManualDp = runCatching {
            preferences.getInt(
                KEY_MANUAL_INSET_DP,
                PhoneHubCapabilitiesContract.DEFAULT_HUD_TOP_INSET_DP,
            )
        }.getOrNull()
        val storedAutoDp = runCatching {
            preferences.getInt(
                KEY_AUTO_INSET_DP,
                PhoneHubCapabilitiesContract.DEFAULT_HUD_TOP_INSET_DP,
            )
        }.getOrNull()
        val next = State(
            auto = storedAuto ?: PhoneHubCapabilitiesContract.DEFAULT_HUD_POSITION_AUTO,
            manualDp = sanitize(
                storedManualDp ?: PhoneHubCapabilitiesContract.DEFAULT_HUD_TOP_INSET_DP,
            ),
            autoDp = sanitize(
                storedAutoDp ?: PhoneHubCapabilitiesContract.DEFAULT_HUD_TOP_INSET_DP,
            ),
        )
        if (
            storedAuto == null ||
            storedManualDp == null || storedManualDp != next.manualDp ||
            storedAutoDp == null || storedAutoDp != next.autoDp
        ) {
            preferences.edit()
                .putBoolean(KEY_AUTO, next.auto)
                .putInt(KEY_MANUAL_INSET_DP, next.manualDp)
                .putInt(KEY_AUTO_INSET_DP, next.autoDp)
                .apply()
        }
        return next
    }

    private fun notifyListeners(value: Int) {
        dispatch {
            listeners.forEach { listener ->
                runCatching { listener(value) }
            }
        }
    }

    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            main.post(action)
        }
    }

    private data class State(
        val auto: Boolean = PhoneHubCapabilitiesContract.DEFAULT_HUD_POSITION_AUTO,
        val manualDp: Int = PhoneHubCapabilitiesContract.DEFAULT_HUD_TOP_INSET_DP,
        val autoDp: Int = PhoneHubCapabilitiesContract.DEFAULT_HUD_TOP_INSET_DP,
    ) {
        val effectiveDp: Int
            get() = if (auto) autoDp else manualDp
    }
}
