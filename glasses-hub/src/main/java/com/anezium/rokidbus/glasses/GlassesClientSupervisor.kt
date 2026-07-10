package com.anezium.rokidbus.glasses

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
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.FrameProtocol
import com.anezium.rokidbus.shared.plugin.PathRules
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

object GlassesClientSupervisor {
    private const val MAX_MESSAGES = 32
    private const val MAX_BYTES = 512 * 1024
    private const val TTL_MS = 30_000L
    private const val REGISTER_WAIT_MS = 5_000L
    private const val IDLE_UNBIND_MS = 60_000L

    private data class Target(val component: ComponentName, val prefixes: List<String>)
    private data class Queued(val envelope: BusEnvelope, val createdAtMs: Long, val bytes: Int)
    private data class Held(val connection: ServiceConnection, @Volatile var lastActiveMs: Long)

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val queue = ArrayDeque<Queued>()
    private val held = ConcurrentHashMap<ComponentName, Held>()
    private var queuedBytes = 0
    private var reaperPosted = false

    fun enqueue(context: Context, envelope: BusEnvelope): Boolean {
        if (envelope.binary != null) {
            log("drop binary wake queue path=${envelope.path} id=${envelope.id}")
            return false
        }
        val target = findTarget(context, envelope.path) ?: return false
        val now = SystemClock.elapsedRealtime()
        val bytes = FrameProtocol.toJsonBytes(envelope).size
        synchronized(lock) {
            pruneExpiredLocked(now)
            queue += Queued(envelope, now, bytes)
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

    fun onClientRegistered(context: Context, prefixes: List<String>) {
        touch()
        flush(context.applicationContext, prefixes)
    }

    fun touch() {
        val now = SystemClock.elapsedRealtime()
        held.values.forEach { it.lastActiveMs = now }
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
        val intent = Intent(BusConstants.ACTION_CLIENT).setComponent(target.component)
        val ok = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (ok) {
            held[target.component] = Held(connection, SystemClock.elapsedRealtime())
        } else {
            logError("wake bind failed ${target.component.flattenToShortString()}")
        }
    }

    private fun flush(context: Context, onlyPrefixes: List<String>? = null) {
        val now = SystemClock.elapsedRealtime()
        val toDeliver = mutableListOf<Queued>()
        synchronized(lock) {
            pruneExpiredLocked(now)
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                val matches = onlyPrefixes == null ||
                    onlyPrefixes.any { PathRules.matchesPrefix(item.envelope.path, it) }
                if (matches) {
                    iterator.remove()
                    queuedBytes -= item.bytes
                    toDeliver += item
                }
            }
        }
        toDeliver.forEach { item ->
            if (!GlassesHub.deliverQueued(item.envelope)) {
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

    private fun findTarget(context: Context, path: String): Target? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        val intent = Intent(BusConstants.ACTION_CLIENT)
        val flags = PackageManager.GET_META_DATA
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentServices(intent, flags)
        }
        val targets = matches.asSequence().mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            val value = service.metaData?.getString(BusConstants.META_DATA_PATHS).orEmpty()
            val rawPrefixes = value.split(',', ';', ' ', '\n', '\t')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val prefixes = rawPrefixes.mapNotNull(PathRules::normalizeAbsolute)
            if (prefixes.size == rawPrefixes.size &&
                prefixes.any { PathRules.matchesPrefix(path, it) }
            ) {
                Target(ComponentName(service.packageName, service.name), prefixes)
            } else {
                null
            }
        }.distinctBy { it.component }.toList()
        return targets.singleOrNull()
    }

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
}
