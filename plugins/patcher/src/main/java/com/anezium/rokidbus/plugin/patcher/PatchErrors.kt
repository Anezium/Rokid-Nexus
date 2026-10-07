package com.anezium.rokidbus.plugin.patcher

import android.util.Log

internal object PatchErrors {
    fun reason(error: Throwable, fallback: String = "Patching failed. Retry with a supported stock APK.",
               log: (String) -> Unit = { Log.w(PatchTimings.TAG, it) }): String {
        log("failure_class=${error.javaClass.name}")
        val ownValidation = (error is IllegalArgumentException || error is IllegalStateException) &&
            error.cause == null && error.stackTrace.firstOrNull()?.className
                ?.startsWith("com.anezium.rokidbus.plugin.patcher.") == true
        return if (ownValidation) error.message?.takeIf { it.isNotBlank() } ?: fallback else fallback
    }
}
