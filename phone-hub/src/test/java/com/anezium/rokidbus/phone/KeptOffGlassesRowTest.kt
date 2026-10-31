package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import com.anezium.rokidbus.shared.AmbientPreferencesContract
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Float over the glasses switch on the real Plugin access screen, for an approved plugin that
 * may show on the glasses: what it promises, what it writes and what it leaves alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class KeptOffGlassesRowTest {
    private val context = RuntimeEnvironment.getApplication()
    private val originalRefresh = PluginPermissionsActivity.onKeptOffGlassesChanged
    private var policyRefreshes = 0
    private lateinit var principal: PhonePluginPrincipal
    private lateinit var preferences: PhoneActivityPreferences

    @Before
    fun setUp() {
        // The process-wide instance would otherwise outlive the test application it was built on.
        PhoneActivityPreferences::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        preferences = PhoneActivityPreferences.shared(context)
        principal = installApprovedPlugin()
        PluginPermissionsActivity.onKeptOffGlassesChanged = { policyRefreshes++ }
    }

    @After
    fun tearDown() {
        PluginPermissionsActivity.onKeptOffGlassesChanged = originalRefresh
    }

    @Test
    fun `the switch says it needs the new launcher, and off keeps the grant and the other choices`() {
        val key = principal.grantKey()
        preferences.setPinned(key, true)
        val screen = open()

        val row = row(screen, FLOAT)
        assertTrue(row.note, row.note.contains("with the new launcher"))
        assertTrue(row.switch.isChecked)
        assertTrue(row.switch.isEnabled)

        row.switch.performClick()

        assertEquals(1, policyRefreshes)
        assertEquals(PhoneActivityPreference(enabled = true, pinned = true, floating = false), preferences.preferenceFor(key))
        assertEquals(
            PluginGrantState.Approved(setOf(PluginCapability.SURFACES)),
            PluginGrantStore(context).stateFor(principal),
        )
        assertFalse(row(screen, FLOAT).switch.isChecked)
        assertTrue(row(screen, "Show its activity on the glasses").switch.isChecked)
        assertTrue(row(screen, "Pin its activity").switch.isChecked)

        row(screen, FLOAT).switch.performClick()

        assertEquals(2, policyRefreshes)
        assertEquals(PhoneActivityPreference(enabled = true, pinned = true), preferences.preferenceFor(key))
        assertTrue(row(screen, FLOAT).switch.isChecked)
    }

    @Test
    fun `with the Classic launcher chosen the switch says where it applies, and still works`() {
        PhoneLauncherBackendStore(context).setChoice(PhoneLauncherBackendChoice.LEGACY)
        val screen = open()

        val row = row(screen, FLOAT)
        assertTrue(row.note, row.note.contains("Classic launcher you chose always shows them"))
        assertTrue(row.note, row.note.contains("applies with the new launcher"))
        assertTrue(row.switch.isEnabled)

        row.switch.performClick()

        assertEquals(1, policyRefreshes)
        assertFalse(preferences.preferenceFor(principal.grantKey()).floating)
    }

    @Test
    fun `once the most plugins are kept off, the switch stays on and says why`() {
        val others = (1..AmbientPreferencesContract.MAX_HIDDEN_OWNERS).map { PluginGrantKey("dev.example.p$it", "p$it", "digest-$it") }
        others.forEach { assertTrue(preferences.setFloating(it, false)) }
        val screen = open()

        val row = row(screen, FLOAT)
        assertTrue(row.note, row.note.contains("${AmbientPreferencesContract.MAX_HIDDEN_OWNERS} plugins already stay off the glasses"))
        assertTrue(row.switch.isChecked)
        assertFalse(row.switch.isEnabled)

        // Filled up from elsewhere after the screen was drawn: the change is refused, not sent.
        row.switch.isChecked = false

        assertEquals(0, policyRefreshes)
        assertTrue(preferences.preferenceFor(principal.grantKey()).floating)
        assertEquals(others.toSet(), preferences.keptOffGlasses())
        assertTrue(row(screen, FLOAT).switch.isChecked)

        assertTrue(preferences.setFloating(others.first(), true))
        val freed = row(open(), FLOAT)
        assertTrue(freed.switch.isEnabled)
        assertTrue(freed.note, freed.note.contains("with the new launcher"))
    }

    private class Row(val note: String, val switch: CompoundButton)

    private fun open(): Activity = Robolectric.buildActivity(PluginPermissionsActivity::class.java).setup().get()

    private fun row(screen: Activity, title: String): Row {
        val label = textViews(screen.window.decorView).single { it !is CompoundButton && it.text.toString() == title }
        val texts = label.parent as ViewGroup
        val row = texts.parent as ViewGroup
        return Row((texts.getChildAt(1) as TextView).text.toString(), row.getChildAt(1) as CompoundButton)
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun installApprovedPlugin(): PhonePluginPrincipal {
        val manager = shadowOf(context.packageManager)
        val app = ApplicationInfo().apply { packageName = "dev.example.nav"; uid = 10001; enabled = true }
        manager.installPackage(PackageInfo().apply {
            packageName = app.packageName
            applicationInfo = app
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(byteArrayOf(1, 2, 3)))) }
            lastUpdateTime = 1
        })
        manager.addResolveInfoForIntent(Intent(BusConstants.ACTION_PLUGIN), ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = app.packageName; name = "${app.packageName}.PluginService"
                applicationInfo = app; exported = true
                metaData = Bundle().apply {
                    putString(BusConstants.META_PLUGIN_ID, "nav")
                    putString(BusConstants.META_PLUGIN_DISPLAY_NAME, "Navigation")
                    putString(BusConstants.META_PLUGIN_API_VERSION, "3")
                    putString(BusConstants.META_PLUGIN_CAPABILITIES, "surfaces")
                    putString(BusConstants.META_PLUGIN_RECEIVE_PREFIXES, "/plugin/nav")
                    // Unit tests carry no hub resources; a framework drawable stands in for its icon.
                    putString(BusConstants.META_PLUGIN_ICON_DRAWABLE, android.R.drawable.star_on.toString())
                }
            }
        })
        val principal = (PhonePluginDiscovery(context.packageManager).discover().single() as PhonePluginCandidate.Valid).principal
        PluginGrantStore(context).approve(principal, setOf(PluginCapability.SURFACES))
        return principal
    }

    private companion object {
        const val FLOAT = "Float over the glasses"
    }
}
