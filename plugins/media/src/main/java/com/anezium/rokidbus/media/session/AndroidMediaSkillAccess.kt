package com.anezium.rokidbus.media.session

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.anezium.rokidbus.plugin.media.MediaPauseOutcome
import com.anezium.rokidbus.plugin.media.MediaSkillAccess
import com.anezium.rokidbus.plugin.media.MediaSkillSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** Skill calls use their own short-lived controller callback and never start the HUD monitor. */
internal class AndroidMediaSkillAccess(context: Context) : MediaSkillAccess {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(this.context, MediaDeckNotificationListenerService::class.java)
    private val main = Handler(Looper.getMainLooper())

    override fun hasAccess(): Boolean = Settings.Secure.getString(context.contentResolver,
        "enabled_notification_listeners").orEmpty().split(':')
        .any { ComponentName.unflattenFromString(it) == listener }

    private fun controllers(): List<MediaController> = manager?.getActiveSessions(listener).orEmpty()

    override fun sessions(includeMetadata: Boolean): List<MediaSkillSession> {
        val controllers = controllers()
        val refs = references.forTokens(controllers.map { it.sessionToken }, SystemClock.elapsedRealtime())
        return controllers.mapIndexed { index, controller ->
            val ref = refs[index]
            val metadata = if (includeMetadata) controller.metadata else null
            MediaSkillSession(ref, player(controller.packageName), state(controller.playbackState),
                metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?.takeIf(String::isNotBlank) ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf(String::isNotBlank)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
        }
    }

    override fun pause(reference: String, remainingMs: () -> Long, cancelled: () -> Boolean): MediaPauseOutcome {
        val token = references.token(reference) ?: return MediaPauseOutcome.STALE
        val result = AtomicReference<MediaPauseOutcome?>()
        val dispatch = AtomicReference(Dispatch.READY)
        val sentAt = AtomicLong(0L)
        val done = CountDownLatch(1)
        var controller: MediaController? = null
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (dispatch.get().sent && state?.state == PlaybackState.STATE_PAUSED) {
                    result.compareAndSet(null, MediaPauseOutcome.PAUSED)
                    done.countDown()
                } else if (dispatch.get() == Dispatch.SENT && state?.state == PlaybackState.STATE_STOPPED) {
                    result.compareAndSet(null, MediaPauseOutcome.ACCEPTED)
                    done.countDown()
                }
            }
            override fun onSessionDestroyed() {
                result.compareAndSet(null, if (dispatch.get().sent)
                    MediaPauseOutcome.UNKNOWN else MediaPauseOutcome.STALE)
                done.countDown()
            }
        }
        val start = Runnable {
            try {
                when {
                    cancelled() || remainingMs() <= DEADLINE_MARGIN_MS -> result.set(MediaPauseOutcome.DEADLINE)
                    !hasAccess() -> result.set(MediaPauseOutcome.SETUP_REQUIRED)
                    else -> {
                        controller = controllers().find { it.sessionToken == token }
                        val target = controller
                        if (target == null) result.set(MediaPauseOutcome.STALE)
                        else {
                            target.registerCallback(callback, main)
                            val current = target.playbackState
                            when {
                                current?.state == PlaybackState.STATE_PAUSED -> result.set(MediaPauseOutcome.ALREADY_PAUSED)
                                current == null || current.actions and PAUSE_ACTIONS == 0L ->
                                    result.set(MediaPauseOutcome.UNSUPPORTED)
                                !hasAccess() -> result.set(MediaPauseOutcome.SETUP_REQUIRED)
                                controllers().none { it.sessionToken == token } -> result.set(MediaPauseOutcome.STALE)
                                cancelled() || remainingMs() <= DEADLINE_MARGIN_MS -> result.set(MediaPauseOutcome.DEADLINE)
                                else -> {
                                    if (dispatch.compareAndSet(Dispatch.READY, Dispatch.SENDING)) {
                                        target.transportControls.pause()
                                        sentAt.set(SystemClock.elapsedRealtime())
                                        dispatch.set(Dispatch.SENT)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                result.set(if (dispatch.get().sent)
                    MediaPauseOutcome.UNKNOWN else MediaPauseOutcome.SETUP_REQUIRED)
            }
            if (result.get() != null) done.countDown()
        }
        main.post(start)
        try {
            while (result.get() == null && !cancelled()) {
                val wait = (remainingMs() - DEADLINE_MARGIN_MS).coerceAtMost(100L)
                if (wait <= 0) break
                if (dispatch.get() == Dispatch.SENT &&
                    SystemClock.elapsedRealtime() - sentAt.get() >= CONFIRMATION_MS) break
                if (done.await(wait, TimeUnit.MILLISECONDS)) break
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            dispatch.compareAndSet(Dispatch.READY, Dispatch.ABORTED)
            main.removeCallbacks(start)
            main.post { controller?.unregisterCallback(callback) }
        }
        return result.get() ?: when (dispatch.get()) {
            Dispatch.SENT -> MediaPauseOutcome.ACCEPTED
            Dispatch.SENDING -> MediaPauseOutcome.UNKNOWN
            else -> MediaPauseOutcome.DEADLINE
        }
    }

    @Suppress("DEPRECATION")
    private fun player(packageName: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault("").trim().ifBlank { packageName.substringAfterLast('.') }.take(80)

    private fun state(playback: PlaybackState?): String = when (playback?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        else -> "stopped"
    }

    private companion object {
        // A headless skill lease destroys its Service after each call; refs live until process death.
        val references = MediaSkillReferences<MediaSession.Token>()
        const val DEADLINE_MARGIN_MS = 250L
        const val CONFIRMATION_MS = 2_500L
        const val PAUSE_ACTIONS = PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
    }

    private enum class Dispatch(val sent: Boolean) { READY(false), SENDING(true), SENT(true), ABORTED(false) }
}
