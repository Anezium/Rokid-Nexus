package com.anezium.rokidbus.plugin.sample

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageAction
import com.anezium.rokidbus.client.plugin.NexusPageClosed
import com.anezium.rokidbus.client.plugin.NexusPageRequest
import com.anezium.rokidbus.client.plugin.NexusPageResult
import com.anezium.rokidbus.client.plugin.NexusPageVisibility
import com.anezium.rokidbus.client.plugin.NexusPluginClient
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONObject

/**
 * The todo example inside [HelloPluginService]: its pages, and the service's side of the
 * activity. The service forwards the matching callbacks here, so everything the example needs
 * from it is a handful of one-line hooks.
 *
 * Main thread, like the callbacks.
 */
internal class TodoServiceHooks(
    private val context: Context,
    private val client: () -> NexusPluginClient?,
) {
    private val main = Handler(Looper.getMainLooper())
    private var pendingRepublish: Runnable? = null
    private var registrationGeneration: Int? = null

    private val pages = TodoPageProvider<NexusPageRequest>(
        store = { TodoControl.store(context) },
        extended = { client()?.supportsLauncherInteractive() == true },
        pageIdOf = { it.pageId },
        frameOf = { TodoFrame(it.sessionGeneration, it.frameIndex) },
        floorOf = { it.minimumRevision },
        publish = { request, page -> client()?.publishPage(request, page) ?: NexusSdkResult.NOT_REGISTERED },
        changed = { TodoControl.changed(context) },
        now = SystemClock::elapsedRealtime,
        schedule = { delayMs, task ->
            val runnable = Runnable {
                pendingRepublish = null
                task()
            }
            pendingRepublish = runnable
            main.postDelayed(runnable, delayMs)
        },
        cancelScheduled = {
            pendingRepublish?.let { main.removeCallbacks(it) }
            pendingRepublish = null
        },
    )

    /** The service's client exists; call after `super.onCreate()`. */
    fun created() {
        val current = client() ?: return
        TodoControl.serviceCreated(context, current, pages::listChanged)
    }

    /** Call before `super.onDestroy()`, while the client is still open. */
    fun destroyed() {
        pages.forget()
        client()?.let(TodoControl::serviceDestroyed)
    }

    fun pageRequest(request: NexusPageRequest): NexusPage = pages.onRequest(request)

    fun pageAction(action: NexusPageAction): NexusPageResult = pages.onAction(
        frame = TodoFrame(action.sessionGeneration, action.frameIndex),
        pageId = action.pageId,
        revision = action.revision,
        actionId = action.actionId,
        invocationId = action.invocationId,
        mode = action.mode,
        typed = action.typed,
    )

    fun pageVisibility(visibility: NexusPageVisibility) =
        pages.onVisibility(TodoFrame(visibility.sessionGeneration, visibility.frameIndex), visibility.pageId, visibility.visible)

    fun pageClosed(closed: NexusPageClosed) =
        pages.onClosed(TodoFrame(closed.sessionGeneration, closed.frameIndex), closed.pageId)

    fun registrationState(result: Int) {
        // Approval is reported twice for one registration; only a new one voids the pages.
        val generation = client()?.registrationGeneration
        if (result != PluginRegistrationResult.APPROVED || generation != registrationGeneration) pages.forget()
        registrationGeneration = generation.takeIf { result == PluginRegistrationResult.APPROVED }
        TodoControl.registrationChanged(context)
    }

    fun activityClosed(reason: String) = TodoControl.activityClosed(context, reason)

    fun message(path: String, payload: JSONObject) {
        if (path == BusPaths.ERROR && payload.optString("code") == ActivitySurfaceContract.ERROR_ACTIVITY_DISABLED) {
            TodoControl.activityRefused(context)
        }
    }
}
