package com.murveit.tgcontrol;

/**
 * Calibration Overlay View - Algorithmic Overview
 *
 * Custom View drawn on top of CalibrationActivity's ivCalibrationImage. Draws
 * everything that used to be baked server-side into a rendered composite image
 * (the green projected court wireframe, the blue fitted-line segments, the
 * anchor-click/net-strap dots) as a live vector overlay instead, plus the
 * editable-handle markers and live drag-line preview it always drew. The server
 * now sends only a plain, unmarked photo once; all drawing happens here, from
 * geometry data (see CalibrationActivity's GET_TOUR_POINTS response parsing).
 *
 * 1. INITIALIZATION:
 * - Instantiated from XML (standard Context + AttributeSet), matching PointVectorView's
 *   pattern. Paint objects created once at init time to avoid allocation in onDraw.
 *
 * 2. CALLING PROCEDURE:
 * - setImageMatrix(Matrix) must be called every time CalibrationActivity updates its
 *   own imageMatrix (pan/zoom), so everything drawn here tracks the underlying image
 *   exactly. CalibrationActivity holds the single source-of-truth Matrix; this view
 *   never modifies it, only reads it to map bitmap-space points to screen space.
 * - setWireframeSegments/setBlueLineSegments/setAnchorPoints/setStrapPoint supply the
 *   static geometry to draw, in bitmap-space, parsed from a GET_TOUR_POINTS response.
 * - setViewMode("green"/"canny"/"both") selects which of the two line layers render --
 *   a pure local choice with no server round-trip, mirroring the Mac UI's e/E toggle.
 * - setHandles(List<HandlePoint>) supplies the current set of endpoint/midpoint
 *   marker positions (also from GET_TOUR_POINTS).
 * - setHighlighted(HandlePoint) marks one handle (the current Adjust/Tour target) for
 *   emphasized drawing; while set, that handle's OWN blue line is skipped from the
 *   static blue-line rendering (see onDraw) -- you're actively editing that line, so
 *   showing its stale old position alongside the live drag preview would be
 *   confusing. Matches court_recognition.py, where the original line disappears once
 *   you start moving its replacement.
 * - startDrag(pivotX, pivotY) / endDrag(): while a drag is active, draws a live line
 *   from the fixed pivot point (bitmap-space) to the screen-center crosshair position
 *   -- this is pure on-device geometry (no server round-trip), so it tracks the
 *   user's panning instantly regardless of network/server speed. The crosshair
 *   itself is a separate, already-existing fixed view in the layout; this class
 *   only draws the connecting line and the pivot marker.
 *
 * 3. INTERNAL ALGORITHMIC LOGIC:
 * - All geometry is stored in bitmap-space (the underlying photo's own pixel
 *   coordinates) and mapped to screen space via imageMatrix.mapPoints() at draw
 *   time -- the same transform ivCalibrationImage itself uses, so nothing drawn
 *   here ever drifts from the image during pan/zoom.
 * - Every stroke width is fixed in screen pixels (set once on each Paint, never
 *   scaled), so lines stay a constant, narrow width on screen at any zoom level --
 *   the whole point of moving this drawing off the server, where lines were baked
 *   into the image pixels and grew thicker/blurrier the further you zoomed in.
 * - Endpoints ('e1'/'e2') draw as circles; midpoints ('mid') draw as diamonds,
 *   mirroring the Mac UI's circle-vs-diamond handle convention.
 * - The highlighted handle draws larger and in a distinct color (yellow) so it's
 *   unambiguous which one is currently selected/toured.
 *
 * 4. EXPECTED OUTPUTS / SIDE EFFECTS:
 * - Pushes 2D vector shapes (lines, circles, diamonds) to the hardware-accelerated
 *   Android Canvas. No network or file I/O.
 */

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class CalibrationOverlayView extends View {

    private static final float ENDPOINT_RADIUS_PX = 10f;
    private static final float HIGHLIGHT_RADIUS_PX = 15f;
    private static final float MIDPOINT_HALF_DIAGONAL_PX = 11f;
    // Narrow and constant regardless of zoom -- the entire motivation for drawing
    // these client-side instead of baking them into the server's image, where a
    // fixed image-space line width grows thicker/blurrier the further you zoom in.
    // Widened from 2f for outdoor/bright-sunlight visibility, still kept narrow.
    private static final float WIREFRAME_STROKE_PX = 4f;
    private static final float BLUE_LINE_STROKE_PX = 4f;
    private static final float ANCHOR_DOT_RADIUS_PX = 6f;
    private static final float STRAP_DOT_RADIUS_PX = 6f;

    public static final String VIEW_MODE_GREEN = "green";
    public static final String VIEW_MODE_CANNY = "canny";
    public static final String VIEW_MODE_BOTH = "both";

    public static class HandlePoint {
        public final String lineName;
        public final String pointType; // "e1", "e2", or "mid"
        public final float x, y;       // bitmap-space coordinates

        public HandlePoint(String lineName, String pointType, float x, float y) {
            this.lineName = lineName;
            this.pointType = pointType;
            this.x = x;
            this.y = y;
        }
    }

    /** One blue-line segment to draw between two bitmap-space endpoints, tagged
     *  with its line name so it can be (a) skipped while it's the active edit
     *  target (see class header: hidden once editing is armed) and (b) drawn pink
     *  instead of blue if locked -- an endpoint on it has already been manually
     *  edited this session, matching court_recognition.py's is_locked coloring. */
    public static class LineSegment {
        public final String lineName;
        public final float x1, y1, x2, y2;
        public final boolean locked;

        public LineSegment(String lineName, float x1, float y1, float x2, float y2, boolean locked) {
            this.lineName = lineName;
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.locked = locked;
        }
    }

    private Matrix imageMatrix = new Matrix();
    private List<HandlePoint> handles = new ArrayList<>();
    private HandlePoint highlightedHandle = null;
    private String viewMode = VIEW_MODE_BOTH;

    // float[4] = {x1,y1,x2,y2}; the green wireframe has no per-segment identity
    // worth tracking (never individually hidden/highlighted), unlike blue lines.
    private List<float[]> wireframeSegments = new ArrayList<>();
    private List<LineSegment> blueLineSegments = new ArrayList<>();
    private List<float[]> anchorPoints = new ArrayList<>();   // float[2] = {x,y}
    private float[] strapPoint = null;                        // {x,y}, or null
    // Names of lines with a manually-locked midpoint this session; that midpoint's
    // own marker draws pink (see startDrag's class-header note: a deliberate
    // departure from court_recognition.py, which gives no visual feedback for a
    // midpoint-only edit).
    private java.util.Set<String> lockedMidpointLineNames = new java.util.HashSet<>();

    private boolean dragActive = false;
    // One pivot for an endpoint drag (the line's other, fixed endpoint -- draws one
    // pivot-to-crosshair line, the candidate new line). Two pivots for a midpoint
    // drag (that line's e1 AND e2 -- draws e1-to-crosshair and crosshair-to-e2,
    // the candidate "bent" line through the new midpoint). Previously always used
    // a single pivot equal to the midpoint's own original position for midpoint
    // drags too, which drew a stray line from the old position to the new one
    // instead of showing the line's actual new shape -- reported as showing "a
    // line from the original control point to the edited point" instead of "from
    // the ends to the central point".
    private List<float[]> dragPivots = new ArrayList<>();

    private final Paint endpointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint midpointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockedEndpointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dragLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pivotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wireframePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blueLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockedLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint anchorDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strapDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CalibrationOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);

        endpointPaint.setColor(Color.CYAN);
        endpointPaint.setStyle(Paint.Style.STROKE);
        endpointPaint.setStrokeWidth(3f);

        midpointPaint.setColor(Color.CYAN);
        midpointPaint.setStyle(Paint.Style.STROKE);
        midpointPaint.setStrokeWidth(3f);

        lockedEndpointPaint.setColor(Color.MAGENTA);
        lockedEndpointPaint.setStyle(Paint.Style.STROKE);
        lockedEndpointPaint.setStrokeWidth(3f);

        highlightPaint.setColor(Color.YELLOW);
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeWidth(4f);

        dragLinePaint.setColor(Color.YELLOW);
        dragLinePaint.setStrokeWidth(3f);
        dragLinePaint.setStyle(Paint.Style.STROKE);

        pivotPaint.setColor(Color.YELLOW);
        pivotPaint.setStyle(Paint.Style.FILL);

        wireframePaint.setColor(Color.GREEN);
        wireframePaint.setStyle(Paint.Style.STROKE);
        wireframePaint.setStrokeWidth(WIREFRAME_STROKE_PX);

        blueLinePaint.setColor(Color.BLUE);
        blueLinePaint.setStyle(Paint.Style.STROKE);
        blueLinePaint.setStrokeWidth(BLUE_LINE_STROKE_PX);

        lockedLinePaint.setColor(Color.MAGENTA);
        lockedLinePaint.setStyle(Paint.Style.STROKE);
        lockedLinePaint.setStrokeWidth(BLUE_LINE_STROKE_PX);

        anchorDotPaint.setColor(Color.BLUE);
        anchorDotPaint.setStyle(Paint.Style.FILL);

        strapDotPaint.setColor(Color.MAGENTA);
        strapDotPaint.setStyle(Paint.Style.FILL);
    }

    /** Must be called whenever CalibrationActivity's own imageMatrix changes
     *  (pan/zoom), so markers and the live-drag line track the image exactly. */
    public void setImageMatrix(Matrix m) {
        imageMatrix = m;
        invalidate();
    }

    public void setHandles(List<HandlePoint> newHandles) {
        handles = newHandles != null ? newHandles : new ArrayList<>();
        invalidate();
    }

    /** Clears only the editable-handle markers/highlight/drag state (used when
     *  leaving Adjust/Tour mode back to plain Review) -- does NOT clear the static
     *  wireframe/blue-line/anchor geometry, which stays valid and should keep
     *  rendering in Review. */
    public void clearHandles() {
        handles = new ArrayList<>();
        highlightedHandle = null;
        dragActive = false;
        invalidate();
    }

    public void setHighlighted(HandlePoint h) {
        highlightedHandle = h;
        invalidate();
    }

    /** segments: bitmap-space (x1,y1,x2,y2) pairs for the projected green court
     *  wireframe (see CalibrationActivity's WF: entries from GET_TOUR_POINTS). */
    public void setWireframeSegments(List<float[]> segments) {
        wireframeSegments = segments != null ? segments : new ArrayList<>();
        invalidate();
    }

    public void setBlueLineSegments(List<LineSegment> segments) {
        blueLineSegments = segments != null ? segments : new ArrayList<>();
        invalidate();
    }

    /** points: bitmap-space (x,y) pairs for the original anchor-click positions. */
    public void setAnchorPoints(List<float[]> points) {
        anchorPoints = points != null ? points : new ArrayList<>();
        invalidate();
    }

    /** point: bitmap-space (x,y), or null if no net strap was used. */
    public void setStrapPoint(float[] point) {
        strapPoint = point;
        invalidate();
    }

    /** One of VIEW_MODE_GREEN/VIEW_MODE_CANNY/VIEW_MODE_BOTH -- a pure local
     *  rendering choice, no server round-trip (see class header). */
    public void setViewMode(String mode) {
        viewMode = mode;
        invalidate();
    }

    /** names: lines with a manually-locked midpoint this session (see class
     *  header / handle_get_tour_points's "LKM:name" entries). */
    public void setLockedMidpointLineNames(java.util.Set<String> names) {
        lockedMidpointLineNames = names != null ? names : new java.util.HashSet<>();
        invalidate();
    }

    /** pivotX, pivotY: bitmap-space position of the line's OTHER (fixed) endpoint.
     *  Draws one pivot-to-crosshair line -- the candidate new line, rotated
     *  around this pivot. Convenience overload for the common endpoint-drag case;
     *  see startDrag(List) for midpoint drags (two pivots, e1 and e2). */
    public void startDrag(float pivotX, float pivotY) {
        startDrag(java.util.Collections.singletonList(new float[]{pivotX, pivotY}));
    }

    /** pivots: one bitmap-space {x,y} per fixed point to draw a line from, to the
     *  crosshair. One pivot (the other endpoint) for an endpoint drag; two (e1
     *  and e2) for a midpoint drag, so the preview shows the actual candidate
     *  "bent" line through the new midpoint position, not just a stray line from
     *  the midpoint's old position to the new one. */
    public void startDrag(List<float[]> pivots) {
        dragActive = true;
        dragPivots = pivots != null ? pivots : new ArrayList<>();
        invalidate();
    }

    public void endDrag() {
        dragActive = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        boolean showGreen = VIEW_MODE_GREEN.equals(viewMode) || VIEW_MODE_BOTH.equals(viewMode);
        boolean showCanny = VIEW_MODE_CANNY.equals(viewMode) || VIEW_MODE_BOTH.equals(viewMode);
        // Handles are only meaningful alongside the canny/blue layer -- there's
        // nothing to drag while looking at the green wireframe alone.
        boolean showHandles = showCanny;

        float[] seg = new float[4];
        if (showGreen) {
            for (float[] s : wireframeSegments) {
                seg[0] = s[0]; seg[1] = s[1]; seg[2] = s[2]; seg[3] = s[3];
                imageMatrix.mapPoints(seg);
                canvas.drawLine(seg[0], seg[1], seg[2], seg[3], wireframePaint);
            }
        }

        if (showCanny) {
            // Skip the highlighted line's own static segment only while ACTIVELY
            // editing it (dragActive, armed via the Edit button) -- merely browsing
            // to/highlighting a point (Prev/Next, or just landing on it) keeps
            // showing its real current line, per "shows the line, but isn't editing
            // it yet". Once armed, hide it in favor of the live drag preview,
            // matching court_recognition.py: the original line disappears once you
            // start moving its replacement.
            String skipLineName = (dragActive && highlightedHandle != null)
                    ? highlightedHandle.lineName : null;
            for (LineSegment ls : blueLineSegments) {
                if (ls.lineName.equals(skipLineName)) continue;
                seg[0] = ls.x1; seg[1] = ls.y1; seg[2] = ls.x2; seg[3] = ls.y2;
                imageMatrix.mapPoints(seg);
                canvas.drawLine(seg[0], seg[1], seg[2], seg[3], ls.locked ? lockedLinePaint : blueLinePaint);
            }

            float[] pt2 = new float[2];
            for (float[] a : anchorPoints) {
                pt2[0] = a[0]; pt2[1] = a[1];
                imageMatrix.mapPoints(pt2);
                canvas.drawCircle(pt2[0], pt2[1], ANCHOR_DOT_RADIUS_PX, anchorDotPaint);
            }
            if (strapPoint != null) {
                pt2[0] = strapPoint[0]; pt2[1] = strapPoint[1];
                imageMatrix.mapPoints(pt2);
                canvas.drawCircle(pt2[0], pt2[1], STRAP_DOT_RADIUS_PX, strapDotPaint);
            }
        }

        float[] pt = new float[2];
        for (HandlePoint h : (showHandles ? handles : java.util.Collections.<HandlePoint>emptyList())) {
            pt[0] = h.x;
            pt[1] = h.y;
            imageMatrix.mapPoints(pt);
            boolean isHighlighted = (h == highlightedHandle);
            // Endpoint handles on a locked line draw pink, matching the line itself
            // (and court_recognition.py's handle_color, endpoint-only). Midpoint
            // markers draw pink based on lockedMidpointLineNames instead -- a
            // deliberate departure from the Mac (see that field's comment): gives
            // visible confirmation a center-point edit stuck, independent of
            // whether the corresponding endpoint has also been touched.
            boolean isMid = "mid".equals(h.pointType);
            boolean lineLocked = isMid ? lockedMidpointLineNames.contains(h.lineName)
                                        : isLineLocked(h.lineName);
            Paint paint = isHighlighted ? highlightPaint
                    : (lineLocked ? lockedEndpointPaint : (isMid ? midpointPaint : endpointPaint));
            float radius = isHighlighted ? HIGHLIGHT_RADIUS_PX : ENDPOINT_RADIUS_PX;

            if (isMid) {
                drawDiamond(canvas, pt[0], pt[1],
                        isHighlighted ? HIGHLIGHT_RADIUS_PX : MIDPOINT_HALF_DIAGONAL_PX, paint);
            } else {
                canvas.drawCircle(pt[0], pt[1], radius, paint);
            }
        }

        if (dragActive && showHandles) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            // One pivot (endpoint drag) draws one line -- the candidate new line.
            // Two pivots (midpoint drag: e1 and e2) draw two -- the candidate
            // "bent" line through the new midpoint position.
            for (float[] pivot : dragPivots) {
                float[] pivotScreen = {pivot[0], pivot[1]};
                imageMatrix.mapPoints(pivotScreen);
                // At the ~16x Adjust-mode zoom, a pivot (a line's real endpoint,
                // often well over 1000px away in bitmap space) can map to a screen
                // position tens of thousands of px away -- clip it to just outside
                // the view bounds along the true line direction (same technique as
                // the server's _clip_to_image_bounds) rather than handing
                // drawLine() an extreme, possibly precision-losing coordinate.
                // This only shortens where the line is drawn to/from -- the
                // direction/slope shown is exact.
                float[] clipped = clipToViewBounds(cx, cy, pivotScreen[0], pivotScreen[1]);
                canvas.drawLine(clipped[0], clipped[1], cx, cy, dragLinePaint);
                canvas.drawCircle(clipped[0], clipped[1], 6f, pivotPaint);
            }
        }
    }

    /** Clips targetXY to a generous margin outside this view's own bounds, along
     *  the line from anchorXY (which must already be within/near the view -- here,
     *  always the screen-center crosshair). Mirrors the parametric line-vs-
     *  rectangle clip used server-side (tgserver.py's _clip_to_image_bounds /
     *  court_recognition.py's _clip_to_display_bounds) for the same reason: keep
     *  the drawn point at a bounded, reasonable screen coordinate while preserving
     *  the true direction from the anchor. A margin (rather than the exact edge)
     *  keeps the line visibly running off the edge of the screen, not stopping
     *  conspicuously right at it. */
    private float[] clipToViewBounds(float anchorX, float anchorY, float targetX, float targetY) {
        float margin = Math.max(getWidth(), getHeight());
        float left = -margin, top = -margin;
        float right = getWidth() + margin, bottom = getHeight() + margin;
        if (targetX >= left && targetX <= right && targetY >= top && targetY <= bottom) {
            return new float[]{targetX, targetY};
        }
        float dx = targetX - anchorX, dy = targetY - anchorY;
        float t = 1f;
        if (dx > 0)      t = Math.min(t, (right - anchorX) / dx);
        else if (dx < 0) t = Math.min(t, (left - anchorX) / dx);
        if (dy > 0)      t = Math.min(t, (bottom - anchorY) / dy);
        else if (dy < 0) t = Math.min(t, (top - anchorY) / dy);
        t = Math.max(0f, Math.min(1f, t));
        return new float[]{anchorX + t * dx, anchorY + t * dy};
    }

    /** Linear scan over blueLineSegments -- fine at this scale (a few dozen lines
     *  at most), not worth a separate Set kept in sync with blueLineSegments. */
    private boolean isLineLocked(String lineName) {
        for (LineSegment ls : blueLineSegments) {
            if (ls.lineName.equals(lineName)) return ls.locked;
        }
        return false;
    }

    private void drawDiamond(Canvas canvas, float cx, float cy, float halfDiagonal, Paint paint) {
        Path path = new Path();
        path.moveTo(cx, cy - halfDiagonal);
        path.lineTo(cx + halfDiagonal, cy);
        path.lineTo(cx, cy + halfDiagonal);
        path.lineTo(cx - halfDiagonal, cy);
        path.close();
        canvas.drawPath(path, paint);
    }
}
