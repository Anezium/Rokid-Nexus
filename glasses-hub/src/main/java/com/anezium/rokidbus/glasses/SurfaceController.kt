package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object SurfaceController {
    private const val PREFS = "surface_renderer"
    private const val PREF_DISPLAY_PATH = "display_path"
    private val main = Handler(Looper.getMainLooper())
    private val latestSeqBySurface = ConcurrentHashMap<String, Long>()
    private val listeners = CopyOnWriteArrayList<(NexusSurface?) -> Unit>()
    @Volatile private var active: NexusSurface? = null

    fun activeSurface(): NexusSurface? = active

    // Overlay is the default: TYPE_ACCESSIBILITY_OVERLAY stays visible even when
    // another app (e.g. Rokid Relay's glasses activity) keeps relaunching itself
    // to the foreground, which starves activity-based surfaces on this firmware.
    fun displayPath(context: Context): SurfaceDisplayPath =
        when (
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_DISPLAY_PATH, SurfaceDisplayPath.OVERLAY.prefValue)
        ) {
            SurfaceDisplayPath.ACTIVITY.prefValue -> SurfaceDisplayPath.ACTIVITY
            else -> SurfaceDisplayPath.OVERLAY
        }

    fun setDisplayPath(context: Context, path: SurfaceDisplayPath) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_DISPLAY_PATH, path.prefValue)
            .apply()
    }

    fun observe(listener: (NexusSurface?) -> Unit): () -> Unit {
        listeners += listener
        listener(active)
        return { listeners.remove(listener) }
    }

    fun handleSurfaceEnvelope(context: Context, envelope: BusEnvelope): Boolean {
        return when (envelope.path) {
            BusPaths.SURFACE_SHOW,
            BusPaths.SURFACE_UPDATE,
            -> {
                if (isUnmatchedAnchorOnlyUpdate(envelope.payload)) return true
                val surface = runCatching { NexusSurface.fromPayload(envelope.payload, active) }
                    .onFailure { logError("Surface parse failed", it) }
                    .getOrNull()
                    ?: return true
                showOrUpdate(context.applicationContext, surface)
                true
            }
            BusPaths.SURFACE_HIDE -> {
                val surfaceId = envelope.payload.optString("surfaceId")
                val seq = envelope.payload.optLong("seq", 0L)
                hideRemote(surfaceId, seq)
                true
            }
            else -> false
        }
    }

    fun showDemoCard(context: Context, path: SurfaceDisplayPath): String {
        setDisplayPath(context, path)
        val surface = NexusSurface(
            surfaceId = "demo-${path.prefValue}",
            seq = System.currentTimeMillis(),
            kind = NexusSurface.KIND_CARD,
            contentKey = "demo-${path.prefValue}",
            title = "Rokid Nexus",
            subtitle = "surface renderer demo",
            footer = path.prefValue,
            textLines = listOf(
                "Card surface is rendering locally.",
                "Back hides it and reports input to the phone.",
            ),
            timedLines = emptyList(),
            anchor = null,
        )
        showOrUpdate(context.applicationContext, surface, forcedPath = path)
        return "surfaceDemo=${path.prefValue} surfaceId=${surface.surfaceId}"
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val surface = active ?: return false
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            GlassesHub.sendSurfaceInput(
                JSONObject()
                    .put("surfaceId", surface.surfaceId)
                    .put("keyCode", event.keyCode)
                    .put("action", event.action),
            )
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            hideLocal()
            return true
        }
        return event.keyCode in FORWARDED_KEYS
    }

    private fun showOrUpdate(
        context: Context,
        surface: NexusSurface,
        forcedPath: SurfaceDisplayPath? = null,
    ) {
        val previousSeq = latestSeqBySurface[surface.surfaceId] ?: Long.MIN_VALUE
        if (surface.seq <= previousSeq) {
            log("Surface stale drop id=${surface.surfaceId} seq=${surface.seq} latest=$previousSeq")
            return
        }
        latestSeqBySurface[surface.surfaceId] = surface.seq
        main.post {
            wakeScreen(context)
            active = surface
            notifyListeners(surface)
            when (forcedPath ?: displayPath(context)) {
                SurfaceDisplayPath.ACTIVITY -> showActivity(context, surface)
                SurfaceDisplayPath.OVERLAY -> {
                    if (!SurfaceOverlayRenderer.show(context, surface)) {
                        log("Surface overlay unavailable; falling back to activity")
                        showActivity(context, surface)
                    }
                }
            }
        }
    }

    private fun isUnmatchedAnchorOnlyUpdate(payload: JSONObject): Boolean {
        if (payload.has("lines")) return false
        if (payload.optString("kind") != NexusSurface.KIND_TIMED_LINES) return false
        val surfaceId = payload.optString("surfaceId")
        val contentKey = payload.optString("contentKey")
        val current = active
        val matched = current != null &&
            current.surfaceId == surfaceId &&
            current.kind == NexusSurface.KIND_TIMED_LINES &&
            (contentKey.isBlank() || current.contentKey == contentKey)
        if (!matched) {
            log("Surface anchor ignored until base timed-lines arrives id=$surfaceId")
        }
        return !matched
    }

    private fun hideRemote(surfaceId: String, seq: Long) {
        if (surfaceId.isBlank()) return
        val previousSeq = latestSeqBySurface[surfaceId] ?: Long.MIN_VALUE
        if (seq <= previousSeq) {
            log("Surface stale hide drop id=$surfaceId seq=$seq latest=$previousSeq")
            return
        }
        latestSeqBySurface[surfaceId] = seq
        if (active?.surfaceId == surfaceId) {
            hideLocal()
        }
    }

    private fun hideLocal() {
        main.post {
            active = null
            notifyListeners(null)
            SurfaceOverlayRenderer.hide()
        }
    }

    private fun showActivity(context: Context, surface: NexusSurface) {
        SurfaceOverlayRenderer.hide()
        runCatching {
            context.startActivity(
                Intent(context, SurfaceActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("surfaceId", surface.surfaceId),
            )
        }.onFailure {
            logError("SurfaceActivity start failed; trying overlay", it)
            SurfaceOverlayRenderer.show(context, surface)
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(context: Context) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        if (power.isInteractive) return
        runCatching {
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "rokidbus:surface-wake",
            ).acquire(WAKE_MS)
        }.onFailure { logError("Surface screen wake failed", it) }
    }

    private const val WAKE_MS = 3_000L

    private fun notifyListeners(surface: NexusSurface?) {
        listeners.forEach { listener ->
            runCatching { listener(surface) }
        }
    }

    private val FORWARDED_KEYS = setOf(
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    )
}
