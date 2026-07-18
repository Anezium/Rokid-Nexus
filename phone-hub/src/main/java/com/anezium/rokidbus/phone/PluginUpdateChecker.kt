package com.anezium.rokidbus.phone

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

data class PluginUpdateInfo(
    val pluginId: String,
    val name: String,
    val installedVersionName: String,
    val availableVersionName: String,
)

internal data class InstalledPluginVersion(
    val versionCode: Long,
    val versionName: String,
)

internal object PluginUpdateThrottle {
    fun shouldRefresh(
        lastSuccessfulCheckEpochMillis: Long?,
        nowEpochMillis: Long,
        maxAgeMs: Long,
    ): Boolean {
        if (lastSuccessfulCheckEpochMillis == null || lastSuccessfulCheckEpochMillis < 0L) return true
        if (maxAgeMs <= 0L || nowEpochMillis < lastSuccessfulCheckEpochMillis) return true
        return nowEpochMillis - lastSuccessfulCheckEpochMillis >= maxAgeMs
    }
}

object PluginUpdateChecker {
    private const val DEFAULT_MAX_AGE_MS = 60L * 60L * 1_000L
    private const val PREFERENCES = "plugin-update-checker"
    private const val KEY_UPDATES = "updates"
    private const val KEY_LAST_SUCCESS = "last-success-epoch-ms"
    private const val FORMAT_VERSION = 1

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nexus-plugin-update-check").apply { isDaemon = true }
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val lock = Any()
    private val pendingCallbacks = mutableListOf<(List<PluginUpdateInfo>) -> Unit>()
    private var refreshInFlight = false

    fun cachedUpdates(context: Context): List<PluginUpdateInfo> {
        val raw = preferences(context).getString(KEY_UPDATES, null) ?: return emptyList()
        return decodeUpdates(raw)
    }

    /**
     * Recomputes the badge from the registry snapshot already on disk — no network, no throttle.
     * The Store's own live refresh keeps that snapshot current, so this makes the home badge agree
     * with what the Store shows the moment the user comes back, instead of waiting out the network
     * throttle. Returns the freshly computed updates (or the existing cache if no snapshot exists).
     */
    fun recomputeFromCachedRegistry(
        context: Context,
        onResult: (List<PluginUpdateInfo>) -> Unit,
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val updates = runCatching {
                val snapshot = RegistryClient.create(appContext).cachedSnapshot()
                    ?: return@runCatching cachedUpdates(appContext)
                val installedVersions = installedVersions(
                    appContext,
                    snapshot.feed.plugins.mapTo(linkedSetOf()) { it.artifact.packageName },
                )
                val hostVersionCode = packageInfo(appContext, appContext.packageName)
                    ?.longVersionCode ?: 0L
                val catalog = StoreCatalog.build(
                    feed = snapshot.feed,
                    localCatalog = BusHubService.pluginCatalog(appContext),
                    installedVersionCodes = installedVersions.mapValues { it.value.versionCode },
                    hostVersionCode = hostVersionCode,
                )
                catalog.availableUpdates(installedVersions).also { updates ->
                    preferences(appContext).edit().putString(KEY_UPDATES, encodeUpdates(updates)).apply()
                }
            }.getOrDefault(cachedUpdates(appContext))
            postToMain { onResult(updates) }
        }
    }

    fun refreshIfStale(
        context: Context,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        onResult: (List<PluginUpdateInfo>) -> Unit,
    ) {
        val appContext = context.applicationContext
        var immediateResult: List<PluginUpdateInfo>? = null
        var shouldStartRefresh = false
        synchronized(lock) {
            val preferences = preferences(appContext)
            val cached = cachedUpdates(appContext)
            val lastSuccess = runCatching {
                if (preferences.contains(KEY_LAST_SUCCESS)) preferences.getLong(KEY_LAST_SUCCESS, 0L) else null
            }.getOrNull()
            if (!PluginUpdateThrottle.shouldRefresh(lastSuccess, System.currentTimeMillis(), maxAgeMs)) {
                immediateResult = cached
            } else {
                pendingCallbacks += onResult
                if (!refreshInFlight) {
                    refreshInFlight = true
                    shouldStartRefresh = true
                }
            }
        }

        immediateResult?.let { cached ->
            postToMain { onResult(cached) }
            return
        }
        if (shouldStartRefresh) startRefresh(appContext)
    }

    private fun startRefresh(context: Context) {
        executor.execute {
            val previousUpdates = cachedUpdates(context)
            runCatching {
                val registryClient = RegistryClient.create(context)
                val previousRegistryFetch = registryClient.cachedSnapshot()?.lastFetchEpochMillis
                registryClient.refresh { result ->
                    executor.execute {
                        val updates = runCatching {
                            computeAndPersistUpdates(
                                context = context,
                                result = result,
                                previousRegistryFetch = previousRegistryFetch,
                                previousUpdates = previousUpdates,
                            )
                        }.getOrDefault(previousUpdates)
                        finishRefresh(updates)
                    }
                }
            }.onFailure {
                finishRefresh(previousUpdates)
            }
        }
    }

    private fun computeAndPersistUpdates(
        context: Context,
        result: RegistryLoadResult,
        previousRegistryFetch: Long?,
        previousUpdates: List<PluginUpdateInfo>,
    ): List<PluginUpdateInfo> {
        val snapshot = (result as? RegistryLoadResult.Success)?.snapshot ?: return previousUpdates
        val registryRefreshSucceeded = snapshot.source == RegistrySource.NETWORK ||
            snapshot.lastFetchEpochMillis != previousRegistryFetch
        if (!registryRefreshSucceeded) return previousUpdates

        val installedVersions = installedVersions(
            context,
            snapshot.feed.plugins.mapTo(linkedSetOf()) { it.artifact.packageName },
        )
        val hostVersionCode = packageInfo(context, context.packageName)?.longVersionCode ?: 0L
        val catalog = StoreCatalog.build(
            feed = snapshot.feed,
            localCatalog = BusHubService.pluginCatalog(context),
            installedVersionCodes = installedVersions.mapValues { it.value.versionCode },
            hostVersionCode = hostVersionCode,
        )
        val updates = catalog.availableUpdates(installedVersions)
        persist(context, updates, System.currentTimeMillis())
        return updates
    }

    private fun finishRefresh(updates: List<PluginUpdateInfo>) {
        val callbacks = synchronized(lock) {
            refreshInFlight = false
            pendingCallbacks.toList().also { pendingCallbacks.clear() }
        }
        callbacks.forEach { callback -> postToMain { callback(updates) } }
    }

    private fun persist(context: Context, updates: List<PluginUpdateInfo>, timestamp: Long) {
        preferences(context).edit()
            .putString(KEY_UPDATES, encodeUpdates(updates))
            .putLong(KEY_LAST_SUCCESS, timestamp)
            .commit()
    }

    private fun installedVersions(
        context: Context,
        packageNames: Set<String>,
    ): Map<String, InstalledPluginVersion> = buildMap {
        packageNames.forEach { packageName ->
            val info = packageInfo(context, packageName) ?: return@forEach
            put(
                packageName,
                InstalledPluginVersion(
                    versionCode = info.longVersionCode,
                    versionName = info.versionName?.takeIf(String::isNotBlank)
                        ?: info.longVersionCode.toString(),
                ),
            )
        }
    }

    private fun packageInfo(context: Context, packageName: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
    }.getOrNull()

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun postToMain(action: () -> Unit) {
        mainHandler.post(action)
    }

    private fun encodeUpdates(updates: List<PluginUpdateInfo>): String {
        val values = JSONArray()
        updates.forEach { update ->
            values.put(
                JSONObject()
                    .put("pluginId", update.pluginId)
                    .put("name", update.name)
                    .put("installedVersionName", update.installedVersionName)
                    .put("availableVersionName", update.availableVersionName),
            )
        }
        return JSONObject()
            .put("version", FORMAT_VERSION)
            .put("updates", values)
            .toString()
    }

    private fun decodeUpdates(raw: String): List<PluginUpdateInfo> = runCatching {
        val root = JSONObject(raw)
        if (root.getInt("version") != FORMAT_VERSION) return emptyList()
        val values = root.getJSONArray("updates")
        buildList {
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                add(
                    PluginUpdateInfo(
                        pluginId = value.getString("pluginId"),
                        name = value.getString("name"),
                        installedVersionName = value.getString("installedVersionName"),
                        availableVersionName = value.getString("availableVersionName"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}
