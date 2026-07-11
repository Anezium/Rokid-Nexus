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
    private const val BACK_FAILSAFE_MS = 1_500L
    private val main = Handler(Looper.getMainLooper())
    private val latestSeqBySurface = ConcurrentHashMap<String, Long>()
    private val listeners = CopyOnWriteArrayList<(NexusSurface?) -> Unit>()
    private val inputDedupe = DpadPairDedupe()
    private val suppressedDpadUps = mutableSetOf<Int>()
    private var backFailsafeSurfaceId: String? = null
    private var backFailsafe: Runnable? = null
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
            // Width/height ruler: count the last digit that fits before the wrap
            // and the last row number that renders to calibrate card formatters.
            rows = listOf(
                "123456789012345678901234567890",
                "row 02",
                "row 03",
                "row 04",
                "row 05",
                "row 06",
                "row 07",
                "row 08",
                "row 09",
                "row 10",
                "row 11",
                "row 12",
            ).map { SurfaceRow(text = it) },
            timedLines = emptyList(),
            anchor = null,
            handlesBack = false,
        )
        showOrUpdate(context.applicationContext, surface, forcedPath = path)
        return "surfaceDemo=${path.prefValue} surfaceId=${surface.surfaceId}"
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val surface = active ?: return false
        if (shouldSuppressDpadEvent(event)) {
            return true
        }
        if ((event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) &&
            event.keyCode in FORWARDED_KEYS
        ) {
            forwardSurfaceInput(event.keyCode, event.action)
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (surface.handlesBack) {
                armBackFailsafe(surface.surfaceId)
            } else {
                hideLocal()
            }
            return true
        }
        return event.keyCode in FORWARDED_KEYS
    }

    fun forwardSurfaceInput(keyCode: Int, action: Int): Boolean {
        val surface = active ?: return false
        GlassesHub.sendSurfaceInput(
            JSONObject()
                .put("surfaceId", surface.surfaceId)
                .put("keyCode", keyCode)
                .put("action", action),
        )
        return true
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
            cancelBackFailsafeOnMain(surface.surfaceId)
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
        val kind = payload.optString("kind")
        val anchorOnly = when (kind) {
            NexusSurface.KIND_TIMED_LINES -> !payload.has("lines")
            NexusSurface.KIND_MEDIA ->
                payload.has("anchor") && !payload.has("mediaTitle") && !payload.has("artwork")
            else -> false
        }
        if (!anchorOnly) return false
        val surfaceId = payload.optString("surfaceId")
        val contentKey = payload.optString("contentKey")
        val current = active
        val matched = current != null &&
            current.surfaceId == surfaceId &&
            current.kind == kind &&
            (contentKey.isBlank() || current.contentKey == contentKey)
        if (!matched) {
            log("Surface anchor ignored until matching base arrives id=$surfaceId kind=$kind")
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
        main.post {
            cancelBackFailsafeOnMain(surfaceId)
            if (active?.surfaceId == surfaceId) {
                hideLocalOnMain()
            }
        }
    }

    private fun hideLocal() {
        runOnMain { hideLocalOnMain() }
    }

    private fun hideLocalOnMain() {
        active?.surfaceId?.let { cancelBackFailsafeOnMain(it) }
        active = null
        notifyListeners(null)
        SurfaceOverlayRenderer.hide()
    }

    private fun shouldSuppressDpadEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in DPAD_DIRECTION_KEYS) return false
        if (event.action == KeyEvent.ACTION_UP && suppressedDpadUps.remove(event.keyCode)) {
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        val direction = inputDedupe.onKey(event.keyCode, event.action, event.repeatCount, event.eventTime)
        if (direction != null) return false
        suppressedDpadUps += event.keyCode
        return true
    }

    private fun armBackFailsafe(surfaceId: String) {
        runOnMain {
            cancelBackFailsafeOnMain()
            val runnable = Runnable {
                if (active?.surfaceId == surfaceId) {
                    hideLocalOnMain()
                }
                if (backFailsafeSurfaceId == surfaceId) {
                    backFailsafeSurfaceId = null
                    backFailsafe = null
                }
            }
            backFailsafeSurfaceId = surfaceId
            backFailsafe = runnable
            main.postDelayed(runnable, BACK_FAILSAFE_MS)
        }
    }

    private fun cancelBackFailsafeOnMain(surfaceId: String? = null) {
        if (surfaceId != null && backFailsafeSurfaceId != surfaceId) return
        backFailsafe?.let { main.removeCallbacks(it) }
        backFailsafeSurfaceId = null
        backFailsafe = null
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            main.post(action)
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

    private val DPAD_DIRECTION_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
    )
}
