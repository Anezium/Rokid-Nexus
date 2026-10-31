package com.anezium.rokidbus.plugin.nav

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When the listener is asked to bind again: only with the grant, only with guidance on, only
 * while it is not connected, once per visit however often the screen refreshes, and a platform
 * refusal is that visit's one attempt. Nothing here ever reports a connection. Robolectric, as
 * the policy's component names and its logged refusal are real Android classes, not stubs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class NavListenerRebindTest {
    private val component = ComponentName("com.anezium.rokidbus.plugin.nav", "com.anezium.rokidbus.plugin.nav.NavNotificationListener")
    private val requests = mutableListOf<ComponentName>()
    private val rebind = NavListenerRebind { requests += it }

    @Test
    fun `without the grant, with guidance off, or once connected nothing is asked`() {
        rebind.onEnter()
        assertFalse(rebind.consider(component, accessGranted = false, guidanceEnabled = true, connected = false))
        assertFalse(rebind.consider(component, accessGranted = true, guidanceEnabled = false, connected = false))
        assertFalse(rebind.consider(component, accessGranted = true, guidanceEnabled = true, connected = true))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `granted, on and not connected asks once per visit, whatever the screen's refreshes`() {
        rebind.onEnter()
        assertTrue(rebind.consider(component, accessGranted = true, guidanceEnabled = true, connected = false))
        repeat(5) { assertFalse(rebind.consider(component, accessGranted = true, guidanceEnabled = true, connected = false)) }
        assertEquals(listOf(component), requests)

        // Back on the screen: one more, and none once the listener has connected.
        rebind.onEnter()
        assertTrue(rebind.consider(component, accessGranted = true, guidanceEnabled = true, connected = false))
        assertEquals(2, requests.size)
        rebind.onEnter()
        assertFalse(rebind.consider(component, accessGranted = true, guidanceEnabled = true, connected = true))
        assertEquals(2, requests.size)
    }

    @Test
    fun `a platform refusal is logged, counts as the visit's attempt and is not retried`() {
        var calls = 0
        val failing = NavListenerRebind { calls++; throw IllegalStateException("not bound") }
        failing.onEnter()
        assertFalse(failing.consider(component, accessGranted = true, guidanceEnabled = true, connected = false))
        assertFalse(failing.consider(component, accessGranted = true, guidanceEnabled = true, connected = false))
        assertEquals(1, calls)
    }
}
