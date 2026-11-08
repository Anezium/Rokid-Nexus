package com.anezium.rokidbus.phone

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import com.anezium.rokidbus.shared.HeadTiltSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class HeadTiltSettingsActivityTest {
    @Test fun `disconnected setup cannot enable the gesture and can be closed cleanly`() {
        val app = RuntimeEnvironment.getApplication()
        val store = PhoneHeadTiltStore(app)
        store.setStockDisabled(false)
        store.save(HeadTiltSettings())
        val screen = Robolectric.buildActivity(HeadTiltSettingsActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val views = descendants(screen.get().findViewById(android.R.id.content))
        val toggle = views.filterIsInstance<Switch>().single { it.text.toString() == "Enable head tilt" }
        assertFalse(toggle.isChecked)
        assertFalse(toggle.isEnabled)
        assertTrue(views.filterIsInstance<TextView>().any { it.text.contains("Connect Nexus") })
        screen.pause().stop().destroy()
    }

    @Test fun `withdrawing the stock setting acknowledgement disables an enabled gesture`() {
        val store = PhoneHeadTiltStore(RuntimeEnvironment.getApplication())
        store.setStockDisabled(true)
        store.save(HeadTiltSettings(enabled = true))
        store.setStockDisabled(false)
        assertFalse(store.settings().enabled)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
