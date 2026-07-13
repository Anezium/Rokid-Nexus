package com.anezium.rokidbus.glasses

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import com.anezium.rokidbus.shared.CameraOverlayBounds
import com.anezium.rokidbus.shared.CameraOverlayContract
import com.anezium.rokidbus.shared.CameraOverlayItem
import com.anezium.rokidbus.shared.CameraOverlayLayout

/** Debug-only deterministic camera-overlay fixtures that do not require the phone or OCR. */
class CameraFixtureActivity : Activity() {
    private lateinit var overlay: CameraOverlayView
    private var fixture = FIXTURE_LIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixture = intent.getStringExtra(EXTRA_FIXTURE).orEmpty().ifBlank { FIXTURE_LIVE }
        overlay = CameraOverlayView(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(25, 25, 25)) }
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
        showFixture()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            overlay.setFrozenDisplayScale(2f)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            overlay.setFrozenDisplayScale(1f)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showFixture() {
        when (fixture) {
            FIXTURE_FROZEN -> {
                overlay.setMode(CameraOverlayMode.FROZEN)
                val bitmap = gridBitmap()
                overlay.setFrozenBackground(
                    bitmap,
                    CameraPreviewGeometry.matchingFrozenSourceViewport(
                        frozenWidth = bitmap.width,
                        frozenHeight = bitmap.height,
                        liveWidth = 720,
                        liveHeight = 1_280,
                        viewWidth = 480,
                        viewHeight = 640,
                    ),
                )
                overlay.updateStatus("FIXTURE / FROZEN", "DPAD ZOOM")
                overlay.updateOverlay(frozenItems())
            }
            FIXTURE_LEGACY -> {
                overlay.setMode(CameraOverlayMode.LIVE)
                overlay.updateStatus("FIXTURE / LEGACY")
                overlay.updateOverlay(legacyItems())
            }
            else -> {
                overlay.setMode(CameraOverlayMode.LIVE)
                overlay.updateStatus("FIXTURE / LIVE")
                overlay.updateOverlay(liveItems())
            }
        }
    }

    private fun paragraph(
        id: String,
        text: String,
        box: CameraOverlayBounds,
        lineHeight: Float,
        growDown: Float,
        column: Int,
    ): CameraOverlayItem = CameraOverlayItem(
        id = id,
        text = text,
        box = box,
        role = "translation",
        layout = CameraOverlayLayout(
            kind = CameraOverlayContract.PARAGRAPH_LAYOUT_KIND,
            version = CameraOverlayContract.PARAGRAPH_LAYOUT_VERSION,
            medianLineHeight = lineHeight,
            growDown = growDown,
            column = column,
        ),
    )

    private fun liveItems(): List<CameraOverlayItem> = listOf(
        paragraph(
            id = "fixture-live-long",
            text = "This translated paragraph must use the empty space on its right and below before it is allowed to truncate.",
            box = CameraOverlayBounds(0.08f, 0.22f, 0.34f, 0.33f),
            lineHeight = 0.045f,
            growDown = 0.30f,
            column = 0,
        ),
        paragraph(
            id = "fixture-live-short",
            text = "Separate block",
            box = CameraOverlayBounds(0.58f, 0.62f, 0.82f, 0.70f),
            lineHeight = 0.04f,
            growDown = 0.12f,
            column = 1,
        ),
        CameraOverlayItem(
            id = "fixture-live-source",
            text = "pending source",
            box = CameraOverlayBounds(0.12f, 0.78f, 0.38f, 0.84f),
            role = "source",
            layout = CameraOverlayLayout(
                kind = CameraOverlayContract.PARAGRAPH_LAYOUT_KIND,
                version = CameraOverlayContract.PARAGRAPH_LAYOUT_VERSION,
                medianLineHeight = 0.035f,
                growDown = 0.08f,
                column = 0,
            ),
        ),
    )

    private fun frozenItems(): List<CameraOverlayItem> = listOf(
        paragraph(
            id = "fixture-frozen-left",
            text = "A long frozen translation should grow in multiple directions and show complete horizontal lines.",
            box = CameraOverlayBounds(0.08f, 0.18f, 0.32f, 0.28f),
            lineHeight = 0.04f,
            growDown = 0f,
            column = 0,
        ),
        paragraph(
            id = "fixture-frozen-right",
            text = "Another translated paragraph occupies a staggered neighboring column without overlapping the first panel.",
            box = CameraOverlayBounds(0.62f, 0.48f, 0.88f, 0.58f),
            lineHeight = 0.04f,
            growDown = 0f,
            column = 1,
        ),
    )

    private fun legacyItems(): List<CameraOverlayItem> = listOf(
        CameraOverlayItem(
            id = "fixture-legacy",
            text = "Legacy fixed box",
            box = CameraOverlayBounds(0.20f, 0.30f, 0.55f, 0.42f),
            role = "translation",
        ),
    )

    private fun gridBitmap(): Bitmap = Bitmap.createBitmap(720, 960, Bitmap.Config.RGB_565).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(20, 20, 20))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(50, 180, 90)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        for (x in 0..720 step 120) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 960f, paint)
        for (y in 0..960 step 120) canvas.drawLine(0f, y.toFloat(), 720f, y.toFloat(), paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 36f
        canvas.drawText("TOP LEFT", 20f, 50f, paint)
        canvas.drawText("BOTTOM RIGHT", 430f, 930f, paint)
    }

    companion object {
        const val EXTRA_FIXTURE = "fixture"
        const val FIXTURE_LIVE = "live"
        const val FIXTURE_FROZEN = "frozen"
        const val FIXTURE_LEGACY = "legacy"
    }
}
