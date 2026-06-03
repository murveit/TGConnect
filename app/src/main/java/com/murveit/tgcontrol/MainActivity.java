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
 * - Applies FLAG_KEEP_SCREEN_ON to the Window Manager to prevent the Android OS from sleeping the display 
 * and aggressively severing active TCP tracking sockets.
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
 * - Visual Plotting: Extracts bounce X/Y coordinates during Serve Practice and routes them to a custom 
 * hardware-accelerated `ServeScatterView` for top-down spatial visualization.
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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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
    private static final String SENSOR_ID_LEFT_STR = "0";
    private static final String SENSOR_ID_RIGHT_STR = "1";
    private static final String CALIBRATION_ACTIVE_STR = "1";

    // --- Algorithmic Constants for Play Modes ---
    private static final String MODE_SINGLES = "SINGLES";
    private static final String MODE_DOUBLES = "DOUBLES";
    private static final String MODE_SERVE_PRACTICE = "SERVE_PRACTICE";
    private static final String MODE_RALLY_PRACTICE = "RALLY_PRACTICE";

    // --- Algorithmic Constants for Auditory Feedback ---
    private static final int BEEP_VOLUME_MAX = 100;
    private static final int HAPPY_BEEP_DURATION_MS = 150;

    // --- Algorithmic Constants for Client-Side Histogram Calculation ---
    private static final int HISTOGRAM_PIXEL_STRIDE = 5;
    private static final int HISTOGRAM_COLOR_BINS = 256;

    private static final int DEFAULT_SERVE_THRESH = 90;
    
    private static final String Emulator_HOST = "10.0.2.2";
    private static final String TG_AP_HOST = "10.42.0.1";
    private static final String TG_CHICO_HOST = "192.168.86.40";
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
    
    // Tracking active calibration states for UI locking
    private boolean isLeftCalibrated = false;
    private boolean isRightCalibrated = false;

    private Button btnBack, btnConnect, btnDebugAudio;
    private ImageButton btnPowerOff, btnSettings;
    private LinearLayout llHome, llHomeButtons, llRawRecording, llTennisMenu, llActiveTennis;
    private TextView tvHomeMessage, tvStatusLine1, tvStatusLine2;
    private Button btnGoRawRecording, btnGoTennis, btnStartRecording, btnCapturePhotos, btnStartTracking;
    private ImageView ivImage1, ivImage2, ivCheckLeft, ivCheckRight;
    private HistogramView histView1, histView2;
    private TextView tvTennisModeTitle, tvTrackingLog, tvSelectPlayMode, tvLiveTelemetry;
    private Button btnModeSingles, btnModeDoubles, btnModeServe, btnModeRally, btnCalibrateLeft, btnCalibrateRight;
    private TextView tvPoseLeft, tvPoseRight;
    private CheckBox cbRecordSession;
    
    // UI Elements for Serve Plotting
    private LinearLayout llServePlotContainer;
    private View llTrackingLogContainer; // Added to support the new border layout
    private View svTrackingLog;
    private Button btnToggleView;
    private Button btnClearServes;
    private TextView tvAvgMph;
    private TextView tvLastServe;
    private ServeScatterView serveScatterView;
    
    // State Tracking for Serve Plotting
    private List<ServeScatterView.ServeImpact> serveImpacts = new ArrayList<>();
    private int totalServeCount = 0;
    private int inServeCount = 0;
    private double sumInMph = 0.0;
    private boolean sessionRecording = false;

    // Recording indicator icon (right side of title bar)
    private ImageView ivRecordingIndicator;

    // Disconnect overlay: full-screen semi-transparent view shown on WiFi loss while connected.
    private LinearLayout llDisconnectOverlay;

    // Network reachability indicator shown in STATE_DISCONNECTED.
    private TextView tvNetworkStatus;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback mainActivityNetworkCallback;

    // Sends SET_NANO_AUDIO to the Nano immediately when the preference changes while tracking.
    private final SharedPreferences.OnSharedPreferenceChangeListener nanoAudioPrefListener =
            (prefs, key) -> {
                if (SettingsActivity.KEY_NANO_AUDIO.equals(key) ||
                        SettingsActivity.KEY_NANO_AUDIO_SPEAK_MPH.equals(key)) {
                    if (CommunicationService.isTracking) {
                        boolean enabled = prefs.getBoolean(SettingsActivity.KEY_NANO_AUDIO, false);
                        boolean speakMph = prefs.getBoolean(SettingsActivity.KEY_NANO_AUDIO_SPEAK_MPH, true);
                        sendCommand("SET_NANO_AUDIO:" + (enabled ? "1" : "0")
                                + ",SPEAK_MPH=" + (speakMph ? "1" : "0") + "\n");
                        CommunicationService.nanoAudioActive = enabled;
                    }
                }
            };
    
    // Hardware Audio Engines
    private TextToSpeech textToSpeech;
    private ToneGenerator toneGenerator;
    private FastSpeechEngine fastSpeechEngine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Prevent screen sleep to maintain TCP socket integrity during active tracking
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        requestNotificationPermission();
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Initialize Audio Engines
        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME_MAX);
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.US);

                // Initialize the fast speech engine cache
                fastSpeechEngine = new FastSpeechEngine(MainActivity.this, textToSpeech);
                fastSpeechEngine.initializeCache();
                // Register for early audio: speech fires from the socket-reader background thread
                // to bypass the ~194ms LiveData→UI-thread scheduling lag.
                CommunicationService.setEarlyAudioEngine(fastSpeechEngine);
            }
        });
        
        initializeUI();
        setupListeners();
        setupObservers();
        
        // --- Algorithmic Fix: State Recovery via Server Authority ---
        if (CommunicationService.isServerConnected) {
            isConnected = true;
            
            // Show home briefly while we ping the server for its true physical state
            switchState(STATE_HOME);
            
            mainHandler.postDelayed(() -> {
                sendCommand(buildGetCalibrationStatusCommand());
                sendCommand("GET_SYSTEM_STATE\n");
            }, 250);
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
        tvPoseLeft = findViewById(R.id.tvPoseLeft);
        tvPoseRight = findViewById(R.id.tvPoseRight);
        tvTennisModeTitle = findViewById(R.id.tvTennisModeTitle);
        btnStartTracking = findViewById(R.id.btnStartTracking);
        tvTrackingLog = findViewById(R.id.tvTrackingLog);
        tvSelectPlayMode = findViewById(R.id.tvSelectPlayMode);
        tvLiveTelemetry = findViewById(R.id.tvLiveTelemetry);
        cbRecordSession = findViewById(R.id.cbRecordSession);
        
        // Serve Plot Elements
        llServePlotContainer = findViewById(R.id.llServePlotContainer);
        llTrackingLogContainer = findViewById(R.id.llTrackingLogContainer);
        svTrackingLog = findViewById(R.id.svTrackingLog);
        btnToggleView = findViewById(R.id.btnToggleView);
        btnClearServes = findViewById(R.id.btnClearServes);
        tvAvgMph = findViewById(R.id.tvAvgMph);
        tvLastServe = findViewById(R.id.tvLastServe);
        serveScatterView = findViewById(R.id.serveScatterView);
        ivRecordingIndicator = findViewById(R.id.ivRecordingIndicator);
        llDisconnectOverlay = findViewById(R.id.llDisconnectOverlay);
        tvNetworkStatus = findViewById(R.id.tvNetworkStatus);
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

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
                
                double r = Math.random();
                String call;
                double randX, randY;
                
                if (r < 0.6) {
                    // IN: Force mathematical coordinate inside the physical service box bounds
                    call = "In";
                    randX = (Math.random() * 8.0) - 4.0; // Stay inside singles lines
                    randY = (Math.random() * 6.0) + 0.2; // Stay between net and service line
                } else if (r < 0.9) {
                    // FAULT/OUT: Force coordinate outside the target boxes
                    call = MODE_SERVE_PRACTICE.equals(CommunicationService.activeTennisMode) ? "Fault" : "Out";
                    if (Math.random() > 0.5) {
                        // Wide (Into the doubles alley)
                        randX = (Math.random() > 0.5 ? 1 : -1) * ((Math.random() * 1.5) + 4.2);
                        randY = (Math.random() * 6.0) + 0.2;
                    } else {
                        // Deep (Past the service line towards the baseline)
                        randX = (Math.random() * 8.0) - 4.0;
                        randY = (Math.random() * 5.0) + 6.5; 
                    }
                } else {
                    // LET: Dropping very close to the net
                    call = MODE_SERVE_PRACTICE.equals(CommunicationService.activeTennisMode) ? "Let" : "In";
                    randX = (Math.random() * 8.0) - 4.0;
                    randY = (Math.random() * 1.0) + 0.1;
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
        
        if (btnToggleView != null) {
            btnToggleView.setOnClickListener(v -> {
                boolean isPlotVisible = llServePlotContainer != null && llServePlotContainer.getVisibility() == View.VISIBLE;
                
                if (llServePlotContainer != null) llServePlotContainer.setVisibility(isPlotVisible ? View.GONE : View.VISIBLE);
                if (tvLastServe != null) tvLastServe.setVisibility(isPlotVisible ? View.GONE : View.VISIBLE);
                
                if (llTrackingLogContainer != null) {
                    llTrackingLogContainer.setVisibility(isPlotVisible ? View.VISIBLE : View.GONE);
                } else if (svTrackingLog != null) {
                    svTrackingLog.setVisibility(isPlotVisible ? View.VISIBLE : View.GONE);
                }
                
                btnToggleView.setText(isPlotVisible ? "Plot" : "Log");
            });
        }

        if (btnClearServes != null) {
            btnClearServes.setOnClickListener(v -> {
                FileLogger.log(this, "Clearing serves from plot");
                serveImpacts.clear();
                totalServeCount = 0;
                inServeCount = 0;
                sumInMph = 0.0;
                if (tvAvgMph != null) tvAvgMph.setText("0 serves, 0 In, -- MPH avg");
                if (serveScatterView != null) serveScatterView.setServes(serveImpacts);
                if (tvTrackingLog != null) tvTrackingLog.setText("");
                if (tvLastServe != null) tvLastServe.setText("Ready for serves");
            });
        }
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

            // Hide debugging status lines on Tennis pages to keep the UI clean
            if (tvStatusLine1 != null && tvStatusLine2 != null) {
                if (newState == STATE_TENNIS_MENU || newState == STATE_ACTIVE_TENNIS) {
                    tvStatusLine1.setVisibility(View.GONE);
                    tvStatusLine2.setVisibility(View.GONE);
                } else {
                    tvStatusLine1.setVisibility(View.VISIBLE);
                    tvStatusLine2.setVisibility(View.VISIBLE);
                }
            }
            
            // Handle specific Serve Practice layout requirements safely
            if (newState == STATE_ACTIVE_TENNIS) {
                if (MODE_SERVE_PRACTICE.equals(CommunicationService.activeTennisMode)) {
                    // Default to showing the graphical plot when entering Serve Practice
                    if (btnToggleView != null) btnToggleView.setVisibility(View.VISIBLE);
                    if (btnClearServes != null) btnClearServes.setVisibility(View.VISIBLE);
                    if (llServePlotContainer != null) llServePlotContainer.setVisibility(View.VISIBLE);
                    if (tvAvgMph != null) tvAvgMph.setVisibility(View.VISIBLE);
                    if (tvLastServe != null) tvLastServe.setVisibility(View.VISIBLE);
                    
                    if (llTrackingLogContainer != null) llTrackingLogContainer.setVisibility(View.GONE);
                    else if (svTrackingLog != null) svTrackingLog.setVisibility(View.GONE);
                    
                    if (btnToggleView != null) btnToggleView.setText("Log");
                } else {
                    // Force the text log for all other modes
                    if (btnToggleView != null) btnToggleView.setVisibility(View.GONE);
                    if (btnClearServes != null) btnClearServes.setVisibility(View.GONE);
                    if (llServePlotContainer != null) llServePlotContainer.setVisibility(View.GONE);
                    if (tvAvgMph != null) tvAvgMph.setVisibility(View.GONE);
                    if (tvLastServe != null) tvLastServe.setVisibility(View.GONE);
                    
                    if (llTrackingLogContainer != null) llTrackingLogContainer.setVisibility(View.VISIBLE);
                    else if (svTrackingLog != null) svTrackingLog.setVisibility(View.VISIBLE);
                }
            }

            if (newState == STATE_DISCONNECTED) {
                llHome.setVisibility(View.VISIBLE);
                tvHomeMessage.setVisibility(View.VISIBLE);
                llHomeButtons.setVisibility(View.GONE);
                btnConnect.setText("CONNECT");
                btnPowerOff.setEnabled(false);
                ivCheckLeft.setVisibility(View.GONE);
                ivCheckRight.setVisibility(View.GONE);
                if (tvPoseLeft != null) tvPoseLeft.setVisibility(View.GONE);
                if (tvPoseRight != null) tvPoseRight.setVisibility(View.GONE);
                if (btnCalibrateLeft != null) btnCalibrateLeft.setText("Calibrate Left");
                if (btnCalibrateRight != null) btnCalibrateRight.setText("Calibrate Right");
                if (tvNetworkStatus != null) tvNetworkStatus.setVisibility(View.VISIBLE);
                updateConnectButtonState();
            } else if (newState == STATE_HOME) {
                llHome.setVisibility(View.VISIBLE);
                tvHomeMessage.setVisibility(View.GONE);
                llHomeButtons.setVisibility(View.VISIBLE);
                btnConnect.setText("Disconnect");
                btnConnect.setEnabled(true); // always allow disconnect regardless of network state
                btnPowerOff.setEnabled(true);
                if (tvNetworkStatus != null) tvNetworkStatus.setVisibility(View.GONE);
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

            if ("WIFI_LOST".equals(status)) {
                // Show overlay only while we have an active connection; ignore spurious callbacks.
                if (isConnected) {
                    FileLogger.log(this, "WiFi lost while connected. Showing disconnect overlay.");
                    showDisconnectOverlay();
                }
                return;
            }

            if ("WIFI_RESTORED".equals(status)) {
                // Reset isConnected so the upcoming "Connected" message from the re-established
                // socket triggers GET_SYSTEM_STATE and restores the UI to the correct state.
                FileLogger.log(this, "WiFi restored. Resetting isConnected for state re-query.");
                isConnected = false;
                hideDisconnectOverlay();
                return;
            }

            if ("TRACK_EVENT".equals(status)) {
                appendToTrackingLog(message);
                // When server signals readiness in Serve Practice, update the plot status lines
                if (message != null && message.endsWith(" Active") &&
                        MODE_SERVE_PRACTICE.equals(CommunicationService.activeTennisMode)) {
                    mainHandler.post(() -> {
                        if (tvAvgMph != null) tvAvgMph.setText("0 serves, 0 In, -- MPH avg");
                        if (tvLastServe != null) tvLastServe.setText("Ready for serves");
                    });
                }
                return;
            }

            if ("TRACK_EVENT_JSON".equals(status)) {
                processTrackEventJson(message);
                return;
            }

            if ("TRACK_TELEMETRY".equals(status)) {
                if (tvLiveTelemetry != null) tvLiveTelemetry.setText(message);
                return;
            }

            if ("CALIBRATION_STATUS".equals(status)) {
                handleCalibrationStatus(message);
                return;
            }

            if ("SYSTEM_STATE".equals(status)) {
                handleSystemState(message);
                return;
            }

            if ("AUDIO_STATUS".equals(status)) {
                // Nano reports whether its USB audio is ready: ok, no_usb_device, missing_files:..., etc.
                final String audioStatus = message;
                mainHandler.post(() -> {
                    String display = "Nano audio: " + (audioStatus != null ? audioStatus : "unknown");
                    appendToTrackingLog(display);
                });
                return;
            }

            if ("CALIBRATION_SAVED".equals(message)) {
                sendCommand(buildGetCalibrationStatusCommand());
            }

            updateUIStatus(status, message);

            if ("Connected".equals(status)) {
                if (!isConnected) {
                    isConnected = true;
                    hideDisconnectOverlay();
                    switchState(STATE_HOME);
                    mainHandler.postDelayed(() -> {
                        sendCommand(buildSetTimeCommand());
                        sendCommand(buildGetCalibrationStatusCommand());
                        sendCommand("GET_SYSTEM_STATE\n");
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
                CommunicationService.isRecording = false;
                CommunicationService.isTracking = false;
                isLeftCalibrated = false;
                isRightCalibrated = false;
                updateTennisModeButtonsState();
                hideDisconnectOverlay();
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
        final long receiveMs = System.currentTimeMillis();
        try {
            org.json.JSONObject json = new org.json.JSONObject(message);
            String wallClock = json.optString("wall_clock", "");
            String strikeType = json.optString("strike_type", "Hit");

            // Trim whitespace defensively so matching "In" is bulletproof
            String callStr = json.optString("call_str", "Unknown").trim();

            double mph = json.optDouble("speed_mph", 0.0);
            double sX = json.optDouble("strike_x", 0.0);
            double sY = json.optDouble("strike_y", 0.0);
            double bX = json.optDouble("bounce_x", 0.0);
            double bY = json.optDouble("bounce_y", 0.0);
            String reason = json.optString("reason", "");
            String sideStr = json.optString("side", "");

            long serverSendMs = json.optLong("server_send_unix_ms", 0L);
            final long transportMs = (serverSendMs > 0) ? (receiveMs - serverSendMs) : -1L;
            FileLogger.log(this, String.format(Locale.US,
                "[LATENCY] JSON_RECEIVED bounce_frame=%d server_send_ms=%d receive_ms=%d transport_ms=%d",
                json.optInt("bounce_frame", -1), serverSendMs, receiveMs, transportMs));

            FileLogger.log(this, String.format(Locale.US, "processTrackEvent: Type=%s, Call=%s, bX=%.2f, bY=%.2f, MPH=%.1f reason=%s", strikeType, callStr, bX, bY, mph, reason));

            
            if (strikeType.length() > 0) {
                strikeType = strikeType.substring(0, 1).toUpperCase() + strikeType.substring(1);
            }
            
            String timePrefix = wallClock.isEmpty() ? "" : wallClock + " ";

            // Customize by mode? Check if mph exists? Plot?
            String formatted = String.format(Locale.US, "%s%s. %s. %.0fmph", 
                                             timePrefix, strikeType, callStr, mph);
            appendToTrackingLog(formatted);
            
            // --- SERVE PRACTICE VISUALIZATION & AUDIO ---
            if (MODE_SERVE_PRACTICE.equals(CommunicationService.activeTennisMode)) {
                
                // 1. Update Spatial Scatter Plot & Summary Text
                if (bX != 0.0 || bY != 0.0) {
                    FileLogger.log(this, "PLOTTING DOT AT: X=" + bX + " Y=" + bY);
                    serveImpacts.add(new ServeScatterView.ServeImpact((float)bX, (float)bY, callStr));
                    
                    // Exclude "Let" from mathematical counts completely.
                    if (!"Let".equalsIgnoreCase(callStr)) {
                        totalServeCount++;
                        
                        // Explicitly isolate "In" averages from Let, Out, and Fault
                        if ("In".equalsIgnoreCase(callStr)) {
                            inServeCount++;
                            if (mph > 0) {
                                sumInMph += mph;
                            }
                        }
                    }
                    
                    if (tvAvgMph != null) {
                        String avgStr = "--";
                        String pctStr = "";
                        if (inServeCount > 0) {
                            avgStr = String.format(Locale.US, "%.0f", (sumInMph / inServeCount));
                            // Relying on totalServeCount implicitly being > 0 since inServeCount > 0
                            pctStr = String.format(Locale.US, " %.0f%%", (inServeCount * 100.0 / totalServeCount));
                        }
                        tvAvgMph.setText(String.format(Locale.US, "%d serves, %d In%s, %s MPH avg", totalServeCount, inServeCount, pctStr, avgStr));
                    }

                    if (serveScatterView != null) {
                        serveScatterView.setServes(serveImpacts);
                    }
                    
                    // Update single-line summary above the plot
                    String displaySideStr;
                    if (!sideStr.isEmpty()) {
                        displaySideStr = sideStr.endsWith("Court") ? sideStr : sideStr + " Court";
                    } else {
                        // Legacy fallback if the Jetson payload is missing the side attribute
                        displaySideStr = bX > 0 ? "Ad Court" : "Deuce Court";
                    }
                    String lastServeStr = String.format(Locale.US, "Last: %s, %s, %.0f mph", displaySideStr, callStr, mph);
                    if (tvLastServe != null) {
                        tvLastServe.setText(lastServeStr);
                        
                        if ("In".equalsIgnoreCase(callStr)) {
                            tvLastServe.setBackgroundColor(android.graphics.Color.parseColor("#00E676"));
                        } else if ("Out".equalsIgnoreCase(callStr) || "Fault".equalsIgnoreCase(callStr)) {
                            tvLastServe.setBackgroundColor(android.graphics.Color.parseColor("#FF1744"));
                        } else if ("Let".equalsIgnoreCase(callStr)) {
                            tvLastServe.setBackgroundColor(android.graphics.Color.parseColor("#FFEA00"));
                        }

                        final long colorSetMs = System.currentTimeMillis();
                        tvLastServe.post(() -> FileLogger.log(MainActivity.this, String.format(Locale.US,
                            "[LATENCY] RENDER_DONE transport_ms=%d receive_to_color_ms=%d color_to_vsync_ms=%d total_receive_to_vsync_ms=%d",
                            transportMs, colorSetMs - receiveMs, System.currentTimeMillis() - colorSetMs,
                            System.currentTimeMillis() - receiveMs)));

                        mainHandler.postDelayed(() -> {
                            if (tvLastServe != null) {
                                tvLastServe.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                            }
                        }, 1000);
                    }
                }

                // 2. Audio Feedback
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                boolean playVoice = prefs.getBoolean(SettingsActivity.KEY_VOICE_CALLS, false);
                boolean playBeep = prefs.getBoolean(SettingsActivity.KEY_BEEP_IN, false);
                boolean speakMph = prefs.getBoolean(SettingsActivity.KEY_SPEAK_MPH, false);

                // Speech is fired early from tryPlayEarlyAudio() on the network background thread.
                // Fall back to here for the debug button and any path that bypasses the network,
                // since those never trigger tryPlayEarlyAudio(). Skip if early audio already fired,
                // or if the Nano is handling audio output directly.
                boolean earlyAudioHandled =
                        (System.currentTimeMillis() - CommunicationService.lastEarlyAudioFiredMs) < 500;
                if (!earlyAudioHandled && !CommunicationService.nanoAudioActive && fastSpeechEngine != null) {
                    if ("In".equalsIgnoreCase(callStr) && speakMph) {
                        int mphInt = (int) Math.round(mph);
                        long now = System.currentTimeMillis();
                        String ttsMphStr;
                        if (now - CommunicationService.lastEarlySpokenMphTimeMs > CommunicationService.EARLY_AUDIO_MPH_COOLDOWN_MS) {
                            ttsMphStr = mphInt + " miles per hour";
                            CommunicationService.lastEarlySpokenMphTimeMs = now;
                        } else {
                            ttsMphStr = String.valueOf(mphInt);
                        }
                        fastSpeechEngine.speak(ttsMphStr);
                    } else if (("Out".equalsIgnoreCase(callStr) || "Fault".equalsIgnoreCase(callStr)) && playVoice) {
                        fastSpeechEngine.speak("Fault");
                    } else if ("Let".equalsIgnoreCase(callStr) && playVoice) {
                        fastSpeechEngine.speak("Let");
                    }
                }
                // Beep for "In" (mutually exclusive with speech, stays on main thread)
                if (!CommunicationService.nanoAudioActive && !speakMph && playBeep && toneGenerator != null && "In".equalsIgnoreCase(callStr)) {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_PROMPT, HAPPY_BEEP_DURATION_MS);
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
                    String sensorId = pair[0];
                    String valueAndPose = pair[1];
                    String[] valParts = valueAndPose.split("\\|");
                    
                    boolean active = "1".equals(valParts[0]);
                    String poseStr = valParts.length > 1 ? valParts[1] : "";
                    
                    if (SENSOR_ID_LEFT_STR.equals(sensorId)) {
                        isLeftCalibrated = active;
                        ivCheckLeft.setVisibility(active ? View.VISIBLE : View.GONE);
                        if (active) {
                            btnCalibrateLeft.setText("Calibrate Left");
                            if (!poseStr.isEmpty()) {
                                tvPoseLeft.setText(poseStr);
                                tvPoseLeft.setVisibility(View.VISIBLE);
                            } else {
                                tvPoseLeft.setVisibility(View.GONE);
                            }
                        } else {
                            btnCalibrateLeft.setText("Calibrate Left");
                            tvPoseLeft.setVisibility(View.GONE);
                        }
                    }
                    if (SENSOR_ID_RIGHT_STR.equals(sensorId)) {
                        isRightCalibrated = active;
                        ivCheckRight.setVisibility(active ? View.VISIBLE : View.GONE);
                        if (active) {
                            btnCalibrateRight.setText("Calibrate Right");
                            if (!poseStr.isEmpty()) {
                                tvPoseRight.setText(poseStr);
                                tvPoseRight.setVisibility(View.VISIBLE);
                            } else {
                                tvPoseRight.setVisibility(View.GONE);
                            }
                        } else {
                            btnCalibrateRight.setText("Calibrate Right");
                            tvPoseRight.setVisibility(View.GONE);
                        }
                    }
                }
            }
            updateTennisModeButtonsState();
        });
    }
    
    private void handleSystemState(String data) {
        // Parse synchronously so any button press happening after this call sees correct flags.
        // This avoids a race where the user presses the Start/Stop button before the
        // mainHandler.post() below has run and updated isTracking.
        String[] parts = data.split(",");
        boolean serverTracking = false;
        boolean serverRecording = false;
        String serverMode = "";
        for (String part : parts) {
            String[] pair = part.split("=");
            if (pair.length == 2) {
                if ("TRACKING".equals(pair[0])) serverTracking = "1".equals(pair[1]);
                if ("RECORDING".equals(pair[0])) serverRecording = "1".equals(pair[1]);
                if ("MODE".equals(pair[0])) serverMode = pair[1];
                if ("NANO_AUDIO".equals(pair[0])) CommunicationService.nanoAudioActive = "on".equals(pair[1]);
            }
        }
        // Overwrite Android local state immediately (we're already on the main thread
        // via the LiveData observer, so this is safe and takes effect before any pending
        // button-click handler that may be queued).
        CommunicationService.isTracking = serverTracking;
        CommunicationService.isRecording = serverRecording;

        final boolean fServerTracking = serverTracking;
        final boolean fServerRecording = serverRecording;
        final String fServerMode = serverMode;

        mainHandler.post(() -> {
            // Route the UI to match the physical hardware state
            if (fServerTracking && !fServerMode.isEmpty() && !"NONE".equals(fServerMode)) {
                CommunicationService.activeTennisMode = fServerMode;

                String title = fServerMode;
                if (MODE_SINGLES.equals(fServerMode)) title = "Singles Match";
                else if (MODE_DOUBLES.equals(fServerMode)) title = "Doubles Match";
                else if (MODE_SERVE_PRACTICE.equals(fServerMode)) title = "Serve Practice";
                else if (MODE_RALLY_PRACTICE.equals(fServerMode)) title = "Rally Practice";

                startTennisModeUI(fServerMode, title);
                updateTrackingButtons(true);
            } else if (fServerRecording) {
                switchState(STATE_RAW_RECORDING);
                updateRecordingButtons(true);
            } else {
                // If the server is doing nothing, don't automatically force the Tennis Menu.
                // Only step in and rescue the UI if it falsely thinks it is currently tracking/recording.
                if (currentState == STATE_ACTIVE_TENNIS || currentState == STATE_RAW_RECORDING || currentState == STATE_DISCONNECTED) {
                    switchState(STATE_HOME);
                }
            }
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
        if (!CommunicationService.isRecording) {
            CommunicationService.isRecording = true;
            updateRecordingButtons(true);
            sendCommand(buildStartRecordingCommand());
        } else {
            CommunicationService.isRecording = false;
            sendCommand(CMD_STOP_RECORDING);
            updateRecordingButtons(false);
        }
    }

    private void toggleTracking() {
        if (!CommunicationService.isTracking) {
            sessionRecording = cbRecordSession != null && cbRecordSession.isChecked();
            FileLogger.log(this, "Start tracking pressed: mode=" + CommunicationService.activeTennisMode
                    + " recording=" + sessionRecording);
            CommunicationService.isTracking = true;
            updateTrackingButtons(true);
            tvTrackingLog.setText("");
            tvLiveTelemetry.setText(""); // Erase 1s stats line at startup

            // Clear tracking visualization data specific to this session
            serveImpacts.clear();
            totalServeCount = 0;
            inServeCount = 0;
            sumInMph = 0.0;
            // Show "Spinning up..." immediately in both plot status lines;
            // they will update to real values once the server signals Active.
            if (tvAvgMph != null) tvAvgMph.setText("Spinning up...");
            if (serveScatterView != null) serveScatterView.setServes(serveImpacts);
            if (tvLastServe != null) tvLastServe.setText("");

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            CommunicationService.nanoAudioActive = prefs.getBoolean(SettingsActivity.KEY_NANO_AUDIO, false);
            sendCommand(buildStartTrackingCommand(CommunicationService.activeTennisMode));
            updateRecordingIndicator();
        } else {
            FileLogger.log(this, "Stop tracking pressed");
            CommunicationService.isTracking = false;
            CommunicationService.nanoAudioActive = false;
            sendCommand(CMD_STOP_TRACKING);
            updateTrackingButtons(false);
            tvTrackingLog.append("\n--- Stopped ---");
            updateRecordingIndicator();
        }
    }

    private void showDisconnectOverlay() {
        mainHandler.post(() -> {
            if (llDisconnectOverlay == null) return;
            llDisconnectOverlay.setVisibility(View.VISIBLE);
            // Wire the escape button each time the overlay is shown.
            Button btnOverlayDisconnect = llDisconnectOverlay.findViewById(R.id.btnOverlayDisconnect);
            if (btnOverlayDisconnect != null) {
                btnOverlayDisconnect.setOnClickListener(v -> {
                    FileLogger.log(MainActivity.this, "User tapped Disconnect on overlay — cancelling grace period.");
                    disconnectFromServer();
                });
            }
        });
    }

    private void hideDisconnectOverlay() {
        mainHandler.post(() -> {
            if (llDisconnectOverlay != null) llDisconnectOverlay.setVisibility(View.GONE);
        });
    }

    private void updateRecordingIndicator() {
        if (ivRecordingIndicator == null) return;
        boolean isServePractice = MODE_SERVE_PRACTICE.equals(CommunicationService.activeTennisMode);
        if (isServePractice && CommunicationService.isTracking) {
            ivRecordingIndicator.setVisibility(View.VISIBLE);
            int color = sessionRecording
                    ? android.graphics.Color.parseColor("#FF1744")  // red = recording
                    : android.graphics.Color.parseColor("#808080"); // gray = not recording
            ivRecordingIndicator.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        } else {
            ivRecordingIndicator.setVisibility(View.INVISIBLE);
        }
    }

    private void startTennisModeUI(String backendMode, String uiTitle) {
        CommunicationService.activeTennisMode = backendMode;
        CommunicationService.activeTennisTitle = uiTitle;
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
            String newLabel = active ? "Stop" : "Start";
            String oldLabel = btnStartTracking.getText().toString();
            if (!newLabel.equals(oldLabel)) {
                FileLogger.log(MainActivity.this, "Tracking button: '" + oldLabel + "' → '" + newLabel
                        + "' (active=" + active + " mode=" + CommunicationService.activeTennisMode + ")");
            }
            btnStartTracking.setText(newLabel);
            // Tint: green when showing "Start" (safe to press), red when showing "Stop" (tracking live).
            int tintColor = active
                    ? android.graphics.Color.parseColor("#FF1744")  // red = tracking active
                    : android.graphics.Color.parseColor("#00C853"); // green = ready to start
            btnStartTracking.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(tintColor));
            btnBack.setEnabled(!active);
        });
    }

    private void appendToTrackingLog(String text) {
        if (tvTrackingLog != null) {
            tvTrackingLog.append(text + "\n");
            tvTrackingLog.post(() -> {
                if (tvTrackingLog.getParent() instanceof android.widget.ScrollView) {
                    android.widget.ScrollView scrollView = (android.widget.ScrollView) tvTrackingLog.getParent();
                    scrollView.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
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
        CommunicationService.isTracking = false;
        CommunicationService.isRecording = false;
        updateTrackingButtons(false);
        updateRecordingButtons(false);
        hideDisconnectOverlay();
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
          .append(",serve_thresh=").append(prefs.getInt(SettingsActivity.KEY_SERVE_THRESH, DEFAULT_SERVE_THRESH))
          .append(",nano_audio=").append(prefs.getBoolean(SettingsActivity.KEY_NANO_AUDIO, false) ? 1 : 0)
          .append(",nano_audio_speak_mph=").append(prefs.getBoolean(SettingsActivity.KEY_NANO_AUDIO_SPEAK_MPH, true) ? 1 : 0)
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
    private String buildSetTimeCommand() { return "SET_TIME_MS:" + System.currentTimeMillis() + "\n"; }

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
        if (currentState == STATE_ACTIVE_TENNIS && !CommunicationService.isTracking) switchState(STATE_TENNIS_MENU);
        else if ((currentState == STATE_RAW_RECORDING || currentState == STATE_TENNIS_MENU) && !CommunicationService.isRecording) switchState(STATE_HOME);
        else super.onBackPressed();
    }

    /**
     * Checks whether the phone is on the same subnet as the configured server, then
     * enables or disables the Connect button and updates the network status indicator.
     * Only has effect in STATE_DISCONNECTED; ignored when already connected.
     */
    private void updateConnectButtonState() {
        if (currentState != STATE_DISCONNECTED) return;
        String serverAddress = getServerAddress();
        boolean onServerNet = CommunicationService.isOnServerNetwork(connectivityManager, serverAddress);
        btnConnect.setEnabled(onServerNet);
        if (tvNetworkStatus != null) {
            if (onServerNet) {
                tvNetworkStatus.setText("● On server network (" + serverAddress + ")");
                tvNetworkStatus.setTextColor(android.graphics.Color.parseColor("#00C853")); // green
            } else {
                tvNetworkStatus.setText("● Not on server network (" + serverAddress + ")");
                tvNetworkStatus.setTextColor(android.graphics.Color.parseColor("#FF6D00")); // orange
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-check network immediately (server IP may have changed in Settings).
        updateConnectButtonState();
        // Live-toggle nano audio: send SET_NANO_AUDIO immediately if tracking is active.
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(nanoAudioPrefListener);
        // Register a lightweight callback to react to network switches in real time.
        mainActivityNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                mainHandler.post(() -> updateConnectButtonState());
            }
            @Override
            public void onLost(@NonNull Network network) {
                mainHandler.post(() -> updateConnectButtonState());
            }
            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
                mainHandler.post(() -> updateConnectButtonState());
            }
        };
        connectivityManager.registerNetworkCallback(
                new NetworkRequest.Builder().build(), mainActivityNetworkCallback);
    }

    @Override
    protected void onPause() {
        super.onPause();
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(nanoAudioPrefListener);
        if (mainActivityNetworkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(mainActivityNetworkCallback); } catch (Exception ignored) { }
            mainActivityNetworkCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        // Deregister early audio so the background thread doesn't play audio with no visible UI
        CommunicationService.setEarlyAudioEngine(null);
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
