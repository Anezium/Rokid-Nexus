package com.anezium.rokidbus.plugin.relay

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import java.text.Collator

internal data class RelaySourceApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val detailsAvailable: Boolean,
)

/** Only package identities are remembered here, for this process's lifetime. */
internal object RelaySourceApps {
    private val observed = linkedSetOf<String>()

    @Synchronized
    fun observe(packageName: String) {
        observed += packageName
        while (observed.size > 256) observed.remove(observed.first())
    }

    fun list(context: Context, settings: RelaySettings): List<RelaySourceApp> {
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).map { it.activityInfo.packageName }
        // Relay itself is headless, but its folded harness needs the same explicit opt-in.
        val packages = launchable + context.packageName + synchronized(this) { observed.toList() } + settings.configuredPackages()
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        return packages.distinct().map { packageName ->
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }
            val icon = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
            RelaySourceApp(packageName, label.getOrDefault(packageName), icon, label.isSuccess)
        }.sortedWith { left, right ->
            val byLabel = collator.compare(left.label, right.label)
            if (byLabel != 0) byLabel else left.packageName.compareTo(right.packageName)
        }
    }
}
