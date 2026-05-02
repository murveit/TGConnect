package com.murveit.tgcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Image Histogram - Algorithmic Overview
 *
 * This module provides a fast, custom-drawn View that renders an exposure histogram
 * simulating the back of a professional camera.
 *
 * 1. INITIALIZATION:
 * - Parameterized by standard Android Context and AttributeSet for XML inflation.
 * - Instantiates Paint objects once to avoid allocation overhead during onDraw loop.
 *
 * 2. CALLING PROCEDURE:
 * - Hidden by default (View.GONE) in XML.
 * - An external controller calls `setHistogramData(data, total)` when a new image arrives.
 * - The `invalidate()` call flags the Android UI pipeline to asynchronously redraw the component.
 *
 * 3. INTERNAL ALGORITHMIC LOGIC:
 * - Linear Scaling with Peak Clipping: Rather than using a logarithmic scale that flattens
 * dynamic range, this algorithm enforces a hard ceiling (e.g., 3% of total sampled pixels).
 * This allows massive background spikes (like dark green courts) to clip off the top of
 * the graph, keeping the important highlight and midtone details proportionally visible.
 * - Renders 256 vertical bins representing 8-bit luminance channels (0-255).
 * - Draws subtle dividing gridlines representing shadows, midtones, and highlights.
 *
 * 4. EXPECTED OUTPUTS / SIDE EFFECTS:
 * - Pushes 2D shapes directly to the hardware-accelerated Android Canvas.
 */
public class HistogramView extends View {

    // Limits the Y-axis height to a fraction of total pixels to prevent background washouts
    private static final float PEAK_CLIP_PERCENTAGE = 0.03f;

    private int[] histogramData = new int[256];
    private int maxCap = 1;
    private boolean hasData = false;

    private final Paint barPaint = new Paint();
    private final Paint bgPaint = new Paint();
    private final Paint gridPaint = new Paint();

    public HistogramView(Context context, AttributeSet attrs) {
        super(context, attrs);

        barPaint.setColor(Color.argb(200, 255, 255, 255)); // Semi-transparent white
        barPaint.setStyle(Paint.Style.FILL);

        bgPaint.setColor(Color.argb(128, 0, 0, 0)); // 50% Black overlay
        bgPaint.setStyle(Paint.Style.FILL);

        gridPaint.setColor(Color.argb(80, 255, 255, 255)); // Faint white gridlines
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(2f);
    }

    public void setHistogramData(int[] data, int totalSampledPixels) {
        this.histogramData = data;
        // Clipping Logic: Enforce a peak ceiling based on the total pixel count
        this.maxCap = Math.max(1, (int) (totalSampledPixels * PEAK_CLIP_PERCENTAGE));
        this.hasData = true;
        invalidate(); // Trigger a hardware redraw
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        // 1. Draw Background Box
        canvas.drawRect(0, 0, width, height, bgPaint);

        // 2. Draw Zone Dividers (Quarters: Shadows, Midtones, Highlights)
        canvas.drawLine(width * 0.25f, 0, width * 0.25f, height, gridPaint);
        canvas.drawLine(width * 0.50f, 0, width * 0.50f, height, gridPaint);
        canvas.drawLine(width * 0.75f, 0, width * 0.75f, height, gridPaint);

        if (!hasData) return;

        // 3. Render the 256 luminance bins
        float barWidth = width / 256f;

        for (int i = 0; i < 256; i++) {
            float rawValue = histogramData[i];
            float cappedValue = Math.min(rawValue, maxCap);

            // Normalize 0.0 to 1.0 against the ceiling
            float normalizedHeight = cappedValue / (float) maxCap;
            float actualBarHeight = normalizedHeight * height;

            float left = i * barWidth;
            float top = height - actualBarHeight;
            float right = left + barWidth;
            float bottom = height;

            canvas.drawRect(left, top, right, bottom, barPaint);
        }
    }
}