package com.anezium.rokidbus.plugin.agents

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.NexusUi

internal const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

internal fun tailscaleInstalled(context: Context): Boolean =
    runCatching { context.packageManager.getPackageInfo(TAILSCALE_PACKAGE, 0) }.isSuccess

internal fun openTailscaleInstall(activity: Activity) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PACKAGE"))
    runCatching { activity.startActivity(market) }.onFailure {
        activity.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE_PACKAGE"),
            ),
        )
    }
}

/**
 * Whether Android will leave this process alone. Without the exemption the
 * system is free to freeze it, and a frozen process still accepts TCP from the
 * kernel backlog while never answering a byte — which reads on the computer as
 * a phone that connects and then ignores the handshake.
 */
internal fun batteryExemptionGranted(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return false
    return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }
        .getOrDefault(false)
}

internal fun openBatteryExemptionRequest(activity: Activity) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.fromParts("package", activity.packageName, null))
    runCatching { activity.startActivity(direct) }.onFailure {
        // Some ROMs refuse the one-tap request and only offer the list screen,
        // where the wearer picks this app themselves.
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

/**
 * The app's own settings page. A ROM that keeps its real battery decision in a
 * vendor power manager guards that screen with a system permission, so this is
 * the closest a plugin can get the wearer to it.
 */
internal fun openAppInfo(activity: Activity) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", activity.packageName, null))
    runCatching { activity.startActivity(intent) }
}

internal fun connectionRow(context: Context, dot: View, label: TextView) =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val dotSize = NexusUi.dp(context, 8)
        addView(
            dot,
            LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = NexusUi.dp(context, 8)
            },
        )
        addView(label)
    }

internal fun actionRow(
    context: Context,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String,
    onSecondary: () -> Unit,
) = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    addView(
        NexusUi.pillButton(context, primary).apply {
            setOnClickListener { onPrimary() }
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 6
        },
    )
    addView(
        NexusUi.outlinePillButton(context, secondary).apply {
            setOnClickListener { onSecondary() }
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 6
        },
    )
}

/** "5 min ago", not a timestamp: the wearer wants recency, not a clock. */
internal fun lastSeenText(lastSeenAtMs: Long?, now: Long = System.currentTimeMillis()): String {
    lastSeenAtMs ?: return "Not seen since this update"
    val minutes = ((now - lastSeenAtMs).coerceAtLeast(0L)) / 60_000L
    return when {
        minutes < 2L -> "Last seen just now"
        minutes < 60L -> "Last seen $minutes min ago"
        minutes < 48L * 60L -> "Last seen ${minutes / 60L} h ago"
        else -> "Last seen ${minutes / (24L * 60L)} days ago"
    }
}
