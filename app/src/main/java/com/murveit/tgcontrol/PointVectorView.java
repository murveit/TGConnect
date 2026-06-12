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
 * - For each PointEvent with a confirmed bounce: a solid coloured line from
 *   (hitX, hitY) to (bounceX, bounceY), a dashed connector from the previous
 *   bounce to this hit, and circular dots at both positions.
 *   Colour for prior strokes: green=In, red=Out/Fault, yellow=Let.
 * - Most recent resolved stroke always drawn in bright yellow (RECENT_STROKE_COLOR)
 *   with a thicker line width so it stands out at a glance.
 * - White ring on most-recent bounce dot provides additional emphasis.
 * - Pending stroke (ball in flight, pending=true or bounceX/Y==0): draws the
 *   dashed connector from the previous bounce to this hit, the hit label, and a
 *   dashed yellow ring at the hit position; no arc, no bounce dot.
 * - Hit position is labelled: "S" for serve (index 0), "R" for return (index 1),
 *   numeric "3", "4", … for subsequent strokes.  Label color matches the stroke color.
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
        public float hitX, hitY;         // Strike position in court meters
        public float bounceX, bounceY;   // First bounce position in court meters (ignored when pending)
        public float bounce2X, bounce2Y; // Second bounce (double-bounce only); 0,0 = absent
        public String call;              // "In", "Out", "Fault", "Let"
        public String type;              // "serve", "hit"
        public boolean pending;          // true = ball in flight, no confirmed bounce yet

        public PointEvent(float hitX, float hitY, float bounceX, float bounceY,
                          String call, String type) {
            this.hitX    = hitX;
            this.hitY    = hitY;
            this.bounceX = bounceX;
            this.bounceY = bounceY;
            this.call    = call;
            this.type    = type;
            this.pending  = false;
            this.bounce2X = 0f;
            this.bounce2Y = 0f;
        }
    }

    // --- Court Dimensions (meters) ---
    private static final float COURT_HALF_SINGLES_WIDTH  = 4.115f;
    private static final float COURT_HALF_DOUBLES_WIDTH  = 5.485f;
    private static final float COURT_SERVICE_LINE_DEPTH  = 6.40f;
    private static final float COURT_BASELINE_DEPTH      = 11.885f;

    // --- View coordinate bounds (meters): extra margin around the full court ---
    // ±16 m gives ~4 m behind the baseline (11.885 m) for behind-baseline hit positions.
    private static final float VIEW_MIN_X = -7.0f;
    private static final float VIEW_MAX_X =  7.0f;
    private static final float VIEW_MIN_Y = -16.0f; // south side
    private static final float VIEW_MAX_Y =  16.0f; // north side

    // --- Visual sizing constants ---
    private static final float HIT_DOT_RADIUS    = 10f;
    private static final float BOUNCE_DOT_RADIUS = 12f;
    private static final float VECTOR_STROKE_W   =  6f;
    private static final float RECENT_STROKE_W   = 10f; // thicker for most-recent stroke
    private static final float HIGHLIGHT_RING_W  =  5f;
    private static final float LINE_STROKE_W     =  4f;
    // Text height for hit labels (S/R/3/4/…).
    private static final float HIT_LABEL_TEXT_SIZE = 44f;
    // Gap between the label centre and the start/end of lines so they don't overdraw the letter.
    private static final float HIT_LABEL_LINE_OFFSET_PX = 24f;

    // Bright yellow for the most-recent stroke so it stands out from call-colored prior strokes.
    private static final String RECENT_STROKE_COLOR = "#FFFF00";

    // --- Paints ---
    private final Paint courtPaint      = new Paint();
    private final Paint linePaint       = new Paint();
    private final Paint alleyPaint      = new Paint(); // dimmer for doubles alleys
    private final Paint netPaint        = new Paint();
    private final Paint inPaint         = new Paint();
    private final Paint outPaint        = new Paint();
    private final Paint letPaint        = new Paint();
    private final Paint connectPaint    = new Paint(); // dashed connector between events
    private final Paint highlightPaint  = new Paint(); // white ring on most-recent bounce dot
    private final Paint recentPaint     = new Paint(); // yellow for most-recent stroke and dots
    private final Paint dotPaint         = new Paint();
    private final Paint pendingRingPaint = new Paint(); // dashed ring for in-flight (pending) hit
    private final Paint serveRingPaint   = new Paint(); // solid ring always drawn around "S" label
    private final Paint hitLabelPaint    = new Paint(); // text labels (S/R/3/4/…) at hit positions

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

        // Dashed ring drawn around the hit label when the ball is in flight (pending stroke).
        pendingRingPaint.setColor(Color.parseColor(RECENT_STROKE_COLOR));
        pendingRingPaint.setStyle(Paint.Style.STROKE);
        pendingRingPaint.setStrokeWidth(3f);
        pendingRingPaint.setAntiAlias(true);
        pendingRingPaint.setPathEffect(new DashPathEffect(new float[]{8f, 6f}, 0f));

        // Solid ring drawn around the "S" serve label to distinguish it from R/3/4/… labels.
        serveRingPaint.setStyle(Paint.Style.STROKE);
        serveRingPaint.setStrokeWidth(2.5f);
        serveRingPaint.setAntiAlias(true);

        // Bold centered text for hit labels (S, R, 3, 4, …).
        hitLabelPaint.setStyle(Paint.Style.FILL);
        hitLabelPaint.setAntiAlias(true);
        hitLabelPaint.setTextSize(HIT_LABEL_TEXT_SIZE);
        hitLabelPaint.setTextAlign(Paint.Align.CENTER);
        hitLabelPaint.setFakeBoldText(true);
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

    // Returns the hit label for stroke at list index i: "S" (serve), "R" (return), then "3","4",…
    private String hitLabel(int i) {
        if (i == 0) return "S";
        if (i == 1) return "R";
        return String.valueOf(i + 1);
    }

    // Draws the hit label centred at (x, y) using the given fill color.
    // For the serve (index 0), also draws a solid ring around the label.
    private void drawHitLabel(Canvas canvas, int strokeIndex, float x, float y, int color) {
        hitLabelPaint.setColor(color);
        Paint.FontMetrics fm = hitLabelPaint.getFontMetrics();
        float textY = y - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(hitLabel(strokeIndex), x, textY, hitLabelPaint);
        if (strokeIndex == 0) {
            serveRingPaint.setColor(color);
            canvas.drawCircle(x, y, HIT_LABEL_TEXT_SIZE / 2f + 6f, serveRingPaint);
        }
    }

    // Returns the point offset by `dist` pixels from (fromX,fromY) toward (toX,toY).
    // Returns the original point unchanged if the two points are closer than dist.
    private float[] offsetToward(float fromX, float fromY, float toX, float toY, float dist) {
        float dx = toX - fromX, dy = toY - fromY;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= dist) return new float[]{fromX, fromY};
        return new float[]{fromX + dx / len * dist, fromY + dy / len * dist};
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

            float hx = mapX(ev.hitX, w);
            float hy = mapY(ev.hitY, h);

            // A stroke is "pending" (ball in flight, no confirmed bounce) when either
            // the explicit flag is set or the bounce coords are the unset sentinel (0,0).
            boolean hasBounce = !ev.pending && (ev.bounceX != 0f || ev.bounceY != 0f);

            if (!hasBounce) {
                // Ball is in flight — no confirmed bounce yet.
                // Dashed connector from previous stroke's final bounce to this hit.
                if (i > 0) {
                    PointEvent prev = events.get(i - 1);
                    boolean prevHasB2 = prev.bounce2X != 0f || prev.bounce2Y != 0f;
                    float pbx = prevHasB2 ? mapX(prev.bounce2X, w) : mapX(prev.bounceX, w);
                    float pby = prevHasB2 ? mapY(prev.bounce2Y, h) : mapY(prev.bounceY, h);
                    float[] end = offsetToward(hx, hy, pbx, pby, HIT_LABEL_LINE_OFFSET_PX);
                    canvas.drawLine(pbx, pby, end[0], end[1], connectPaint);
                }
                // Hit label + dashed ring; no arc, no bounce dot.
                drawHitLabel(canvas, i, hx, hy, recentPaint.getColor());
                canvas.drawCircle(hx, hy, HIT_LABEL_TEXT_SIZE / 2f + 8f, pendingRingPaint);
            } else {
                float bx = mapX(ev.bounceX, w);
                float by = mapY(ev.bounceY, h);
                boolean hasB2 = ev.bounce2X != 0f || ev.bounce2Y != 0f;
                float b2x = hasB2 ? mapX(ev.bounce2X, w) : 0f;
                float b2y = hasB2 ? mapY(ev.bounce2Y, h) : 0f;
                // "Final" position: B2 for double-bounce, B1 otherwise — connector origin.
                float finalBx = hasB2 ? b2x : bx;
                float finalBy = hasB2 ? b2y : by;

                // Dashed connector: previous stroke's final bounce → this hit.
                if (i > 0) {
                    PointEvent prev = events.get(i - 1);
                    boolean prevHasB2 = prev.bounce2X != 0f || prev.bounce2Y != 0f;
                    float pbx = prevHasB2 ? mapX(prev.bounce2X, w) : mapX(prev.bounceX, w);
                    float pby = prevHasB2 ? mapY(prev.bounce2Y, h) : mapY(prev.bounceY, h);
                    float[] end = offsetToward(hx, hy, pbx, pby, HIT_LABEL_LINE_OFFSET_PX);
                    canvas.drawLine(pbx, pby, end[0], end[1], connectPaint);
                }

                // Vector: start away from the label toward the bounce.
                // Most-recent stroke: bright yellow + thicker. Prior: call-color.
                Paint activePaint = isLast ? recentPaint : vPaint;
                float[] vecStart = offsetToward(hx, hy, bx, by, HIT_LABEL_LINE_OFFSET_PX);
                canvas.drawLine(vecStart[0], vecStart[1], bx, by, activePaint);

                // Hit label and bounce dots use the same color as their stroke.
                drawHitLabel(canvas, i, hx, hy, activePaint.getColor());
                dotPaint.setColor(activePaint.getColor());
                canvas.drawCircle(bx, by, BOUNCE_DOT_RADIUS, dotPaint);

                // Double-bounce: draw B1→B2 segment and a second bounce dot.
                if (hasB2) {
                    canvas.drawLine(bx, by, b2x, b2y, activePaint);
                    canvas.drawCircle(b2x, b2y, BOUNCE_DOT_RADIUS, dotPaint);
                }

                // White ring on the final bounce position for additional emphasis.
                if (isLast) {
                    canvas.drawCircle(finalBx, finalBy, BOUNCE_DOT_RADIUS + 4f, highlightPaint);
                }
            }
        }
    }
}
