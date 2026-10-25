package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AndroidSkillLeaseRuntimeTest {
    private class RecordingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val bindings = mutableListOf<Triple<Intent, ServiceConnection, Int>>()
        val unbound = mutableListOf<ServiceConnection>()
        val calls = mutableListOf<String>()
        var accepted = true

        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            bindings += Triple(service, conn, flags)
            calls += "bind"
            return accepted
        }

        override fun unbindService(conn: ServiceConnection) {
            unbound += conn
            calls += "unbind"
        }
    }

    private val principal = PhonePluginPrincipal(
        packageName = "dev.sample",
        serviceComponent = ComponentName("dev.sample", "dev.sample.Service"),
        uid = 10001,
        signingDigestSha256 = "digest",
        descriptor = PluginDescriptor(
            id = "sample",
            displayName = "Sample",
            apiVersion = 3,
            requestedCapabilities = setOf(PluginCapability.SURFACES),
            receivePrefixes = listOf("/system/plugin", "/activity"),
            settingsActivity = null,
            launchable = true,
        ),
    )

    @Test
    fun `commands inherit importance without releasing page or skill bindings`() {
        val context = RecordingContext()
        val pages = AndroidExternalPluginRuntime(context, { true }, { _, _, _, _ -> true }, hideCallback = {}, disconnectedCallback = {})
        val skills = AndroidSkillLeaseRuntime(context) {}
        val commands = AndroidSkillLeaseRuntime(context) {}
        assertTrue(pages.bind(principal))
        assertTrue(skills.bind(principal))
        assertTrue(commands.bind(principal))
        assertTrue(commands.bind(principal))
        assertEquals(3, context.bindings.size)
        context.bindings.forEach { (intent, _, flags) ->
            assertEquals(BusConstants.ACTION_PLUGIN, intent.action)
            assertEquals(principal.serviceComponent, intent.component)
            assertEquals(Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT, flags)
        }

        commands.unbind(principal)
        commands.unbind(principal)
        assertEquals(listOf(context.bindings[2].second), context.unbound)
        pages.unbind(principal)
        skills.unbind(principal)
        assertEquals(context.bindings.map { it.second }.toSet(), context.unbound.toSet())
    }

    @Test
    fun `delivery rebind creates a new connection and ignores an obsolete binding death`() {
        val context = RecordingContext()
        val died = mutableListOf<PhonePluginPrincipal>()
        val runtime = AndroidSkillLeaseRuntime(context) { died += it }
        assertTrue(runtime.bind(principal))
        val old = context.bindings.last().second
        assertTrue(runtime.rebind(principal))
        val fresh = context.bindings.last().second
        assertTrue(fresh !== old)
        assertEquals(listOf(old), context.unbound)
        assertEquals(listOf("bind", "bind", "unbind"), context.calls)
        old.onBindingDied(principal.serviceComponent)
        assertTrue(died.isEmpty())
        fresh.onBindingDied(principal.serviceComponent)
        assertEquals(listOf(principal), died)
        runtime.unbind(principal)
        assertEquals(1, context.unbound.count { it === fresh })
    }

    @Test
    fun `a refused command rebind keeps the previous hold until its normal release`() {
        val context = RecordingContext()
        val runtime = AndroidSkillLeaseRuntime(context) {}
        assertTrue(runtime.bind(principal))
        val first = context.bindings.single().second
        context.accepted = false
        assertFalse(runtime.rebind(principal))
        assertTrue(context.unbound.isEmpty())
        runtime.unbind(principal)
        assertEquals(listOf(first), context.unbound)
    }

    @Test
    fun `a refused bind leaves no hold and a dead binding releases its owner`() {
        val context = RecordingContext()
        val died = mutableListOf<PhonePluginPrincipal>()
        val commands = AndroidSkillLeaseRuntime(context) { died += it }
        context.accepted = false
        assertFalse(commands.bind(principal))
        commands.unbind(principal)
        assertTrue(context.unbound.isEmpty())

        context.accepted = true
        assertTrue(commands.bind(principal))
        val connection = context.bindings.last().second
        connection.onNullBinding(principal.serviceComponent)
        assertEquals(listOf(principal), died)
        assertEquals(listOf(connection), context.unbound)
        commands.unbind(principal)
        assertEquals(1, context.unbound.size)
    }
}
