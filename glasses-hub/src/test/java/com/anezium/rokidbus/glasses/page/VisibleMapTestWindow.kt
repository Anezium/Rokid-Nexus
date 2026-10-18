package com.anezium.rokidbus.glasses.page

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

internal object VisibleMapTestWindow {
    @JvmStatic
    fun attach(controller: ActivityController<Activity>, view: View) {
        controller.get().setContentView(view)
        controller.visible().windowFocusChanged(true)
        // Legacy Robolectric omits ADD_FLAG_APP_VISIBLE. Supply the real WindowManager
        // transition through ViewRoot, which schedules visibility dispatch and traversal.
        setAppVisible(view, true)
        assertTrue(view.isAttachedToWindow)
        assertTrue(view.isShown)
        assertEquals(View.VISIBLE, view.windowVisibility)
        assertTrue(view.width > 0 && view.height > 0)
        val visible = Rect()
        assertTrue(view.getLocalVisibleRect(visible))
        assertEquals(Rect(0, 0, view.width, view.height), visible)
    }

    fun setAppVisible(view: View, visible: Boolean) {
        val root = ReflectionHelpers.callInstanceMethod<Any>(view, "getViewRootImpl")
        ReflectionHelpers.callInstanceMethod<Unit>(root, "handleAppVisibility",
            ClassParameter.from(Boolean::class.javaPrimitiveType, visible))
        shadowOf(Looper.getMainLooper()).idle()
    }
}
