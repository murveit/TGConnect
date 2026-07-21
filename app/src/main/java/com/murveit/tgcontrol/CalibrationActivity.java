package com.murveit.tgcontrol;

/**
 * Calibration Activity - Algorithmic Overview
 *
 * This module provides the "Dedicated Control Bar" interface for aligning the 3D court model 
 * with the 2D camera feed.
 *
 * 1. INITIALIZATION:
 * - Requests Immersive Sticky Mode from the OS to claim 100% of the screen pixels, hiding nav/status bars.
 * - Initializes `ScaleGestureDetector` and `GestureDetector` to intercept raw touch events.
 * - Sends `START_CALIBRATION` command over the TCP socket.
 *
 * 2. CALLING PROCEDURE:
 * - Launched from `SettingsActivity` via the "Calibrate Left" or "Calibrate Right" buttons.
 *
 * 3. INTERNAL ALGORITHMIC LOGIC:
 * - Matrix Math: Touch events do not move an image; they apply affine transformations (Translation/Scale) 
 * to an `android.graphics.Matrix` object attached to the ImageView. 
 * - Gestures: Supports two-finger pinch for granular scaling, one-finger drag for translation, and 
 * single-tap for a one-time strong zoom centered on the tapped coordinate.
 * - Inverse Mapping: The UI renders a fixed crosshair at exactly (ViewWidth / 2, ViewHeight / 2). 
 * When the user taps "Confirm", the mathematical inverse of the current affine Matrix is calculated. 
 * The screen's center point is multiplied through this inverted matrix to yield the exact sub-pixel 
 * coordinate on the underlying 1080p raw Bitmap.
 * - State Machine: Drives the user through 4 discrete corner selections plus an optional net-strap
 * click, managing UI button visibility, tracking extracted coordinates in a `Float` array, and
 * orchestrating the TCP protocol (START -> PROCESS -> CONFIRM).
 * - Three-button STATE_REVIEW: Accept (green), Retake (gray, full restart), and a contextual
 * blue third button — "+Strap" when the current calibration used no strap, "-Strap"
 * when it did. "+Strap" returns to STATE_TARGET_NET_STRAP with the original calibration image
 * and pink dots marking all previous strap attempts. "-Strap" strips the strap coords,
 * resubmits the 8-anchor calibration, and shows a fresh validation image with "+Strap" again.
 * - Lifecycle Resets: Resets the viewport (zoom and pan) after every confirmation to ensure the user
 * starts with a centered full-view perspective for the next target.
 *
 * 4. EXPECTED OUTPUTS / SIDE EFFECTS:
 * - Pushes Affine Matrix transforms directly to the GPU for smooth 60fps zooming.
 * - Commits calibration JSONs to the Orin Nano disk upon completion.
 */

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.os.Build;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

public class CalibrationActivity extends AppCompatActivity {
    
    // --- Algorithmic Constants ---
    private static final float TCP_SCALE_FACTOR = 1.0f; // 0.5 would force the server to downsample 4K to 1080p
    private static final float STRONG_ZOOM_MULTIPLIER = 30.0f; // Target scale multiplier for single tap
    // Adjust/Tour mode auto-zooms to every handle unattended (unlike the single-tap
    // zoom above, which only ever fires where the user chose to tap). Canny line
    // endpoints are often near the edge of the undistorted/cropped image (lines
    // extend toward the frame boundary), and at 30x zoom the viewport for a point
    // that close to the edge can extend entirely past the actual bitmap into the
    // activity's black background -- reported as "several images were black" during
    // Tour. A lower multiplier keeps more of the viewport within real image content
    // for edge-adjacent points, at the cost of somewhat less precision per pan.
    // Raised from an initial 10.0 once handle_get_tour_points started clipping
    // out-of-bounds points to the image edge (see tgserver.py) instead of just
    // omitting them -- a landed-on point is now guaranteed to have real image
    // content nearby, so more zoom is safe, and browsing no longer shows a live
    // preview line that would make the extra zoom feel unstable while panning.
    // Raised again from 16.0 for more precision when placing a point.
    private static final float ADJUST_ZOOM_MULTIPLIER = 22.0f;

    private static final int STATE_LOADING = 0;
    private static final int STATE_TARGET_FAR_OUT = 1;
    private static final int STATE_TARGET_FAR_IN = 2;
    private static final int STATE_TARGET_NEAR_OUT = 3;
    private static final int STATE_TARGET_NEAR_IN = 4;
    private static final int STATE_TARGET_NET_STRAP = 5; // optional net-strap top click
    private static final int STATE_VALIDATING = 6;
    private static final int STATE_REVIEW = 7;
    // Adjust Lines and Tour are the same mode (merged after field testing showed
    // touring-without-editing was an unwanted extra step): Prev/Next step through
    // every endpoint/midpoint, auto-zooming and highlighting the current one;
    // Accept is available at every step to send whatever pan adjustment has been
    // made to THAT point (via ADJUST_BLUE_LINE), without a separate "select a
    // handle" step. See NEXT_STEPS.md "Court Calibration" for the full design.
    private static final int STATE_ADJUST = 8;

    // View-mode toggle (mirrors the Mac UI's e/E behavior): which layer(s) the
    // current validation composite shows. Cycled by btnViewMode; see cycleViewMode().
    private static final String VIEW_MODE_GREEN = "green";
    private static final String VIEW_MODE_CANNY = "canny";
    private static final String VIEW_MODE_BOTH = "both";
    private String viewMode = VIEW_MODE_BOTH;

    // --- State Tracking ---
    private int sensorId;
    private int currentState = STATE_LOADING;
    private Bitmap currentBitmap = null;
    // Original calibration image from START_CALIBRATION — never replaced by the
    // validation composite, so it remains available for redo-strap overlays.
    private Bitmap originalCalibBitmap = null;
    // Previous net-strap click positions in bitmap coords, accumulated across redo
    // cycles and drawn as dots so the user can see where they clicked before.
    private List<PointF> previousStrapClicks = new ArrayList<>();
    // True when the last submitted calibration included a net-strap click; drives
    // "Redo Strap" label on the Retake button in STATE_REVIEW.
    private boolean lastCalibUsedStrap = false;
    private List<Float> extractedCoords = new ArrayList<>();
    private float baseScale = 1.0f;

    // --- Adjust Lines / Tour / vector-drawing state ---
    // Everything the server used to bake into a rendered composite image is now
    // geometry data, parsed from GET_TOUR_POINTS and drawn client-side by
    // CalibrationOverlayView (constant on-screen line width at any zoom, and it
    // lets the currently-edited line's stale rendering be hidden during a drag --
    // see that class's header comment). The server sends the plain photo exactly
    // once (PROCESS_CALIBRATION); everything after that is text.
    private List<CalibrationOverlayView.HandlePoint> currentHandles = new ArrayList<>();
    private List<float[]> currentWireframeSegments = new ArrayList<>();   // {x1,y1,x2,y2}
    private List<float[]> currentAnchorPoints = new ArrayList<>();        // {x,y}
    private float[] currentStrapPoint = null;                            // {x,y}, or null
    // Names of lines with a manually-locked endpoint this session (parsed from
    // GET_TOUR_POINTS's "LK:name" entries); drawn pink instead of blue, matching
    // court_recognition.py's is_locked coloring.
    private java.util.Set<String> currentLockedLineNames = new java.util.HashSet<>();
    // Names of lines with a manually-locked midpoint this session ("LKM:name"
    // entries); that midpoint's own marker draws pink, independent of
    // currentLockedLineNames -- see CalibrationOverlayView's field comment.
    private java.util.Set<String> currentLockedMidpointNames = new java.util.HashSet<>();
    // The handle currently selected (Adjust Lines dragging) or toured (Tour mode).
    private CalibrationOverlayView.HandlePoint selectedHandle = null;
    // Index into currentHandles for Tour mode's Prev/Next navigation.
    private int tourIndex = 0;
    // Landing on a point (via Next/Prev or initial entry) is pure inspection --
    // free pan/zoom/navigation, no effect on anything, and the point's real
    // current line stays visible. editArmed becomes true only after explicitly
    // tapping the Edit button (see armEdit()), which is when panning starts
    // affecting the candidate position and the live preview line appears; a
    // second tap (now labeled Accept) commits it. Always reset to false when
    // landing on any point, so "just inspecting" never requires remembering not
    // to touch the edit button.
    private boolean editArmed = false;
    // Tracks which step of a multi-request server round-trip is in flight. Only
    // two remain: fetching geometry once right after the initial image, and
    // re-fetching it after an accepted adjustment (the pose, and hence every
    // line's position, can shift as a side effect of one edit). View-mode
    // toggling and entering Adjust/Tour are now purely local -- no request at
    // all, since geometry is already cached from the last fetch. The wire
    // protocol is strictly one-request/one-response at a time, so a multi-step
    // sequence like this is never fired concurrently -- see the big comment
    // block above cycleViewMode().
    private static final int PENDING_NONE = 0;
    private static final int PENDING_INITIAL_GEOMETRY = 1;   // after PROCESS_CALIBRATION's image, before STATE_REVIEW
    private static final int PENDING_REFRESH_AFTER_ADJUST = 2; // after ADJUST_COMPLETE ack, refetching geometry
    private int pendingAction = PENDING_NONE;
    // True from the moment Accept is tapped until the server's ADJUST_COMPLETE (or
    // an abort) arrives -- blocks a second Accept from firing mid-recompute.
    private boolean adjustInFlight = false;

    // --- Matrix Math ---
    private Matrix imageMatrix = new Matrix();
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    // Tracks scroll translations applied since the last ACTION_DOWN so they can be
    // cancelled by onScaleBegin if the gesture turns out to be a pinch-zoom.
    private float scrollDeltaX = 0f;
    private float scrollDeltaY = 0f;

    // --- UI Widgets ---
    private ImageView ivCalibrationImage;
    private TextView tvInstruction;
    private TextView tvConfirmLabel;
    private TextView tvRetakeLabel;
    private TextView tvThirdLabel;
    private FloatingActionButton btnConfirm;
    private FloatingActionButton btnRetake;
    private FloatingActionButton btnThird;
    private ImageButton btnCancel;
    private LinearLayout llRetake;
    private LinearLayout llConfirm;
    private LinearLayout llThird;

    private CalibrationOverlayView calibrationOverlay;
    private LinearLayout llViewMode;
    private ImageButton btnViewMode;
    private TextView tvViewModeLabel;
    private LinearLayout llAdjustEntry;
    private FloatingActionButton btnAdjustLines;
    private LinearLayout llAdjustNav;
    private ImageButton btnAdjustPrev;
    private FloatingActionButton btnAdjustAccept;
    private ImageButton btnAdjustNext;
    private TextView tvAdjustPointLabel;
    private TextView tvAdjustAcceptLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 1. Force Immersive Sticky Mode & Layout into Camera Notch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        setContentView(R.layout.activity_calibration);
        
        sensorId = getIntent().getIntExtra("SENSOR_ID", 0);
        
        ivCalibrationImage = findViewById(R.id.ivCalibrationImage);
        tvInstruction = findViewById(R.id.tvInstruction);
        tvConfirmLabel = findViewById(R.id.tvConfirmLabel);
        tvRetakeLabel = findViewById(R.id.tvRetakeLabel);
        tvThirdLabel = findViewById(R.id.tvThirdLabel);
        btnConfirm = findViewById(R.id.btnConfirm);
        btnRetake = findViewById(R.id.btnRetake);
        btnThird = findViewById(R.id.btnThird);
        btnCancel = findViewById(R.id.btnCancel);
        llRetake = findViewById(R.id.llRetake);
        llConfirm = findViewById(R.id.llConfirm);
        llThird = findViewById(R.id.llThird);

        calibrationOverlay = findViewById(R.id.calibrationOverlay);
        llViewMode = findViewById(R.id.llViewMode);
        btnViewMode = findViewById(R.id.btnViewMode);
        tvViewModeLabel = findViewById(R.id.tvViewModeLabel);
        llAdjustEntry = findViewById(R.id.llAdjustEntry);
        btnAdjustLines = findViewById(R.id.btnAdjustLines);
        llAdjustNav = findViewById(R.id.llAdjustNav);
        btnAdjustPrev = findViewById(R.id.btnAdjustPrev);
        btnAdjustAccept = findViewById(R.id.btnAdjustAccept);
        btnAdjustNext = findViewById(R.id.btnAdjustNext);
        tvAdjustPointLabel = findViewById(R.id.tvAdjustPointLabel);
        tvAdjustAcceptLabel = findViewById(R.id.tvAdjustAcceptLabel);
        calibrationOverlay.setImageMatrix(imageMatrix);

        setupTouchMatrix();
        setupObservers();

        btnViewMode.setOnClickListener(v -> cycleViewMode());
        btnAdjustLines.setOnClickListener(v -> enterAdjust());
        btnAdjustPrev.setOnClickListener(v -> tourStep(-1));
        btnAdjustNext.setOnClickListener(v -> tourStep(1));
        btnAdjustAccept.setOnClickListener(v -> {
            if (editArmed) acceptAdjustment(); else armEdit();
        });

        btnConfirm.setOnClickListener(v -> handleConfirmAction());
        btnRetake.setOnClickListener(v -> {
            if (currentState == STATE_TARGET_NET_STRAP) {
                // "Capture" — lock the crosshair as the net strap point (10 coords total).
                extractScreenCenterToBitmapPixel();
                // Record position for the dots overlay on future redo attempts.
                int n = extractedCoords.size();
                previousStrapClicks.add(new PointF(extractedCoords.get(n - 2), extractedCoords.get(n - 1)));
                lastCalibUsedStrap = true;
                advanceState(STATE_VALIDATING);
                sendProcessCalibration();
            } else if (currentState == STATE_REVIEW && lastCalibUsedStrap) {
                // "Redo Strap" — keep the 4 anchor coords, drop the strap, and return
                // to the strap-click step with dots showing previous attempts.
                while (extractedCoords.size() > 8) extractedCoords.remove(extractedCoords.size() - 1);
                Bitmap withDots = drawStrapDotsOnBitmap(originalCalibBitmap);
                currentBitmap = withDots;
                ivCalibrationImage.setImageBitmap(withDots);
                advanceState(STATE_TARGET_NET_STRAP);
                ivCalibrationImage.post(() -> resetMatrixForBitmap(currentBitmap));
            } else {
                startCalibrationFlow();
            }
        });
        btnThird.setOnClickListener(v -> handleThirdAction());
        btnCancel.setOnClickListener(v -> {
            // State-aware "back": from Adjust/Tour, X steps back to Review (there's
            // no other way out of Adjust/Tour short of stepping through every
            // point, which was reported as a real problem). Only exits the whole
            // activity once already at Review or earlier.
            if (currentState == STATE_ADJUST) {
                exitToReview();
            } else {
                finish();
            }
        });

        // Kick off the state machine
        startCalibrationFlow();
    }

    private void setupTouchMatrix() {
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                // Second finger has landed: this is confirmed pinch-zoom, not a scroll.
                // Roll back any scroll translations that fired in the brief window between
                // the first and second finger touching down.
                if (scrollDeltaX != 0f || scrollDeltaY != 0f) {
                    imageMatrix.postTranslate(-scrollDeltaX, -scrollDeltaY);
                    ivCalibrationImage.setImageMatrix(imageMatrix);
                    calibrationOverlay.invalidate();
                    scrollDeltaX = 0f;
                    scrollDeltaY = 0f;
                }
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scaleFactor = detector.getScaleFactor();
                imageMatrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
                ivCalibrationImage.setImageMatrix(imageMatrix);
                calibrationOverlay.invalidate();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                // Reset accumulator so a follow-on single-finger scroll starts clean.
                scrollDeltaX = 0f;
                scrollDeltaY = 0f;
            }
        });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                // Guard: ScaleGestureDetector and GestureDetector both receive all touch events.
                // Without this check, an ongoing pinch also fires onScroll, causing the matrix
                // to accumulate spurious translations that corrupt coordinate extraction.
                if (scaleDetector.isInProgress()) return false;
                // PostTranslate requires absolute direction. distanceX/Y is "scroll delta", so we invert it.
                float tx = -distanceX;
                float ty = -distanceY;
                // Accumulate so onScaleBegin can roll back if this scroll preceded a pinch-zoom.
                scrollDeltaX += tx;
                scrollDeltaY += ty;
                imageMatrix.postTranslate(tx, ty);
                ivCalibrationImage.setImageMatrix(imageMatrix);
                calibrationOverlay.invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (currentState == STATE_ADJUST) {
                    // Navigation in Adjust/Tour mode is Prev/Next only (see
                    // tourStep()); a stray tap here shouldn't re-zoom somewhere
                    // arbitrary and disturb the crosshair-based positioning.
                    return true;
                }
                // Algorithmic step: Apply a single strong zoom only if near the base view.
                // Further zooming is delegated to the user's pinch gestures.
                float[] values = new float[9];
                imageMatrix.getValues(values);
                float currentScale = values[Matrix.MSCALE_X];

                if (currentScale < baseScale * 2.0f) {
                    float targetScale = baseScale * STRONG_ZOOM_MULTIPLIER;
                    float scaleFactor = targetScale / currentScale;
                    imageMatrix.postScale(scaleFactor, scaleFactor, e.getX(), e.getY());
                    ivCalibrationImage.setImageMatrix(imageMatrix);
                    calibrationOverlay.invalidate();
                }
                return true;
            }
        });

        ivCalibrationImage.setOnTouchListener((v, event) -> {
            // Reset scroll accumulator on each new touch sequence so prior scroll
            // deltas don't bleed into a completely separate gesture.
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                scrollDeltaX = 0f;
                scrollDeltaY = 0f;
            }
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private void setupObservers() {
        CommunicationService.getImageData().observe(this, pair -> {
            if (pair == null) return;
            Bitmap bmp = pair.first;
            String target = pair.second;

            if (currentState == STATE_LOADING && "calibration_baseline".equals(target)) {
                currentBitmap = bmp;
                originalCalibBitmap = bmp; // Preserved — never replaced by the validation composite
                ivCalibrationImage.setImageBitmap(bmp);
                ivCalibrationImage.post(() -> resetMatrixForBitmap(bmp)); // Wait for UI Layout pass
                advanceState(STATE_TARGET_FAR_OUT);

            } else if (currentState == STATE_VALIDATING && "calibration_validation".equals(target)) {
                // Plain, unmarked photo -- no lines baked in. Display it, then fetch the
                // line/wireframe geometry before entering STATE_REVIEW (see
                // PENDING_INITIAL_GEOMETRY in handleTourPointsResponse), since there's
                // nothing to draw over the image yet otherwise.
                currentBitmap = bmp;
                originalCalibBitmap = bmp;
                ivCalibrationImage.setImageBitmap(bmp);
                ivCalibrationImage.post(() -> resetMatrixForBitmap(bmp));
                tvInstruction.setText("Loading calibration lines...");
                pendingAction = PENDING_INITIAL_GEOMETRY;
                sendCommand("GET_TOUR_POINTS:" + sensorId);
            }
        });

        CommunicationService.getStatusData().observe(this, pair -> {
            if (pair == null) return;
            if ("CALIBRATION_SAVED".equals(pair.second)) {
                // Toast.makeText(this, "Matrix Locked to Hardware.", Toast.LENGTH_SHORT).show();
                finish();
            } else if ("TOUR_POINTS".equals(pair.first)) {
                handleTourPointsResponse(pair.second);
            } else if ("ADJUST_COMPLETE".equals(pair.first)) {
                // The edit landed server-side; now fetch the (possibly-shifted-for-every-
                // line) geometry it produced. See acceptAdjustment().
                sendCommand("GET_TOUR_POINTS:" + sensorId);
            } else if (pair.second != null && pair.second.contains("ABORTED")) {
                adjustInFlight = false;
                pendingAction = PENDING_NONE;
                Toast.makeText(this, "Server Error: " + pair.second, Toast.LENGTH_LONG).show();
                startCalibrationFlow();
            }
        });
    }

    // ==================================================================================
    // Adjust Lines / Tour / View-Mode support
    //
    // These features (see NEXT_STEPS.md "Court Calibration") reuse the pan-to-crosshair
    // technique already used for the 4-anchor clicks: selecting a handle re-centers the
    // viewport on that handle's current position, then panning moves the crosshair
    // (hence the handle's new candidate position) exactly as it does for anchor clicks.
    // The blue-line/live-drag rendering itself is delegated to CalibrationOverlayView,
    // which reads the SAME imageMatrix object this activity mutates, so no separate
    // sync step is needed beyond calling invalidate() after each matrix change (already
    // done at every ivCalibrationImage.setImageMatrix(imageMatrix) call site above).
    //
    // All server round-trips here are sequential by design (send one command, wait for
    // its response, then send the next) -- the wire protocol is strictly request/response
    // on a single TCP stream, so firing two commands without waiting would risk the
    // client misreading which bytes belong to which reply. pendingAction tracks which
    // step of a multi-request sequence is in flight.
    // ==================================================================================

    /** Purely local now: no server round-trip at all, since the wireframe and blue
     *  lines are already cached client-side (see class header / CalibrationOverlayView).
     *  Mirrors the Mac UI's e/E toggle exactly -- a display choice, not a recompute. */
    private void cycleViewMode() {
        if (currentState != STATE_REVIEW && currentState != STATE_ADJUST) {
            return; // nothing on screen to toggle (e.g. still clicking anchors)
        }
        if (VIEW_MODE_BOTH.equals(viewMode)) {
            viewMode = VIEW_MODE_GREEN;
            tvViewModeLabel.setText("Green");
        } else if (VIEW_MODE_GREEN.equals(viewMode)) {
            viewMode = VIEW_MODE_CANNY;
            tvViewModeLabel.setText("Canny");
        } else {
            viewMode = VIEW_MODE_BOTH;
            tvViewModeLabel.setText("Both");
        }
        calibrationOverlay.setViewMode(viewMode);
    }

    /** Entry point for the merged Adjust/Tour mode. Also purely local: the handle
     *  positions were already fetched for STATE_REVIEW's overlay (see
     *  PENDING_INITIAL_GEOMETRY), so entering Adjust just switches which of that
     *  already-cached data is shown/interactive -- no new request needed. */
    private void enterAdjust() {
        viewMode = VIEW_MODE_CANNY;
        tvViewModeLabel.setText("Canny");
        calibrationOverlay.setViewMode(viewMode);
        calibrationOverlay.setHandles(currentHandles);
        advanceState(STATE_ADJUST);
        tourIndex = 0;
        if (!currentHandles.isEmpty()) {
            goToTourIndex(0);
        }
    }

    /** Groups currentHandles' e1/e2 pairs by lineName into drawable segments for
     *  CalibrationOverlayView. A line missing either endpoint (only possible if
     *  handle_get_tour_points dropped it as fully out-of-bounds -- see its
     *  docstring) is simply not drawn. Marks each segment locked/not per
     *  currentLockedLineNames, so CalibrationOverlayView can draw it pink. */
    private List<CalibrationOverlayView.LineSegment> deriveBlueLineSegments() {
        java.util.Map<String, CalibrationOverlayView.HandlePoint> e1s = new java.util.HashMap<>();
        java.util.Map<String, CalibrationOverlayView.HandlePoint> e2s = new java.util.HashMap<>();
        java.util.Map<String, CalibrationOverlayView.HandlePoint> mids = new java.util.HashMap<>();
        for (CalibrationOverlayView.HandlePoint h : currentHandles) {
            if ("e1".equals(h.pointType)) e1s.put(h.lineName, h);
            else if ("e2".equals(h.pointType)) e2s.put(h.lineName, h);
            else if ("mid".equals(h.pointType)) mids.put(h.lineName, h);
        }
        List<CalibrationOverlayView.LineSegment> segments = new ArrayList<>();
        for (String lineName : e1s.keySet()) {
            CalibrationOverlayView.HandlePoint e1 = e1s.get(lineName);
            CalibrationOverlayView.HandlePoint e2 = e2s.get(lineName);
            if (e1 == null || e2 == null) continue;
            // Deliberate departure from court_recognition.py (endpoint-only):
            // the line itself draws pink if EITHER an endpoint OR its own
            // center point has been edited, since editing either one affects
            // the whole line -- an untouched center point is just the
            // physical midpoint of the current e1/e2, so once the line is
            // "touched" at all, all of it reflects that edit.
            boolean locked = currentLockedLineNames.contains(lineName)
                    || currentLockedMidpointNames.contains(lineName);
            // If the center point has been individually edited, draw the line as
            // two segments through its exact (possibly off-straight-line) locked
            // position -- e1-to-center and center-to-e2 -- matching
            // court_recognition.py's rendering for a locked midpoint exactly,
            // rather than a single straight line that would visibly miss the
            // center marker once it's been dragged off that line.
            CalibrationOverlayView.HandlePoint mid = mids.get(lineName);
            if (mid != null && currentLockedMidpointNames.contains(lineName)) {
                segments.add(new CalibrationOverlayView.LineSegment(lineName, e1.x, e1.y, mid.x, mid.y, locked));
                segments.add(new CalibrationOverlayView.LineSegment(lineName, mid.x, mid.y, e2.x, e2.y, locked));
            } else {
                segments.add(new CalibrationOverlayView.LineSegment(lineName, e1.x, e1.y, e2.x, e2.y, locked));
            }
        }
        return segments;
    }

    /** Parses a GET_TOUR_POINTS response into currentHandles plus the wireframe/
     *  anchor/strap/locked-line geometry (see handle_get_tour_points's docstring
     *  for the "name:type=x,y" / "WF:x1,y1,x2,y2" / "LK:name" / "LKM:name" /
     *  "AN:x,y" / "ST:x,y" entry formats), updates the overlay, then continues
     *  whatever action was waiting on this fetch. */
    private void handleTourPointsResponse(String payload) {
        List<CalibrationOverlayView.HandlePoint> parsedHandles = new ArrayList<>();
        List<float[]> parsedWireframe = new ArrayList<>();
        List<float[]> parsedAnchors = new ArrayList<>();
        java.util.Set<String> parsedLocked = new java.util.HashSet<>();
        java.util.Set<String> parsedLockedMid = new java.util.HashSet<>();
        float[] parsedStrap = null;

        if (payload != null && !payload.isEmpty()) {
            for (String entry : payload.split(";")) {
                try {
                    if (entry.startsWith("WF:")) {
                        String[] xy = entry.substring("WF:".length()).split(",");
                        parsedWireframe.add(new float[]{Float.parseFloat(xy[0]), Float.parseFloat(xy[1]),
                                Float.parseFloat(xy[2]), Float.parseFloat(xy[3])});
                        continue;
                    }
                    if (entry.startsWith("LKM:")) {
                        parsedLockedMid.add(entry.substring("LKM:".length()));
                        continue;
                    }
                    if (entry.startsWith("LK:")) {
                        parsedLocked.add(entry.substring("LK:".length()));
                        continue;
                    }
                    if (entry.startsWith("AN:")) {
                        String[] xy = entry.substring("AN:".length()).split(",");
                        parsedAnchors.add(new float[]{Float.parseFloat(xy[0]), Float.parseFloat(xy[1])});
                        continue;
                    }
                    if (entry.startsWith("ST:")) {
                        String[] xy = entry.substring("ST:".length()).split(",");
                        parsedStrap = new float[]{Float.parseFloat(xy[0]), Float.parseFloat(xy[1])};
                        continue;
                    }
                    int colonIdx = entry.indexOf(':');
                    int eqIdx = entry.indexOf('=');
                    if (colonIdx < 0 || eqIdx < 0 || eqIdx < colonIdx) continue;
                    String lineName = entry.substring(0, colonIdx);
                    String pointType = entry.substring(colonIdx + 1, eqIdx);
                    String[] xy = entry.substring(eqIdx + 1).split(",");
                    float x = Float.parseFloat(xy[0]);
                    float y = Float.parseFloat(xy[1]);
                    parsedHandles.add(new CalibrationOverlayView.HandlePoint(lineName, pointType, x, y));
                } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
                    // Skip malformed entries rather than aborting the whole response.
                }
            }
        }

        // Preserve position in the sequence across a refresh (e.g. after Accept)
        // by matching on (lineName, pointType) rather than resetting to index 0,
        // so accepting an edit doesn't kick the user back to the start.
        String keepLineName = (selectedHandle != null) ? selectedHandle.lineName : null;
        String keepPointType = (selectedHandle != null) ? selectedHandle.pointType : null;

        currentHandles = parsedHandles;
        currentWireframeSegments = parsedWireframe;
        currentAnchorPoints = parsedAnchors;
        currentStrapPoint = parsedStrap;
        currentLockedLineNames = parsedLocked;
        currentLockedMidpointNames = parsedLockedMid;

        calibrationOverlay.setWireframeSegments(currentWireframeSegments);
        calibrationOverlay.setBlueLineSegments(deriveBlueLineSegments());
        calibrationOverlay.setAnchorPoints(currentAnchorPoints);
        calibrationOverlay.setStrapPoint(currentStrapPoint);
        calibrationOverlay.setLockedMidpointLineNames(currentLockedMidpointNames);

        switch (pendingAction) {
            case PENDING_INITIAL_GEOMETRY:
                pendingAction = PENDING_NONE;
                calibrationOverlay.setViewMode(viewMode);
                advanceState(STATE_REVIEW);
                break;
            case PENDING_REFRESH_AFTER_ADJUST:
                pendingAction = PENDING_NONE;
                adjustInFlight = false; // full accept-adjustment round-trip is now complete
                calibrationOverlay.setHandles(currentHandles);
                // Find the just-edited point in the refreshed list, then advance ONE
                // PAST it (wrapping around) -- Accept auto-advances to the next point,
                // rather than sitting on the one just accepted.
                int editedIndex = 0;
                if (keepLineName != null) {
                    for (int i = 0; i < currentHandles.size(); i++) {
                        CalibrationOverlayView.HandlePoint h = currentHandles.get(i);
                        if (h.lineName.equals(keepLineName) && h.pointType.equals(keepPointType)) {
                            editedIndex = i;
                            break;
                        }
                    }
                }
                if (!currentHandles.isEmpty()) {
                    goToTourIndex((editedIndex + 1) % currentHandles.size());
                }
                break;
            default:
                break;
        }
    }

    private CalibrationOverlayView.HandlePoint findPivotFor(CalibrationOverlayView.HandlePoint h) {
        String otherType = "e1".equals(h.pointType) ? "e2" : "e1";
        for (CalibrationOverlayView.HandlePoint other : currentHandles) {
            if (other.lineName.equals(h.lineName) && otherType.equals(other.pointType)) {
                return other;
            }
        }
        return null;
    }

    private void tourStep(int delta) {
        if (currentHandles.isEmpty()) return;
        int next = ((tourIndex + delta) % currentHandles.size() + currentHandles.size())
                % currentHandles.size();
        goToTourIndex(next);
    }

    /** Navigates to a specific handle: zooms/pans to it and highlights it, in pure
     *  inspection mode -- editArmed resets to false and no live preview line is
     *  drawn, so its real current line stays visible and free pan/zoom/Prev/Next
     *  never affects anything. Editing only starts once armEdit() is explicitly
     *  called (see the Edit/Accept button listener in onCreate). Used both for
     *  Prev/Next stepping and for resuming (one past) after an Accept round-trip. */
    private void goToTourIndex(int index) {
        tourIndex = index;
        CalibrationOverlayView.HandlePoint h = currentHandles.get(index);
        selectedHandle = h;
        editArmed = false;
        calibrationOverlay.setHighlighted(h);
        calibrationOverlay.endDrag();
        zoomToBitmapPoint(h.x, h.y);
        tvAdjustPointLabel.setText((index + 1) + "/" + currentHandles.size() + "  " + h.lineName + ":" + h.pointType);
        tvInstruction.setText("Pan/zoom to inspect,\ntap Edit to reposition");
        btnAdjustAccept.setImageResource(android.R.drawable.ic_menu_edit);
        tvAdjustAcceptLabel.setText("Edit");
    }

    /** Arms editing for the currently-highlighted point: from this moment, panning
     *  repositions the candidate location (tracked via the live preview line --
     *  see CalibrationOverlayView) and the point's real line hides in favor of
     *  that preview, matching court_recognition.py. The preview starts from
     *  wherever the crosshair currently sits (from whatever inspecting/panning
     *  already happened), same as an endpoint drag's pivot-to-crosshair line;
     *  for a midpoint (no pivot to rotate around) the anchor is its own current
     *  position instead, so the preview shows the perpendicular offset being
     *  applied. A second tap of the same button (now labeled Accept) commits it. */
    private void armEdit() {
        if (selectedHandle == null) return;
        editArmed = true;
        boolean isMid = "mid".equals(selectedHandle.pointType);
        if (isMid) {
            // Two pivots (e1 and e2 of the SAME line): the preview draws
            // e1-to-crosshair and crosshair-to-e2, showing the candidate "bent"
            // line through the new midpoint -- not a stray line from the
            // midpoint's old position to the new one (that was the bug reported:
            // "a line from the original control point to the edited point,
            // not from the ends to the central point").
            CalibrationOverlayView.HandlePoint e1 = null, e2 = null;
            for (CalibrationOverlayView.HandlePoint h : currentHandles) {
                if (!h.lineName.equals(selectedHandle.lineName)) continue;
                if ("e1".equals(h.pointType)) e1 = h;
                else if ("e2".equals(h.pointType)) e2 = h;
            }
            List<float[]> pivots = new ArrayList<>();
            if (e1 != null) pivots.add(new float[]{e1.x, e1.y});
            if (e2 != null) pivots.add(new float[]{e2.x, e2.y});
            calibrationOverlay.startDrag(pivots);
        } else {
            CalibrationOverlayView.HandlePoint pivot = findPivotFor(selectedHandle);
            if (pivot != null) {
                calibrationOverlay.startDrag(pivot.x, pivot.y);
            }
        }
        String pointDesc = isMid ? "curve" : "endpoint";
        tvInstruction.setText("Pan so the crosshair\nis on the true line " + pointDesc
                + ".\nThen tap Accept.");
        btnAdjustAccept.setImageResource(android.R.drawable.checkbox_on_background);
        tvAdjustAcceptLabel.setText("Accept");
    }

    /** Sends the crosshair's current bitmap-space position as the new location for
     *  selectedHandle, and waits for the server's recomputed geometry. Only
     *  reachable once armEdit() has been called (see the button listener in
     *  onCreate) -- deliberately not automatic on every pan-stop, a full
     *  recompute pass measured ~0.5s+ on a MacBook Pro (likely slower on the
     *  Orin Nano). */
    private void acceptAdjustment() {
        if (selectedHandle == null || adjustInFlight) return;
        adjustInFlight = true;
        float[] crosshair = extractScreenCenterToBitmapPixelReturning();
        if (crosshair == null) {
            adjustInFlight = false;
            return;
        }
        tvInstruction.setText("Recomputing...");
        pendingAction = PENDING_REFRESH_AFTER_ADJUST;
        sendCommand(String.format(java.util.Locale.US, "ADJUST_BLUE_LINE:%d,%s,%s,%.2f,%.2f",
                sensorId, selectedHandle.lineName, selectedHandle.pointType,
                crosshair[0] * TCP_SCALE_FACTOR, crosshair[1] * TCP_SCALE_FACTOR));
        // adjustInFlight stays true until the response (handleTourPointsResponse's
        // PENDING_REFRESH_AFTER_ADJUST case, reached via the ADJUST_COMPLETE ->
        // GET_TOUR_POINTS chain in setupObservers) or an abort (setupObservers'
        // ABORTED handler) clears it -- NOT reset here, or a second Accept tap
        // could fire mid-recompute.
    }

    private void exitToReview() {
        selectedHandle = null;
        editArmed = false;
        calibrationOverlay.clearHandles();
        viewMode = VIEW_MODE_BOTH;
        tvViewModeLabel.setText("Both");
        calibrationOverlay.setViewMode(viewMode);
        advanceState(STATE_REVIEW);
    }

    /** Re-centers the viewport so the given bitmap-space point ends up under the
     *  fixed screen-center crosshair, at ADJUST_ZOOM_MULTIPLIER -- the same "zoom to
     *  point" pattern as the single-tap zoom, generalized to an arbitrary target
     *  point instead of wherever the user happened to tap, and deliberately less
     *  aggressive than STRONG_ZOOM_MULTIPLIER since this fires automatically for
     *  every handle (including ones near the image edge), not just where the user
     *  chose to tap. */
    private void zoomToBitmapPoint(float bx, float by) {
        if (currentBitmap == null) return;
        resetMatrixForBitmap(currentBitmap);
        float[] pt = {bx, by};
        imageMatrix.mapPoints(pt);
        float targetScale = baseScale * ADJUST_ZOOM_MULTIPLIER;
        imageMatrix.postScale(targetScale / baseScale, targetScale / baseScale, pt[0], pt[1]);
        float[] afterScale = {bx, by};
        imageMatrix.mapPoints(afterScale);
        float viewCx = ivCalibrationImage.getWidth() / 2f;
        float viewCy = ivCalibrationImage.getHeight() / 2f;
        imageMatrix.postTranslate(viewCx - afterScale[0], viewCy - afterScale[1]);
        ivCalibrationImage.setImageMatrix(imageMatrix);
        calibrationOverlay.invalidate();
    }

    /**
     * Resets the viewport matrix to fit the entire bitmap vertically within the ImageView,
     * centered horizontally.
     */
    private void resetMatrixForBitmap(Bitmap bmp) {
        if (bmp == null) return;
        imageMatrix.reset();
        
        int viewWidth = ivCalibrationImage.getWidth();
        int viewHeight = ivCalibrationImage.getHeight();
        if (viewWidth == 0 || viewHeight == 0) {
            // Layout hasn't been measured yet. Re-post so we retry after the next layout pass
            // rather than silently leaving the matrix uninitialized.
            ivCalibrationImage.post(() -> resetMatrixForBitmap(bmp));
            return;
        }
        
        // Scale to perfectly fit the vertical bounds of the landscape display
        baseScale = (float) viewHeight / bmp.getHeight();
        imageMatrix.postScale(baseScale, baseScale);
        
        // Center horizontally
        float scaledWidth = bmp.getWidth() * baseScale;
        float tx = (viewWidth - scaledWidth) / 2f;
        imageMatrix.postTranslate(tx, 0);
        
        ivCalibrationImage.setImageMatrix(imageMatrix);
        calibrationOverlay.invalidate();
    }

    private void startCalibrationFlow() {
        // Synchronously clear the sticky LiveData values before the observers go active
        // (onStart). postValue(null) inside sendCommand() runs too late because startService()
        // is asynchronous — the observers would otherwise receive stale data from the
        // previous calibration session: a stale image (clearImageData), or a stale
        // "TOUR_POINTS" status payload (clearStatusData) that would repopulate
        // CalibrationOverlayView with the previous session's wireframe/blue-line
        // geometry on top of this session's fresh image -- reported directly as
        // seeing old lines overlaid on the new "uncorrected image".
        CommunicationService.clearImageData();
        CommunicationService.clearStatusData();
        extractedCoords.clear();
        previousStrapClicks.clear();
        lastCalibUsedStrap = false;
        originalCalibBitmap = null;
        currentBitmap = null;
        ivCalibrationImage.setImageBitmap(null);
        // Discard any wireframe/blue-line/handle geometry from a previous attempt --
        // a fresh START_CALIBRATION means a new photo and pose, so none of it is
        // valid anymore.
        currentHandles = new ArrayList<>();
        currentWireframeSegments = new ArrayList<>();
        currentAnchorPoints = new ArrayList<>();
        currentStrapPoint = null;
        currentLockedLineNames = new java.util.HashSet<>();
        currentLockedMidpointNames = new java.util.HashSet<>();
        selectedHandle = null;
        editArmed = false;
        pendingAction = PENDING_NONE;
        adjustInFlight = false;
        calibrationOverlay.clearHandles();
        calibrationOverlay.setWireframeSegments(null);
        calibrationOverlay.setBlueLineSegments(null);
        calibrationOverlay.setAnchorPoints(null);
        calibrationOverlay.setStrapPoint(null);
        calibrationOverlay.setLockedMidpointLineNames(null);
        advanceState(STATE_LOADING);
        
        sendCommand("START_CALIBRATION:" + sensorId + "," + TCP_SCALE_FACTOR + "," + getSettingsPayload());
    }

    private String getSettingsPayload() {
        android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this);
        float expComp = (prefs.getInt(SettingsActivity.KEY_EXP_COMP_PROGRESS, 8) - 8) * 0.25f;
        StringBuilder sb = new StringBuilder();
        sb.append("exp_comp=").append(expComp)
          .append(",gain=").append(prefs.getFloat(SettingsActivity.KEY_GAIN, 1.0f))
          .append(",digital_gain=").append(prefs.getFloat(SettingsActivity.KEY_DIGITAL_GAIN, 1.0f))
          .append(",exposureLow=").append(prefs.getLong(SettingsActivity.KEY_EXPOSURE_LOW, 33333L))
          .append(",exposureHigh=").append(prefs.getLong(SettingsActivity.KEY_EXPOSURE_HIGH, 33333L))
          .append(",aelock=").append(prefs.getBoolean(SettingsActivity.KEY_AE_LOCK, false) ? 1 : 0)
          .append(",awblock=").append(prefs.getBoolean(SettingsActivity.KEY_AWB_LOCK, false) ? 1 : 0)
          .append(",logging=1") // detailed tracking logs are always on -- no longer user-toggleable
          .append(",det_thresh=").append(prefs.getInt(SettingsActivity.KEY_DET_THRESH, 50))
          .append(",use_canned=").append(prefs.getBoolean(SettingsActivity.KEY_USE_CANNED_CALIBRATION, false) ? 1 : 0);
        return sb.toString();
    }

    private void handleConfirmAction() {
        if (currentState >= STATE_TARGET_FAR_OUT && currentState <= STATE_TARGET_NEAR_IN) {
            extractScreenCenterToBitmapPixel();

            if (currentState == STATE_TARGET_NEAR_IN) {
                // All 4 anchor points collected — advance to optional net-strap step.
                advanceState(STATE_TARGET_NET_STRAP);
                ivCalibrationImage.post(() -> resetMatrixForBitmap(currentBitmap));
            } else {
                advanceState(currentState + 1);
                // RESET VIEWPORT: Ensures every click starts from a fresh, centered perspective.
                // We use post() to ensure the advanceState layout changes have settled.
                ivCalibrationImage.post(() -> resetMatrixForBitmap(currentBitmap));
            }

        } else if (currentState == STATE_TARGET_NET_STRAP) {
            // "Skip" (green Confirm button) — proceed with 8 anchor coords only, no strap.
            lastCalibUsedStrap = false;
            advanceState(STATE_VALIDATING);
            sendProcessCalibration();

        } else if (currentState == STATE_REVIEW) {
            sendCommand("CONFIRM_CALIBRATION:" + sensorId);
        }
    }

    private void handleThirdAction() {
        if (currentState != STATE_REVIEW) return;
        if (lastCalibUsedStrap) {
            // "Remove Strap" — strip the strap coords and resubmit with 8 anchors only.
            while (extractedCoords.size() > 8) extractedCoords.remove(extractedCoords.size() - 1);
            lastCalibUsedStrap = false;
            advanceState(STATE_VALIDATING);
            sendProcessCalibration();
        } else {
            // "Add Strap" — return to the strap-click step, keeping the 4 anchor coords.
            // Show the original calibration image with dots at any previous strap attempts.
            Bitmap withDots = drawStrapDotsOnBitmap(originalCalibBitmap);
            currentBitmap = withDots;
            ivCalibrationImage.setImageBitmap(withDots);
            advanceState(STATE_TARGET_NET_STRAP);
            ivCalibrationImage.post(() -> resetMatrixForBitmap(currentBitmap));
        }
    }

    /** Builds and sends PROCESS_CALIBRATION with whatever is in extractedCoords.
     *  8 values = 4 anchor points only; 10 values = 4 anchors + net strap. */
    private void sendProcessCalibration() {
        String baselineSide = (sensorId == 0) ? "left" : "right";
        StringBuilder sb = new StringBuilder();
        sb.append("PROCESS_CALIBRATION:").append(sensorId).append(",")
          .append(baselineSide).append(",").append(TCP_SCALE_FACTOR);
        for (Float f : extractedCoords) {
            sb.append(",").append(f);
        }
        sendCommand(sb.toString());
    }

    private void extractScreenCenterToBitmapPixel() {
        if (currentBitmap == null) return;
        
        Matrix inverse = new Matrix();
        if (imageMatrix.invert(inverse)) {
            // Screen geometric center
            float[] center = {ivCalibrationImage.getWidth() / 2f, ivCalibrationImage.getHeight() / 2f};
            
            // Map Screen UI Point -> Raw Image Pixel
            inverse.mapPoints(center);
            
            float imgX = center[0];
            float imgY = center[1];
            
            // Guard against extreme zooming past the image bounds
            imgX = Math.max(0, Math.min(currentBitmap.getWidth(), imgX));
            imgY = Math.max(0, Math.min(currentBitmap.getHeight(), imgY));
            
            extractedCoords.add(imgX);
            extractedCoords.add(imgY);
        }
    }

    /** Same screen-center-to-bitmap-pixel mapping as extractScreenCenterToBitmapPixel(),
     *  but returns the value instead of appending to extractedCoords (the 4-anchor/
     *  net-strap click list) -- used by acceptAdjustment(), which is a completely
     *  separate flow from the initial anchor-collection one. */
    private float[] extractScreenCenterToBitmapPixelReturning() {
        if (currentBitmap == null) return null;
        Matrix inverse = new Matrix();
        if (!imageMatrix.invert(inverse)) return null;
        float[] center = {ivCalibrationImage.getWidth() / 2f, ivCalibrationImage.getHeight() / 2f};
        inverse.mapPoints(center);
        center[0] = Math.max(0, Math.min(currentBitmap.getWidth(), center[0]));
        center[1] = Math.max(0, Math.min(currentBitmap.getHeight(), center[1]));
        return center;
    }

    private void advanceState(int newState) {
        currentState = newState;

        // Adjust/Tour / view-mode controls are only relevant to their own states;
        // default them to hidden and let the specific cases below re-show what
        // they need, rather than repeating "GONE" in every unrelated case.
        llViewMode.setVisibility(View.GONE);
        llAdjustEntry.setVisibility(View.GONE);
        llAdjustNav.setVisibility(View.GONE);
        tvAdjustPointLabel.setVisibility(View.GONE);

        switch(currentState) {
            case STATE_LOADING:
                tvInstruction.setText("Waking Camera Hardware...");
                llConfirm.setVisibility(View.GONE);
                llRetake.setVisibility(View.GONE);
                llThird.setVisibility(View.GONE);
                break;
                
            case STATE_TARGET_FAR_OUT:
                tvInstruction.setText("1/4\nFar Baseline\n&\nFar Sideline");
                llConfirm.setVisibility(View.VISIBLE);
                llRetake.setVisibility(View.VISIBLE);
                tvConfirmLabel.setText("Confirm");
                break;
                
            case STATE_TARGET_FAR_IN:
                tvInstruction.setText("2/4\nFar Baseline\n&\nNear Sideline");
                break;
                
            case STATE_TARGET_NEAR_OUT:
                tvInstruction.setText("3/4\nService Line\n&\nFar Sideline");
                break;
                
            case STATE_TARGET_NEAR_IN:
                tvInstruction.setText("4/4\nService Line\n&\nNear Sideline");
                break;

            case STATE_TARGET_NET_STRAP:
                tvInstruction.setText("5/5 (Optional)\nZoom to net strap\nthen tap Capture.\nOr tap Skip.");
                tvRetakeLabel.setText("Capture");
                tvConfirmLabel.setText("Skip");
                btnRetake.setImageResource(android.R.drawable.ic_menu_camera);
                break;

            case STATE_VALIDATING:
                tvInstruction.setText("Processing 4K Math...");
                tvRetakeLabel.setText("Retake");
                llConfirm.setVisibility(View.GONE);
                llRetake.setVisibility(View.GONE);
                llThird.setVisibility(View.GONE);
                break;

            case STATE_REVIEW:
                tvInstruction.setText("Review");
                llConfirm.setVisibility(View.VISIBLE);
                llRetake.setVisibility(View.VISIBLE);
                llThird.setVisibility(View.VISIBLE);
                llViewMode.setVisibility(View.VISIBLE);
                llAdjustEntry.setVisibility(View.VISIBLE);
                calibrationOverlay.clearHandles();
                tvConfirmLabel.setText("Accept");
                // When a strap was used: gray button redoes just the strap (keeps 4 anchors).
                // When no strap: gray button does a full restart.
                tvRetakeLabel.setText(lastCalibUsedStrap ? "Redo" : "Retake");
                btnRetake.setImageResource(android.R.drawable.ic_menu_rotate);
                tvThirdLabel.setText(lastCalibUsedStrap ? "-Strap" : "+Strap");
                btnThird.setImageResource(lastCalibUsedStrap
                        ? android.R.drawable.ic_menu_delete
                        : android.R.drawable.ic_menu_add);
                break;

            case STATE_ADJUST:
                // Tour and Adjust Lines merged into one mode: Prev/Next freely browse
                // every handle (pure inspection, no effect), Edit/Accept (same
                // button, see armEdit()/acceptAdjustment()) is the explicit two-step
                // action that actually changes anything. X (btnCancel) steps back to
                // Review from here instead of exiting the activity.
                llConfirm.setVisibility(View.GONE);
                llRetake.setVisibility(View.GONE);
                llThird.setVisibility(View.GONE);
                llViewMode.setVisibility(View.VISIBLE);
                llAdjustNav.setVisibility(View.VISIBLE);
                tvAdjustPointLabel.setVisibility(View.VISIBLE);
                // goToTourIndex() (called right after entering this state) sets the
                // real instruction text/button label for the specific point landed on.
                break;
        }
    }

    /** Returns a mutable copy of source with a magenta dot drawn at each previous
     *  strap click position. Dot radius scales with image width so it is visible
     *  at both 1080p and 4K. */
    private Bitmap drawStrapDotsOnBitmap(Bitmap source) {
        if (source == null) return null;
        Bitmap mutable = source.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutable);
        int radius = Math.max(2, source.getWidth() / 1200); // ~1-2px at 1080p, ~3px at 4K
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xFFFF69B4); // pink
        fill.setStyle(Paint.Style.FILL);
        for (PointF pt : previousStrapClicks) {
            canvas.drawCircle(pt.x, pt.y, radius, fill);
        }
        return mutable;
    }

    private void sendCommand(String cmd) {
        Intent intent = new Intent(this, CommunicationService.class);
        intent.setAction(CommunicationService.ACTION_SEND_COMMAND);
        intent.putExtra(CommunicationService.EXTRA_COMMAND, cmd + "\n");
        startService(intent);
    }
}
