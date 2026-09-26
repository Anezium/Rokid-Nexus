package com.anezium.rokidbus.plugin.transit

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSkillInvocation
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.skills.SkillDispatch
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class TransitPluginService : NexusPluginService() {
    private var runtime: TransitRuntime? = null
    private var surface: NexusSurfaceSession? = null
    private var locationForeground = false
    @Volatile private var journeyActive = false
    private var seenRegistrationGeneration = -1
    private val main = Handler(Looper.getMainLooper())
    private val statusStore by lazy { TransitPluginStatusStore(applicationContext) }
    private val migrationReceiver by lazy {
        TransitLegacyMigrationReceiver(AndroidTransitMigrationStorage(applicationContext))
    }
    private val favoritesStore by lazy { TransitFavoritesStore(applicationContext) }
    private val homeStore by lazy { TransitHomeStore(applicationContext) }
    private val locationProvider by lazy { TransitLocationProvider(applicationContext) }

    // Skill calls and journey ticks run here, one at a time, never on the main thread.
    private val work = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "transit-work") }
    private var journeyTicks: ScheduledFuture<*>? = null
    private val readSkills by lazy { TransitReadSkills(favoritesStore) }
    private val journeyPlanner: TransitJourneyPlanner by lazy { TransitousJourneyPlanner() }

    private val activitySink = object : TransitJourneyActivitySink {
        override val registrationGeneration: Int
            get() = nexusClient?.registrationGeneration ?: -1

        override fun start(activity: NexusActivity): Boolean =
            nexusClient?.startActivity(activity) == NexusSdkResult.SENT

        override fun update(activity: NexusActivity, significant: Boolean, urgent: Boolean): Boolean {
            val client = nexusClient ?: return false
            return client.updateActivity(activity, significant, urgent && client.supportsActivityExtras) ==
                NexusSdkResult.SENT
        }

        override fun end(): Boolean = nexusClient?.endActivity() == NexusSdkResult.SENT
    }

    private val journeyController by lazy {
        TransitJourneyController(
            sink = activitySink,
            storage = TransitJourneyStore(applicationContext),
            planner = journeyPlanner,
            boardingDepartures = { stopId, deadlineAtMs ->
                TransitRepository(http = { url -> getWithinDeadline(url, deadlineAtMs) }).departures(stopId)
            },
            listener = { active -> main.post { onJourneyActiveChanged(active) } },
        )
    }

    private val journeySkills by lazy {
        TransitJourneySkills(
            controller = journeyController,
            planner = journeyPlanner,
            home = homeStore,
            environment = object : TransitJourneyEnvironment {
                override fun locationAccess(): TransitLocationAccess = transitLocationAccess()

                override fun canShowActivity(): Boolean {
                    val client = nexusClient ?: return false
                    return client.supportsActivitySurface && client.hasCapability(PluginCapability.SURFACES)
                }

                override fun currentLocation(timeoutMs: Long): TransitCoordinate? = runBlocking {
                    withTimeoutOrNull(timeoutMs) { locationProvider.currentLocation() }
                }
            },
        )
    }

    private val runtimeHost = object : TransitRuntimeHost {
        override fun sendCard(card: TransitCardContent, show: Boolean) {
            val session = surface ?: return
            val sdkCard = NexusCard(
                title = card.title,
                lines = emptyList(),
                footer = card.footer,
                contentKey = card.contentKey(),
                richLines = card.lines.map { line ->
                    NexusCardLine(
                        text = line.text,
                        badge = line.badge.takeIf(String::isNotBlank),
                        trail = line.trail,
                    )
                },
                handlesBack = true,
            )
            if (show) session.showCard(sdkCard) else session.updateCard(sdkCard)
        }

        override fun hideSurface() {
            surface?.hide()
        }

        override fun post(action: () -> Unit) {
            mainExecutor.execute(action)
        }

        override fun log(message: String) {
            Log.i(TAG, message)
        }

        override fun setNearMeForeground(active: Boolean): Boolean =
            if (active) startLocationForeground() else {
                stopLocationForeground()
                true
            }

        override fun journey(): JourneyState? = if (journeyActive) journeyController.active() else null

        override fun stopJourney() {
            work.execute { journeyController.stop(null) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        TransitRepository.userAgent = TransitRepository.userAgentFor(versionName ?: "unknown")
        // A journey persisted before the process went away resumes rather than being dropped.
        work.execute { journeyController.resume() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return if (journeyActive) START_STICKY else START_NOT_STICKY
    }

    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        ensureRuntime().open()
    }

    override fun onNexusClose() {
        runtime?.close()
        stopLocationForeground()
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        runtime?.input(event)
    }

    override fun onNexusRegistrationState(result: Int) {
        statusStore.setRegistration(result)
        if (result != PluginRegistrationResult.APPROVED) {
            runtime?.close()
            stopLocationForeground()
            return
        }
        // Approval is reported twice per registration; only a new one has lost the activity.
        val generation = nexusClient?.registrationGeneration ?: return
        if (generation != seenRegistrationGeneration) {
            seenRegistrationGeneration = generation
            work.execute { journeyController.onRegistrationChanged() }
        }
    }

    override fun onNexusLinkState(state: Int) {
        statusStore.setLinkState(state)
    }

    override fun onNexusActivityClosed(reason: String) {
        if (reason != ACTIVITY_CLOSED_BY_OWNER) work.execute { journeyController.onActivityLost() }
    }

    override fun onNexusMessage(path: String, id: String, payload: JSONObject) {
        if (path == BusPaths.ERROR) {
            // The hub refused an activity call, for example while only a skill call kept Transit
            // running: forget the activity so the next tick starts it again.
            if (payload.optString("code") == SKILL_LEASE_DENIED) work.execute { journeyController.onActivityLost() }
            return
        }
        if (path != TransitLegacyMigrationReceiver.IMPORT_PATH) return
        val acknowledgement = migrationReceiver.receive(payload) ?: return
        nexusClient?.send(TransitLegacyMigrationReceiver.ACK_PATH, id, acknowledgement)
    }

    override fun onNexusSkillInvoked(invocation: NexusSkillInvocation) {
        work.execute { answer(invocation, runSkill(invocation)) }
    }

    override fun onDestroy() {
        runtime?.close()
        runtime = null
        locationProvider.stopUpdates()
        journeyTicks?.cancel(false)
        work.shutdownNow()
        stopLocationForeground()
        surface = null
        statusStore.setDisconnected()
        super.onDestroy()
    }

    private fun runSkill(invocation: NexusSkillInvocation): TransitSkillOutcome {
        val deadlineAtMs = monotonicMs() + invocation.remainingMs - DEADLINE_MARGIN_MS
        val data = object : TransitSkillData {
            private val repository = TransitRepository(http = { url -> getWithinDeadline(url, deadlineAtMs) })
            override fun searchStops(query: String, limit: Int) = repository.searchStopsUpTo(query, limit)
            override fun departures(stopId: String) = repository.departures(stopId)
        }
        return try {
            when (invocation.operationId) {
                TransitSkillContract.LIST_FAVORITES -> readSkills.listFavorites(invocation.arguments)
                TransitSkillContract.SEARCH_STOPS -> readSkills.searchStops(invocation.arguments, data)
                TransitSkillContract.GET_DEPARTURES -> readSkills.getDepartures(invocation.arguments, data)
                TransitSkillContract.START_JOURNEY -> journeySkills.start(invocation.arguments, deadlineAtMs)
                TransitSkillContract.JOURNEY_STATUS -> journeySkills.status(invocation.arguments)
                TransitSkillContract.STOP_JOURNEY -> journeySkills.stop(invocation.arguments)
                else -> TransitSkillOutcome.Failed(SkillErrorCodes.UNSUPPORTED_OPERATION)
            }
        } catch (failure: Exception) {
            Log.w(TAG, "skill failed op=${invocation.operationId}: ${failure.javaClass.simpleName}")
            val dispatch = if (invocation.operationId == TransitSkillContract.START_JOURNEY ||
                invocation.operationId == TransitSkillContract.STOP_JOURNEY
            ) {
                SkillDispatch.UNKNOWN
            } else {
                SkillDispatch.NONE
            }
            TransitSkillOutcome.Failed(SkillErrorCodes.UNAVAILABLE, dispatch)
        }
    }

    private fun answer(invocation: NexusSkillInvocation, outcome: TransitSkillOutcome) {
        val result = when (outcome) {
            is TransitSkillOutcome.Completed -> invocation.complete(outcome.data)
            is TransitSkillOutcome.NeedsInput -> invocation.needsInput(outcome.reason, outcome.prompt)
            is TransitSkillOutcome.Failed -> invocation.fail(outcome.code, outcome.dispatch)
        }
        if (result != NexusSdkResult.SENT) {
            Log.w(TAG, "skill answer not sent op=${invocation.operationId} result=$result")
            if (result == NexusSdkResult.INVALID_PAYLOAD) {
                invocation.fail(SkillErrorCodes.UNAVAILABLE, SkillDispatch.UNKNOWN)
            }
        }
    }

    /**
     * A journey holds the one Transit foreground service, with the location type, for its whole
     * duration, plus position updates and a tick; all of it is released at arrival or stop.
     */
    private fun onJourneyActiveChanged(active: Boolean) {
        if (active == journeyActive) return
        journeyActive = active
        if (active) {
            if (!holdNexusOngoingWork(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)) {
                Log.w(TAG, "journey foreground refused; guidance continues while Transit stays bound")
            }
            locationProvider.startUpdates(POSITION_INTERVAL_MS) { fix ->
                work.execute { journeyController.onPosition(fix) }
            }
            journeyTicks?.cancel(false)
            journeyTicks = work.scheduleWithFixedDelay(
                { journeyController.onTick() },
                TICK_MS,
                TICK_MS,
                TimeUnit.MILLISECONDS,
            )
        } else {
            locationProvider.stopUpdates()
            journeyTicks?.cancel(false)
            journeyTicks = null
            releaseNexusOngoingWork()
            if (locationForeground) promoteNexusSessionForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }
    }

    private fun ensureRuntime(): TransitRuntime = runtime ?: TransitRuntime(
        host = runtimeHost,
        dependencies = TransitDependencies(
            repository = TransitRepository(),
            location = locationProvider,
            favorites = favoritesStore,
        ),
    ).also { runtime = it }

    private fun startLocationForeground(): Boolean {
        val access = transitLocationAccess()
        if (access != TransitLocationAccess.READY) {
            Log.w(TAG, "Location foreground blocked access=$access")
            return false
        }
        if (locationForeground) return true
        val started = promoteNexusSessionForeground(
            additionalTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            onFailure = { failure ->
                Log.w(TAG, "Location foreground start rejected: ${failure.javaClass.simpleName}")
            },
        )
        if (started) {
            locationForeground = true
            return true
        }
        locationForeground = false
        promoteNexusSessionForeground()
        return false
    }

    private fun stopLocationForeground() {
        if (!locationForeground) return
        locationForeground = false
        when {
            // The journey keeps the same service, with its location type, until it ends.
            journeyActive -> promoteNexusSessionForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            isNexusSessionOpen -> promoteNexusSessionForeground()
            else -> stopNexusSessionForeground()
        }
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val TAG = "NexusTransit"
        const val SURFACE_ID = "transit"
        const val ACTIVITY_CLOSED_BY_OWNER = "owner"
        const val SKILL_LEASE_DENIED = "SKILL_LEASE_DENIED"
        const val POSITION_INTERVAL_MS = 10_000L
        const val TICK_MS = 15_000L

        // Answering takes a moment; keep network work clear of the hub's own deadline.
        const val DEADLINE_MARGIN_MS = 400L
    }
}
