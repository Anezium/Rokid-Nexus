package com.anezium.rokidbus.plugin.nav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.util.concurrent.Executors

/**
 * Destination selection and location consent stay on the phone. The panel is
 * one flow: search a place, pick it, start walking, then watch the journey it
 * started, with the live card replacing the planner while the route runs.
 * Location is asked for when Start is tapped, never before; granting it from
 * the permission row alone starts nothing.
 */
internal class NavJourneyPanel(private val activity: NavSettingsActivity) : LinearLayout(activity) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val field = NexusUi.field(activity, "Place or address")
    private val search = NexusUi.pillButton(activity, "Search")
    private val start = NexusUi.pillButton(activity, "Start walking")
    private val stop = NexusUi.pillButton(activity, "Stop journey", danger = true)
    private val change = NexusUi.textButton(activity, "Change")
    private val statusDot = NexusUi.dot(activity)
    private val status = NexusUi.statusLine(activity)
    private val results = LinearLayout(activity).apply { orientation = VERTICAL }
    private val selectedName = NexusUi.rowTitle(activity, "")
    private val selectedArea = NexusUi.rowSub(activity, "")
    private val selectedRow = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        addView(NexusUi.iconTileImage(activity, R.drawable.nexus_glyph_route, 34))
        addView(
            LinearLayout(activity).apply {
                orientation = VERTICAL
                addView(selectedName)
                addView(BusTheme.gap(activity, 3))
                addView(selectedArea)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = BusTheme.dp(activity, 12) },
        )
        addView(change)
    }
    private val planner = NexusUi.card(activity)
    private val live = NexusUi.card(activity).apply { visibility = GONE }
    private val liveDistance = NexusUi.hero(activity, 40f)
    private val liveDestination = NexusUi.rowTitle(activity, "")
    private val liveArrival = NexusUi.rowValue(activity)
    private val liveSignal = NexusUi.rowValue(activity)
    private val liveStep = NexusUi.cardBody(activity, "")
    private val permission = NexusUi.rowValue(activity)
    private var destination: NavDestination? = null
    private var request: TransitousPlanner? = null
    private var closed = false
    private var token = 0
    private var lastSearchAtMs = -5_000L
    private var startingUntilMs = 0L
    private var startAfterPermission = false
    private var localMessage: String? = null
    private var localMessageUntilMs = 0L

    init {
        orientation = VERTICAL
        planner.apply {
            addView(NexusUi.rowTitle(activity, "Where are you walking?"))
            addView(BusTheme.gap(activity, 4))
            addView(NexusUi.cardBody(activity, DISCLOSURE))
            addView(BusTheme.gap(activity, 14))
            addView(
                LinearLayout(activity).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(field, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                    addView(search, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = BusTheme.dp(activity, 8) })
                },
                NexusUi.block(),
            )
            addView(results, NexusUi.block())
            addView(selectedRow, NexusUi.block().apply { topMargin = BusTheme.dp(activity, 14) })
            addView(BusTheme.gap(activity, 14))
            addView(start, NexusUi.block())
            addView(BusTheme.gap(activity, 12))
            addView(statusRow(), NexusUi.block())
        }
        live.apply {
            addView(
                LinearLayout(activity).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.BOTTOM
                    addView(liveDistance)
                    addView(
                        NexusUi.metaLabel(activity, "left", NexusUi.INK3),
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                            marginStart = BusTheme.dp(activity, 8)
                            bottomMargin = BusTheme.dp(activity, 8)
                        },
                    )
                },
            )
            addView(BusTheme.gap(activity, 4))
            addView(liveDestination)
            addView(BusTheme.gap(activity, 12))
            addView(NexusUi.divider(activity))
            addView(valueRow("Arrival", liveArrival))
            addView(BusTheme.gap(activity, 8))
            addView(valueRow("GPS", liveSignal))
            addView(BusTheme.gap(activity, 12))
            addView(liveStep)
            addView(BusTheme.gap(activity, 14))
            addView(stop, NexusUi.block())
            addView(BusTheme.gap(activity, 10))
            addView(NexusUi.cardBody(activity, "On the glasses the route is one of your activities: open it for the map, the whole route, or End the journey."))
        }
        addView(planner, NexusUi.block())
        addView(live, NexusUi.block())
        addView(BusTheme.gap(activity, 8))
        addView(permissionRow(), NexusUi.block())
        addView(BusTheme.gap(activity, 4))
        addView(
            NexusUi.textButton(activity, "Transitous · OpenStreetMap · Data sources").apply {
                setOnClickListener { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://transitous.org/sources/"))) }
            },
        )
        addView(
            NexusUi.textButton(activity, "Map data © OpenStreetMap contributors").apply {
                setOnClickListener { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) }
            },
        )
        search.setOnClickListener { search() }
        field.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false }
        change.setOnClickListener { select(null) }
        start.setOnClickListener {
            if (locationGranted()) startJourney()
            else {
                startAfterPermission = true
                activity.requestPermissions(journeyPermissions(), LOCATION_REQUEST)
            }
        }
        stop.setOnClickListener {
            activity.startService(Intent(activity, NavJourneyService::class.java).setAction(NavJourneyService.ACTION_STOP))
            showMessage("Stopping journey")
        }
        refresh()
    }

    fun refresh() {
        val active = NavControl.journeyActive
        val snapshot = NavState.journey
        start.isEnabled = destination != null && !active && SystemClock.elapsedRealtime() >= startingUntilMs
        start.text = if (destination == null) "Choose a destination" else "Start walking"
        stop.isEnabled = active
        planner.visibility = if (active) GONE else VISIBLE
        live.visibility = if (active) VISIBLE else GONE
        permission.text = if (locationGranted()) "ALLOWED" else "NEEDED"
        permission.setTextColor(if (locationGranted()) NexusUi.GREEN_DIM else NexusUi.AMBER)
        if (active) renderLive(snapshot) else renderIdleStatus()
    }

    private fun renderIdleStatus() {
        val message = when {
            request != null -> "Searching places"
            localMessage != null && SystemClock.elapsedRealtime() < localMessageUntilMs -> localMessage!!
            else -> NavState.journeyMessage ?: "No walking journey yet"
        }
        status.text = message
        NexusUi.setDotColor(
            statusDot,
            when {
                request != null -> NexusUi.AMBER
                message.startsWith("Could not") || message.contains("unavailable") || message.contains("needed", ignoreCase = true) -> NexusUi.DANGER
                destination != null -> NexusUi.GREEN
                else -> NexusUi.INK3
            },
        )
    }

    private fun renderLive(snapshot: NavRouteSnapshot?) {
        if (snapshot == null) {
            liveDistance.text = "—"
            liveDestination.text = NavState.journeyMessage ?: "Waiting for GPS"
            liveArrival.text = "—"
            liveSignal.text = (NavState.journeyMessage ?: "Waiting for GPS").uppercase()
            liveSignal.setTextColor(NexusUi.AMBER)
            liveStep.text = "The route is planned from your first accurate fix."
            return
        }
        val guidance = snapshot.guidance()
        liveDistance.text = distanceLabel(snapshot.remainingMeters)
        liveDestination.text = "To ${snapshot.route.destination.name}"
        liveArrival.text = (guidance.eta ?: "—").uppercase()
        liveSignal.text = snapshot.quality.message.uppercase()
        liveSignal.setTextColor(
            when (snapshot.quality) {
                RouteQuality.TRACKING, RouteQuality.ARRIVED -> NexusUi.GREEN_DIM
                RouteQuality.OFF_ROUTE, RouteQuality.STALE, RouteQuality.INACCURATE -> NexusUi.AMBER
                RouteQuality.WAITING -> NexusUi.INK3
            },
        )
        liveStep.text = guidance.instruction ?: snapshot.quality.message
    }

    fun permissionResult() {
        val startRequested = startAfterPermission
        startAfterPermission = false
        if (locationGranted()) {
            if (startRequested) startJourney() else showMessage("Precise location allowed")
        }
        else showMessage("Precise location is needed for walking guidance. You can keep using notification guidance.")
        refresh()
    }

    private fun statusRow() = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(statusDot, LayoutParams(BusTheme.dp(activity, 8), BusTheme.dp(activity, 8)).apply { marginEnd = BusTheme.dp(activity, 10) })
        addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun valueRow(label: String, value: TextView) = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(NexusUi.rowLabel(activity, label), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(value)
    }

    /** Location is asked for at the moment of starting; this row only shows where that stands. */
    private fun permissionRow() = NexusUi.pressableCard(activity).apply {
        addView(
            LinearLayout(activity).apply {
                orientation = VERTICAL
                addView(NexusUi.rowTitle(activity, "Precise location"))
                addView(BusTheme.gap(activity, 4))
                addView(NexusUi.rowSub(activity, "Used only while a walking journey runs"))
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(permission)
        setOnClickListener {
            if (locationGranted()) {
                activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
            } else {
                startAfterPermission = false
                activity.requestPermissions(journeyPermissions(), LOCATION_REQUEST)
            }
        }
    }

    private fun locationGranted() = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Precise location, and on Android 13+ the journey's own notification with its Stop. */
    private fun journeyPermissions(): Array<String> = listOfNotNull(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
    ).toTypedArray()

    private fun select(place: NavDestination?) {
        destination = place
        results.removeAllViews()
        selectedRow.visibility = if (place == null) GONE else VISIBLE
        if (place != null) {
            val (name, area) = split(place.name)
            selectedName.text = name
            selectedArea.text = area ?: "Selected destination"
            localMessage = null
        }
        refresh()
    }

    private fun search() {
        if (request != null || closed) return
        val query = field.text.toString().trim()
        if (query.length !in 3..200) { showMessage("Enter a place or address (3–200 characters)"); return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSearchAtMs < 5_000L) return
        lastSearchAtMs = now
        select(null)
        search.isEnabled = false
        val current = TransitousPlanner().also { request = it }
        val generation = ++token
        refresh()
        worker.execute {
            val found = runCatching { current.search(query) }
            main.post {
                if (closed || generation != token) return@post
                request = null
                search.isEnabled = true
                found.fold(onSuccess = { places ->
                    showMessage(if (places.isEmpty()) "No matching place. Try a more precise address." else "Select a place")
                    places.forEachIndexed { index, place -> results.addView(resultRow(place, index > 0), NexusUi.block()) }
                }, onFailure = { showMessage("Search unavailable. Try again later.") })
            }
        }
    }

    private fun resultRow(place: NavDestination, divided: Boolean): View {
        val (name, area) = split(place.name)
        return LinearLayout(activity).apply {
            orientation = VERTICAL
            isClickable = true
            isFocusable = true
            background = NexusUi.pressed(activity, Color.TRANSPARENT, 10)
            setPadding(BusTheme.dp(activity, 6), BusTheme.dp(activity, 10), BusTheme.dp(activity, 6), BusTheme.dp(activity, 10))
            if (divided) addView(NexusUi.divider(activity).apply { (layoutParams as LayoutParams).setMargins(0, 0, 0, BusTheme.dp(activity, 10)) })
            addView(NexusUi.rowTitle(activity, name))
            if (area != null) {
                addView(BusTheme.gap(activity, 3))
                addView(NexusUi.rowSub(activity, area))
            }
            setOnClickListener { select(place) }
        }
    }

    /** Geocoder results come as "Name, Area, Country"; the name leads and the rest is its context. */
    private fun split(label: String): Pair<String, String?> {
        val comma = label.indexOf(", ")
        return if (comma <= 0) label to null else label.substring(0, comma) to label.substring(comma + 2).ifBlank { null }
    }

    private fun startJourney() {
        val target = destination ?: return
        if (NavControl.journeyActive || closed) return
        startingUntilMs = SystemClock.elapsedRealtime() + 3_000L
        start.isEnabled = false
        // A plain start from this visible screen: the service promotes itself, or stops when it may not.
        val launched = runCatching {
            activity.startService(
                Intent(activity, NavJourneyService::class.java).setAction(NavJourneyService.ACTION_START)
                    .putExtra(NavJourneyService.EXTRA_NAME, target.name)
                    .putExtra(NavJourneyService.EXTRA_LAT, target.point.lat)
                    .putExtra(NavJourneyService.EXTRA_LON, target.point.lon),
            )
        }.getOrNull() != null
        showMessage(if (launched) "Starting walking journey" else "Could not start guidance. Reopen this screen and try again.")
    }

    // A message outlives the one-second refresh, which would otherwise overwrite it at once.
    private fun showMessage(message: String) {
        localMessage = message
        localMessageUntilMs = SystemClock.elapsedRealtime() + 10_000L
        refresh()
    }

    fun close() {
        closed = true
        token++
        request?.cancel()
        worker.shutdownNow()
    }

    companion object {
        const val LOCATION_REQUEST = 42

        /** Everything a journey sends off the phone, said before it starts. */
        const val DISCLOSURE = "Search a place, then start. Your position and the destination go to Transitous once, to plan the route. " +
            "While a route map is on the glasses, street map tiles for the area it shows are downloaded from OpenStreetMap: " +
            "the tiles asked for reveal roughly where you are, never your exact position."
    }
}
