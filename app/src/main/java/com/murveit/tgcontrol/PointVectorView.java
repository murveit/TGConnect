package com.murveit.tgcontrol;

/**
 * Point Vector View - Algorithmic Overview
 *
 * Custom hardware-accelerated View that renders a real-time top-down court diagram
 * of an in-progress or completed SINGLES/DOUBLES tennis point.
 *
 * 1. INITIALIZATION:
 * - Instantiated from XML (standard Context + AttributeSet).
 * - Paint objects created once at init time to avoid allocation in onDraw.
 *
 * 2. CALLING PROCEDURE:
 * - Call setPointData(List<PointEvent>) whenever a POINT_UPDATE_JSON arrives.
 * - setPointData() calls invalidate() to schedule an asynchronous redraw.
 * - Call clearPoint() to erase all data (e.g. new-point start, clear button).
 *
 * 3. INTERNAL ALGORITHMIC LOGIC:
 * - Coordinate system: court meters, X=0 center, Y=0 net, north=positive, south=negative.
 *   VIEW_MIN_Y = -13f (south), VIEW_MAX_Y = 13f (north).  North baseline at screen top.
 * - Court lines drawn for both half-courts: baselines, singles/doubles sidelines,
 *   service lines, center service lines, net.  Doubles alleys rendered at half opacity.
 * - For each PointEvent: a solid coloured line from (hitX, hitY) to (bounceX, bounceY).
 *   Colour for prior strokes: green=In, red=Out/Fault, yellow=Let.
 * - Most recent stroke always drawn in bright yellow (RECENT_STROKE_COLOR) with a thicker
 *   line width so it stands out at a glance regardless of its call type.
 * - Consecutive events connected by a dashed grey line: bounceN → hitN+1.
 * - Circular dots drawn at each hit and bounce position.
 * - White ring on most-recent bounce dot provides additional emphasis.
 *
 * 4. EXPECTED OUTPUTS / SIDE EFFECTS:
 * - Pushes 2D vector shapes to the hardware-accelerated Android Canvas.
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

public class PointVectorView extends View {

    public static class PointEvent {
        public float hitX, hitY;       // Strike position in court meters
        public float bounceX, bounceY; // Bounce position in court meters
        public String call;            // "In", "Out", "Fault", "Let"
        public String type;            // "serve", "hit"

        public PointEvent(float hitX, float hitY, float bounceX, float bounceY,
                          String call, String type) {
            this.hitX    = hitX;
            this.hitY    = hitY;
            this.bounceX = bounceX;
            this.bounceY = bounceY;
            this.call    = call;
            this.type    = type;
        }
    }

    // --- Court Dimensions (meters) ---
    private static final float COURT_HALF_SINGLES_WIDTH  = 4.115f;
    private static final float COURT_HALF_DOUBLES_WIDTH  = 5.485f;
    private static final float COURT_SERVICE_LINE_DEPTH  = 6.40f;
    private static final float COURT_BASELINE_DEPTH      = 11.885f;

    // --- View coordinate bounds (meters): extra margin around the full court ---
    private static final float VIEW_MIN_X = -7.0f;
    private static final float VIEW_MAX_X =  7.0f;
    private static final float VIEW_MIN_Y = -13.5f; // south side
    private static final float VIEW_MAX_Y =  13.5f; // north side

    // --- Visual sizing constants ---
    private static final float HIT_DOT_RADIUS    = 10f;
    private static final float BOUNCE_DOT_RADIUS = 12f;
    private static final float VECTOR_STROKE_W   =  6f;
    private static final float RECENT_STROKE_W   = 10f; // thicker for most-recent stroke
    private static final float HIGHLIGHT_RING_W  =  5f;
    private static final float LINE_STROKE_W     =  4f;

    // Bright yellow for the most-recent stroke so it stands out from call-colored prior strokes.
    private static final String RECENT_STROKE_COLOR = "#FFFF00";

    // --- Paints ---
    private final Paint courtPaint    = new Paint();
    private final Paint linePaint     = new Paint();
    private final Paint alleyPaint    = new Paint(); // dimmer for doubles alleys
    private final Paint netPaint      = new Paint();
    private final Paint inPaint       = new Paint();
    private final Paint outPaint      = new Paint();
    private final Paint letPaint      = new Paint();
    private final Paint connectPaint    = new Paint(); // dashed connector between events
    private final Paint highlightPaint  = new Paint(); // white ring on most-recent bounce dot
    private final Paint recentPaint     = new Paint(); // yellow for most-recent stroke and dots
    private final Paint dotPaint        = new Paint();

    private List<PointEvent> events = new ArrayList<>();

    public PointVectorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    private void initPaints() {
        courtPaint.setColor(Color.parseColor("#1B3329")); // deep green
        courtPaint.setStyle(Paint.Style.FILL);

        linePaint.setColor(Color.WHITE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(LINE_STROKE_W);
        linePaint.setAntiAlias(true);

        // Doubles alleys shown at half opacity so they're visible but secondary
        alleyPaint.setColor(Color.parseColor("#80FFFFFF"));
        alleyPaint.setStyle(Paint.Style.STROKE);
        alleyPaint.setStrokeWidth(LINE_STROKE_W);
        alleyPaint.setAntiAlias(true);

        netPaint.setColor(Color.parseColor("#90A4AE"));
        netPaint.setStyle(Paint.Style.STROKE);
        netPaint.setStrokeWidth(8f);
        netPaint.setAntiAlias(true);
        netPaint.setPathEffect(new DashPathEffect(new float[]{20f, 15f}, 0f));

        inPaint.setColor(Color.parseColor("#00E676")); // bright green
        inPaint.setStyle(Paint.Style.STROKE);
        inPaint.setStrokeWidth(VECTOR_STROKE_W);
        inPaint.setAntiAlias(true);

        outPaint.setColor(Color.parseColor("#FF1744")); // bright red
        outPaint.setStyle(Paint.Style.STROKE);
        outPaint.setStrokeWidth(VECTOR_STROKE_W);
        outPaint.setAntiAlias(true);

        letPaint.setColor(Color.parseColor("#FFEA00")); // bright yellow
        letPaint.setStyle(Paint.Style.STROKE);
        letPaint.setStrokeWidth(VECTOR_STROKE_W);
        letPaint.setAntiAlias(true);

        connectPaint.setColor(Color.parseColor("#80FFFFFF")); // translucent white
        connectPaint.setStyle(Paint.Style.STROKE);
        connectPaint.setStrokeWidth(3f);
        connectPaint.setAntiAlias(true);
        connectPaint.setPathEffect(new DashPathEffect(new float[]{12f, 10f}, 0f));

        highlightPaint.setColor(Color.WHITE);
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeWidth(HIGHLIGHT_RING_W);
        highlightPaint.setAntiAlias(true);

        // recentPaint: bright yellow, thicker stroke — used for the most-recent stroke's
        // vector and dots so it stands out from prior call-colored strokes at a glance.
        recentPaint.setColor(Color.parseColor(RECENT_STROKE_COLOR));
        recentPaint.setStyle(Paint.Style.STROKE);
        recentPaint.setStrokeWidth(RECENT_STROKE_W);
        recentPaint.setAntiAlias(true);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setAntiAlias(true);
    }

    public void setPointData(List<PointEvent> data) {
        this.events = data != null ? data : new ArrayList<>();
        invalidate();
    }

    public void clearPoint() {
        this.events = new ArrayList<>();
        invalidate();
    }

    // Map court meters to screen pixels (X axis)
    private float mapX(float x, int w) {
        return (x - VIEW_MIN_X) / (VIEW_MAX_X - VIEW_MIN_X) * w;
    }

    // Map court meters to screen pixels (Y axis); north (positive) at top, south at bottom
    private float mapY(float y, int h) {
        return h - ((y - VIEW_MIN_Y) / (VIEW_MAX_Y - VIEW_MIN_Y) * h);
    }

    private Paint paintForCall(String call) {
        if ("In".equalsIgnoreCase(call))                                     return inPaint;
        if ("Let".equalsIgnoreCase(call))                                    return letPaint;
        return outPaint; // Out or Fault
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        // 1. Court background
        canvas.drawRect(0, 0, w, h, courtPaint);

        // 2. Court lines — draw both north and south halves symmetrically

        // Baselines (north: +Y, south: -Y)
        canvas.drawLine(mapX(-COURT_HALF_DOUBLES_WIDTH, w), mapY(+COURT_BASELINE_DEPTH, h),
                        mapX(+COURT_HALF_DOUBLES_WIDTH, w), mapY(+COURT_BASELINE_DEPTH, h), linePaint);
        canvas.drawLine(mapX(-COURT_HALF_DOUBLES_WIDTH, w), mapY(-COURT_BASELINE_DEPTH, h),
                        mapX(+COURT_HALF_DOUBLES_WIDTH, w), mapY(-COURT_BASELINE_DEPTH, h), linePaint);

        // Singles sidelines (both sides, full length)
        canvas.drawLine(mapX(-COURT_HALF_SINGLES_WIDTH, w), mapY(+COURT_BASELINE_DEPTH, h),
                        mapX(-COURT_HALF_SINGLES_WIDTH, w), mapY(-COURT_BASELINE_DEPTH, h), linePaint);
        canvas.drawLine(mapX(+COURT_HALF_SINGLES_WIDTH, w), mapY(+COURT_BASELINE_DEPTH, h),
                        mapX(+COURT_HALF_SINGLES_WIDTH, w), mapY(-COURT_BASELINE_DEPTH, h), linePaint);

        // Doubles sidelines (dimmed alleys)
        canvas.drawLine(mapX(-COURT_HALF_DOUBLES_WIDTH, w), mapY(+COURT_BASELINE_DEPTH, h),
                        mapX(-COURT_HALF_DOUBLES_WIDTH, w), mapY(-COURT_BASELINE_DEPTH, h), alleyPaint);
        canvas.drawLine(mapX(+COURT_HALF_DOUBLES_WIDTH, w), mapY(+COURT_BASELINE_DEPTH, h),
                        mapX(+COURT_HALF_DOUBLES_WIDTH, w), mapY(-COURT_BASELINE_DEPTH, h), alleyPaint);

        // Service lines (north and south)
        canvas.drawLine(mapX(-COURT_HALF_SINGLES_WIDTH, w), mapY(+COURT_SERVICE_LINE_DEPTH, h),
                        mapX(+COURT_HALF_SINGLES_WIDTH, w), mapY(+COURT_SERVICE_LINE_DEPTH, h), linePaint);
        canvas.drawLine(mapX(-COURT_HALF_SINGLES_WIDTH, w), mapY(-COURT_SERVICE_LINE_DEPTH, h),
                        mapX(+COURT_HALF_SINGLES_WIDTH, w), mapY(-COURT_SERVICE_LINE_DEPTH, h), linePaint);

        // Center service lines (net to service line, both halves)
        canvas.drawLine(mapX(0, w), mapY(0, h),
                        mapX(0, w), mapY(+COURT_SERVICE_LINE_DEPTH, h), linePaint);
        canvas.drawLine(mapX(0, w), mapY(0, h),
                        mapX(0, w), mapY(-COURT_SERVICE_LINE_DEPTH, h), linePaint);

        // Center marks on baselines
        canvas.drawLine(mapX(0, w), mapY(+COURT_BASELINE_DEPTH, h),
                        mapX(0, w), mapY(+COURT_BASELINE_DEPTH - 0.2f, h), linePaint);
        canvas.drawLine(mapX(0, w), mapY(-COURT_BASELINE_DEPTH, h),
                        mapX(0, w), mapY(-COURT_BASELINE_DEPTH + 0.2f, h), linePaint);

        // Net
        canvas.drawLine(mapX(-COURT_HALF_DOUBLES_WIDTH - 0.5f, w), mapY(0, h),
                        mapX(+COURT_HALF_DOUBLES_WIDTH + 0.5f, w), mapY(0, h), netPaint);

        // 3. Tennis vectors and connectors
        if (events.isEmpty()) return;

        for (int i = 0; i < events.size(); i++) {
            PointEvent ev = events.get(i);
            boolean isLast = (i == events.size() - 1);
            Paint vPaint = paintForCall(ev.call);

            float hx = mapX(ev.hitX,    w);
            float hy = mapY(ev.hitY,    h);
            float bx = mapX(ev.bounceX, w);
            float by = mapY(ev.bounceY, h);

            // Dashed connector: previous bounce → this hit (skip for first event)
            if (i > 0) {
                PointEvent prev = events.get(i - 1);
                canvas.drawLine(mapX(prev.bounceX, w), mapY(prev.bounceY, h),
                                hx, hy, connectPaint);
            }

            // Vector: hit → bounce.
            // Most-recent stroke: bright yellow + thicker. Prior strokes: call-color (green/red/yellow).
            Paint activePaint = isLast ? recentPaint : vPaint;
            canvas.drawLine(hx, hy, bx, by, activePaint);

            // Hit dot and bounce dot use the same color as their stroke.
            dotPaint.setColor(activePaint.getColor());
            canvas.drawCircle(hx, hy, HIT_DOT_RADIUS, dotPaint);
            canvas.drawCircle(bx, by, BOUNCE_DOT_RADIUS, dotPaint);

            // White ring on most-recent bounce for additional emphasis
            if (isLast) {
                canvas.drawCircle(bx, by, BOUNCE_DOT_RADIUS + 4f, highlightPaint);
            }
        }
    }
}
