package com.murveit.tgcontrol;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String CMD_SHUTDOWN_SYSTEM = "shutdown-system\n";
    private static final String CMD_STOP_RECORDING = "stop-recording\n";
    private static final String CMD_GET_PREVIEW_FRAME = "get-preview-frame\n";

    private static final String Emulator_HOST = "10.0.2.2";
    private static final String TG_AP_HOST = "10.42.0.1";
    private static final String TG_CHICO_HOST = "192.168.86.43";
    private static final String LOCAL_HOST = "localhost";
    private Handler mainHandler;
    private TextView tvStatusLine1, tvStatusLine2;
    private Button btnConnect, btnStartRecording, btnCapturePhotos;
    private ImageButton btnPowerOff, btnSettings;
    private RadioGroup rgResolution, rgFormat;
    private RadioButton rb4K, rbHD, rbJPEG, rbRAW;
    private ImageView ivImage1, ivImage2;
    private boolean isConnected = false;
    private boolean isRecording = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());
        initializeUI();
        setupListeners();
        setupObservers();

        updateUIStatus("Ready", "Connect to TennisGenius AP WiFi.");
        updateControlButtons(false);
    }

    private void initializeUI() {
        tvStatusLine1 = findViewById(R.id.tvStatus1);
        tvStatusLine2 = findViewById(R.id.tvStatus2);
        btnConnect = findViewById(R.id.btnConnect);
        btnStartRecording = findViewById(R.id.btnStartRecording);
        btnCapturePhotos = findViewById(R.id.btnCapturePhotos);
        btnPowerOff = findViewById(R.id.btnPowerOff);
        btnSettings = findViewById(R.id.btnSettings);
        rgResolution = findViewById(R.id.rgResolution);
        rgFormat = findViewById(R.id.rgFormat);
        rb4K = findViewById(R.id.rb4K);
        rbHD = findViewById(R.id.rbHD);
        rbJPEG = findViewById(R.id.rbJPEG);
        rbRAW = findViewById(R.id.rbRAW);
        ivImage1 = findViewById(R.id.ivImage1);
        ivImage2 = findViewById(R.id.ivImage2);
    }

    private void setupListeners() {
        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                connectToServer();
            } else {
                disconnectFromServer();
            }
        });

        btnStartRecording.setOnClickListener(v -> {
            if (!isRecording) {
                String command = buildStartRecordingCommand();
                sendCommand(command);
            } else {
                sendCommand(CMD_STOP_RECORDING);
            }
            isRecording = !isRecording;
            btnStartRecording.setText(isRecording ? "Stop Recording" : "Start Recording");
        });

        btnCapturePhotos.setOnClickListener(v -> {
            sendCommand(CMD_GET_PREVIEW_FRAME);
        });

        btnPowerOff.setOnClickListener(v -> showPowerOffDialog());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));
        ivImage1.setOnClickListener(v -> sendCommand(CMD_GET_PREVIEW_FRAME));
        ivImage2.setOnClickListener(v -> sendCommand(CMD_GET_PREVIEW_FRAME));
    }

    // In MainActivity.java

    private void setupObservers() {
        CommunicationService.getStatusData().observe(this, statusPair -> {
            if (statusPair == null) return;

            // First, always update the status text fields
            updateUIStatus(statusPair.first, statusPair.second);

            // --- NEW, MORE ROBUST LOGIC ---
            String status = statusPair.first;
            String message = statusPair.second;

            // Check for definitive connection or disconnection events
            if ("Connected".equals(status)) {
                if (!isConnected) { // Only update if state changes
                    isConnected = true;
                    updateControlButtons(true);
                }
            } else if ("Error".equals(status) || (message != null && message.startsWith("Disconnected"))) {
                if (isConnected) { // Only update if state changes
                    isConnected = false;
                    isRecording = false; // Also reset recording state
                    updateControlButtons(false);
                }
            }
            // For any other message (like "Server:", "Sent:", etc.), we do nothing to the
            // isConnected flag or the button states, because the connection is still active.
        });

        CommunicationService.getImageData().observe(this, imagePair -> {
            if (imagePair != null) {
                Bitmap bitmap = imagePair.first;
                String target = imagePair.second;
                ImageView targetView = "image1".equals(target) ? ivImage1 : ivImage2;
                displayBitmap(bitmap, targetView);
            }
        });
    }

    private void connectToServer() {
        mainHandler.post(() -> {
            ivImage1.setImageDrawable(null);
            ivImage2.setImageDrawable(null);
        });

        Intent serviceIntent = new Intent(this, CommunicationService.class);
        serviceIntent.setAction(CommunicationService.ACTION_CONNECT);
        serviceIntent.putExtra(CommunicationService.EXTRA_SERVER_ADDRESS, getServerAddress());
        startService(serviceIntent);

        updateUIStatus("Status", "Connecting...");
    }

    private void disconnectFromServer() {
        Intent serviceIntent = new Intent(this, CommunicationService.class);
        serviceIntent.setAction(CommunicationService.ACTION_DISCONNECT);
        startService(serviceIntent);

        isRecording = false;
        isConnected = false;
        updateControlButtons(false);
        updateUIStatus("Status", "Disconnected.");
    }

    public void sendCommand(String command) {
        if (!isConnected) {
            Toast.makeText(this, "Not connected to server", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent serviceIntent = new Intent(this, CommunicationService.class);
        serviceIntent.setAction(CommunicationService.ACTION_SEND_COMMAND);
        serviceIntent.putExtra(CommunicationService.EXTRA_COMMAND, command);
        startService(serviceIntent);

        Log.d(TAG, "Requested to send command: " + command.trim());
        mainHandler.post(() -> updateUIStatus("Sent", command.trim()));
    }

    private void showPowerOffDialog() {
        if (!isConnected) {
            Toast.makeText(this, "Not connected to server", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Power Down Tennis Genius?")
                .setMessage("Are you sure you want to shut down the server?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    sendCommand(CMD_SHUTDOWN_SYSTEM);
                    mainHandler.postDelayed(this::disconnectFromServer, 500);
                })
                .setNegativeButton("Cancel", null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private String buildStartRecordingCommand() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String resolution = rb4K.isChecked() ? "4K" : "HD";
        String format = rbJPEG.isChecked() ? "JPEG" : "RAW";
        String exposureLow = String.valueOf(prefs.getInt("exposure_low", 10000));
        String exposureHigh = String.valueOf(prefs.getInt("exposure_high", 10000));
        String gain = String.valueOf(prefs.getFloat("gain", 1.0f));
        String digitalGain = String.valueOf(prefs.getFloat("digital_gain", 1.0f));

        return String.format(Locale.US,
                "start-recording --resolution %s --format %s --exp_low %s --exp_high %s --gain %s --digital_gain %s --quality %s --exp_comp %s\n",
                resolution, format,
                prefs.getString("exposure_low", "10000"),
                prefs.getString("exposure_high", "10000"),
                prefs.getString("gain", "1.0"),
                prefs.getString("digital_gain", "1.0"),
                prefs.getInt("jpeg_quality", 85),
                (prefs.getInt("exp_comp", 8) - 8)
        );
    }

    public void updateUIStatus(String line1, String line2) {
        mainHandler.post(() -> {
            if (line1 != null) tvStatusLine1.setText(line1);
            if (line2 != null) tvStatusLine2.setText(line2);
        });
    }

    public void displayBitmap(Bitmap bitmap, ImageView imageView) {
        mainHandler.post(() -> {
            if (bitmap != null && imageView != null) {
                imageView.setImageBitmap(bitmap);
            }
        });
    }

    private void updateControlButtons(boolean isEnabled) {
        mainHandler.post(() -> {
            // Set the text for the connect/disconnect button
            btnConnect.setText(isEnabled ? "Disconnect" : "Connect");

            // Always keep the settings button enabled
            btnSettings.setEnabled(true);

            // Enable/disable the power off button based on connection
            btnPowerOff.setEnabled(isEnabled);

            // Determine the visibility state for the action buttons
            int visibility = isEnabled ? View.VISIBLE : View.GONE;

            // Apply visibility and enabled state to the Start Recording button
            btnStartRecording.setVisibility(visibility);
            btnStartRecording.setEnabled(isEnabled);

            btnCapturePhotos.setVisibility(visibility);
            btnCapturePhotos.setEnabled(isEnabled);

            // Always reset the recording button's text when it's hidden
            if (!isEnabled) {
                btnStartRecording.setText("Start Recording");
            }
        });
    }

    private String getServerAddress() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        // The key for the spinner setting must match what's used in SettingsActivity
        // Let's use "connection_target" as established in our previous code.
        String networkName = prefs.getString("connection_target", "Chico"); // Default to "Chico"

        switch (networkName) {
            case "Localhost":
                return LOCAL_HOST;
            case "TG_AP":
                return TG_AP_HOST;
            case "Emulator":
                return Emulator_HOST;
            case "Chico":
                return TG_CHICO_HOST;
            default:
                Log.w(TAG, "Unknown network target: " + networkName + ". Defaulting to Chico.");
                return TG_CHICO_HOST;
        }
    }
}
