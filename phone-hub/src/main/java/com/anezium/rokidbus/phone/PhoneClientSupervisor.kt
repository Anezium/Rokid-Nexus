package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.FrameProtocol
import com.anezium.rokidbus.shared.plugin.PathRules
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ROKIDBUS-PHONE"

object PhoneClientSupervisor {
    private const val MAX_MESSAGES = 32
    private const val MAX_BYTES = 512 * 1024
    private const val TTL_MS = 30_000L
    private const val REGISTER_WAIT_MS = 5_000L
    private const val IDLE_UNBIND_MS = 60_000L

    private data class Target(
        val component: ComponentName,
        val prefixes: List<String>,
        val action: String,
        val uid: Int,
        val principalKey: PluginGrantKey? = null,
    )
    private data class Queued(
        val envelope: BusEnvelope,
        val target: Target,
        val createdAtMs: Long,
        val bytes: Int,
    )
    private data class Held(
        val connection: ServiceConnection,
        val target: Target,
        @Volatile var lastActiveMs: Long,
    )

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val queue = ArrayDeque<Queued>()
    private val held = ConcurrentHashMap<ComponentName, Held>()
    @Volatile private var hub: BusHubService? = null
    private var queuedBytes = 0
    private var reaperPosted = false

    fun attach(hub: BusHubService) {
        this.hub = hub
    }

    fun detach(context: Context, hub: BusHubService) {
        if (this.hub === hub) this.hub = null
        stop(context.applicationContext)
    }

    fun enqueue(context: Context, envelope: BusEnvelope, excludeUid: Int? = null): Boolean {
        if (envelope.binary != null) {
            log("drop binary wake queue path=${envelope.path} id=${envelope.id}")
            return false
        }
        val target = findTarget(context, envelope.path, excludeUid) ?: return false
        val now = SystemClock.elapsedRealtime()
        val bytes = FrameProtocol.toJsonBytes(envelope).size
        synchronized(lock) {
            pruneExpiredLocked(now)
            queue += Queued(envelope, target, now, bytes)
            queuedBytes += bytes
            while (queue.size > MAX_MESSAGES || queuedBytes > MAX_BYTES) {
                queuedBytes -= queue.removeFirst().bytes
            }
        }
        bind(context.applicationContext, target)
        main.postDelayed({ flush(context.applicationContext) }, REGISTER_WAIT_MS)
        scheduleReaper(context.applicationContext)
        log("queued wake path=${envelope.path} target=${target.component.flattenToShortString()}")
        return true
    }

    fun onClientRegistered(
        context: Context,
        prefixes: List<String>,
        principalKey: PluginGrantKey? = null,
    ) {
        touch()
        flush(context.applicationContext, prefixes, principalKey)
    }

    fun onPrincipalRevoked(context: Context, principalKey: PluginGrantKey) {
        synchronized(lock) {
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (item.target.principalKey == principalKey) {
                    iterator.remove()
                    queuedBytes -= item.bytes
                }
            }
        }
        held.entries.filter { it.value.target.principalKey == principalKey }.forEach { entry ->
            runCatching { context.applicationContext.unbindService(entry.value.connection) }
            held.remove(entry.key)
        }
    }

    fun touch() {
        val now = SystemClock.elapsedRealtime()
        held.values.forEach { it.lastActiveMs = now }
    }

    fun stop(context: Context) {
        main.removeCallbacksAndMessages(null)
        reaperPosted = false
        held.entries.forEach { entry ->
            runCatching { context.unbindService(entry.value.connection) }
            log("wake bind stopped ${entry.key.flattenToShortString()}")
        }
        held.clear()
        synchronized(lock) {
            queue.clear()
            queuedBytes = 0
        }
    }

    private fun bind(context: Context, target: Target) {
        val current = held[target.component]
        if (current != null) {
            current.lastActiveMs = SystemClock.elapsedRealtime()
            return
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                log("wake bind connected ${name.flattenToShortString()}")
                held[name]?.lastActiveMs = SystemClock.elapsedRealtime()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                log("wake bind disconnected ${name.flattenToShortString()}")
                held.remove(name)
            }
        }
        val intent = Intent(target.action).setComponent(target.component)
        val ok = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (ok) {
            held[target.component] = Held(connection, target, SystemClock.elapsedRealtime())
        } else {
            logError("wake bind failed ${target.component.flattenToShortString()}")
        }
    }

    private fun flush(
        context: Context,
        onlyPrefixes: List<String>? = null,
        principalKey: PluginGrantKey? = null,
    ) {
        val now = SystemClock.elapsedRealtime()
        val toDeliver = mutableListOf<Queued>()
        synchronized(lock) {
            pruneExpiredLocked(now)
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                val matches = when {
                    principalKey != null -> item.target.principalKey == principalKey
                    onlyPrefixes != null -> item.target.principalKey == null &&
                        onlyPrefixes.any { PathRules.matchesPrefix(item.envelope.path, it) }
                    else -> true
                }
                if (matches) {
                    iterator.remove()
                    queuedBytes -= item.bytes
                    toDeliver += item
                }
            }
        }
        toDeliver.forEach { item ->
            if (!targetStillAuthorized(context, item.target, item.envelope.path)) {
                return@forEach
            }
            if (hub?.deliverQueued(item.envelope) != true) {
                synchronized(lock) {
                    queue += item
                    queuedBytes += item.bytes
                }
            } else {
                touch()
            }
        }
        scheduleReaper(context)
    }

    private fun findTarget(context: Context, path: String, excludeUid: Int? = null): Target? {
        val externalTargets = approvedPluginTargets(context)
        val legacyTargets = if (isDebuggable(context)) legacyTargets(context) else emptyList()
        val targetsByOwner = (externalTargets + legacyTargets).associateBy { it.component.flattenToString() }
        val candidates = targetsByOwner.map { (owner, target) ->
            PluginWakeCandidate(
                ownerKey = owner,
                uid = target.uid,
                prefixes = target.prefixes,
                approvedAndEnabled = true,
            )
        }
        return when (val selected = PluginWakePolicy.select(path, candidates, excludeUid)) {
            PluginWakeSelection.None -> null
            PluginWakeSelection.Conflict -> {
                logError("wake target conflict path=$path")
                null
            }
            is PluginWakeSelection.Selected -> targetsByOwner[selected.candidate.ownerKey]
        }
    }

    private fun approvedPluginTargets(context: Context): List<Target> {
        val store = PluginGrantStore(context.applicationContext)
        return PhonePluginDiscovery(context.packageManager).discover().mapNotNull { candidate ->
            val principal = (candidate as? PhonePluginCandidate.Valid)?.principal ?: return@mapNotNull null
            val state = store.stateFor(principal) as? PluginGrantState.Approved ?: return@mapNotNull null
            val prefixes = principal.descriptor.receivePrefixes.filter { prefix ->
                PathRules.requiredCapabilityForReceivePrefix(prefix)?.let { it in state.capabilities } != false
            }
            Target(
                component = principal.serviceComponent,
                prefixes = prefixes,
                action = BusConstants.ACTION_PLUGIN,
                uid = principal.uid,
                principalKey = principal.grantKey(),
            )
        }
    }

    private fun legacyTargets(context: Context): List<Target> =
        queryServices(context, Intent(BusConstants.ACTION_CLIENT)).mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            val rawPrefixes = service.metaData?.getString(BusConstants.META_DATA_PATHS).orEmpty()
                .split(',', ';', ' ', '\n', '\t')
                .map(String::trim)
                .filter(String::isNotEmpty)
            val prefixes = rawPrefixes.mapNotNull(PathRules::normalizeAbsolute)
            if (prefixes.size != rawPrefixes.size || prefixes.isEmpty()) return@mapNotNull null
            Target(
                component = ComponentName(service.packageName, service.name),
                prefixes = prefixes,
                action = BusConstants.ACTION_CLIENT,
                uid = service.applicationInfo?.uid ?: return@mapNotNull null,
            )
        }

    private fun targetStillAuthorized(context: Context, target: Target, path: String): Boolean {
        if (target.principalKey == null) {
            return isDebuggable(context) && legacyTargets(context).any { current ->
                current.component == target.component && current.uid == target.uid &&
                    current.prefixes.any { PathRules.matchesPrefix(path, it) }
            }
        }
        return approvedPluginTargets(context).any { current ->
            current.component == target.component &&
                current.uid == target.uid &&
                current.principalKey == target.principalKey &&
                current.prefixes.any { PathRules.matchesPrefix(path, it) }
        }
    }

    private fun queryServices(context: Context, intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }

    private fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun scheduleReaper(context: Context) {
        if (reaperPosted) return
        reaperPosted = true
        main.postDelayed({
            reaperPosted = false
            reap(context.applicationContext)
        }, 10_000L)
    }

    private fun reap(context: Context) {
        val now = SystemClock.elapsedRealtime()
        held.entries.forEach { entry ->
            if (now - entry.value.lastActiveMs > IDLE_UNBIND_MS) {
                runCatching { context.unbindService(entry.value.connection) }
                held.remove(entry.key)
                log("wake bind reaped ${entry.key.flattenToShortString()}")
            }
        }
        if (held.isNotEmpty()) scheduleReaper(context)
    }

    private fun pruneExpiredLocked(now: Long) {
        while (queue.isNotEmpty() && now - queue.first.createdAtMs > TTL_MS) {
            queuedBytes -= queue.removeFirst().bytes
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }
}
