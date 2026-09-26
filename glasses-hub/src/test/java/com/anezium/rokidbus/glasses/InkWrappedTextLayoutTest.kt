package com.anezium.rokidbus.glasses

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.anezium.rokidbus.ink.InkEngine
import com.anezium.rokidbus.ink.InkSource
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real text measurement: wrapping only shows up once lines are actually laid out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "w320dp-h427dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkWrappedTextLayoutTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup()

    @After
    fun tearDown() {
        activity.pause().stop().destroy()
    }

    @Test
    fun `a value that wraps once its tile is stretched grows the tile instead of spilling out`() {
        val view = show(
            """{"title":"Résultat","cells":[{"label":"127 × 43","value":"5 461"},""" +
                """{"label":"Calcul","value":"127 × 40 + 127 × 3"}]}""",
        )

        val value = texts(view).single { it.text.toString() == "127 × 40 + 127 × 3" }
        assertTrue("the value should wrap in its tile", value.lineCount > 1)
        texts(view).forEach { text ->
            assertEquals("height of '${text.text}'", neededHeight(text), text.height)
        }
    }

    private fun show(data: String): InkHudView {
        val compiled = InkEngine.compile(InkSource.Sfc(METRICS_PAGE), JSONObject(data))
        val document = requireNotNull(compiled.document) { "fixture must compile: ${compiled.problems}" }
        val view = InkHudView(activity.get())
        activity.get().setContentView(view)
        view.show(InkNodeStore.from(document), debugActions = false)
        repeat(3) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(440, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, 440, view.measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return view
    }

    private fun neededHeight(text: TextView): Int = text.layout.height + text.paddingTop + text.paddingBottom

    private fun texts(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private companion object {
        // The Assistant's metrics template, reduced to what shapes the tiles.
        const val METRICS_PAGE = """
<script type="application/json" def>
{ "data": { "title": "", "cells": [] } }
</script>

<page>
  <view class="page">
    <text class="title">{{ title }}</text>
    <view class="cells">
      <view class="cell" wx:for="{{ cells }}">
        <text class="label">{{ item.label }}</text>
        <text class="value">{{ item.value }}</text>
      </view>
    </view>
  </view>
</page>

<style>
.page { display: flex; flex-direction: column; gap: 16rpx; width: 100%; box-sizing: border-box; padding: 18rpx; }
.title { font-size: 34rpx; font-weight: 700; line-height: 40rpx; }
.cells { display: flex; flex-direction: row; flex-wrap: wrap; gap: 10rpx; }
.cell {
  display: flex; flex-direction: column; gap: 5rpx; flex-grow: 1; flex-basis: 40%; min-width: 0rpx;
  box-sizing: border-box; padding: 12rpx; border-width: 1rpx; border-style: solid;
}
.label { font-size: 19rpx; line-height: 24rpx; }
.value { font-size: 32rpx; font-weight: 700; line-height: 38rpx; }
</style>
"""
    }
}
