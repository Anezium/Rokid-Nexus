package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.client.ui.PluginCustomIcon
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Catalog assembly and formatting shared by the Store list and detail screens. */
internal object StoreScreens {

    fun buildCatalog(
        context: Context,
        feed: RegistryFeed,
        hostVersionCode: Long,
        logger: (String) -> Unit = {},
    ): StoreCatalog {
        val local = BusHubService.pluginCatalog(context)
        val packageNames = buildSet {
            feed.plugins.forEach { add(it.artifact.packageName) }
            local.entries.mapNotNullTo(this) {
                it.principal?.packageName ?: it.settingsComponent?.packageName
            }
        }
        return StoreCatalog.build(
            feed = feed,
            localCatalog = local,
            installedVersionCodes = installedVersionCodes(context.packageManager, packageNames),
            hostVersionCode = hostVersionCode,
            logger = logger,
        )
    }

    fun installedVersionCodes(
        packageManager: PackageManager,
        packageNames: Set<String>,
    ): Map<String, Long> = buildMap {
        packageNames.forEach { packageName ->
            val info = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0)
                }
            }.getOrNull() ?: return@forEach
            put(packageName, info.longVersionCode)
        }
    }

    fun iconView(activity: Activity, iconLoader: StoreIconLoader, entry: StoreEntry, sizeDp: Int): ImageView {
        val imageView = NexusUi.iconTileDrawable(activity, fallbackIcon(activity, entry), sizeDp)
        val iconUrl = entry.registryPlugin?.iconUrl ?: return imageView
        imageView.tag = iconUrl
        iconLoader.load(iconUrl) { bitmap ->
            if (activity.isFinishing || activity.isDestroyed || imageView.tag != iconUrl) return@load
            NexusUi.applyIconTileArtwork(
                imageView,
                BitmapDrawable(activity.resources, bitmap),
                sizeDp = sizeDp,
            )
        }
        return imageView
    }

    fun fallbackIcon(activity: Activity, entry: StoreEntry): Drawable {
        val local = entry.localEntry
        return when (
            val fallback = selectStoreIconFallback(
                pluginId = entry.id,
                installedPackageName = local?.principal?.packageName,
                iconKey = local?.iconKey,
                customIconResId = local?.iconDrawableResId,
            )
        ) {
            is StoreIconFallback.InstalledDescriptor -> NexusPluginIcons.resolve(
                context = activity,
                iconKey = fallback.iconKey,
                customIcon = fallback.customIconResId?.let { resId ->
                    PluginCustomIcon(fallback.packageName, resId)
                },
                pluginId = fallback.pluginId,
                fallbackResId = fallback.legacyResId,
            )
            is StoreIconFallback.Legacy -> requireNotNull(activity.getDrawable(fallback.resId))
        }
    }

    fun formatSize(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        return if (bytes >= 1024 * 1024) {
            String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        } else {
            "${(bytes + 1023) / 1024} KB"
        }
    }

    fun grantLabel(state: PluginCatalogState?): String = when (state) {
        PluginCatalogState.PENDING -> "pending approval"
        PluginCatalogState.ENABLED -> "enabled"
        PluginCatalogState.DISABLED -> "disabled"
        PluginCatalogState.DENIED -> "denied"
        PluginCatalogState.INVALID -> "invalid"
        PluginCatalogState.MISSING_CAPABILITY -> "missing access"
        null -> "installed"
    }

    /** "2026-08-07T12:15:57Z" or "2026-08-07" → "Aug 7, 2026"; anything else comes back as-is. */
    fun formatReleaseDate(value: String?): String? {
        val raw = value?.takeIf(String::isNotBlank) ?: return null
        val date = runCatching { Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDate() }
            .recoverCatching { LocalDate.parse(raw.take(10)) }
            .getOrNull() ?: return raw
        return date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
    }
}
