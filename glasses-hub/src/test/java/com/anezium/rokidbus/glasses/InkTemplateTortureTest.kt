package com.anezium.rokidbus.glasses

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.anezium.rokidbus.ink.InkEngine
import com.anezium.rokidbus.ink.InkSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Assistant's seven Ink templates at their worst: every count at its maximum and every
 * string at the length `render_template` accepts (`InkTemplateLimits` in the Assistant plugin;
 * the lengths below must match it). Real text measurement, on the glasses' 480 × 640 hdpi
 * display, in the card the Ink page gets there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "w320dp-h427dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkTemplateTortureTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup()

    @After
    fun tearDown() {
        activity.pause().stop().destroy()
    }

    @Test
    fun `metrics at its limits`() = check("metrics", fits = true) {
        put("cells", items(6) { labeledValue(label = 20, value = 16, detail = 24) })
    }

    @Test
    fun `ranking at its limits`() = check("ranking", fits = false) {
        put("rows", items(10) { labeledValue(label = 28, value = 12, detail = 32) })
    }

    @Test
    fun `comparison at its limits`() = check("comparison", fits = true) {
        fun side() = JSONObject()
            .put("label", text(16))
            .put("items", items(6) { JSONObject().put("label", text(12)).put("value", text(12)) })
        put("left", side())
        put("right", side())
        put("verdict", text(80))
    }

    @Test
    fun `schedule at its limits`() = check("schedule", fits = false) {
        put(
            "entries",
            items(12) {
                JSONObject().put("time", "Midday–1:30").put("title", text(40)).put("detail", text(48))
            },
        )
    }

    @Test
    fun `steps at its limits`() = check("steps", fits = false) {
        put("current", 3)
        put("progressPercent", 37)
        put("steps", items(8) { JSONObject().put("label", text(36)).put("detail", text(48)) })
    }

    @Test
    fun `weather at its limits`() = check("weather", fits = true) {
        put("location", text(16))
        put("temperature", text(8))
        put("condition", text(24))
        put("high", text(8))
        put("low", text(8))
        put("precipitation", text(12))
        put("humidity", text(12))
        put("wind", text(12))
        put("hourly", items(24) { index -> JSONObject().put("label", text(6)).put("temp", -12 + index) })
        put(
            "forecast",
            items(5) { JSONObject().put("label", text(10)).put("temperature", text(8)).put("condition", text(16)) },
        )
    }

    @Test
    fun `chart at its limits`() = check("chart", fits = true) {
        val labels = (0 until 64).map { text(12) }
        put("chartType", "line")
        put(
            "chartSeries",
            items(4) { index -> JSONObject().put("yName", "value$index").put("label", text(16)) },
        )
        put(
            "chartPoints",
            JSONArray().apply {
                labels.forEachIndexed { point, label ->
                    put(JSONObject().put("label", label).apply { repeat(4) { put("value$it", point * (it + 1)) } })
                }
            },
        )
        put("legend", items(4) { JSONObject().put("label", text(16)) })
        put("caption", text(40))
    }

    private fun check(template: String, fits: Boolean, data: JSONObject.() -> Unit) {
        val page = File("../plugins/assistant/src/main/assets/ink_templates/$template.ink").readText()
        val payload = JSONObject().put("title", text(32)).apply(data)
        val compiled = InkEngine.compile(InkSource.Sfc(page), payload)
        val document = requireNotNull(compiled.document) { "$template must compile: ${compiled.problems}" }
        val view = InkHudView(activity.get())
        // The card on the glasses: its width, and a height the page may use up to but not past.
        val card = FrameLayout(activity.get()).apply {
            addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        activity.get().setContentView(card, ViewGroup.LayoutParams(CARD_WIDTH_PX, CARD_HEIGHT_PX))
        view.show(InkNodeStore.from(document), debugActions = false)
        repeat(3) { shadowOf(Looper.getMainLooper()).idle() }

        val broken = mutableListOf<String>()
        texts(view).filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }.forEach { text ->
            val layout = text.layout
            val needed = layout.height + text.paddingTop + text.paddingBottom
            if (text.height < needed) broken += "squeezed ${text.height}/$needed px: '${text.text}'"
            val ellipsized = (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }
            if (ellipsized) broken += "cut with an ellipsis: '${text.text}'"
            val room = text.width - text.paddingLeft - text.paddingRight
            val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f
            if (widest > room + 1f) broken += "clipped ${widest.toInt()}/$room px wide: '${text.text}'"
        }
        if (fits) {
            val lowest = descendants(view).filter { it.visibility == View.VISIBLE }.maxBy { bottomIn(view, it) }
            val bottom = bottomIn(view, lowest)
            if (bottom > CARD_HEIGHT_PX) {
                broken += "page runs to $bottom px, past the card's $CARD_HEIGHT_PX " +
                    "(${lowest.javaClass.simpleName} ${lowest.width}×${lowest.height} at top ${lowest.top})"
            }
        }
        assertTrue("$template:\n" + broken.joinToString("\n"), broken.isEmpty())
    }

    private fun labeledValue(label: Int, value: Int, detail: Int): JSONObject = JSONObject()
        .put("label", text(label))
        .put("value", text(value))
        .put("detail", text(detail))

    private fun items(count: Int, item: (Int) -> JSONObject) = JSONArray().apply {
        repeat(count) { put(item(it)) }
    }

    private fun texts(view: View): List<TextView> = descendants(view).filterIsInstance<TextView>()

    private fun descendants(view: View): List<View> = listOf(view) +
        ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { descendants(group.getChildAt(it)) } }
            ?: emptyList())

    private fun bottomIn(root: View, view: View): Int {
        var bottom = view.height
        var current: View = view
        while (current !== root) {
            bottom += current.top - ((current.parent as? View)?.scrollY ?: 0)
            current = current.parent as? View ?: break
        }
        return bottom
    }

    private companion object {
        /** The glasses' card: 92 % of the 480 px display, and all of it below the 18 px top margin. */
        const val CARD_WIDTH_PX = 441
        const val CARD_HEIGHT_PX = 622

        /** Words of real length, so wrapping happens where it would; monospace makes them all as wide. */
        fun text(length: Int): String {
            val words = "Chamonix Mont Blanc glacier sunrise traverse ridge summit valley"
            return List(length / words.length + 1) { words }.joinToString(" ")
                .take(length).trimEnd().padEnd(length, 'x')
        }
    }
}
