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
 * - Inverse Mapping: The UI renders a fixed crosshair at exactly (ViewWidth / 2, ViewHeight / 2). 
 * When the user taps "Confirm", the mathematical inverse of the current affine Matrix is calculated. 
 * The screen's center point is multiplied through this inverted matrix to yield the exact sub-pixel 
 * coordinate on the underlying 1080p raw Bitmap.
 * - State Machine: Drives the user through 4 discrete corner selections, managing UI button visibility, 
 * tracking extracted coordinates in a `Float` array, and orchestrating the TCP protocol (START -> PROCESS -> CONFIRM).
 * - Lifecycle Resets: Resets the viewport (zoom and pan) after every confirmation to ensure the user 
 * starts with a centered full-view perspective for the next target.
 *
 * 4. EXPECTED OUTPUTS / SIDE EFFECTS:
 * - Pushes Affine Matrix transforms directly to the GPU for smooth 60fps zooming.
 * - Commits calibration JSONs to the Orin Nano disk upon completion.
 */

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
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
    private static final float TCP_SCALE_FACTOR = 0.5f; // Forces Jetson to downsample 4K to 1080p
    
    private static final int STATE_LOADING = 0;
    private static final int STATE_TARGET_FAR_OUT = 1;
    private static final int STATE_TARGET_FAR_IN = 2;
    private static final int STATE_TARGET_NEAR_OUT = 3;
    private static final int STATE_TARGET_NEAR_IN = 4;
    private static final int STATE_VALIDATING = 5;
    private static final int STATE_REVIEW = 6;

    // --- State Tracking ---
    private int sensorId;
    private int currentState = STATE_LOADING;
    private Bitmap currentBitmap = null;
    private List<Float> extractedCoords = new ArrayList<>();

    // --- Matrix Math ---
    private Matrix imageMatrix = new Matrix();
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    // --- UI Widgets ---
    private ImageView ivCalibrationImage;
    private TextView tvInstruction;
    private TextView tvConfirmLabel;
    private FloatingActionButton btnConfirm;
    private FloatingActionButton btnRetake;
    private ImageButton btnCancel;
    private LinearLayout llRetake;
    private LinearLayout llConfirm;

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
        btnConfirm = findViewById(R.id.btnConfirm);
        btnRetake = findViewById(R.id.btnRetake);
        btnCancel = findViewById(R.id.btnCancel);
        llRetake = findViewById(R.id.llRetake);
        llConfirm = findViewById(R.id.llConfirm);

        setupTouchMatrix();
        setupObservers();
        
        btnConfirm.setOnClickListener(v -> handleConfirmAction());
        btnRetake.setOnClickListener(v -> startCalibrationFlow());
        btnCancel.setOnClickListener(v -> finish()); // Gracefully exits the activity

        // Kick off the state machine
        startCalibrationFlow();
    }

    private void setupTouchMatrix() {
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scaleFactor = detector.getScaleFactor();
                imageMatrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
                ivCalibrationImage.setImageMatrix(imageMatrix);
                return true;
            }
        });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                // PostTranslate requires absolute direction. distanceX/Y is "scroll delta", so we invert it.
                imageMatrix.postTranslate(-distanceX, -distanceY);
                ivCalibrationImage.setImageMatrix(imageMatrix);
                return true;
            }
        });

        ivCalibrationImage.setOnTouchListener((v, event) -> {
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
                ivCalibrationImage.setImageBitmap(bmp);
                ivCalibrationImage.post(() -> resetMatrixForBitmap(bmp)); // Wait for UI Layout pass
                advanceState(STATE_TARGET_FAR_OUT);
                
            } else if (currentState == STATE_VALIDATING && "calibration_validation".equals(target)) {
                currentBitmap = bmp;
                ivCalibrationImage.setImageBitmap(bmp);
                ivCalibrationImage.post(() -> resetMatrixForBitmap(bmp));
                advanceState(STATE_REVIEW);
            }
        });
        
        CommunicationService.getStatusData().observe(this, pair -> {
            if (pair == null) return;
            if ("CALIBRATION_SAVED".equals(pair.second)) {
                Toast.makeText(this, "Matrix Locked to Hardware.", Toast.LENGTH_SHORT).show();
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
        if (viewWidth == 0 || viewHeight == 0) return;
        
        // Scale to perfectly fit the vertical bounds of the landscape display
        float scale = (float) viewHeight / bmp.getHeight();
        imageMatrix.postScale(scale, scale);
        
        // Center horizontally
        float scaledWidth = bmp.getWidth() * scale;
        float tx = (viewWidth - scaledWidth) / 2f;
        imageMatrix.postTranslate(tx, 0);
        
        ivCalibrationImage.setImageMatrix(imageMatrix);
    }

    private void startCalibrationFlow() {
        extractedCoords.clear();
        currentBitmap = null;
        ivCalibrationImage.setImageBitmap(null);
        advanceState(STATE_LOADING);
        
        sendCommand("START_CALIBRATION:" + sensorId + "," + TCP_SCALE_FACTOR);
    }

    private void handleConfirmAction() {
        if (currentState >= STATE_TARGET_FAR_OUT && currentState <= STATE_TARGET_NEAR_IN) {
            extractScreenCenterToBitmapPixel();
            
            if (currentState == STATE_TARGET_NEAR_IN) {
                // All 4 points collected! Fire math processor.
                advanceState(STATE_VALIDATING);
                String baselineSide = (sensorId == 0) ? "right" : "left";
                
                StringBuilder sb = new StringBuilder();
                sb.append("PROCESS_CALIBRATION:").append(sensorId).append(",")
                  .append(baselineSide).append(",").append(TCP_SCALE_FACTOR);
                  
                for (Float f : extractedCoords) {
                    sb.append(",").append(f);
                }
                sendCommand(sb.toString());
            } else {
                advanceState(currentState + 1);
                // RESET VIEWPORT: Ensures every click starts from a fresh, centered perspective.
                // We use post() to ensure the advanceState layout changes have settled.
                ivCalibrationImage.post(() -> resetMatrixForBitmap(currentBitmap));
            }
            
        } else if (currentState == STATE_REVIEW) {
            sendCommand("CONFIRM_CALIBRATION:" + sensorId);
        }
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
                
            case STATE_VALIDATING:
                tvInstruction.setText("Processing 4K Math...");
                llConfirm.setVisibility(View.GONE);
                llRetake.setVisibility(View.GONE);
                break;
                
            case STATE_REVIEW:
                tvInstruction.setText("Review Algorithm Fit\n(Check Blue & Green Lines)");
                llConfirm.setVisibility(View.VISIBLE);
                llRetake.setVisibility(View.VISIBLE);
                tvConfirmLabel.setText("Accept");
                break;
        }
    }

    private void sendCommand(String cmd) {
        Intent intent = new Intent(this, CommunicationService.class);
        intent.setAction(CommunicationService.ACTION_SEND_COMMAND);
        intent.putExtra(CommunicationService.EXTRA_COMMAND, cmd + "\n");
        startService(intent);
    }
}
