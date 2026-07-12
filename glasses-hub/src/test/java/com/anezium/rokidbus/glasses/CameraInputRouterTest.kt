package com.anezium.rokidbus.glasses

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraInputRouterTest {
    @Test
    fun `paired right down swipe moves once`() {
        val router = CameraInputRouter(directionalDebounceMs = 250L)
        assertEquals(CameraInputAction.ZOOM_IN, router.routeKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0, 1_000L).action)
        assertNull(router.routeKey(KeyEvent.KEYCODE_DPAD_DOWN, 0, 1_020L).action)
    }
}
