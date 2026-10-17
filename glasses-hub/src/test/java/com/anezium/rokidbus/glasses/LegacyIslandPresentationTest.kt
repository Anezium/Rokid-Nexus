package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.anezium.rokidbus.glasses.input.LauncherBackend
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.AmbientPolicy
import com.anezium.rokidbus.shared.AmbientPreferencesContract
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.AmbientSizes
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ForegroundAmbientMode
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * The activity islands of the classic launcher and of a pair without the launcher bundle are
 * those of the hub before the bundle: no foreground policy, no saved ambient size. Driven through
 * the production activity tier, the hub's ingress and the backend selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class LegacyIslandPresentationTest {
    class OverlayService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    private val app = RuntimeEnvironment.getApplication()
    private var render = ActivityRenderState()
    private var unobserve: (() -> Unit)? = null
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
        unobserve = ActivityController.observe { render = it }
        cxrUp(true)
        idle()
    }

    @After
    fun tearDown() {
        GlassesHub.onRemoteEnvelope(
            BusEnvelope(path = BusPaths.ACTIVITY_END, payload = ActivitySurfaceContract.emptySlotAssertPayload(SEQ.incrementAndGet())),
        )
        savePreferences(sizes = emptyMap(), modes = emptyMap(), revision = 1_000_000)
        announce(phone(bundle = false))
        cxrUp(false)
        unobserve?.invoke()
        ActivityController.onServiceDestroyed()
        AmbientForeground.onServiceDestroyed()
        NexusInput.onServiceDestroyed()
        NexusInput.restartProcess(LauncherBackend.BUNDLE_DEFAULT)
        appContextField().set(null, previousAppContext)
        idle()
    }

    @Test
    fun `over an older phone an app in front shows the panel, then the chip`() {
        announce(phone(bundle = false))
        assertEquals(LauncherBackend.LEGACY, NexusInput.backend)
        assertClassicIslandsOverAnApp()
    }

    @Test
    fun `with the glasses' explicit legacy choice an app in front shows the panel, then the chip, whatever was saved`() {
        announce(phone(bundle = true))
        NexusInput.setBackend(app, LauncherBackend.LEGACY)
        idle()
        assertEquals(LauncherBackend.LEGACY, NexusInput.backend)
        savePreferences(sizes = mapOf(OWNER to AmbientSize.PANEL), modes = mapOf(APP to ForegroundAmbientMode.NOTHING))
        assertClassicIslandsOverAnApp()
    }

    @Test
    fun `with the phone's explicit legacy choice an app in front shows the panel, then the chip, whatever was saved`() {
        announce(phone(bundle = true, backend = LauncherInteractiveContract.BACKEND_LEGACY))
        assertEquals(LauncherBackend.LEGACY, NexusInput.backend)
        savePreferences(sizes = mapOf(OWNER to AmbientSize.PANEL), modes = mapOf(APP to ForegroundAmbientMode.NOTHING))
        assertClassicIslandsOverAnApp()
    }

    @Test
    fun `the new launcher over a negotiated pair applies the app's ambient mode`() {
        announce(phone(bundle = true))
        assertEquals(LauncherBackend.SESSION, NexusInput.backend)
        startActivity()
        AmbientForeground.onWindowState(APP)
        idle()
        // No policy saved for this app: its default keeps only significant flares.
        assertEquals(ActivityPresentation.HIDDEN, island().presentation)
    }

    /** Before the bundle, an app in front was the residents' own context: panel, then chip after the collapse. */
    private fun assertClassicIslandsOverAnApp() {
        startActivity()
        AmbientForeground.onWindowState(APP)
        idle()
        assertEquals(ActivityPresentation.PANEL, island().presentation)
        assertNull(island().size)
        advance(ActivityStateMachine.COLLAPSE_AFTER_MS + 100)
        assertEquals(ActivityPresentation.CHIP, island().presentation)
        assertNull(island().size)
    }

    private fun island(): ActivityRenderItem = render.items.single { it.activity.ownerPluginId == OWNER }

    private fun startActivity() {
        val content = ActivitySurfaceContent(
            glyph = "music", primary = "Now", secondary = null, progress = null, eta = null,
            detail = emptyList(), actions = emptyList(), maxDurationMs = null,
        )
        val payload = ActivitySurfaceContract.toPayload("$OWNER:${ActivitySurfaceContract.LOCAL_SURFACE_ID}", content)
            .put("ownerPluginId", OWNER)
            .put("localSurfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
            .put("seq", SEQ.incrementAndGet())
        GlassesHub.onRemoteEnvelope(BusEnvelope(path = BusPaths.ACTIVITY_START, payload = payload))
        idle()
    }

    private fun savePreferences(sizes: Map<String, AmbientSize>, modes: Map<String, ForegroundAmbientMode>, revision: Long = 1) {
        GlassesHub.onRemoteEnvelope(
            BusEnvelope(path = AmbientPreferencesContract.SIZES_PATH, payload = AmbientPreferencesContract.sizesPayload(AmbientSizes(revision, sizes))),
        )
        GlassesHub.onRemoteEnvelope(
            BusEnvelope(path = AmbientPreferencesContract.POLICY_PATH, payload = AmbientPreferencesContract.policyPayload(AmbientPolicy(revision, modes))),
        )
        idle()
    }

    private fun phone(bundle: Boolean, backend: String? = null): JSONObject = PhoneHubCapabilitiesContract.toJson(
        PhoneHubCapabilitiesContract.create(
            features = 0,
            cameraConsumerName = null,
            pageSessionVersion = if (bundle) PageSurfaceContract.VERSION else 0,
            launcherInteractiveVersion = if (bundle) LauncherInteractiveContract.VERSION else 0,
            launcherBackend = backend,
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
        const val APP = "com.example.player"
    }
}
