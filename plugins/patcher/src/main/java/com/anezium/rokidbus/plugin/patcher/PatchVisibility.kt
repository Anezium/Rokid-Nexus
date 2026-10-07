package com.anezium.rokidbus.plugin.patcher

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.Collections
import java.util.IdentityHashMap

internal object PatchVisibility : Application.ActivityLifecycleCallbacks {
    private val started = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private val resumed = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private var installed: Application? = null
    val hasResumedActivity: Boolean @Synchronized get() = resumed.isNotEmpty()
    val mode: String @Synchronized get() = when {
        started.any { it.isInPictureInPictureMode } -> "pip"
        resumed.isNotEmpty() -> "fullscreen"
        else -> "hidden"
    }

    @Synchronized
    fun install(application: Application) {
        if (installed !== application) {
            installed?.unregisterActivityLifecycleCallbacks(this)
            started.clear(); resumed.clear()
            application.registerActivityLifecycleCallbacks(this)
            installed = application
        }
    }
    @Synchronized override fun onActivityResumed(activity: Activity) { resumed.add(activity) }
    @Synchronized override fun onActivityPaused(activity: Activity) { resumed.remove(activity) }
    @Synchronized override fun onActivityDestroyed(activity: Activity) { started.remove(activity); resumed.remove(activity) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    @Synchronized override fun onActivityStarted(activity: Activity) { started.add(activity) }
    @Synchronized override fun onActivityStopped(activity: Activity) { started.remove(activity) }
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
}
