package com.anezium.lensspike;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LensOverlayView extends View {
    public static final class Metrics {
        public final long avgLatencyMs;
        public final long minLatencyMs;
        public final long maxLatencyMs;
        public final double hz;
        public final long framesAnalyzed;
        public final long elapsedMs;

        Metrics(long avgLatencyMs, long minLatencyMs, long maxLatencyMs,
                double hz, long framesAnalyzed, long elapsedMs) {
            this.avgLatencyMs = avgLatencyMs;
            this.minLatencyMs = minLatencyMs;
            this.maxLatencyMs = maxLatencyMs;
            this.hz = hz;
            this.framesAnalyzed = framesAnalyzed;
            this.elapsedMs = elapsedMs;
        }
    }

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Rect> blocks = new ArrayList<>();
    private String mode = "LATIN";
    private String status = "STARTING";
    private int imageWidth;
    private int imageHeight;
    private int blockCount;
    private long avgLatencyMs;
    private long minLatencyMs;
    private long maxLatencyMs;
    private double hz;
    private long framesAnalyzed;
    private long elapsedMs;
    private float batteryTempC = Float.NaN;

    public LensOverlayView(Context context) {
        super(context);
        init();
    }

    public LensOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LensOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        boxPaint.setColor(Color.rgb(0, 255, 102));
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3f);

        hudTextPaint.setColor(Color.rgb(0, 255, 102));
        hudTextPaint.setTypeface(Typeface.MONOSPACE);
        hudTextPaint.setTextSize(13f * getResources().getDisplayMetrics().scaledDensity);

        hudBgPaint.setColor(Color.BLACK);
        hudBgPaint.setStyle(Paint.Style.FILL);
    }

    public void setMode(String mode) {
        this.mode = mode;
        invalidate();
    }

    public void setStatus(String status) {
        this.status = status;
        invalidate();
    }

    public void updateBatteryTemp(float batteryTempC) {
        this.batteryTempC = batteryTempC;
        invalidate();
    }

    public void updateOcrResult(String mode, int imageWidth, int imageHeight, List<Rect> blockBounds,
                                int blockCount, Metrics metrics) {
        this.mode = mode;
        this.status = "OCR ACTIVE";
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.blocks.clear();
        this.blocks.addAll(blockBounds);
        this.blockCount = blockCount;
        this.avgLatencyMs = metrics.avgLatencyMs;
        this.minLatencyMs = metrics.minLatencyMs;
        this.maxLatencyMs = metrics.maxLatencyMs;
        this.hz = metrics.hz;
        this.framesAnalyzed = metrics.framesAnalyzed;
        this.elapsedMs = metrics.elapsedMs;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        for (Rect block : blocks) {
            RectF mapped = mapToView(new RectF(block), imageWidth, imageHeight);
            if (mapped != null) {
                canvas.drawRect(mapped, boxPaint);
            }
        }

        drawHud(canvas);
    }

    private void drawHud(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float pad = 7f * density;
        float lineHeight = hudTextPaint.getTextSize() + 3f * density;
        String[] lines = new String[] {
                "MODE " + mode,
                "AVG " + avgLatencyMs + "ms MIN " + minLatencyMs + " MAX " + maxLatencyMs,
                String.format(Locale.US, "HZ %.1f BLOCKS %d", hz, blockCount),
                "TEMP " + formatTemp(batteryTempC),
                "FRAMES " + framesAnalyzed + " TIME " + formatElapsed(elapsedMs),
                status
        };

        float maxWidth = 0f;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, hudTextPaint.measureText(line));
        }

        float left = 0f;
        float top = 0f;
        float right = Math.min(getWidth(), maxWidth + pad * 2f);
        float bottom = Math.min(getHeight(), pad * 2f + lineHeight * lines.length);
        canvas.drawRect(left, top, right, bottom, hudBgPaint);

        float y = pad + hudTextPaint.getTextSize();
        for (String line : lines) {
            canvas.drawText(line, pad, y, hudTextPaint);
            y += lineHeight;
        }
    }

    private RectF mapToView(RectF rect, int iw, int ih) {
        if (getWidth() == 0 || getHeight() == 0 || iw <= 0 || ih <= 0) return null;
        float s = Math.max(getWidth() / (float) iw, getHeight() / (float) ih);
        float dx = (getWidth() - iw * s) / 2f;
        float dy = (getHeight() - ih * s) / 2f;
        return new RectF(rect.left * s + dx, rect.top * s + dy,
                rect.right * s + dx, rect.bottom * s + dy);
    }

    private static String formatTemp(float tempC) {
        if (Float.isNaN(tempC)) {
            return "NaN";
        }
        return String.format(Locale.US, "%.1fC", tempC);
    }

    private static String formatElapsed(long elapsedMs) {
        long totalSeconds = Math.max(0L, elapsedMs / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
