package com.anezium.rokidbus.glasses

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.PinSurfacePosition
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "w320dp-h640dp-hdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ActivityDisplayAnimationTest {
    class OverlayService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    @Test fun `another hidden publish cannot tear down an expiry animation in flight`() {
        val previousMotion = HudMotion.enabled
        HudMotion.enabled = true
        val service = Robolectric.setupService(OverlayService::class.java)
        val host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val rootField = ActivityOverlayRenderer::class.java.getDeclaredField("root").apply { isAccessible = true }
        val render = ActivityOverlayRenderer::class.java.getDeclaredMethod("render", ActivityRenderState::class.java).apply { isAccessible = true }
        val content = ActivitySurfaceContent("disc", "Track", "Artist", null, null, emptyList(), emptyList(), null)
        val item = ActivityRenderItem(NexusActivitySurface("media:activity", "media", 1, content,
            PinSurfacePosition.TOP_RIGHT, 1, 1, null, 0, null, -1, 1), primary = true, presentation = ActivityPresentation.CHIP)
        try {
            ActivityOverlayRenderer.onServiceConnected(service)
            val root = FrameLayout(host.get())
            host.get().setContentView(root)
            rootField.set(ActivityOverlayRenderer, root)
            val nodes = ActivityOverlayRenderer::class.java.getDeclaredField("nodes").apply { isAccessible = true }
                .get(ActivityOverlayRenderer) as MutableMap<String, ActivityOverlayRenderer.ActivityIsland>
            val island = ActivityOverlayRenderer.ActivityIsland(host.get(), glyphOf = { _, _ -> ColorDrawable(Color.GREEN) })
            nodes[item.activity.surfaceId] = island
            root.addView(island, FrameLayout.LayoutParams(480, 640))
            render.invoke(ActivityOverlayRenderer, ActivityRenderState(listOf(item)))
            root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 480, 640)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            val hidden = ActivityRenderState(listOf(item.copy(presentation = ActivityPresentation.HIDDEN, animateExit = true)))
            render.invoke(ActivityOverlayRenderer, hidden)
            assertSame(root, rootField.get(ActivityOverlayRenderer))
            render.invoke(ActivityOverlayRenderer, hidden)
            assertSame("leave the folding window attached", root, rootField.get(ActivityOverlayRenderer))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertNull(rootField.get(ActivityOverlayRenderer))
        } finally {
            ActivityOverlayRenderer.onServiceDestroyed(service)
            host.pause().stop().destroy()
            HudMotion.enabled = previousMotion
        }
    }
}
