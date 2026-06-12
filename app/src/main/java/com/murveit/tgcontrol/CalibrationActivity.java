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
 * blue third button — "Add Strap" when the current calibration used no strap, "Remove Strap"
 * when it did. "Add Strap" returns to STATE_TARGET_NET_STRAP with the original calibration image
 * and pink dots marking all previous strap attempts. "Remove Strap" strips the strap coords,
 * resubmits the 8-anchor calibration, and shows a fresh validation image with "Add Strap" again.
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
    
    private static final int STATE_LOADING = 0;
    private static final int STATE_TARGET_FAR_OUT = 1;
    private static final int STATE_TARGET_FAR_IN = 2;
    private static final int STATE_TARGET_NEAR_OUT = 3;
    private static final int STATE_TARGET_NEAR_IN = 4;
    private static final int STATE_TARGET_NET_STRAP = 5; // optional net-strap top click
    private static final int STATE_VALIDATING = 6;
    private static final int STATE_REVIEW = 7;

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

        setupTouchMatrix();
        setupObservers();

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
        btnCancel.setOnClickListener(v -> finish()); // Gracefully exits the activity

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
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
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
                currentBitmap = bmp; // Validation composite replaces current; originalCalibBitmap stays
                ivCalibrationImage.setImageBitmap(bmp);
                ivCalibrationImage.post(() -> resetMatrixForBitmap(bmp));
                advanceState(STATE_REVIEW);
            }
        });
        
        CommunicationService.getStatusData().observe(this, pair -> {
            if (pair == null) return;
            if ("CALIBRATION_SAVED".equals(pair.second)) {
                // Toast.makeText(this, "Matrix Locked to Hardware.", Toast.LENGTH_SHORT).show();
                finish();
            } else if (pair.second != null && pair.second.contains("ABORTED")) {
                Toast.makeText(this, "Server Error: " + pair.second, Toast.LENGTH_LONG).show();
                startCalibrationFlow();
            }
        });
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
    }

    private void startCalibrationFlow() {
        extractedCoords.clear();
        previousStrapClicks.clear();
        lastCalibUsedStrap = false;
        originalCalibBitmap = null;
        currentBitmap = null;
        ivCalibrationImage.setImageBitmap(null);
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
          .append(",logging=").append(prefs.getBoolean(SettingsActivity.KEY_ENABLE_LOGGING, false) ? 1 : 0)
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

    private void advanceState(int newState) {
        currentState = newState;
        
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
                tvInstruction.setText("Review Algorithm Fit\n(Check Blue & Green Lines)");
                llConfirm.setVisibility(View.VISIBLE);
                llRetake.setVisibility(View.VISIBLE);
                llThird.setVisibility(View.VISIBLE);
                tvConfirmLabel.setText("Accept");
                // When a strap was used: gray button redoes just the strap (keeps 4 anchors).
                // When no strap: gray button does a full restart.
                tvRetakeLabel.setText(lastCalibUsedStrap ? "Redo Strap" : "Retake");
                btnRetake.setImageResource(android.R.drawable.ic_menu_rotate);
                tvThirdLabel.setText(lastCalibUsedStrap ? "Remove Strap" : "Add Strap");
                btnThird.setImageResource(lastCalibUsedStrap
                        ? android.R.drawable.ic_menu_delete
                        : android.R.drawable.ic_menu_add);
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
