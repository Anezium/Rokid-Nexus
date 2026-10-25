package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidExternalPluginRuntimeTest {
    private class RecordingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        lateinit var connection: ServiceConnection
        var unbindCount = 0
        var bindFlags = 0
        var bindAccepted = true
        val calls = mutableListOf<String>()

        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            calls += "bind"
            if (bindAccepted) connection = conn
            bindFlags = flags
            return bindAccepted
        }

        override fun unbindService(conn: ServiceConnection) {
            assertTrue(conn === connection)
            calls += "unbind"
            unbindCount += 1
        }
    }

    private val principal = PhonePluginPrincipal(
        packageName = "dev.lens",
        serviceComponent = ComponentName("dev.lens", "dev.lens.Service"),
        uid = 10001,
        signingDigestSha256 = "digest",
        descriptor = PluginDescriptor(
            id = "lens",
            displayName = "Lens",
            apiVersion = 3,
            requestedCapabilities = setOf(PluginCapability.CAMERA),
            receivePrefixes = listOf("/system/plugin", "/camera/session/state"),
            settingsActivity = null,
            launchable = false,
        ),
    )

    @Test
    fun `page delivery rebind replaces a held connection with a fresh bind event`() {
        val calls = mutableListOf<String>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            val connections = mutableListOf<ServiceConnection>()
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
                assertEquals(Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT, flags)
                connections += conn
                calls += "bind"
                return true
            }
            override fun unbindService(conn: ServiceConnection) { calls += "unbind" }
        }
        val disconnected = mutableListOf<String>()
        val runtime = runtime(context, disconnected)
        assertTrue(runtime.bind(principal))
        val original = context.connections.single()
        assertTrue(runtime.rebind(principal))
        assertTrue(original !== context.connections.last())
        assertEquals(listOf("bind", "bind", "unbind"), calls)
        original.onServiceDisconnected(principal.serviceComponent)
        assertTrue(disconnected.isEmpty())
        runtime.unbind(principal)
        assertEquals(listOf("bind", "bind", "unbind", "unbind"), calls)
    }

    @Test
    fun `a refused page rebind preserves the existing service hold`() {
        val context = RecordingContext()
        val runtime = runtime(context, mutableListOf())
        assertTrue(runtime.bind(principal))
        context.bindAccepted = false
        assertTrue(!runtime.rebind(principal))
        assertEquals(0, context.unbindCount)
        runtime.unbind(principal)
        assertEquals(1, context.unbindCount)
    }

    @Test
    fun `transient service disconnect preserves auto create binding`() {
        val context = RecordingContext()
        val disconnected = mutableListOf<String>()
        val runtime = runtime(context, disconnected)

        assertTrue(runtime.bind(principal))
        assertEquals(Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT, context.bindFlags)
        context.connection.onServiceDisconnected(principal.serviceComponent)

        assertEquals(listOf("lens"), disconnected)
        assertEquals(0, context.unbindCount)
        assertTrue(runtime.bind(principal))
        val first = context.connection
        runtime.unbind(principal)
        assertEquals(1, context.unbindCount)
        assertTrue(runtime.bind(principal))
        assertTrue(context.connection !== first)
    }

    private fun runtime(
        context: Context,
        disconnected: MutableList<String>,
    ) = AndroidExternalPluginRuntime(
        context = context,
        isRegisteredCallback = { false },
        deliverCallback = { _, _, _, _ -> false },
        hideCallback = {},
        disconnectedCallback = { disconnected += it.descriptor.id },
    )

    @Test
    fun `a separate runtime keeps its own connection to the same plugin`() {
        val connections = mutableListOf<ServiceConnection>()
        val unbound = mutableListOf<ServiceConnection>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
                connections += conn
                return true
            }

            override fun unbindService(conn: ServiceConnection) {
                unbound += conn
            }
        }
        val immersion = AndroidExternalPluginRuntime(context, { true }, { _, _, _, _ -> true }, hideCallback = {}, disconnectedCallback = {})
        val pages = AndroidExternalPluginRuntime(context, { true }, { _, _, _, _ -> false }, hideCallback = {}, disconnectedCallback = {})

        assertTrue(immersion.bind(principal))
        assertTrue(pages.bind(principal))
        assertEquals(2, connections.size)
        assertTrue(connections[0] !== connections[1])

        // Releasing the page binding leaves the immersion's binding in place, and the reverse.
        pages.unbind(principal)
        assertEquals(listOf(connections[1]), unbound)
        immersion.unbind(principal)
        assertEquals(listOf(connections[1], connections[0]), unbound)
    }
}
