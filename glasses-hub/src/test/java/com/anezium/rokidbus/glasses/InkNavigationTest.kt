package com.anezium.rokidbus.glasses

import android.app.Activity
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import com.anezium.rokidbus.ink.InkActionBinding
import com.anezium.rokidbus.ink.RenderChange
import com.anezium.rokidbus.ink.RenderDocument
import com.anezium.rokidbus.ink.RenderNode
import com.anezium.rokidbus.ink.RenderPatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController as RobolectricActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class InkNavigationTest {
    private lateinit var activity: RobolectricActivityController<Activity>
    private lateinit var view: InkHudView
    private lateinit var store: InkNodeStore
    private val actions = mutableListOf<Pair<String, Map<String, Any?>>>()
    private var eventTime = 1_000L

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup()
        view = InkHudView(activity.get()).apply {
            onAction = { actionId, dataset -> actions += actionId to dataset.toMap() }
        }
        activity.get().setContentView(view)
    }

    @After
    fun tearDown() {
        activity.pause().stop().destroy()
    }

    @Test
    fun `first action is selected and directional aliases activate the selected button`() {
        show(action("mic"), action("refresh"), action("close"))

        assertSelected("mic")
        assertTrue(press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertSelected("refresh")
        assertTrue(press(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf("refresh"), actionIds())

        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertSelected("close")
        assertTrue(press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertSelected("refresh")
        assertTrue(press(KeyEvent.KEYCODE_DPAD_UP))
        assertSelected("mic")
        assertTrue(press(KeyEvent.KEYCODE_ENTER))
        assertEquals(listOf("refresh", "mic"), actionIds())
    }

    @Test
    fun `selection wraps in both directions without activating during navigation`() {
        show(action("a"), action("b"), action("c"))

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertSelected("c")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertSelected("a")
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `key release and held direction do not move selection twice`() {
        show(action("a"), action("b"), action("c"))

        assertTrue(key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN))
        assertTrue(key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, repeat = 1))
        assertTrue(key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP))
        assertSelected("b")
        assertTrue(press(KeyEvent.KEYCODE_ENTER))
        assertEquals(listOf("b"), actionIds())
    }

    @Test
    fun `data event text and style patches preserve selected node and use its latest action`() {
        show(action("a"), action("b"), action("c"))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val selectedView = nodeView("b")

        patch(
            RenderChange.DatasetChanged("b", "row", 42),
            RenderChange.EventChanged("b", "tap", InkActionBinding("updated-b", false)),
            RenderChange.TextChanged("b-label", "Updated button"),
            RenderChange.StyleChanged("b", "border-width", "3px"),
        )

        assertSame(selectedView, nodeView("b"))
        assertSelected("b")
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("updated-b"), actionIds())
        assertEquals(mapOf("row" to 42), actions.single().second)
    }

    @Test
    fun `selection follows node identity when siblings share an action id`() {
        show(action("a", actionId = "pick"), action("b", actionId = "pick"), action("c", actionId = "pick"))
        press(KeyEvent.KEYCODE_DPAD_DOWN)

        patch(RenderChange.NodeAdded("before", "root", 0, action("before", actionId = "pick")))

        assertSelected("b")
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals("b", actions.single().second["row"])
    }

    @Test
    fun `move preserves selection and subsequent navigation uses the new document order`() {
        show(action("a"), action("b"), action("c"))
        press(KeyEvent.KEYCODE_DPAD_DOWN)

        patch(RenderChange.NodeMoved("b", "root", 1, 0))

        assertSelected("b")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertSelected("a")
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("a"), actionIds())
    }

    @Test
    fun `new action participates at its authored position instead of registry insertion order`() {
        show(action("a"), action("b"), action("c"))

        patch(RenderChange.NodeAdded("inserted", "root", 1, action("inserted")))

        assertSelected("a")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertSelected("inserted")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertSelected("b")
    }

    @Test
    fun `removing selected middle action chooses its next surviving neighbor`() {
        show(action("a"), action("b"), action("c"))
        press(KeyEvent.KEYCODE_DPAD_DOWN)

        patch(RenderChange.NodeRemoved("b", "root", 1))

        assertSelected("c")
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("c"), actionIds())
    }

    @Test
    fun `removing selected last action chooses its previous surviving neighbor`() {
        show(action("a"), action("b"), action("c"))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)

        patch(RenderChange.NodeRemoved("c", "root", 2))

        assertSelected("b")
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("b"), actionIds())
    }

    @Test
    fun `conditional subtree removal drops its actions from selection and activation`() {
        show(
            action("a"),
            RenderNode("conditional", "view", children = listOf(action("b"), action("c"))),
            action("d"),
        )
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertSelected("b")

        patch(RenderChange.NodeRemoved("conditional", "root", 1))

        assertSelected("d")
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("d"), actionIds())
    }

    @Test
    fun `removing tap binding selects another action and removing all bindings clears selection`() {
        show(action("a"), action("b"), action("c"))
        press(KeyEvent.KEYCODE_DPAD_DOWN)

        patch(RenderChange.EventChanged("b", "tap", null))
        assertSelected("c")
        patch(
            RenderChange.EventChanged("a", "tap", null),
            RenderChange.EventChanged("c", "tap", null),
        )

        assertTrue(selectedViews().isEmpty())
        assertFalse(press(KeyEvent.KEYCODE_ENTER))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `same document redraw retains selection and a new document resets it`() {
        show(action("a"), action("b"))
        press(KeyEvent.KEYCODE_DPAD_DOWN)

        view.show(store, debugActions = false)
        assertSelected("b")
        store = InkNodeStore.from(store.document().copy(revision = store.revision + 1))
        view.show(store, debugActions = false)
        assertSelected("b")

        show(action("a"), action("b"), documentId = "replacement")
        assertSelected("a")
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("a"), actionIds())
    }

    @Test
    fun `back remains available to the surface controller`() {
        show(action("a"), action("b"))

        assertFalse(press(KeyEvent.KEYCODE_BACK))

        assertSelected("a")
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `accessibility focus shares selection with key confirmation`() {
        show(action("a"), action("b"), action("c"))

        nodeView("b").performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)

        assertSelected("b")
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("b"), actionIds())
    }

    @Test
    fun `successful input focus action reports success and confirms the newly focused button`() {
        show(action("a"), action("b"))
        assertTrue(nodeView("a").requestFocus())
        val target = nodeView("b")

        assertTrue(target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_FOCUS, null))

        assertSelected("b")
        assertSame(target, view.findFocus())
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("b"), actionIds())
    }

    @Test
    fun `successful accessibility focus action reports success and confirms the newly focused button`() {
        shadowOf(activity.get().getSystemService(AccessibilityManager::class.java)).apply {
            setEnabled(true)
            setTouchExplorationEnabled(true)
        }
        show(action("a"), action("b"))
        nodeView("a").performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        assertTrue(nodeView("a").isAccessibilityFocused)
        val target = nodeView("b")

        assertTrue(target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))

        assertSelected("b")
        assertTrue(target.isAccessibilityFocused)
        assertFalse(nodeView("a").isAccessibilityFocused)
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("b"), actionIds())
    }

    @Test
    fun `accessibility Select changes the canonical choice without activating it`() {
        show(action("a"), action("b"))
        val target = nodeView("b")
        val select = target.createAccessibilityNodeInfo().actionList.single { it.label == "Select" }

        assertTrue(target.performAccessibilityAction(select.id, null))

        assertSelected("b")
        assertTrue(actions.isEmpty())
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("b"), actionIds())
    }

    @Test
    fun `accessibility click selects the target and uses its current dataset`() {
        show(action("a"), action("b"))
        patch(RenderChange.DatasetChanged("b", "row", 42))

        assertTrue(nodeView("b").performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))

        assertSelected("b")
        assertEquals(listOf("b"), actionIds())
        assertEquals(mapOf("row" to 42), actions.single().second)
    }

    @Test
    fun `local navigation moves remote accessibility and input focus before the next click`() {
        shadowOf(activity.get().getSystemService(AccessibilityManager::class.java)).apply {
            setEnabled(true)
            setTouchExplorationEnabled(true)
        }
        show(action("a"), action("b"))
        val first = nodeView("a")
        val next = nodeView("b")
        assertTrue(first.requestFocus())
        first.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        assertTrue("fixture must establish real accessibility focus on A", first.isAccessibilityFocused)

        press(KeyEvent.KEYCODE_DPAD_DOWN)

        assertSelected("b")
        assertSame(next, view.findFocus())
        assertFalse(first.isAccessibilityFocused)
        assertTrue(next.isAccessibilityFocused)
        patch(
            RenderChange.DatasetChanged("b", "row", 42),
            RenderChange.EventChanged("b", "tap", InkActionBinding("updated-b", false)),
        )
        val remotelyFocused = descendants(view).single { it.isAccessibilityFocused }
        assertSame(next, remotelyFocused)
        assertTrue(remotelyFocused.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf("updated-b"), actionIds())
        assertEquals(mapOf("row" to 42), actions.single().second)
    }

    @Test
    fun `direction pages a pure scroll document with no actions`() {
        showRoot(scrollRoot(listOf(RenderNode("body", "view", style = mapOf("height" to "1000px", "flex-shrink" to "0")))))
        val scroller = descendants(view).filterIsInstance<ScrollView>().single()
        val before = scroller.scrollY

        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN))

        assertTrue("reader scrolling must remain available", scroller.scrollY > before)
        assertFalse(press(KeyEvent.KEYCODE_ENTER))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `navigation reaches an offscreen action through its real scroll viewport`() {
        showRoot(scrollRoot((0..5).map { action("item-$it") }))
        val scroller = descendants(view).filterIsInstance<ScrollView>().single()

        var steps = 0
        while ((!nodeView("item-5").isSelected || !insideViewport(nodeView("item-5"), scroller)) && steps < 20) {
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            shadowOf(Looper.getMainLooper()).idle()
            steps += 1
        }

        assertSelected("item-5")
        assertTrue("selected action must scroll into view", scroller.scrollY > 0)
        assertTrue(insideViewport(nodeView("item-5"), scroller))
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("item-5"), actionIds())
    }

    @Test
    fun `reading before the first action pages instead of jumping straight to that action`() {
        showRoot(
            scrollRoot(
                listOf(
                    RenderNode("intro", "view", style = mapOf("height" to "800px", "flex-shrink" to "0")),
                    action("continue"),
                ),
            ),
        )
        val scroller = descendants(view).filterIsInstance<ScrollView>().single()
        val target = nodeView("continue")
        assertFalse(insideViewport(target, scroller))

        press(KeyEvent.KEYCODE_ENTER)
        assertTrue(actions.isEmpty())
        press(KeyEvent.KEYCODE_DPAD_DOWN)

        assertTrue(scroller.scrollY > 0)
        assertTrue("the first page must retain the intervening reading", scroller.scrollY < target.top)
        assertFalse(insideViewport(target, scroller))
        press(KeyEvent.KEYCODE_ENTER)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `an action taller than its viewport remains activable while partly visible`() {
        val oversized = action("large").let {
            it.copy(style = it.style + ("height" to "320px"))
        }
        showRoot(scrollRoot(listOf(oversized)))
        val scroller = descendants(view).filterIsInstance<ScrollView>().single()
        assertTrue(nodeView("large").height > scroller.height)
        assertFalse(insideViewport(nodeView("large"), scroller))
        assertSelected("large")

        assertTrue(press(KeyEvent.KEYCODE_ENTER))

        assertEquals(listOf("large"), actionIds())
    }

    @Test
    fun `text after the final action stays pageable before navigation wraps`() {
        showRoot(
            scrollRoot(
                listOf(
                    action("first"), action("last"),
                    RenderNode("tail", "view", style = mapOf("height" to "800px", "flex-shrink" to "0")),
                ),
            ),
        )
        val scroller = descendants(view).filterIsInstance<ScrollView>().single()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertSelected("last")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("direction after the last action must expose trailing text", scroller.scrollY > 0)
        assertSelected("last")

        var steps = 0
        while (scroller.canScrollVertically(1) && steps < 20) {
            val before = scroller.scrollY
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue("trailing text must remain reachable without jumping back", scroller.scrollY > before)
            assertSelected("last")
            steps += 1
        }
        assertFalse(scroller.canScrollVertically(1))
        assertTrue(actions.isEmpty())

        press(KeyEvent.KEYCODE_DPAD_DOWN)

        assertSelected("first")
        assertTrue(insideViewport(nodeView("first"), scroller))
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("first"), actionIds())
    }

    private fun show(vararg nodes: RenderNode, documentId: String = "navigation") = showRoot(
        RenderNode(
            "root", "view",
            style = mapOf("display" to "flex", "flex-direction" to "column", "width" to "100%"),
            children = nodes.toList(),
        ),
        documentId,
    )

    private fun showRoot(root: RenderNode, documentId: String = "navigation") {
        store = InkNodeStore.from(RenderDocument(listOf(root), documentId = documentId))
        view.show(store, debugActions = false)
        settleLayout()
    }

    private fun patch(vararg changes: RenderChange) {
        val patch = RenderPatch(changes.toList(), store.documentId, store.revision, store.revision + 1)
        val applied = InkNodeStore.Executor.apply(store, patch)
        assertTrue("fixture patch must satisfy Ink v1: $applied", applied is InkPatchApplyResult.Applied)
        store = (applied as InkPatchApplyResult.Applied).store
        view.applyPatch(store, patch.changes, debugActions = false)
        settleLayout()
    }

    private fun action(id: String, actionId: String = id) = RenderNode(
        id, "view",
        attributes = mapOf("id" to id),
        style = mapOf("height" to "64px", "width" to "100%", "flex-shrink" to "0", "border-width" to "1px"),
        events = mapOf("tap" to InkActionBinding(actionId, false)),
        dataset = mapOf("row" to id),
        children = listOf(RenderNode("$id-label", "text", text = id)),
    )

    private fun scrollRoot(children: List<RenderNode>) = RenderNode(
        "scroll", "scroll-view",
        attributes = mapOf("scroll-y" to true),
        style = mapOf("height" to "160px", "width" to "100%", "flex-direction" to "column"),
        children = children,
    )

    private fun settleLayout() {
        repeat(3) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 480, 640)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun press(keyCode: Int): Boolean {
        val handled = key(keyCode, KeyEvent.ACTION_DOWN)
        key(keyCode, KeyEvent.ACTION_UP)
        return handled
    }

    private fun key(keyCode: Int, action: Int, repeat: Int = 0): Boolean {
        eventTime += 250L
        return view.handleInkKeyEvent(KeyEvent(eventTime, eventTime, action, keyCode, repeat))
    }

    private fun nodeView(id: String): View = descendants(view).single { it.contentDescription == id }

    private fun selectedViews(): List<View> = descendants(view).filter {
        val id = it.contentDescription?.toString()
        it.isSelected && id != null && store.node(id)?.attributes?.get("id") == id
    }

    private fun assertSelected(id: String) {
        assertEquals(listOf(nodeView(id)), selectedViews())
        assertTrue("selection must have a visible highlight", nodeView(id).foreground != null)
    }

    private fun actionIds() = actions.map { it.first }

    private fun insideViewport(target: View, viewport: View): Boolean {
        val targetPosition = IntArray(2).also(target::getLocationOnScreen)
        val viewportPosition = IntArray(2).also(viewport::getLocationOnScreen)
        return target.height > 0 && viewport.height > 0 &&
            targetPosition[1] >= viewportPosition[1] &&
            targetPosition[1] + target.height <= viewportPosition[1] + viewport.height
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) {
            repeat(root.childCount) { addAll(descendants(root.getChildAt(it))) }
        }
    }
}
