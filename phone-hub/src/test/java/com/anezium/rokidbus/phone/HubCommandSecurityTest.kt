package com.anezium.rokidbus.phone

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean
import com.anezium.rokidbus.client.IBusService
import com.anezium.rokidbus.shared.BusConstants
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32])
class HubCommandSecurityTest {
    private val hub = Robolectric.buildService(BusHubService::class.java).get()
    private val actions = listOf("STOP", "SET_TOKEN", "INSTALL_GLASSES_APP", "QUERY_GLASSES_APP",
        "OPEN_GLASSES_APP", "START_GLASSES_SETUP", "DEBUG_IMAGE_SURFACE", "DEBUG_MANUAL_PAIRING",
        "UNKNOWN", null)

    @Before fun prepareWithoutStartingRadios() {
        hub.applicationInfo.flags = hub.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        hub.getSharedPreferences("rokidbus_phone", 0).edit()
            .putBoolean("hub_enabled", true).putString("cxrl_token", "original").commit()
        field("startupBlockedByBluetoothPermission").set(hub, true)
        shadowOf(hub.application).denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
    }

    @Test fun releaseStartsWithoutAuthorityCannotStopOrReplaceAuthorizationOrDispatchCommands() {
        actions.forEach { action ->
            val intent = command(action).putExtra("auth_token", "attacker")
            assertEquals(action, Service.START_STICKY, hub.onStartCommand(intent, 0, 1))
            assertTrue(BusHubService.isEnabled(hub))
            assertEquals("original", hub.getSharedPreferences("rokidbus_phone", 0).getString("cxrl_token", null))
            // Accepted commands would reach the missing-permission branch and clear started state.
            assertFalse(shadowOf(hub).isStoppedBySelf)
        }
    }

    @Test fun debugBuildStillRejectsEveryNonDebugStartWithoutAuthority() {
        hub.applicationInfo.flags = hub.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        actions.filterNot { it?.startsWith("DEBUG_") == true }.forEach { action ->
            assertEquals(Service.START_STICKY, hub.onStartCommand(command(action), 0, 1))
            assertFalse(shadowOf(hub).isStoppedBySelf)
        }
    }

    @Test fun debugAdbCommandsReachTheExistingPermissionGate() {
        hub.applicationInfo.flags = hub.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        listOf("DEBUG_IMAGE_SURFACE", "DEBUG_MANUAL_PAIRING").forEach { action ->
            assertEquals(Service.START_NOT_STICKY, hub.onStartCommand(command(action), 0, 1))
            assertTrue(shadowOf(hub).isStoppedBySelf)
        }
    }

    @Test fun internalStopStillDisablesTheHubWhenBluetoothPermissionIsMissing() {
        val intent = HubCommandIntents.create(hub).setAction("com.anezium.rokidbus.phone.STOP")
        assertEquals(Service.START_NOT_STICKY, hub.onStartCommand(intent, 0, 1))
        assertFalse(BusHubService.isEnabled(hub))
        assertTrue(shadowOf(hub).isStoppedBySelf)
    }

    @Test fun internalStartEnablesTheStoppedHub() {
        field("hubEnabled").set(hub, false)
        field("startupBlockedByBluetoothPermission").set(hub, false)
        (field("sppLoopStarted").get(hub) as AtomicBoolean).set(true)
        hub.getSharedPreferences("rokidbus_phone", 0).edit()
            .putBoolean("hub_enabled", false).putString("cxrl_token", "").commit()
        shadowOf(hub.application).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        assertEquals(Service.START_STICKY, hub.onStartCommand(HubCommandIntents.create(hub), 0, 1))
        assertTrue(BusHubService.isEnabled(hub))
    }

    @Test fun stickyRestartAndExternalEmptyStartDoNotEnableStoppedHub() {
        field("hubEnabled").set(hub, false)
        hub.getSharedPreferences("rokidbus_phone", 0).edit().putBoolean("hub_enabled", false).commit()
        assertEquals(Service.START_NOT_STICKY, hub.onStartCommand(null, 0, 1))
        assertEquals(Service.START_NOT_STICKY, hub.onStartCommand(command(null), 0, 2))
        assertFalse(BusHubService.isEnabled(hub))
    }

    @Test fun pluginBinderRemainsAvailableAndCannotAuthorizeAdminStarts() {
        val binder = hub.onBind(Intent("com.anezium.rokidbus.action.HUB"))
        assertEquals(BusConstants.API_VERSION, IBusService.Stub.asInterface(binder).apiVersion())
        val intent = command("STOP").putExtras(Bundle().apply {
            putBinder("com.anezium.rokidbus.phone.COMMAND_AUTHORITY", binder)
        })
        assertEquals(Service.START_STICKY, hub.onStartCommand(intent, 0, 1))
        assertTrue(BusHubService.isEnabled(hub))
    }

    @Test fun pluginManifestContractRemainsExportedWithoutSignaturePermission() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val services = document.getElementsByTagName("service")
        val service = (0 until services.length).map { services.item(it) }
            .single { it.attributes.getNamedItem("android:name")?.nodeValue == ".BusHubService" }
        assertEquals("true", service.attributes.getNamedItem("android:exported").nodeValue)
        assertNull(service.attributes.getNamedItem("android:permission"))
        val actions = (service as org.w3c.dom.Element).getElementsByTagName("action")
        assertTrue((0 until actions.length).any {
            actions.item(it).attributes.getNamedItem("android:name").nodeValue == "com.anezium.rokidbus.action.HUB"
        })
    }

    private fun command(action: String?) = Intent(hub, BusHubService::class.java).apply {
        this.action = action?.let { "com.anezium.rokidbus.phone.$it" }
    }

    private fun field(name: String) = BusHubService::class.java.getDeclaredField(name).apply { isAccessible = true }
}
