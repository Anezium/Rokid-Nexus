package com.anezium.rokidbus.plugin.line

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.plugin.line.nativebridge.mobile.Engine
import com.anezium.rokidbus.plugin.line.nativebridge.mobile.Listener
import com.anezium.rokidbus.plugin.line.nativebridge.mobile.Mobile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** One native session shared by the phone screen and visible launcher pages. */
internal object LineSession {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cancellation = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val owners = mutableMapOf<Any, () -> Unit>()
    private var engine: Engine? = null
    @Volatile var snapshot = LineSnapshot()
        private set
    @Volatile var login = LineLogin()
        private set

    /**
     * The PIN the settings screen last put on the clipboard, until that screen could confirm the
     * clipboard no longer holds it. Lives here rather than in the Activity so a rotation, Back, or
     * a sign-in that finishes while LINE is in front does not lose the duty to clear it.
     */
    @Volatile var copiedPin: String? = null

    private fun path(context: Context) = File(context.applicationContext.noBackupFilesDir, "line-session").absolutePath

    fun acquire(context: Context, owner: Any, changed: () -> Unit) {
        synchronized(lock) { owners[owner] = changed }
        call(context, owner, { }) { result ->
            if (result.isFailure) {
                snapshot = LineSnapshot("failed")
                synchronized(lock) { owners.values.toList() }.forEach { it() }
            }
        }
    }

    fun release(owner: Any) {
        val retired = synchronized(lock) {
            owners.remove(owner)
            if (owners.isNotEmpty()) null else engine.also { engine = null }
        } ?: return
        snapshot = LineSnapshot()
        login = LineLogin()
        cancellation.execute { runCatching { retired.cancel() } }
        worker.execute { runCatching { retired.close() } }
    }

    fun <T> call(context: Context, owner: Any, work: (Engine) -> T, done: (Result<T>) -> Unit) {
        val callback = synchronized(lock) { owners[owner] } ?: return
        val deadline = SystemClock.elapsedRealtime() + 10_000
        worker.execute {
            if (synchronized(lock) { owners[owner] !== callback }) return@execute
            val result = runCatching {
                check(SystemClock.elapsedRealtime() < deadline)
                val current = synchronized(lock) { engine } ?: Mobile.open(path(context)).also { opened ->
                    val retained = synchronized(lock) {
                        if (owners[owner] !== callback) false else { engine = opened; true }
                    }
                    if (!retained) { opened.cancel(); opened.close(); error("Session closed") }
                    opened.setListener(object : Listener {
                        override fun changed() { worker.execute { refresh(opened) } }
                    })
                }
                check(synchronized(lock) { owners[owner] === callback })
                check(SystemClock.elapsedRealtime() < deadline)
                work(current).also { refresh(current) }
            }.recoverCatching { throw IllegalStateException("LINE unavailable; check sign-in on the phone") }
            main.post {
                if (synchronized(lock) { owners[owner] === callback }) done(result)
            }
        }
    }

    private fun refresh(current: Engine) {
        if (synchronized(lock) { engine !== current }) return
        runCatching {
            val state = JSONObject(current.snapshot())
            val rows = state.optJSONArray("chats")
            snapshot = LineSnapshot(state.optString("state"), buildList {
                if (rows != null) for (i in 0 until rows.length()) {
                    val item = rows.getJSONObject(i)
                    val history = item.optJSONArray("history")
                    add(LineChat(item.getString("id"), item.getString("title"), item.optBoolean("group"), buildList {
                        if (history != null) for (j in 0 until history.length()) {
                            val row = history.getJSONObject(j)
                            add(LineMessage(row.getString("id"), row.optString("text"), row.optBoolean("mine"), row.optString("media")))
                        }
                    }))
                }
            })
            val progress = JSONObject(current.loginStatus())
            val phase = progress.optString("phase")
            login = LineLogin(phase, progress.optString("pin"))
            main.post {
                if (synchronized(lock) { engine === current }) {
                    synchronized(lock) { owners.values.toList() }.forEach { it() }
                }
            }
        }
    }

    fun abortLogin() {
        val current = synchronized(lock) { engine }
        cancellation.execute { runCatching { current?.abortLogin() } }
    }

    fun forget(context: Context, done: (Boolean) -> Unit) {
        val retired = synchronized(lock) { engine.also { engine = null } }
        cancellation.execute { runCatching { retired?.cancel() } }
        worker.execute {
            val ok = runCatching {
                retired?.close()
                Mobile.forget(path(context))
                snapshot = LineSnapshot("signed_out")
                login = LineLogin()
            }.isSuccess
            main.post {
                if (ok) synchronized(lock) { owners.values.toList() }.forEach { it() }
                done(ok)
            }
        }
    }
}
