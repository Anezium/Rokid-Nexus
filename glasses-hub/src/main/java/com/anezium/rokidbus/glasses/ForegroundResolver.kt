package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import com.anezium.rokidbus.shared.ForegroundAmbientMode
import java.util.concurrent.CopyOnWriteArrayList

/** What is in front of the wearer under Nexus's own windows. */
internal sealed interface Foreground {
    /** The glasses' home (the Rokid launcher). */
    data object Home : Foreground

    /** A native app, by package. */
    data class App(val packageName: String) : Foreground

    /** Nothing seen yet: treated like home, today's behaviour. */
    data object Unknown : Foreground
}

/**
 * Tells home from native apps from accessibility window events (plan 028a §8.2). Pure: the home
 * packages and the input methods are asked of the caller.
 *
 * Nexus's own windows (the session, launcher, islands, surfaces) never count: they float over
 * whatever is in front and must not hide it. Input methods and the system UI are excluded the
 * same way, as they come and go over an app without leaving it.
 */
internal class ForegroundResolver(
    private val ownPackage: String,
    private val isHome: (String) -> Boolean,
    private val isInputMethod: (String) -> Boolean,
) {
    var current: Foreground = Foreground.Unknown
        private set

    /** A window-state change from [packageName]; true when what is in front changed. */
    fun onWindowState(packageName: String?): Boolean {
        val name = packageName?.trim().orEmpty()
        if (name.isEmpty() || name == ownPackage || name in IGNORED || isInputMethod(name)) return false
        val next = if (isHome(name)) Foreground.Home else Foreground.App(name)
        if (next == current) return false
        current = next
        return true
    }

    companion object {
        private val IGNORED = setOf("com.android.systemui", "com.rokid.sysconfig", "android")
    }
}

/**
 * The ambient context the activity tier presents in: the [ForegroundResolver]'s answer and the
 * phone's per-app policy. Main thread only.
 *
 * Z-order: the activity window keeps its place in [HudOverlayStack] (pin, activity, notice,
 * pointer). A native app's windows are application windows, always under every accessibility
 * overlay, so islands never need re-stacking when an app comes forward; what changes over an
 * app is only how much they show (capped at text) and whether they show at all. The overlay is
 * FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE, so an island never takes a key or a touch from the app.
 */
internal object AmbientForeground {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var resolver: ForegroundResolver? = null
    private var homePackages = emptySet<String>()
    private var inputMethods = emptySet<String>()

    val current: Foreground get() = resolver?.current ?: Foreground.Unknown

    /** Home, or nothing known yet: the residents' own context. */
    val homeInFront: Boolean get() = current !is Foreground.App

    fun onServiceConnected(context: Context) {
        homePackages = resolveHomePackages(context)
        inputMethods = resolveInputMethods(context)
        resolver = ForegroundResolver(context.packageName, { it in homePackages }, { it in inputMethods })
        log("foreground resolver ready homes=${homePackages.size} inputMethods=${inputMethods.size}")
    }

    fun onServiceDestroyed() {
        resolver = null
    }

    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    /** A TYPE_WINDOW_STATE_CHANGED event's package, from the accessibility service. */
    fun onWindowState(packageName: CharSequence?) {
        val changed = resolver?.onWindowState(packageName?.toString()) ?: return
        if (!changed) return
        log("foreground ${describe(current)}")
        notifyChanged()
    }

    /** The policy's mode for what is in front: residents at home, the app's mode elsewhere. */
    fun mode(): ForegroundAmbientMode = when (val front = current) {
        is Foreground.App -> AmbientPreferences.modeFor(front.packageName, isHome = false)
        else -> AmbientPreferences.modeFor(null, isHome = true)
    }

    fun notifyChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(::notifyChanged)
            return
        }
        listeners.forEach { listener -> runCatching { listener() } }
    }

    private fun describe(front: Foreground): String = when (front) {
        Foreground.Home -> "home"
        Foreground.Unknown -> "unknown"
        is Foreground.App -> "app"
    }

    /**
     * The default home, or every home-capable package when none is chosen. Settings' fallback
     * home is never home: the wearer in Settings is in an app.
     */
    private fun resolveHomePackages(context: Context): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pm = context.packageManager
        val chosen = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (chosen != null && chosen != "android" && chosen != AccessibilityWindowRoots.SETTINGS_PACKAGE) {
            setOf(chosen)
        } else {
            pm.queryIntentActivities(intent, 0).mapNotNull { it.activityInfo?.packageName }
                .filterNot { it == AccessibilityWindowRoots.SETTINGS_PACKAGE || it == "android" }
                .toSet()
        }
    }.getOrElse { emptySet() }

    private fun resolveInputMethods(context: Context): Set<String> = runCatching {
        context.getSystemService(InputMethodManager::class.java)?.inputMethodList?.map { it.packageName }?.toSet().orEmpty()
    }.getOrElse { emptySet() }
}
