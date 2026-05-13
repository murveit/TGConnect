package com.murveit.tgcontrol;

/**
 * Main Activity - Algorithmic Overview
 *
 * This module acts as the primary dashboard and orchestrator for the TennisGenius control application.
 *
 * 1. INITIALIZATION:
 * - Binds calibration checkmark ImageViews and mode selection buttons.
 * - Instantiates TextToSpeech and ToneGenerator engines to provide auditory user feedback.
 * - On creation or recreation (e.g., orientation changes), checks the persistent `CommunicationService` 
 * to see if an active socket exists. If so, it recovers the UI state seamlessly without dropping the connection.
 * - Sets default UI state to DISCONNECTED if no active connection is found.
 *
 * 2. INTERNAL ALGORITHMIC LOGIC:
 * - On connection, sends "GET_CALIBRATION_STATUS" to the Jetson.
 * - Listens for "CALIBRATION_STATUS:0=1,1=0" type messages over the LiveData observer.
 * - Parses incoming protocol strings utilizing class-level constants to evaluate hardware state.
 * - Updates local boolean state flags (`isLeftCalibrated`, `isRightCalibrated`) and triggers `updateTennisModeButtonsState()`.
 * - Toggles checkmark visibility and dynamically manages `.setEnabled()` states on the tennis mode buttons, 
 * enforcing the algorithmic requirement that both cameras must be calibrated before play modes unlock.
 * - Intercepts "CALIBRATION_SAVED" to instantly query and update UI when returning from CalibrationActivity.
 * - JSON Interception: Parses "TRACK_EVENT_JSON" and utilizes TextToSpeech to vocally call "Faults" 
 * or triggers a ToneGenerator "beep" for "In" balls during Serve Practice mode.
 * - Error Handling: Intercepts hardware-level watchdog timeouts from the server and displays blocking 
 * Alert Dialogs so the user knows exactly when a Jetson reboot is required.
 *
 * 3. EXPECTED OUTPUTS / SIDE EFFECTS:
 * - Visually disables (greys out) or enables Singles, Doubles, Serve, and Rally buttons in real-time.
 * - Dispatches specific startup, telemetry, and tracking stop/start strings to the TCP socket layer.
 * - Generates physical audio outputs (Voice synthesis, DTMF tones) matching real-time point progression.
 */

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    
    // --- Algorithmic Constants for TCP Commands ---
    private static final String CMD_SHUTDOWN_SYSTEM = "SHUTDOWN_SYSTEM\n";
    private static final String CMD_REBOOT_SYSTEM = "REBOOT_SYSTEM\n";
    private static final String CMD_RESTART_SERVICE = "RESTART_SERVICE\n";
    private static final String CMD_STOP_RECORDING = "STOP_RECORDING\n";
    private static final String CMD_STOP_TRACKING = "STOP_TRACKING\n";
    
    private static final String KEY_RECORD_SESSION = "record_session";

    // --- Algorithmic Constants for Protocol Parsing ---
    // Represents the string identifiers used by the server to identify camera arrays
    private static final String SENSOR_ID_LEFT_STR = "0";
    private static final String SENSOR_ID_RIGHT_STR = "1";
    // Represents the server's boolean-integer string for an active calibration
    private static final String CALIBRATION_ACTIVE_STR = "1";

    // --- Algorithmic Constants for Play Modes ---
    private static final String MODE_SINGLES = "SINGLES";
    private static final String MODE_DOUBLES = "DOUBLES";
    private static final String MODE_SERVE_PRACTICE = "SERVE_PRACTICE";
    private static final String MODE_RALLY_PRACTICE = "RALLY_PRACTICE";

    // --- Algorithmic Constants for Auditory Feedback ---
    // Represents the volume level for the 'in' beep tone generator (0-100)
    private static final int BEEP_VOLUME_MAX = 100;
    // Represents the duration in milliseconds for the happy 'in' beep
    private static final int HAPPY_BEEP_DURATION_MS = 150;
    // The text string spoken by TTS when a serve is out
    private static final String TTS_TEXT_FAULT = "Fault";
    // The text string spoken by TTS when a serve is a let
    private static final String TTS_TEXT_LET = "Let";
    // Cooldown duration in milliseconds before repeating "miles per hour"
    private static final long MPH_COOLDOWN_MS = 60000;

    // --- Algorithmic Constants for Client-Side Histogram Calculation ---
    // Determines how many rows/cols to skip during pixel extraction to preserve UI framerate
    private static final int HISTOGRAM_PIXEL_STRIDE = 5;
    // The standard number of luminance bins for an 8-bit image channel
    private static final int HISTOGRAM_COLOR_BINS = 256;

    private static final String Emulator_HOST = "10.0.2.2";
    private static final String TG_AP_HOST = "10.42.0.1";
    private static final String TG_CHICO_HOST = "192.168.86.43";
    private static final String LOCAL_HOST = "localhost";

    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 101;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_HOME = 1;
    private static final int STATE_RAW_RECORDING = 2;
    private static final int STATE_TENNIS_MENU = 3;
    private static final int STATE_ACTIVE_TENNIS = 4;

    private int currentState = STATE_DISCONNECTED;
    private Handler mainHandler;
    private boolean isConnected = false;
    private boolean isRecording = false;
    private boolean isTracking = false;
    private String activeTennisMode = MODE_SINGLES;
    
    // Tracking active calibration states for UI locking
    private boolean isLeftCalibrated = false;
    private boolean isRightCalibrated = false;

    // Tracks the last time the full "miles per hour" suffix was spoken
    private long lastSpokenMphTimeMs = 0;

    private Button btnBack, btnConnect;
    private ImageButton btnPowerOff, btnSettings, btnDebugAudio;
    private LinearLayout llHome, llHomeButtons, llRawRecording, llTennisMenu, llActiveTennis;
    private TextView tvHomeMessage, tvStatusLine1, tvStatusLine2;
    private Button btnGoRawRecording, btnGoTennis, btnStartRecording, btnCapturePhotos, btnStartTracking;
    private ImageView ivImage1, ivImage2, ivCheckLeft, ivCheckRight;
    private HistogramView histView1, histView2;
    private TextView tvTennisModeTitle, tvTrackingLog, tvSelectPlayMode, tvLiveTelemetry;
    private Button btnModeSingles, btnModeDoubles, btnModeServe, btnModeRally, btnCalibrateLeft, btnCalibrateRight;
    private CheckBox cbRecordSession;
    
    // Hardware Audio Engines
    private TextToSpeech textToSpeech;
    private ToneGenerator toneGenerator;
    private FastSpeechEngine fastSpeechEngine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        requestNotificationPermission();
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Initialize Audio Engines
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.US);

                // Initialize the fast speech engine cache
                fastSpeechEngine = new FastSpeechEngine(MainActivity.this, textToSpeech);
                fastSpeechEngine.initializeCache();
            }
        });
        
        initializeUI();
        setupListeners();
        setupObservers();
        
        // --- Algorithmic Fix: State Recovery ---
        // Interrogate the persistent background service. If the activity was destroyed by Android
        // due to an orientation change returning from calibration, we recover seamlessly here.
        if (CommunicationService.isServerConnected) {
            isConnected = true;
            switchState(STATE_TENNIS_MENU); // Put user right back on the menu
            // Wait slightly for UI to mount, then fetch fresh calibration checkmarks from Orin
            mainHandler.postDelayed(() -> sendCommand(buildGetCalibrationStatusCommand()), 250);
        } else {
            switchState(STATE_DISCONNECTED);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE_POST_NOTIFICATIONS);
            }
        }
    }

    private void initializeUI() {
        btnBack = findViewById(R.id.btnBack);
        btnConnect = findViewById(R.id.btnConnect);
        btnPowerOff = findViewById(R.id.btnPowerOff);
        btnSettings = findViewById(R.id.btnSettings);
        btnDebugAudio = findViewById(R.id.btnDebugAudio);
        llHome = findViewById(R.id.llHome);
        llHomeButtons = findViewById(R.id.llHomeButtons);
        llRawRecording = findViewById(R.id.llRawRecording);
        llTennisMenu = findViewById(R.id.llTennisMenu);
        llActiveTennis = findViewById(R.id.llActiveTennis);
        tvHomeMessage = findViewById(R.id.tvHomeMessage);
        btnGoRawRecording = findViewById(R.id.btnGoRawRecording);
        btnGoTennis = findViewById(R.id.btnGoTennis);
        tvStatusLine1 = findViewById(R.id.tvStatus1);
        tvStatusLine2 = findViewById(R.id.tvStatus2);
        btnStartRecording = findViewById(R.id.btnStartRecording);
        btnCapturePhotos = findViewById(R.id.btnCapturePhotos);
        ivImage1 = findViewById(R.id.ivImage1);
        ivImage2 = findViewById(R.id.ivImage2);
        histView1 = findViewById(R.id.histView1);
        histView2 = findViewById(R.id.histView2);
        ivCheckLeft = findViewById(R.id.ivCheckLeft);
        ivCheckRight = findViewById(R.id.ivCheckRight);
        btnModeSingles = findViewById(R.id.btnModeSingles);
        btnModeDoubles = findViewById(R.id.btnModeDoubles);
        btnModeServe = findViewById(R.id.btnModeServe);
        btnModeRally = findViewById(R.id.btnModeRally);
        btnCalibrateLeft = findViewById(R.id.btnCalibrateLeft);
        btnCalibrateRight = findViewById(R.id.btnCalibrateRight);
        tvTennisModeTitle = findViewById(R.id.tvTennisModeTitle);
        btnStartTracking = findViewById(R.id.btnStartTracking);
        tvTrackingLog = findViewById(R.id.tvTrackingLog);
        tvSelectPlayMode = findViewById(R.id.tvSelectPlayMode);
        tvLiveTelemetry = findViewById(R.id.tvLiveTelemetry);
        cbRecordSession = findViewById(R.id.cbRecordSession);
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (cbRecordSession != null) {
            cbRecordSession.setChecked(prefs.getBoolean(KEY_RECORD_SESSION, false));
            cbRecordSession.setOnCheckedChangeListener((btn, isChecked) -> {
                prefs.edit().putBoolean(KEY_RECORD_SESSION, isChecked).apply();
            });
        }
        
        // Toggle Histograms on Image Click
        ivImage1.setOnClickListener(v -> toggleHistogram(histView1));
        ivImage2.setOnClickListener(v -> toggleHistogram(histView2));
        
        // Force evaluation to ensure buttons are correctly disabled by default on startup
        updateTennisModeButtonsState();
    }

    private void toggleHistogram(HistogramView histView) {
        if (histView != null) {
            histView.setVisibility(histView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        }
    }

    private void setupListeners() {
        btnConnect.setOnClickListener(v -> {
            if (!isConnected) connectToServer();
            else disconnectFromServer();
        });
        btnPowerOff.setOnClickListener(v -> showPowerOffDialog());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));
        
        if (btnDebugAudio != null) {
            btnDebugAudio.setOnClickListener(v -> {
                int randomMph = 30 + (int)(Math.random() * 61); // 30 to 90 mph
                double randX = (Math.random() * 8) - 4;
                double randY = (Math.random() * 10) - 5;
                
                double r = Math.random();
                String call;
                if (r < 0.6) {
                    call = "In";
                } else if (r < 0.9) {
                    call = MODE_SERVE_PRACTICE.equals(activeTennisMode) ? "Fault" : "Out";
                } else {
                    call = MODE_SERVE_PRACTICE.equals(activeTennisMode) ? "Let" : "In";
                }

                String fakeJson = String.format(Locale.US,
                        "{\"wall_clock\": \"Debug\", \"strike_type\": \"Hit\", \"call_str\": \"%s\", \"speed_mph\": %d.0, \"strike_x\": 0.0, \"strike_y\": 11.0, \"bounce_x\": %.1f, \"bounce_y\": %.1f}",
                        call, randomMph, randX, randY);

                processTrackEventJson(fakeJson);
            });
        }
        
        btnBack.setOnClickListener(v -> onBackPressed());
        btnGoRawRecording.setOnClickListener(v -> switchState(STATE_RAW_RECORDING));
        btnGoTennis.setOnClickListener(v -> switchState(STATE_TENNIS_MENU));
        btnModeSingles.setOnClickListener(v -> startTennisModeUI(MODE_SINGLES, "Singles Match"));
        btnModeDoubles.setOnClickListener(v -> startTennisModeUI(MODE_DOUBLES, "Doubles Match"));
        btnModeServe.setOnClickListener(v -> startTennisModeUI(MODE_SERVE_PRACTICE, "Serve Practice"));
        btnModeRally.setOnClickListener(v -> startTennisModeUI(MODE_RALLY_PRACTICE, "Rally Practice"));
        btnCalibrateLeft.setOnClickListener(v -> launchCalibration(0));
        btnCalibrateRight.setOnClickListener(v -> launchCalibration(1));
        btnStartRecording.setOnClickListener(v -> toggleRecording());
        btnCapturePhotos.setOnClickListener(v -> sendCommand(buildCaptureCommand()));
        btnStartTracking.setOnClickListener(v -> toggleTracking());
    }

    private void switchState(int newState) {
        mainHandler.post(() -> {
            currentState = newState;
            llHome.setVisibility(View.GONE);
            llRawRecording.setVisibility(View.GONE);
            llTennisMenu.setVisibility(View.GONE);
            llActiveTennis.setVisibility(View.GONE);
            btnBack.setVisibility((newState == STATE_DISCONNECTED || newState == STATE_HOME) ? View.GONE : View.VISIBLE);

            if (btnDebugAudio != null) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                boolean showDebug = prefs.getBoolean(SettingsActivity.KEY_DEBUG_AUDIO, false);
                btnDebugAudio.setVisibility((newState == STATE_ACTIVE_TENNIS && showDebug) ? View.VISIBLE : View.GONE);
            }

            if (newState == STATE_DISCONNECTED) {
                llHome.setVisibility(View.VISIBLE);
                tvHomeMessage.setVisibility(View.VISIBLE);
                llHomeButtons.setVisibility(View.GONE);
                btnConnect.setText("CONNECT");
                btnPowerOff.setEnabled(false);
                ivCheckLeft.setVisibility(View.GONE);
                ivCheckRight.setVisibility(View.GONE);
            } else if (newState == STATE_HOME) {
                llHome.setVisibility(View.VISIBLE);
                tvHomeMessage.setVisibility(View.GONE);
                llHomeButtons.setVisibility(View.VISIBLE);
                btnConnect.setText("Disconnect");
                btnPowerOff.setEnabled(true);
            } else if (newState == STATE_RAW_RECORDING) llRawRecording.setVisibility(View.VISIBLE);
            else if (newState == STATE_TENNIS_MENU) llTennisMenu.setVisibility(View.VISIBLE);
            else if (newState == STATE_ACTIVE_TENNIS) llActiveTennis.setVisibility(View.VISIBLE);
        });
    }

    private void setupObservers() {
        CommunicationService.getStatusData().observe(this, statusPair -> {
            if (statusPair == null) return;
            String status = statusPair.first;
            String message = statusPair.second;

            if ("TRACK_EVENT".equals(status)) {
                appendToTrackingLog(message);
                return;
            }

            if ("TRACK_EVENT_JSON".equals(status)) {
                processTrackEventJson(message);
                return;
            }

            if ("TRACK_TELEMETRY".equals(status)) {
                tvLiveTelemetry.setText(message);
                return;
            }

            if ("CALIBRATION_STATUS".equals(status)) {
                handleCalibrationStatus(message);
                return;
            }

            if ("CALIBRATION_SAVED".equals(message)) {
                sendCommand(buildGetCalibrationStatusCommand());
            }

            updateUIStatus(status, message);

            if ("Connected".equals(status)) {
                if (!isConnected) {
                    isConnected = true;
                    switchState(STATE_HOME);
                    mainHandler.postDelayed(() -> {
                        sendCommand(buildSetTimeCommand());
                        sendCommand(buildGetCalibrationStatusCommand());
                    }, 500);
                }
            } else if ("Error".equals(status) || (message != null && message.startsWith("Disconnected"))) {
                
                // --- WATCHDOG / HARDWARE ERROR INTERCEPTION ---
                // ONLY trigger the blocking dialog for true system errors, ignoring routine disconnects
                if ("Error".equals(status) && message != null && !message.isEmpty()) {
                    // Use a blocking dialog for critical errors so the user cannot miss it
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Hardware Error")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .setCancelable(false)
                            .show();
                }

                isConnected = false;
                isRecording = false;
                isTracking = false;
                
                // Clear calibration locks internally when disconnect occurs
                isLeftCalibrated = false;
                isRightCalibrated = false;
                updateTennisModeButtonsState();
                
                switchState(STATE_DISCONNECTED);
            }
        });

        CommunicationService.getImageData().observe(this, imagePair -> {
            if (imagePair != null) {
                boolean isLeft = "image1".equals(imagePair.second);
                ImageView targetView = isLeft ? ivImage1 : ivImage2;
                HistogramView targetHist = isLeft ? histView1 : histView2;

                if (targetView != null) {
                    android.graphics.Bitmap bmp = imagePair.first;
                    targetView.setImageBitmap(bmp);
                    
                    // Asynchronously calculate the histogram without blocking the main UI thread
                    new Thread(() -> {
                        int[] hist = new int[HISTOGRAM_COLOR_BINS];
                        int width = bmp.getWidth();
                        int height = bmp.getHeight();
                        int totalSampled = 0;

                        // Fast subsampling logic
                        for (int y = 0; y < height; y += HISTOGRAM_PIXEL_STRIDE) {
                            for (int x = 0; x < width; x += HISTOGRAM_PIXEL_STRIDE) {
                                int pixel = bmp.getPixel(x, y);
                                // Extract Green channel as a fast approximation of perceptual luminance
                                int g = (pixel >> 8) & 0xFF;
                                hist[g]++;
                                totalSampled++;
                            }
                        }

                        int finalTotal = totalSampled;
                        mainHandler.post(() -> {
                            if (targetHist != null) {
                                targetHist.setHistogramData(hist, finalTotal);
                            }
                        });
                    }).start();
                }
            }
        });
    }

    private void processTrackEventJson(String message) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(message);
            String wallClock = json.optString("wall_clock", "");
            String strikeType = json.optString("strike_type", "Hit");
            String callStr = json.optString("call_str", "Unknown");
            double mph = json.optDouble("speed_mph", 0.0);
            double sX = json.optDouble("strike_x", 0.0);
            double sY = json.optDouble("strike_y", 0.0);
            double bX = json.optDouble("bounce_x", 0.0);
            double bY = json.optDouble("bounce_y", 0.0);
            
            if (strikeType.length() > 0) {
                strikeType = strikeType.substring(0, 1).toUpperCase() + strikeType.substring(1);
            }
            
            String timePrefix = wallClock.isEmpty() ? "" : wallClock + " ";

            // Customize by mode? Check if mph exists? Plot?
            String formatted = String.format(Locale.US, "%s%s. %s. %.0fmph", 
                                             timePrefix, strikeType, callStr, mph);
            appendToTrackingLog(formatted);
            
            // --- AUDIO FEEDBACK FOR SERVE PRACTICE ---
            if (MODE_SERVE_PRACTICE.equals(activeTennisMode)) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                boolean playVoice = prefs.getBoolean(SettingsActivity.KEY_VOICE_CALLS, false);
                boolean playBeep = prefs.getBoolean(SettingsActivity.KEY_BEEP_IN, false);
                boolean speakMph = prefs.getBoolean(SettingsActivity.KEY_SPEAK_MPH, false);

                if (fastSpeechEngine != null) {
                    if ("In".equalsIgnoreCase(callStr)) {
                        if (speakMph) {
                            int mphInt = (int) Math.round(mph);
                            String ttsMphStr;
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastSpokenMphTimeMs > MPH_COOLDOWN_MS) {
                                ttsMphStr = mphInt + " miles per hour";
                                lastSpokenMphTimeMs = currentTime;
                            } else {
                                ttsMphStr = String.valueOf(mphInt);
                            }
                            fastSpeechEngine.speak(ttsMphStr);
                        } else if (playBeep && toneGenerator != null) {
                            toneGenerator.startTone(ToneGenerator.TONE_PROP_PROMPT, HAPPY_BEEP_DURATION_MS);
                        }
                    } else if ("Out".equalsIgnoreCase(callStr) || "Fault".equalsIgnoreCase(callStr)) {
                        if (playVoice) {
                            fastSpeechEngine.speak(TTS_TEXT_FAULT);
                        }
                    } else if ("Let".equalsIgnoreCase(callStr)) {
                        if (playVoice) {
                            fastSpeechEngine.speak(TTS_TEXT_LET);
                        }
                    }
                }
            }

        } catch (Exception e) {
            FileLogger.log(this, "JSON Parse Error", e);
        }
    }

    private void handleCalibrationStatus(String data) {
        // Expected format: "0=1,1=0"
        mainHandler.post(() -> {
            String[] parts = data.split(",");
            for (String part : parts) {
                String[] pair = part.split("=");
                if (pair.length == 2) {
                    boolean active = CALIBRATION_ACTIVE_STR.equals(pair[1]);
                    
                    if (SENSOR_ID_LEFT_STR.equals(pair[0])) {
                        isLeftCalibrated = active;
                        ivCheckLeft.setVisibility(active ? View.VISIBLE : View.GONE);
                    }
                    if (SENSOR_ID_RIGHT_STR.equals(pair[0])) {
                        isRightCalibrated = active;
                        ivCheckRight.setVisibility(active ? View.VISIBLE : View.GONE);
                    }
                }
            }
            updateTennisModeButtonsState();
        });
    }
    
    private void updateTennisModeButtonsState() {
        boolean bothCalibrated = isLeftCalibrated && isRightCalibrated;
        
        if (tvSelectPlayMode != null) {
            tvSelectPlayMode.setText(bothCalibrated ? "Select Play Mode" : "Calibrate Before Playing");
        }
        
        btnModeSingles.setEnabled(bothCalibrated);
        btnModeDoubles.setEnabled(bothCalibrated);
        btnModeServe.setEnabled(bothCalibrated);
        btnModeRally.setEnabled(bothCalibrated);
    }

    private void toggleRecording() {
        if (!isRecording) {
            isRecording = true;
            updateRecordingButtons(true);
            sendCommand(buildStartRecordingCommand());
        } else {
            isRecording = false;
            sendCommand(CMD_STOP_RECORDING);
            updateRecordingButtons(false);
        }
    }

    private void toggleTracking() {
        if (!isTracking) {
            isTracking = true;
            updateTrackingButtons(true);
            tvTrackingLog.setText("");
            tvLiveTelemetry.setText(""); // Erase 1s stats line at startup
            sendCommand(buildStartTrackingCommand(activeTennisMode));
        } else {
            isTracking = false;
            sendCommand(CMD_STOP_TRACKING);
            updateTrackingButtons(false);
            tvTrackingLog.append("\n--- Stopped ---");
        }
    }

    private void startTennisModeUI(String backendMode, String uiTitle) {
        activeTennisMode = backendMode;
        tvTennisModeTitle.setText(uiTitle);
        switchState(STATE_ACTIVE_TENNIS);
    }

    private void launchCalibration(int sensorId) {
        if (CommunicationService.isServerConnected) {
            Intent intent = new Intent(MainActivity.this, CalibrationActivity.class);
            intent.putExtra("SENSOR_ID", sensorId);
            startActivity(intent);
        } else {
            Toast.makeText(this, "Connect to server first", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateRecordingButtons(boolean active) {
        mainHandler.post(() -> {
            btnStartRecording.setText(active ? "Stop Recording" : "Start Recording");
            btnCapturePhotos.setEnabled(!active);
            btnBack.setEnabled(!active);
        });
    }

    private void updateTrackingButtons(boolean active) {
        mainHandler.post(() -> {
            btnStartTracking.setText(active ? "Stop" : "Start");
            btnBack.setEnabled(!active);
        });
    }

    private void appendToTrackingLog(String text) {
        tvTrackingLog.append("\n" + text);
        // Post to message queue to ensure the UI paints the new text before scrolling
        tvTrackingLog.post(() -> {
            android.widget.ScrollView scrollView = (android.widget.ScrollView) tvTrackingLog.getParent();
            if (scrollView != null) {
                scrollView.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void showPowerOffDialog() {
        String[] options = {"Power Down", "Reboot", "Restart Service", "Cancel"};
        new AlertDialog.Builder(this)
                .setTitle("Server Power Options")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        sendCommand(CMD_SHUTDOWN_SYSTEM);
                        mainHandler.postDelayed(this::disconnectFromServer, 500);
                    } else if (which == 1) {
                        sendCommand(CMD_REBOOT_SYSTEM);
                        mainHandler.postDelayed(this::disconnectFromServer, 500);
                    } else if (which == 2) {
                        sendCommand(CMD_RESTART_SERVICE);
                        mainHandler.postDelayed(this::disconnectFromServer, 500);
                    }
                })
                .show();
    }

    private void connectToServer() {
        Intent serviceIntent = new Intent(this, CommunicationService.class);
        serviceIntent.setAction(CommunicationService.ACTION_CONNECT);
        serviceIntent.putExtra(CommunicationService.EXTRA_SERVER_ADDRESS, getServerAddress());
        startService(serviceIntent);
    }

    private void disconnectFromServer() {
        startService(new Intent(this, CommunicationService.class).setAction(CommunicationService.ACTION_DISCONNECT));
        isConnected = false;
        switchState(STATE_DISCONNECTED);
    }

    public void sendCommand(String command) {
        if (!isConnected) return;
        Intent intent = new Intent(this, CommunicationService.class);
        intent.setAction(CommunicationService.ACTION_SEND_COMMAND);
        intent.putExtra(CommunicationService.EXTRA_COMMAND, command);
        startService(intent);
    }

    private String getSettingsPayload(boolean omitHeader) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        float expComp = (prefs.getInt(SettingsActivity.KEY_EXP_COMP_PROGRESS, 8) - 8) * 0.25f;
        StringBuilder sb = new StringBuilder();
        if (!omitHeader) sb.append("4K,JPEG,");
        sb.append("exp_comp=").append(expComp)
          .append(",gain=").append(prefs.getFloat(SettingsActivity.KEY_GAIN, 1.0f))
          .append(",digital_gain=").append(prefs.getFloat(SettingsActivity.KEY_DIGITAL_GAIN, 1.0f))
          .append(",exposureLow=").append(prefs.getLong(SettingsActivity.KEY_EXPOSURE_LOW, 33333L))
          .append(",exposureHigh=").append(prefs.getLong(SettingsActivity.KEY_EXPOSURE_HIGH, 33333L))
          .append(",aelock=").append(prefs.getBoolean(SettingsActivity.KEY_AE_LOCK, false) ? 1 : 0)
          .append(",awblock=").append(prefs.getBoolean(SettingsActivity.KEY_AWB_LOCK, false) ? 1 : 0)
          .append(",logging=").append(prefs.getBoolean(SettingsActivity.KEY_ENABLE_LOGGING, false) ? 1 : 0)
          .append(",det_thresh=").append(prefs.getInt(SettingsActivity.KEY_DET_THRESH, 50))
          .append(",debug_calib=").append(prefs.getBoolean(SettingsActivity.KEY_DEBUG_CALIBRATION, false) ? 1 : 0)
          .append("\n");
        return sb.toString();
    }

    private String buildGetCalibrationStatusCommand() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useDebug = prefs.getBoolean(SettingsActivity.KEY_DEBUG_CALIBRATION, false);
        return "GET_CALIBRATION_STATUS:DEBUG=" + (useDebug ? "1" : "0") + "\n";
    }

    private String buildCaptureCommand() { return "CAPTURE_PHOTO:" + getSettingsPayload(false).replace("4K,JPEG,", "4K,JPEG,0.25,"); }
    private String buildStartRecordingCommand() { return "START_RECORDING:" + getSettingsPayload(false); }
    private String buildStartTrackingCommand(String m) {
        String recordFlag = cbRecordSession != null && cbRecordSession.isChecked() ? "RECORD=1" : "RECORD=0";
        return "START_TRACKING:" + m + "," + recordFlag + "," + getSettingsPayload(true);
    }
    private String buildSetTimeCommand() { return "SET_TIME:" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n"; }

    private void updateUIStatus(String l1, String l2) {
        mainHandler.post(() -> {
            if (l1 != null) tvStatusLine1.setText(l1);
            if (l2 != null) tvStatusLine2.setText(l2);
        });
    }

    private String getServerAddress() {
        String target = PreferenceManager.getDefaultSharedPreferences(this).getString(SettingsActivity.KEY_CONNECTION_TARGET, "TG_AP");
        if ("Localhost".equals(target)) return LOCAL_HOST;
        if ("TG_AP".equals(target)) return TG_AP_HOST;
        if ("Emulator".equals(target)) return Emulator_HOST;
        return TG_CHICO_HOST;
    }

    @Override
    public void onBackPressed() {
        if (currentState == STATE_ACTIVE_TENNIS && !isTracking) switchState(STATE_TENNIS_MENU);
        else if ((currentState == STATE_RAW_RECORDING || currentState == STATE_TENNIS_MENU) && !isRecording) switchState(STATE_HOME);
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        if (toneGenerator != null) {
            toneGenerator.release();
        }
        if (fastSpeechEngine != null) {
            fastSpeechEngine.shutdown();
        }
        super.onDestroy();
    }    
}
