package com.anezium.rokidbus.plugin.nav

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.anezium.rokidbus.client.plugin.NexusRenderedMapView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One walking journey, started by the wearer from the visible Navigation settings screen
 * (plugins/AGENTS.md, exception 8). It is a foreground service of type `location` with its own
 * notification carrying Stop, and it asks for location only while it runs.
 *
 * The route is planned once, from the first recent accurate fix, by one Transitous request; a
 * failed plan ends the journey instead of retrying. It ends on arrival, on Stop (the notification,
 * the phone panel or the glasses' route page), as soon as location permission is gone, when no
 * route could be planned in time, after a long loss of signal, and after twelve hours. It is never
 * started at boot, never restarted by the system, and keeps nothing once it ends.
 *
 * It is started with a plain `startService` from the foreground settings screen, then promotes
 * itself: a refusal (no permission, location off) can then simply stop it, with no foreground
 * promise left unkept.
 *
 * Once the route is planned, its maps draw OpenStreetMap streets ([NavBasemap]): street tiles are
 * asked for only for the authenticated, unexpired rendered map views reported by the hubs,
 * and the loader closes with the
 * journey. A tile that does not come changes nothing about guidance.
 */
class NavJourneyService : Service(), LocationListener {
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val runtime by lazy { NavRuntime(this) }
    private var destination: NavDestination? = null
    private var tracker: NavRouteTracker? = null
    private var planner: TransitousPlanner? = null
    private var basemap: NavBasemap? = null
    private val mapDemand: () -> Unit = { main.post { if (!stopping && tracker != null) publish(SystemClock.elapsedRealtime()) } }
    private var lastFix: NavFix? = null
    private var startedAtMs = 0L
    private var generation = 0
    private var stopping = false
    private var arrivalAtMs: Long? = null
    private val tick = object : Runnable {
        override fun run() {
            if (stopping || destination == null) return
            val now = SystemClock.elapsedRealtime()
            val lastFixAt = lastFix?.elapsedMs ?: startedAtMs
            when {
                !hasLocationPermission() -> finishJourney(MESSAGE_PERMISSION_LOST)
                now - startedAtMs >= MAX_JOURNEY_MS -> finishJourney("Journey time limit reached")
                tracker == null && now - startedAtMs >= MAX_PLANNING_MS -> finishJourney("Could not start guidance. Check GPS and try again.")
                arrivalAtMs?.let { now - it >= STOP_AFTER_ARRIVAL_MS } == true -> finishJourney("Arrived")
                tracker != null && now - lastFixAt >= MAX_SIGNAL_LOSS_MS -> finishJourney("GPS lost for too long. Journey ended.")
                else -> {
                    publish(now)
                    main.postDelayed(this, TICK_MS)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishJourney(MESSAGE_STOPPED)
            return START_NOT_STICKY
        }
        // One journey at a time; anything else that reaches this service starts nothing.
        val request = intent?.takeIf { it.action == ACTION_START }
        if (request == null || destination != null || stopping) {
            if (destination == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val selected = runCatching {
            NavDestination(
                request.getStringExtra(EXTRA_NAME)!!.trim().take(MAX_NAME_CHARS).ifBlank { error("unnamed") },
                GeoPoint(request.getDoubleExtra(EXTRA_LAT, Double.NaN), request.getDoubleExtra(EXTRA_LON, Double.NaN)),
            )
        }.getOrNull()
        if (selected == null) {
            finishJourney("Choose a destination first")
            return START_NOT_STICKY
        }
        if (!hasLocationPermission()) {
            finishJourney(MESSAGE_PERMISSION_NEEDED)
            return START_NOT_STICKY
        }
        val manager = getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            finishJourney("Turn on location to start walking guidance")
            return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Walking route", NotificationManager.IMPORTANCE_LOW),
            )
            destination = selected
            startForeground(NOTIFICATION_ID, notification(RouteQuality.WAITING.message), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (_: SecurityException) {
            finishJourney(MESSAGE_PERMISSION_NEEDED)
            return START_NOT_STICKY
        } catch (_: IllegalStateException) {
            // Android refused a location service from the background; the wearer can try from the screen.
            finishJourney("Could not start guidance. Reopen Navigation settings and try again.")
            return START_NOT_STICKY
        }
        startedAtMs = SystemClock.elapsedRealtime()
        NavState.journey = null
        NavState.journeyDestination = selected.name
        NavState.journeyMessage = RouteQuality.WAITING.message
        NavControl.attachJourney(runtime) { message -> main.post { finishJourney(message) } }
        NavControl.mapDemandListener = mapDemand
        runtime.onGuidance(
            NavGuidance(
                source = NavSource.NEXUS,
                glyph = NavText.ROUTE_GLYPH,
                primary = "Waiting GPS",
                secondary = NavText.fit(selected.name, 28),
                detail = listOf(RouteQuality.WAITING.message),
                stepKey = "nexus|waiting",
                instruction = RouteQuality.WAITING.message,
            ),
        )
        try {
            providers.forEach { manager.requestLocationUpdates(it, FIX_INTERVAL_MS, FIX_DISTANCE_M, this, Looper.getMainLooper()) }
            providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.elapsedRealtimeNanos }?.let(::onLocationChanged)
        } catch (_: SecurityException) {
            finishJourney(MESSAGE_PERMISSION_LOST)
            return START_NOT_STICKY
        } catch (_: Exception) {
            finishJourney("Could not start location. Check permission and GPS.")
            return START_NOT_STICKY
        }
        main.postDelayed(tick, TICK_MS)
        return START_NOT_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (stopping || destination == null) return
        val fix = runCatching {
            val moving = location.hasBearing() && location.hasSpeed() && location.speed >= MIN_BEARING_SPEED
            NavFix(
                point = GeoPoint(location.latitude, location.longitude),
                accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY,
                elapsedMs = location.elapsedRealtimeNanos / 1_000_000L,
                bearing = location.bearing.toInt().takeIf { moving },
                bearingAccuracyDeg = location.bearingAccuracyDegrees.toDouble().takeIf { moving && location.hasBearingAccuracy() },
            )
        }.getOrNull() ?: return
        val previous = lastFix
        if (previous != null && fix.elapsedMs <= previous.elapsedMs) return
        // GPS and network fixes interleave: a vague one does not displace a trusted one still recent,
        // or the position would flicker between live and stale every second.
        if (previous != null && !trusted(fix) && trusted(previous) && fix.elapsedMs - previous.elapsedMs < PREFER_TRUSTED_FIX_MS) return
        lastFix = fix
        val now = SystemClock.elapsedRealtime()
        if (tracker == null && planner == null && now - fix.elapsedMs in 0..NavRouteTracker.STALE_AFTER_MS && trusted(fix)) {
            plan(fix)
        }
        publish(now)
    }

    private fun trusted(fix: NavFix) = fix.accuracyMeters in 0.0..NavRouteTracker.MAX_ACCURACY_METERS

    /** The one planning request, from the first recent accurate fix; a failure ends the journey. */
    private fun plan(fix: NavFix) {
        val target = destination ?: return
        val request = newPlanner().also { planner = it }
        val token = generation
        NavState.journeyMessage = MESSAGE_PLANNING
        updateNotification(MESSAGE_PLANNING)
        NavControl.guidanceChanged()
        worker.execute {
            val result = runCatching { request.plan(fix.point, target) }
            main.post {
                if (stopping || generation != token) return@post
                result.fold(
                    onSuccess = { route ->
                        tracker = NavRouteTracker(route)
                        basemap = newBasemap(applicationContext, route.line, { main.post(it) }) {
                            if (!stopping) publish(SystemClock.elapsedRealtime())
                        }
                        publish(SystemClock.elapsedRealtime())
                    },
                    onFailure = {
                        Log.i(TAG, "walking plan failed cause=${it.javaClass.simpleName}")
                        finishJourney("Could not plan a walking route. Try another place or try again later.")
                    },
                )
            }
        }
    }

    private fun publish(now: Long) {
        if (stopping) return
        if (!hasLocationPermission()) {
            finishJourney(MESSAGE_PERMISSION_LOST)
            return
        }
        val current = tracker ?: return
        val snapshot = withStreets(current.update(lastFix, now))
        NavState.journey = snapshot
        NavState.journeyMessage = snapshot.quality.message
        runtime.onJourney(snapshot)
        updateNotification(snapshot.guidance().instruction ?: snapshot.quality.message)
        if (snapshot.quality == RouteQuality.ARRIVED && arrivalAtMs == null) arrivalAtMs = now
    }

    /**
     * Only hub-authenticated rendered views authorize street data. Page visibility and accepted
     * geometry say nothing about the selected map, ambient Text, collapse or foreground cover.
     */
    private fun withStreets(snapshot: NavRouteSnapshot): NavRouteSnapshot {
        val streets = basemap ?: return snapshot
        val rendered = NavControl.serviceClient?.renderedMapViews.orEmpty()
        val keysByView = rendered.associateWith {
            val view = when (it) {
                NexusRenderedMapView.FOLLOW -> NavMapView.FOLLOW
                NexusRenderedMapView.OVERVIEW -> NavMapView.OVERVIEW
            }
            NavMapProjection.tiles(snapshot, view, BASEMAP_SOURCE)
        }
        val keys = keysByView.values.flatten()
        streets.demand(keys) { key ->
            NavControl.serviceClient?.renderedMapViews.orEmpty().any { key in keysByView[it].orEmpty() }
        }
        // A shown view too wide for street tiles has none to wait for.
        NavState.basemapState = if (rendered.isNotEmpty() && keys.isEmpty()) BasemapState.UNAVAILABLE else streets.state
        return snapshot.copy(basemap = if (rendered.isEmpty()) NavBasemapTiles.NONE else streets.tiles)
    }

    private fun finishJourney(message: String) {
        if (stopping) return
        NavState.journeyMessage = message
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        if (stopping) return
        stopping = true
        generation++
        planner?.cancel()
        basemap?.close()
        basemap = null
        NavMapProjection.forgetRecent()
        NavState.basemapState = BasemapState.NONE
        if (NavControl.mapDemandListener === mapDemand) NavControl.mapDemandListener = null
        worker.shutdownNow()
        main.removeCallbacks(tick)
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
        if (NavControl.owns(runtime)) {
            // The service stays bound through the hand-back: a route another app is still guiding
            // resumes on the same registration, and only with nothing to show is it let go.
            runtime.shutdown(releaseBus = false)
            NavState.journey = null
            NavState.journeyDestination = null
            NavControl.detachJourney(runtime)
            if (NavState.guidance == null) NavControl.releaseBus(applicationContext)
        }
    }

    override fun onDestroy() {
        if (!stopping) NavState.journeyMessage = MESSAGE_STOPPED
        cleanup()
        super.onDestroy()
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun updateNotification(text: String) {
        if (stopping) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        } catch (_: SecurityException) {
            // Notifications not allowed: the journey still runs, with Stop on the phone panel and the glasses.
        }
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NavJourneyService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 2, Intent(this, NavSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.nexus_glyph_route)
            .setContentTitle("Navigation · ${destination?.name.orEmpty()}")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.anezium.rokidbus.plugin.nav.START_JOURNEY"
        const val ACTION_STOP = "com.anezium.rokidbus.plugin.nav.STOP_JOURNEY"
        const val EXTRA_NAME = "name"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val MESSAGE_STOPPED = "Journey stopped"
        const val MESSAGE_PLANNING = "Planning walking route"
        const val MESSAGE_PERMISSION_NEEDED = "Allow precise location to start walking guidance"
        const val MESSAGE_PERMISSION_LOST = "Location permission removed"

        const val MAX_JOURNEY_MS = 12L * 60L * 60L * 1000L
        const val MAX_PLANNING_MS = 120_000L
        const val STOP_AFTER_ARRIVAL_MS = 15_000L
        const val MAX_SIGNAL_LOSS_MS = 10L * 60L * 1000L
        const val TICK_MS = 5_000L

        private const val TAG = "NexusNav"
        private const val CHANNEL = "walking_route"
        private const val NOTIFICATION_ID = 41
        private const val MAX_NAME_CHARS = 160
        private const val FIX_INTERVAL_MS = 1_000L
        private const val FIX_DISTANCE_M = 2f
        private const val MIN_BEARING_SPEED = 0.5f
        private const val PREFER_TRUSTED_FIX_MS = 10_000L

        /** Builds the planner each journey uses; tests swap in canned responses. */
        @Volatile
        internal var newPlanner: () -> TransitousPlanner = { TransitousPlanner() }

        internal val BASEMAP_SOURCE: BasemapSource = OsmShortbreadSource

        /** Builds a journey's street loader once its route is planned; tests swap in a fake transport. */
        @Volatile
        internal var newBasemap: (context: Context, line: RouteLine, post: (Runnable) -> Unit, onChanged: () -> Unit) -> NavBasemap =
            { context, line, post, onChanged ->
                NavBasemap(
                    source = BASEMAP_SOURCE,
                    line = line,
                    cache = BasemapCache(File(context.cacheDir, "nav-basemap")),
                    transport = HttpTileTransport(),
                    post = post,
                    onChanged = onChanged,
                    elapsed = SystemClock::elapsedRealtime,
                )
            }
    }
}
