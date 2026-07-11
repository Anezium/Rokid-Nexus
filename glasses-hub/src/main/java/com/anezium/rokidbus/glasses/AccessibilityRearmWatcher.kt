package com.anezium.rokidbus.glasses

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import java.util.concurrent.atomic.AtomicBoolean

internal object AccessibilityRearmWatcher {
    private val registered = AtomicBoolean(false)

    fun start(context: Context, reason: String) {
        val appContext = context.applicationContext
        registerEventListeners(appContext)
        SelfArmController.ensureWatchdog(appContext, reason)
        repairIfNeeded(appContext, "$reason:initial_state")
    }

    private fun registerEventListeners(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        val manager = context.getSystemService(AccessibilityManager::class.java)
        manager.addAccessibilityStateChangeListener { enabled ->
            log("Accessibility state changed enabled=$enabled")
            repairIfNeeded(context, "accessibility_manager")
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    repairIfNeeded(context, "enabled_services_setting")
                }
            },
        )
        log("Accessibility re-arm event listeners registered")
    }

    private fun repairIfNeeded(context: Context, reason: String) {
        // Decide on the raw secure settings, not AccessibilityManager. Its cached enabled-service
        // list lags the setting write that triggers our ContentObserver, so gating on it here
        // silently dropped legitimate repairs. SelfArmController.repairNow re-reads the setting and
        // no-ops when nothing is wrong, so delegating unconditionally is both correct and cheap.
        SelfArmController.repairNow(context, reason)
    }
}
