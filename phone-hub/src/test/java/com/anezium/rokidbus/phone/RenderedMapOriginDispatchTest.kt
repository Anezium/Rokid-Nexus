package com.anezium.rokidbus.phone

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.GlassesHubCapabilitiesContract
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.SppAuthProtocol
import com.example.cxrglobal.CXRLink
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
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
        val serviceType = CXRLink::class.java.getDeclaredField("service").type
        val service = Proxy.newProxyInstance(serviceType.classLoader, arrayOf(serviceType)) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
        ReflectionHelpers.setField(link, "service", service)
        ReflectionHelpers.setField(link, "bound", true)
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
