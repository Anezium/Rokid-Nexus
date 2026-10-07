package com.anezium.rokidbus.plugin.patcher

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.Collections
import java.util.IdentityHashMap

internal object PatchVisibility : Application.ActivityLifecycleCallbacks {
    private val resumed = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private var installed = false
    val hasResumedActivity: Boolean get() = resumed.isNotEmpty()

    fun install(application: Application) {
        if (!installed) { application.registerActivityLifecycleCallbacks(this); installed = true }
    }
    override fun onActivityResumed(activity: Activity) { resumed.add(activity) }
    override fun onActivityPaused(activity: Activity) { resumed.remove(activity) }
    override fun onActivityDestroyed(activity: Activity) { resumed.remove(activity) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
}
