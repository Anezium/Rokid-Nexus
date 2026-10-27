package com.anezium.rokidbus.phone

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.GlassesHubCapabilitiesContract
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.RenderedMapContext
import com.anezium.rokidbus.shared.RenderedMapContract
import com.anezium.rokidbus.shared.SppAuthProtocol
import com.example.cxrglobal.CXRLink
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBluetoothAdapter
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.lang.reflect.Proxy
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class RenderedMapOriginDispatchTest {
    @Implements(BluetoothAdapter::class)
    class GuardedBluetoothAdapter : ShadowBluetoothAdapter() {
        var forbidDiscovery = false

        @Implementation
        override fun getBondedDevices(): Set<BluetoothDevice>? {
            check(!forbidDiscovery) { "Render feedback must not discover bonded Bluetooth devices" }
            return super.getBondedDevices()
        }
    }

    @Test
    @Config(shadows = [GuardedBluetoothAdapter::class])
    fun `page and ambient report dispatch use live negotiation without Bluetooth discovery`() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = shadowOf(BluetoothAdapter.getDefaultAdapter()) as GuardedBluetoothAdapter
        adapter.setState(BluetoothAdapter.STATE_ON)
        val hub = Robolectric.buildService(BusHubService::class.java).get()
        val executor = ReflectionHelpers.getField<ExecutorService>(hub, "executor")
        ReflectionHelpers.setField(hub, "executor", QueuedExecutor())
        ReflectionHelpers.setField(hub, "hubEnabled", false)
        var fixtureSocket: BluetoothSocket? = null
        try {
            val (socket, session) = connect(hub)
            fixtureSocket = socket
            dispatchSpp(hub, session, peer(true))
            adapter.forbidDiscovery = true
            assertTrue(ReflectionHelpers.callInstanceMethod(hub, "renderedMapsSupported"))
            for (kind in listOf("page", "ambient")) {
                val event = RenderedMapContext(kind, if (kind == "page") "nav:main" else "nav:activity", 1,
                    seq = 1, sessionGeneration = 1, frameIndex = 1, requestId = "r1").toPayload()
                    .put("visible", true).put("view", "follow").put("token", "drawn")
                ReflectionHelpers.callInstanceMethod<Unit>(hub, "routeSpp",
                    ClassParameter.from(BusEnvelope::class.java, BusEnvelope(RenderedMapContract.PATH, payload = event)),
                    ClassParameter.from(SppAuthProtocol.Session::class.java, session))
            }
            val logs = ShadowLog.getLogsForTag("ROKIDBUS-PHONE").map { it.msg }
            assertTrue("page validation must run after the fast capability gate",
                logs.any { it.contains("gate=PHONE_PAGE verdict=DROP reason=FRAME") })
            assertTrue("ambient validation must run after the fast capability gate",
                logs.any { it.contains("gate=PHONE_AMBIENT verdict=DROP reason=IDENTITY") })
            val negotiation = ReflectionHelpers.getField<PhoneRenderedMapNegotiation>(hub, "renderedMapNegotiation")
            negotiation.lost()
            assertFalse("retirement immediately disables support", ReflectionHelpers.callInstanceMethod(hub, "renderedMapsSupported"))
            negotiation.sessionStarted(session)
            assertFalse("replacement needs fresh negotiation", ReflectionHelpers.callInstanceMethod(hub, "renderedMapsSupported"))
            negotiation.announce(GlassesHubCapabilitiesContract.parse(peer(true)), PhoneRenderedMapNegotiation.Origin.Spp(session))
            assertTrue(ReflectionHelpers.callInstanceMethod(hub, "renderedMapsSupported"))
            ReflectionHelpers.setField(hub, "socket", null)
            assertFalse("a session without a live socket is insufficient", ReflectionHelpers.callInstanceMethod(hub, "renderedMapsSupported"))
        } finally {
            adapter.forbidDiscovery = false
            close(hub, null)
            fixtureSocket?.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `old reader announcement paused in service dispatch cannot authorize a replacement while CXR stays up`() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setState(BluetoothAdapter.STATE_ON)
        for (authenticateBeforeResume in listOf(false, true)) {
            val hub = Robolectric.buildService(BusHubService::class.java).get()
            val originalExecutor = ReflectionHelpers.getField<ExecutorService>(hub, "executor")
            // Leave ancillary announcement work queued; no service/radio/update startup is run.
            ReflectionHelpers.setField(hub, "executor", QueuedExecutor())
            ReflectionHelpers.setField(hub, "hubEnabled", false)
            bindCxrFixture(hub)
            val reader = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            try {
                val (oldSocket, oldSession) = connect(hub)
                dispatchSpp(hub, oldSession, peer(true))
                assertRendered(hub, true)
                val payload = object : JSONObject(peer(true).toString()) {
                    override fun optInt(name: String, fallback: Int): Int {
                        if (name == "version") {
                            entered.countDown()
                            check(resume.await(5, TimeUnit.SECONDS)) { "Retired reader was not resumed" }
                        }
                        return super.optInt(name, fallback)
                    }
                }
                // This is the same dispatch method called by readSppLoop after authenticated decode.
                val pending = reader.submit { dispatchSpp(hub, oldSession, payload) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                close(hub, oldSocket)
                assertTrue(ReflectionHelpers.callInstanceMethod(hub, "isCxrUp"))
                assertRendered(hub, false)
                var replacement: Pair<BluetoothSocket, SppAuthProtocol.Session>? = null
                if (authenticateBeforeResume) replacement = connect(hub)
                resume.countDown()
                pending.get(5, TimeUnit.SECONDS)
                // The retired read loop's finally must not retire a replacement socket.
                close(hub, oldSocket)
                if (replacement == null) replacement = connect(hub)
                assertRendered(hub, false)
                val current = replacement.second
                dispatchSpp(hub, current, peer(false))
                assertRendered(hub, false)
                dispatchSpp(hub, current, peer(true))
                assertRendered(hub, true)
                // A fresh CXR-first announcement in the still-current CXR generation is valid.
                close(hub, replacement.first)
                dispatchCxr(hub, peer(true))
                assertRendered(hub, false)
                val next = connect(hub)
                assertRendered(hub, true)
                close(hub, next.first)
            } finally {
                resume.countDown()
                reader.shutdownNow()
                close(hub, null)
                originalExecutor.shutdownNow()
            }
        }
    }

    private fun peer(current: Boolean) = JSONObject()
        .put("version", GlassesHubCapabilitiesContract.VERSION)
        .put("features", BusCapabilityBits.LAUNCHER_INTERACTIVE or if (current) BusCapabilityBits.RENDERED_MAP else 0)
        .put(LauncherInteractiveContract.CAPABILITY_FIELD_VERSION, LauncherInteractiveContract.VERSION)

    private fun assertRendered(hub: BusHubService, expected: Boolean) {
        val bits = ReflectionHelpers.callInstanceMethod<Int>(hub, "capabilities")
        assertNotEquals(0, bits and BusCapabilityBits.LAUNCHER_INTERACTIVE)
        assertEquals(if (expected) BusCapabilityBits.RENDERED_MAP else 0, bits and BusCapabilityBits.RENDERED_MAP)
    }

    private fun dispatchSpp(hub: BusHubService, session: SppAuthProtocol.Session, payload: JSONObject) {
        ReflectionHelpers.callInstanceMethod<Unit>(hub, "routeSpp",
            ClassParameter.from(BusEnvelope::class.java, BusEnvelope(BusPaths.HUB_CAPABILITIES, payload = payload)),
            ClassParameter.from(SppAuthProtocol.Session::class.java, session))
    }

    private fun dispatchCxr(hub: BusHubService, payload: JSONObject) {
        val origin = ReflectionHelpers.callInstanceMethod<PhoneRenderedMapNegotiation.Origin>(hub, "renderedMapCxrOrigin")
        ReflectionHelpers.callInstanceMethod<Unit>(hub, "routeRemote",
            ClassParameter.from(BusEnvelope::class.java, BusEnvelope(BusPaths.HUB_CAPABILITIES, payload = payload)),
            ClassParameter.from(PhoneRenderedMapNegotiation.Origin::class.java, origin))
    }

    private fun bindCxrFixture(hub: BusHubService) {
        val link = CXRLink(hub)
        // CxrGlobal 0.3.0 keeps the service on the link's current bind, one per bindService call.
        val bindType = CXRLink::class.java.declaredClasses.single { it.simpleName == "Bind" }
        val serviceType = bindType.getDeclaredField("service").type
        val service = Proxy.newProxyInstance(serviceType.classLoader, arrayOf(serviceType)) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
        val bind = bindType.getDeclaredConstructor(CXRLink::class.java).apply { isAccessible = true }.newInstance(link)
        ReflectionHelpers.setField(bind, "service", service)
        ReflectionHelpers.setField(link, "current", bind)
        ReflectionHelpers.setField(hub, "cxrLink", link)
        ReflectionHelpers.setField(hub, "cxrConnected", true)
        ReflectionHelpers.setField(hub, "glassBtConnected", true)
        assertTrue(ReflectionHelpers.callInstanceMethod(hub, "isCxrUp"))
    }

    private fun connect(hub: BusHubService): Pair<BluetoothSocket, SppAuthProtocol.Session> {
        val socket = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:00:00:00:00:01")
            .createInsecureRfcommSocketToServiceRecord(BusConstants.SPP_UUID)
        socket.connect()
        // Authentication is the fixture boundary; dispatch and retirement execute real hub code.
        val constructor = SppAuthProtocol.Session::class.java.getDeclaredConstructor(ByteArray::class.java, ByteArray::class.java)
            .apply { isAccessible = true }
        val session = constructor.newInstance(ByteArray(32), ByteArray(32))
        val negotiation = ReflectionHelpers.getField<PhoneRenderedMapNegotiation>(hub, "renderedMapNegotiation")
        synchronized(ReflectionHelpers.getField<Any>(hub, "sppLock")) {
            negotiation.sessionStarted(session)
            ReflectionHelpers.setField(hub, "socket", socket)
            ReflectionHelpers.setField(hub, "sppSession", session)
        }
        return socket to session
    }

    private fun close(hub: BusHubService, socket: BluetoothSocket?) {
        ReflectionHelpers.callInstanceMethod<Unit>(hub, "closeSocket", ClassParameter.from(BluetoothSocket::class.java, socket))
    }

    private class QueuedExecutor : AbstractExecutorService() {
        private val tasks = mutableListOf<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { tasks += command }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return tasks.toMutableList().also { tasks.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }
}
