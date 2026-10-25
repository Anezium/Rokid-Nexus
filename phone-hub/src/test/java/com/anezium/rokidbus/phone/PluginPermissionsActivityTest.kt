package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.anezium.rokidbus.shared.QuickActionFamily
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PluginPermissionsActivityTest {
    @Test
    fun `activity choices omit quick action switches and keep existing approvals`() {
        val activity = Robolectric.buildActivity(PluginPermissionsActivity::class.java).get()
        val families = setOf(QuickActionFamily.CHECKLIST, QuickActionFamily.MEDIA)
        val principal = PhonePluginPrincipal(
            packageName = "dev.example.tasks",
            serviceComponent = ComponentName("dev.example.tasks", "dev.example.tasks.Service"),
            uid = 10_001,
            signingDigestSha256 = "digest-tasks",
            descriptor = PluginDescriptor(
                "tasks", "Tasks", 3, setOf(PluginCapability.SURFACES), listOf("/plugin/tasks"), null, true,
                quickActionFamilies = families,
            ),
        )
        val policy = PluginQuickActionPolicyStore.shared(activity.applicationContext)
        policy.setApproved(principal, QuickActionFamily.CHECKLIST, true)
        val rows = PluginPermissionsActivity::class.java.getDeclaredMethod("activityRows", PhonePluginPrincipal::class.java)
            .apply { isAccessible = true }.invoke(activity, principal) as List<*>
        val text = rows.flatMap { labels(it as View) }

        assertEquals(2, rows.size)
        assertEquals(
            listOf("Show its activity on the glasses", "Pin its activity"),
            text.filter { it == "Show its activity on the glasses" || it == "Pin its activity" },
        )
        assertEquals(setOf(QuickActionFamily.CHECKLIST), policy.approvedFamilies(principal.grantKey()))
    }

    private fun labels(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }
}
