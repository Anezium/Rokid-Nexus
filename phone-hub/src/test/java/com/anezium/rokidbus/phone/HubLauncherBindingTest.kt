package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.Optional
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32])
class HubLauncherBindingTest {
    private class RecordingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val bound = mutableListOf<ServiceConnection>()
        val unbound = mutableListOf<ServiceConnection>()
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            assertEquals(Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT, flags)
            bound += conn
            return true
        }
        override fun unbindService(conn: ServiceConnection) { unbound += conn }
    }

    @Test
    fun `hub command glue resolves the approved owner rebinds and isolates connection death from activity death`() {
        val hub = Robolectric.buildService(BusHubService::class.java).get()
        val context = RecordingContext()
        val principal = PhonePluginPrincipal("dev.sample", ComponentName("dev.sample", "dev.sample.Service"), 10001, "digest",
            PluginDescriptor("sample", "Sample", 3, setOf(PluginCapability.SURFACES), listOf("/activity"), null, true))
        try {
            ReflectionHelpers.setField(hub, "pluginGrantStore", PluginGrantStore(context))
            ReflectionHelpers.setField(hub, "pluginDiscovery", PhonePluginDiscovery(context.packageManager))
            ReflectionHelpers.getField<MutableMap<String, Optional<PhonePluginPrincipal>>>(hub, "surfaceOwnerCache")["sample"] = Optional.of(principal)
            val originalRouter = ReflectionHelpers.getField<PhoneActivityRouter>(hub, "activityRouter")
            val launcher = ReflectionHelpers.getField<PhoneActivityLauncherHost>(originalRouter, "launcher")
            val runtime = ReflectionHelpers.callInstanceMethod<AndroidSkillLeaseRuntime>(hub, "createActivityCommandRuntime",
                ClassParameter.from(Context::class.java, context))
            ReflectionHelpers.setField(hub, "activityCommandRuntime", runtime)
            val state = PhoneActivityState(nowMs = { 1_000L }, initialSequence = 1L, launcherVersion = { 1 })
            val sink = RecordingPhoneHudRouteSink(capabilityBits = BusCapabilityBits.ACTIVITY_SURFACE)
            val router = PhoneActivityRouter(state, sink, { 1_000L }, {}, {}, launcher = object : PhoneActivityLauncherHost by launcher {
                override fun registrationCount(pluginId: String) = 1
            })
            ReflectionHelpers.setField(hub, "activityRouter", router)
            val start = JSONObject().put("ownerPluginId", "sample").put("surfaceId", "sample:activity")
                .put("localSurfaceId", "activity").put("kind", "activity").put("glyph", "straight")
                .put("primary", "Tasks").put("actions", JSONArray().put(JSONObject().put("id", "check").put("glyph", "play").put("label", "Check")))
            router.handleLocal(BusEnvelope(BusPaths.ACTIVITY_START, payload = start), hudSender("sample"))
            assertTrue(sink.errors.toString(), sink.errors.isEmpty())
            val seq = sink.remote.single().payload.getLong("seq")
            // An existing connection must also be replaced, rather than bind() returning early.
            assertTrue(runtime.bind(principal))
            val old = context.bound.single()
            router.handleGlassesAction(BusEnvelope(BusPaths.ACTIVITY_ACTION, payload = JSONObject()
                .put("activityId", "sample:activity").put("id", "check").put("invocationId", "i1").put("seq", seq).put("mode", "confirmed")))
            assertEquals(2, context.bound.size)
            assertEquals(listOf(old), context.unbound)
            val principals = ReflectionHelpers.getField<Map<String, PhonePluginPrincipal>>(hub, "activityCommandPrincipals")
            assertEquals(principal, principals["sample"])
            context.bound.last().onNullBinding(principal.serviceComponent)
            assertTrue(state.pendingCommandOwners().isEmpty())
            assertTrue(principals.isEmpty())
            assertEquals(setOf("sample"), router.ownerPluginIds())
            assertFalse(sink.remote.any { it.path == BusPaths.ACTIVITY_END })
            assertEquals(context.bound.toSet(), context.unbound.toSet())
            assertFalse(launcher.bindCommand("missing"))
        } finally {
            ReflectionHelpers.getField<ExecutorService>(hub, "executor").shutdownNow()
        }
    }
}
