package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.content.Intent
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSideloadNotificationPolicyTest {
    @Test
    fun `new ungranted plugin notifies in developer mode`() {
        assertTrue(shouldNotify())
    }

    @Test
    fun `developer mode off never notifies`() {
        assertFalse(shouldNotify(developerModeEnabled = false))
    }

    @Test
    fun `replacement never notifies`() {
        assertFalse(shouldNotify(replacing = true))
    }

    @Test
    fun `existing grant never notifies`() {
        assertFalse(shouldNotify(hasExistingGrant = true))
    }

    @Test
    fun `invalid candidate and non-add events never notify`() {
        assertFalse(shouldNotify(candidate = invalidCandidate()))
        assertFalse(shouldNotify(action = Intent.ACTION_PACKAGE_CHANGED))
    }

    private fun shouldNotify(
        developerModeEnabled: Boolean = true,
        action: String? = Intent.ACTION_PACKAGE_ADDED,
        replacing: Boolean = false,
        candidate: PhonePluginCandidate? = validCandidate(),
        hasExistingGrant: Boolean = false,
    ): Boolean = PluginSideloadNotificationPolicy.shouldNotify(
        developerModeEnabled = developerModeEnabled,
        action = action,
        replacing = replacing,
        candidate = candidate,
        hasExistingGrant = hasExistingGrant,
    )

    private fun validCandidate(): PhonePluginCandidate = PhonePluginCandidate.Valid(
        PhonePluginPrincipal(
            packageName = PACKAGE,
            serviceComponent = ComponentName(PACKAGE, "$PACKAGE.Service"),
            uid = 10001,
            signingDigestSha256 = "digest",
            descriptor = PluginDescriptor(
                id = "feeds",
                displayName = "Feeds",
                apiVersion = 3,
                requestedCapabilities = setOf(PluginCapability.SURFACES),
                receivePrefixes = listOf("/plugin/feeds"),
                settingsActivity = null,
                launchable = true,
            ),
        ),
    )

    private fun invalidCandidate(): PhonePluginCandidate = PhonePluginCandidate.Invalid(
        packageName = PACKAGE,
        displayName = "Feeds",
        serviceComponent = null,
        reason = "INVALID_DESCRIPTOR",
    )

    companion object {
        private const val PACKAGE = "com.example.feeds"
    }
}
