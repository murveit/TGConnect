package com.murveit.tgcontrol;

/**
 * Serve Scatter View - Algorithmic Overview
 *
 * This module provides a fast, custom-drawn 2D View that renders a top-down scatter
 * plot of serve impacts relative to the tennis service boxes.
 *
 * 1. INITIALIZATION:
 * - Parameterized by standard Android Context and AttributeSet for XML inflation.
 * - Instantiates distinct Paint objects (Lines, Net, In, Out, Let, Highlight) once to avoid
 * allocation overhead during the high-frequency onDraw loop.
 *
 * 2. CALLING PROCEDURE:
 * - Hidden by default in XML, toggled via MainActivity.
 * - The controller passes a List of `ServeImpact` objects via `setServes()`.
 * - The `invalidate()` call flags the Android UI pipeline to asynchronously redraw the Canvas.
 *
 * 3. INTERNAL ALGORITHMIC LOGIC:
 * - Coordinate Mapping: Translates physical meters (where X=0 is the center line and Y=0 is the net)
 * into standard Android screen pixels.
 * - Canonical Half-Court Folding: Dynamically detects South court coordinates (Y < 0) and 
 * applies a 180-degree rotation (-X, -Y) to perfectly fold them onto the North court UI 
 * while preserving the true left/right visual orientation for Deuce/Ad.
 * - The mapping intentionally inverts the Y-axis so the Net (Y=0) is rendered at the bottom 
 * of the View, mimicking the server's forward-looking perspective.
 * - The X-axis is inverted to correctly mirror the visual representation of cross-court serves.
 * - Field of View (FOV) Clamping: Mathematically clamps physical impacts to the boundaries 
 * of the canvas. If a massive fault lands outside the mapped coordinate system, it is pinned 
 * to the extreme edge of the screen so it is never lost or drawn invisibly.
 * - Render Order: Draws the court background, then the physical white lines (including a custom 
 * dashed net), then iterates chronologically through the serve impacts. This guarantees that newer 
 * serves paint over older ones.
 * - Highlighting: The absolute last element in the chronological array receives a secondary 
 * stroked circle pass, explicitly highlighting the most recent serve.
 *
 * 4. EXPECTED OUTPUTS / SIDE EFFECTS:
 * - Pushes 2D vector shapes directly to the hardware-accelerated Android Canvas.
 */

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class ServeScatterView extends View {

    public static class ServeImpact {
        public float x;
        public float y;
        public String call;

        public ServeImpact(float x, float y, String call) {
            this.x = x;
            this.y = y;
            this.call = call;
        }
    }

    // --- Algorithmic Constants for Physical Court Mapping (Meters) ---
    private static final float COURT_HALF_SINGLES_WIDTH = 4.115f;
    private static final float COURT_HALF_DOUBLES_WIDTH = 5.485f;
    private static final float COURT_SERVICE_LINE_DEPTH = 6.40f;
    private static final float COURT_BASELINE_DEPTH = 11.885f;

    // Expanded bounds to capture very wide or deep faults
    private static final float VIEW_MIN_X = -7.5f;
    private static final float VIEW_MAX_X = 7.5f;
    private static final float VIEW_MIN_Y = -1.0f;
    private static final float VIEW_MAX_Y = 13.5f;

    // --- Hardware Rendering Paints ---
    private final Paint courtPaint = new Paint();
    private final Paint linePaint = new Paint();
    private final Paint netPaint = new Paint();
    private final Paint inPaint = new Paint();
    private final Paint outPaint = new Paint();
    private final Paint letPaint = new Paint();
    private final Paint highlightPaint = new Paint();

    private List<ServeImpact> serveImpacts = new ArrayList<>();

    public ServeScatterView(Context context, AttributeSet attrs) {
        super(context, attrs);

        courtPaint.setColor(Color.parseColor("#1B3329")); // Deep subdued green
        courtPaint.setStyle(Paint.Style.FILL);

        linePaint.setColor(Color.WHITE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(4f);
        linePaint.setAntiAlias(true);

        // Distinctive net styling (thicker, distinctly colored, dashed)
        netPaint.setColor(Color.parseColor("#90A4AE")); // Cool metallic grey
        netPaint.setStyle(Paint.Style.STROKE);
        netPaint.setStrokeWidth(8f);
        netPaint.setAntiAlias(true);
        netPaint.setPathEffect(new DashPathEffect(new float[]{20f, 15f}, 0f));

        inPaint.setColor(Color.parseColor("#00E676")); // Bright Green
        inPaint.setStyle(Paint.Style.FILL);
        inPaint.setAntiAlias(true);

        outPaint.setColor(Color.parseColor("#FF1744")); // Bright Red
        outPaint.setStyle(Paint.Style.FILL);
        outPaint.setAntiAlias(true);

        letPaint.setColor(Color.parseColor("#FFEA00")); // Bright Yellow
        letPaint.setStyle(Paint.Style.FILL);
        letPaint.setAntiAlias(true);

        highlightPaint.setColor(Color.WHITE);
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeWidth(5f);
        highlightPaint.setAntiAlias(true);
    }

    public void setServes(List<ServeImpact> impacts) {
        this.serveImpacts = impacts;
        invalidate(); // Trigger hardware redraw
    }

    // Mathematical translation from physical meters to screen pixels
    private float mapX(float x, int width) {
        return (x - VIEW_MIN_X) / (VIEW_MAX_X - VIEW_MIN_X) * width;
    }

    private float mapY(float y, int height) {
        // Invert Y so 0.0 (Net) is at the bottom of the screen
        return height - ((y - VIEW_MIN_Y) / (VIEW_MAX_Y - VIEW_MIN_Y) * height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();

        // 1. Draw Court Background
        canvas.drawRect(0, 0, w, h, courtPaint);

        // 2. Draw Mathematical Court Lines
        // Net (Extends slightly past doubles lines)
        canvas.drawLine(mapX(-COURT_HALF_DOUBLES_WIDTH - 0.5f, w), mapY(0, h), 
                        mapX(COURT_HALF_DOUBLES_WIDTH + 0.5f, w), mapY(0, h), netPaint);
        
        // Service Line
        canvas.drawLine(mapX(-COURT_HALF_SINGLES_WIDTH, w), mapY(COURT_SERVICE_LINE_DEPTH, h), 
                        mapX(COURT_HALF_SINGLES_WIDTH, w), mapY(COURT_SERVICE_LINE_DEPTH, h), linePaint);
        
        // Baseline
        canvas.drawLine(mapX(-COURT_HALF_DOUBLES_WIDTH, w), mapY(COURT_BASELINE_DEPTH, h), 
                        mapX(COURT_HALF_DOUBLES_WIDTH, w), mapY(COURT_BASELINE_DEPTH, h), linePaint);

        // Center Service Line
        canvas.drawLine(mapX(0, w), mapY(0, h), 
                        mapX(0, w), mapY(COURT_SERVICE_LINE_DEPTH, h), linePaint);
        
        // Center Mark on Baseline
        canvas.drawLine(mapX(0, w), mapY(COURT_BASELINE_DEPTH, h), 
                        mapX(0, w), mapY(COURT_BASELINE_DEPTH - 0.2f, h), linePaint);
                        
        // Singles Lines
        canvas.drawLine(mapX(-COURT_HALF_SINGLES_WIDTH, w), mapY(0, h), 
                        mapX(-COURT_HALF_SINGLES_WIDTH, w), mapY(COURT_BASELINE_DEPTH, h), linePaint);
        canvas.drawLine(mapX(COURT_HALF_SINGLES_WIDTH, w), mapY(0, h), 
                        mapX(COURT_HALF_SINGLES_WIDTH, w), mapY(COURT_BASELINE_DEPTH, h), linePaint);

        // Doubles Lines
        canvas.drawLine(mapX(-COURT_HALF_DOUBLES_WIDTH, w), mapY(0, h), 
                        mapX(-COURT_HALF_DOUBLES_WIDTH, w), mapY(COURT_BASELINE_DEPTH, h), linePaint);
        canvas.drawLine(mapX(COURT_HALF_DOUBLES_WIDTH, w), mapY(0, h), 
                        mapX(COURT_HALF_DOUBLES_WIDTH, w), mapY(COURT_BASELINE_DEPTH, h), linePaint);

        // 3. Draw Scatter Plot Points (Chronological)
        if (serveImpacts == null || serveImpacts.isEmpty()) {
            return;
        }

        float radius = 12f;
        for (int i = 0; i < serveImpacts.size(); i++) {
            ServeImpact impact = serveImpacts.get(i);
            
            float renderX = impact.x;
            float renderY = impact.y;

            // --- CANONICAL HALF-COURT FOLDING ---
            // The tracking engine natively outputs unified global coordinates.
            // Serves in the South court (Y < 0) are mathematically rotated 180 degrees 
            // (-X, -Y) to perfectly overlay onto the single canonical half-court UI.
            if (renderY < 0) {
                renderX = -renderX;
                renderY = -renderY;
            }
            
            float px = mapX(renderX, w);
            float py = mapY(renderY, h);

            // --- FOV CLAMPING ---
            // If a fault lands outside the mapped dimensions, pin it to the exact edge
            // of the Canvas (minus the dot radius) so the user always sees it.
            px = Math.max(radius + 2, Math.min(w - radius - 2, px));
            py = Math.max(radius + 2, Math.min(h - radius - 2, py));

            Paint activePaint;
            if ("In".equalsIgnoreCase(impact.call)) {
                activePaint = inPaint;
            } else if ("Let".equalsIgnoreCase(impact.call)) {
                activePaint = letPaint;
            } else {
                activePaint = outPaint; // Fault or Out
            }

            canvas.drawCircle(px, py, radius, activePaint);

            // 4. Highlight the absolute most recent serve so it pops out of the cluster
            if (i == serveImpacts.size() - 1) {
                canvas.drawCircle(px, py, radius + 4f, highlightPaint);
            }
        }
    }
}
