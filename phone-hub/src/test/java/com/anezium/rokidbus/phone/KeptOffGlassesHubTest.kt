package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.AmbientPolicy
import com.anezium.rokidbus.shared.AmbientPreferencesContract
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The hub's side of "Float over the glasses": which owners the ambient policy names, and that a
 * grant going away sends the glasses a policy without the owner at once. The hub is not started;
 * its stores are wired directly and its approved-owner cache stands in for a granted plugin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class KeptOffGlassesHubTest {
    private val app = RuntimeEnvironment.getApplication()
    private val hub = Robolectric.buildService(BusHubService::class.java).get()
    private val policies = LinkedBlockingQueue<AmbientPolicy>()
    private val preferences = PhoneActivityPreferences(MemoryStorage())
    private val nav = principal("nav")

    @Before
    fun wireTheStoresWithoutStartingTheHub() {
        set("activityPreferences", preferences)
        set("quickActionPolicyStore", PluginQuickActionPolicyStore(MemoryStorage()))
        set("pluginGrantStore", PluginGrantStore(app))
        set("pluginDiscovery", PhonePluginDiscovery(app.packageManager))
        val owners = BusHubService::class.java.getDeclaredMethod("ownersKeptOffGlasses").apply { isAccessible = true }
        set(
            "ambientSync",
            PhoneAmbientSync(
                store = PhoneAmbientPreferencesStore(MemoryStorage()),
                send = { path, payload ->
                    if (path == AmbientPreferencesContract.POLICY_PATH) policies += AmbientPreferencesContract.parsePolicy(payload)!!
                    null
                },
                launcherActive = { true },
                log = {},
                hiddenOwners = { (owners.invoke(hub) as Set<*>).mapTo(linkedSetOf()) { it as String } },
            ),
        )
        surfaceOwners()["nav"] = Optional.of(nav)
    }

    @Test
    fun `a plugin kept off the glasses is named until its grant goes, then the glasses hear at once`() {
        preferences.setFloating(nav.grantKey(), false)
        pushPolicy()
        assertEquals(setOf("nav"), policies.poll(5, TimeUnit.SECONDS)!!.hiddenOwners)

        // Revoked: the hub drops its owner cache, then the plugin's choices.
        surfaceOwners().clear()
        call("purgeActivityChoicesUnlessApproved", PluginGrantKey::class.java, nav.grantKey())

        assertEquals(emptySet<String>(), policies.poll(5, TimeUnit.SECONDS)!!.hiddenOwners)
        assertTrue(preferences.preferenceFor(nav.grantKey()).floating)
    }

    @Test
    fun `an uninstall found by the reconcile sends the policy again`() {
        preferences.setFloating(nav.grantKey(), false)
        surfaceOwners().clear()

        call("retainActivityChoices", List::class.java, emptyList<PhonePluginPrincipal>())

        assertEquals(emptySet<String>(), policies.poll(5, TimeUnit.SECONDS)!!.hiddenOwners)
        assertTrue(preferences.keptOffGlasses().isEmpty())
    }

    @Test
    fun `a choice made under another signer is never sent for the plugin holding the grant now`() {
        preferences.setFloating(PluginGrantKey(nav.packageName, "nav", "digest-before"), false)
        pushPolicy()

        assertEquals(emptySet<String>(), policies.poll(5, TimeUnit.SECONDS)!!.hiddenOwners)
        assertNull(policies.poll(200, TimeUnit.MILLISECONDS))
    }

    private fun pushPolicy() = call("pushAmbient", Boolean::class.javaPrimitiveType!!, false)

    private fun call(name: String, type: Class<*>, argument: Any) {
        BusHubService::class.java.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(hub, argument)
    }

    private fun set(name: String, value: Any) {
        BusHubService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(hub, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun surfaceOwners(): ConcurrentHashMap<String, Optional<PhonePluginPrincipal>> =
        BusHubService::class.java.getDeclaredField("surfaceOwnerCache").apply { isAccessible = true }
            .get(hub) as ConcurrentHashMap<String, Optional<PhonePluginPrincipal>>

    private fun principal(id: String) = PhonePluginPrincipal(
        packageName = "dev.example.$id",
        serviceComponent = ComponentName("dev.example.$id", "dev.example.$id.Service"),
        uid = 10_000 + id.length,
        signingDigestSha256 = "digest-$id",
        descriptor = PluginDescriptor(id, id, 3, setOf(PluginCapability.SURFACES), listOf("/plugin/$id"), null, true),
    )

    private class MemoryStorage : PluginGrantStorage {
        var value: String? = null

        override fun read(): String? = value

        override fun write(value: String) {
            this.value = value
        }
    }
}
