package com.anezium.rokidbus.phone

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PhoneAudioRelayStoreTest {
    @Test fun `toggle defaults off and settings and statuses round trip with notifications`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences(NexusPhoneState.PREFS, 0).edit().clear().commit()
        val store = PhoneAudioRelayStore(app)
        assertFalse(store.isEnabled()); assertEquals(PhoneAudioRelayStatus.OFF, store.status())
        var calls = 0
        val subscription = store.addChangeListener { calls++ }
        store.setEnabled(true)
        assertTrue(PhoneAudioRelayStore(app).isEnabled())
        PhoneAudioRelayStatus.entries.forEach { status -> store.setStatus(status); assertEquals(status, PhoneAudioRelayStore(app).status()) }
        store.setEnabled(false)
        assertEquals(PhoneAudioRelayStatus.OFF, store.status()); assertTrue(calls > 0)
        subscription.close()
        val previous = calls; store.setEnabled(true); assertEquals(previous, calls)
    }
}
