package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import com.anezium.rokidbus.glasses.input.LauncherBackend
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.AmbientPolicy
import com.anezium.rokidbus.shared.AmbientPreferencesContract
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ForegroundAmbientMode
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract
import com.anezium.rokidbus.shared.PinSurfaceContent
import com.anezium.rokidbus.shared.PinSurfaceContract
import com.anezium.rokidbus.shared.PinSurfaceLine
import com.anezium.rokidbus.shared.PinSurfacePosition
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowPowerManager
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * "Float over the glasses" off on the phone: the owner's island and pin leave the home and every
 * app, its card stays, and back on brings both again without the plugin sending anything. Driven
 * through the hub's ingress, the production activity tier and the pin controller.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class KeptOffGlassesPresentationTest {
    class OverlayService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    private val app = RuntimeEnvironment.getApplication()
    private var render = ActivityRenderState()
    private var drawnPin: NexusPinSurface? = null
    private val unobserve = mutableListOf<() -> Unit>()
    private var previousAppContext: Any? = null

    @Before
    fun setUp() {
        previousAppContext = appContextField().get(null)
        appContextField().set(null, app)
        val service = Robolectric.setupService(OverlayService::class.java)
        NexusInput.restartProcess(LauncherBackend.BUNDLE_DEFAULT)
        ActivityController.onServiceConnected(app)
        AmbientForeground.onServiceConnected(app)
        NexusInput.onServiceConnected(service)
        unobserve += ActivityController.observe { render = it }
        unobserve += PinController.observe { drawnPin = it }
        cxrUp(true)
        announce(phone(bundle = true))
        assertTrue(ActivityController.launcherAmbient())
    }

    @After
    fun tearDown() {
        GlassesHub.onRemoteEnvelope(
            BusEnvelope(path = BusPaths.ACTIVITY_END, payload = ActivitySurfaceContract.emptySlotAssertPayload(SEQ.incrementAndGet())),
        )
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.PIN_HIDE, payload = pinPayload().put("seq", SEQ.incrementAndGet())))
        policy(hidden = emptySet(), revision = Long.MAX_VALUE / 2)
        announce(phone(bundle = false))
        cxrUp(false)
        unobserve.forEach { it() }
        ActivityController.onServiceDestroyed()
        AmbientForeground.onServiceDestroyed()
        NexusInput.onServiceDestroyed()
        NexusInput.restartProcess(LauncherBackend.BUNDLE_DEFAULT)
        appContextField().set(null, previousAppContext)
        idle()
    }

    @Test
    fun `its island leaves the home and every app while its card stays, and comes back unasked`() {
        startActivity(OWNER)
        startActivity(OTHER)
        assertNotNull(island(OWNER))

        policy(hidden = setOf(OWNER))
        assertNull(island(OWNER))
        // The other owner's island expands in its place; the card list is untouched.
        assertEquals(OTHER, render.primary?.activity?.ownerPluginId)
        assertEquals(listOf(OWNER, OTHER), render.collection.map { it.ownerPluginId })

        // A significant update brings no flare either, over the home or over an app the user
        // let residents float over.
        update(OWNER, significant = true)
        assertNull(island(OWNER))
        AmbientForeground.onWindowsChanged(listOf(ForegroundWindow(APP, isApplication = true)))
        policy(hidden = setOf(OWNER), modes = mapOf(APP to ForegroundAmbientMode.RESIDENTS))
        update(OWNER, significant = true)
        assertNull(island(OWNER))
        assertNotNull(island(OTHER))
        // Its card took the update.
        assertEquals(listOf(OWNER, OTHER), render.collection.map { it.ownerPluginId })
        assertTrue(render.collection.first().content.primary.startsWith("Next"))

        policy(hidden = emptySet())
        assertNotNull(island(OWNER))
    }

    @Test
    fun `its pin is held but not drawn, and drawn again when it may float`() {
        showPin()
        assertEquals(PIN_ID, drawnPin?.surfaceId)

        policy(hidden = setOf(OWNER))
        assertNull(drawnPin)
        assertNull(PinController.visiblePin())
        // Its corner is free for an island.
        startActivity(OTHER)
        assertEquals(PinSurfacePosition.TOP_LEFT, island(OTHER)?.activity?.corner)

        policy(hidden = emptySet())
        assertEquals(PIN_ID, drawnPin?.surfaceId)
        assertEquals(PIN_ID, PinController.visiblePin()?.surfaceId)
    }

    @Test
    fun `a pin it may not float leaves room for another owner's map, and takes it back when it may`() {
        showPin()
        startActivity(OTHER)
        startActivity(THIRD)
        assertNotNull(island(OTHER))
        assertNotNull(island(THIRD))
        assertTrue(ActivityOverlayRenderer.mapCrowded(render, PinController.presentablePin() != null))

        policy(hidden = setOf(OWNER))
        // The pin is still held, only not drawn: the two islands alone leave the map its height.
        assertEquals(PIN_ID, PinController.activePin()?.surfaceId)
        assertFalse(ActivityOverlayRenderer.mapCrowded(render, PinController.presentablePin() != null))

        policy(hidden = emptySet())
        assertTrue(ActivityOverlayRenderer.mapCrowded(render, PinController.presentablePin() != null))
    }

    @Test
    fun `the classic launcher and an older phone ignore the choice, like the other ambient preferences`() {
        startActivity(OWNER)
        showPin()
        policy(hidden = setOf(OWNER))
        assertNull(island(OWNER))
        assertNull(drawnPin)

        NexusInput.setBackend(app, LauncherBackend.LEGACY)
        idle()
        assertNotNull(island(OWNER))
        assertEquals(PIN_ID, drawnPin?.surfaceId)

        NexusInput.setBackend(app, LauncherBackend.SESSION)
        idle()
        assertNull(island(OWNER))
        assertNull(drawnPin)

        // An older phone cannot change the choice, so the glasses do not apply it either.
        announce(phone(bundle = false))
        assertNotNull(island(OWNER))
        assertEquals(PIN_ID, drawnPin?.surfaceId)
    }

    @Test
    fun `an update asking to wake the display wakes it only for an owner that may float`() {
        startActivity(OWNER, wakeDisplay = true)
        policy(hidden = setOf(OWNER))
        ShadowPowerManager.reset()
        shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(false)
        advance(DisplayWakePolicy.BUDGET_WINDOW_MS + 100)

        update(OWNER, significant = true)
        assertNull(ShadowPowerManager.getLatestWakeLock())

        policy(hidden = emptySet())
        update(OWNER, significant = true)
        assertNotNull(ShadowPowerManager.getLatestWakeLock())
    }

    private fun island(owner: String): ActivityRenderItem? = render.items.singleOrNull { it.activity.ownerPluginId == owner }

    private fun content(primary: String, wakeDisplay: Boolean = false) = ActivitySurfaceContent(
        glyph = "music", primary = primary, secondary = null, progress = null, eta = null,
        detail = emptyList(), actions = emptyList(), maxDurationMs = null, wakeDisplay = wakeDisplay,
    )

    private fun startActivity(owner: String, wakeDisplay: Boolean = false) {
        val payload = ActivitySurfaceContract.toPayload("$owner:${ActivitySurfaceContract.LOCAL_SURFACE_ID}", content("Now", wakeDisplay))
            .put("ownerPluginId", owner)
            .put("localSurfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
            .put("seq", SEQ.incrementAndGet())
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.ACTIVITY_START, payload = payload))
        idle()
    }

    private fun update(owner: String, significant: Boolean) {
        val payload = ActivitySurfaceContract.toUpdatePayload(
            "$owner:${ActivitySurfaceContract.LOCAL_SURFACE_ID}",
            content("Next ${SEQ.get() % 100}"),
            significant = significant,
        )
            .put("ownerPluginId", owner)
            .put("localSurfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
            .put("seq", SEQ.incrementAndGet())
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.ACTIVITY_UPDATE, payload = payload))
        idle()
    }

    private fun showPin() {
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.PIN_SHOW, payload = pinPayload().put("seq", SEQ.incrementAndGet())))
        idle()
    }

    private fun pinPayload(): JSONObject = PinSurfaceContract.toPayload(
        PIN_ID,
        PinSurfaceContent(title = "PIN", lines = listOf(PinSurfaceLine("held")), position = PinSurfacePosition.TOP_LEFT, ttlMs = null),
    )
        .put("ownerPluginId", OWNER)
        .put("localSurfaceId", PinSurfaceContract.LOCAL_SURFACE_ID)

    private fun policy(
        hidden: Set<String>,
        modes: Map<String, ForegroundAmbientMode> = emptyMap(),
        revision: Long = SEQ.incrementAndGet(),
    ) {
        GlassesHub.onRemoteEnvelope(
            BusEnvelope(
                path = AmbientPreferencesContract.POLICY_PATH,
                payload = AmbientPreferencesContract.policyPayload(AmbientPolicy(revision, modes, hidden)),
            ),
        )
        idle()
    }

    private fun phone(bundle: Boolean): JSONObject = PhoneHubCapabilitiesContract.toJson(
        PhoneHubCapabilitiesContract.create(
            features = 0,
            cameraConsumerName = null,
            pageSessionVersion = if (bundle) PageSurfaceContract.VERSION else 0,
            launcherInteractiveVersion = if (bundle) LauncherInteractiveContract.VERSION else 0,
        ),
    )

    private fun announce(payload: JSONObject) {
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.HUB_CAPABILITIES, payload = payload))
        idle()
    }

    private fun cxrUp(up: Boolean) {
        CxrBusBridge::class.java.getDeclaredField("connected").apply { isAccessible = true }.setBoolean(null, up)
        GlassesHub.onCxrState(up)
        idle()
    }

    private fun appContextField() = GlassesHub::class.java.getDeclaredField("appContext").apply { isAccessible = true }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private companion object {
        val SEQ = AtomicLong(System.nanoTime())
        const val OWNER = "media"
        const val OTHER = "transit"
        const val THIRD = "nav"
        const val APP = "com.example.player"
        val PIN_ID = "$OWNER:${PinSurfaceContract.LOCAL_SURFACE_ID}"
    }
}
