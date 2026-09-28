package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class HubCommandIntentsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val authorityKey = "com.anezium.rokidbus.phone.COMMAND_AUTHORITY"

    @Test fun missingForgedAndWrongTypeAuthoritiesAreRejected() {
        assertFalse(HubCommandIntents.isTrusted(Intent()))
        assertFalse(HubCommandIntents.isTrusted(Intent().putExtra(authorityKey, true)))
        assertFalse(HubCommandIntents.isTrusted(Intent().putExtras(Bundle().apply {
            putBinder(authorityKey, Binder())
        })))
    }

    @Test fun internalAuthoritySurvivesInProcessIntentParcelRoundTrip() {
        val parcel = Parcel.obtain()
        try {
            HubCommandIntents.create(context).writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            assertTrue(HubCommandIntents.isTrusted(Intent.CREATOR.createFromParcel(parcel)))
        } finally {
            parcel.recycle()
        }
    }

    @Test fun everyInternalStartHelperCarriesAuthorityAndKeepsItsStartMode() {
        val starts = mutableListOf<Pair<Intent, Boolean>>()
        val caller = object : ContextWrapper(context) {
            override fun startService(intent: Intent): ComponentName? {
                starts += intent to false
                return intent.component
            }
            override fun startForegroundService(intent: Intent): ComponentName? {
                starts += intent to true
                return intent.component
            }
        }
        BusHubService.start(caller)
        BusHubService.startWithToken(caller, "test-authorization")
        BusHubService.startDebugImage(caller)
        BusHubService.installGlassesApp(caller)
        BusHubService.queryGlassesApp(caller)
        BusHubService.openGlassesApp(caller)
        BusHubService.startGlassesSetup(caller)
        BusHubService.stop(caller)

        assertEquals(listOf(null, "SET_TOKEN", "DEBUG_IMAGE_SURFACE", "INSTALL_GLASSES_APP",
            "QUERY_GLASSES_APP", "OPEN_GLASSES_APP", "START_GLASSES_SETUP", "STOP"),
            starts.map { it.first.action?.substringAfterLast('.') })
        assertEquals(listOf(true, true, true, false, false, false, false, false), starts.map { it.second })
        starts.forEach { (intent, _) ->
            assertEquals(ComponentName(caller, BusHubService::class.java), intent.component)
            assertTrue(HubCommandIntents.isTrusted(intent))
        }
        assertEquals("test-authorization", starts[1].first.getStringExtra("auth_token"))
    }
}
