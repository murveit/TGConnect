package com.murveit.tgcontrol;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String CMD_SHUTDOWN_SYSTEM = "SHUTDOWN_SYSTEM\n";
    private static final String CMD_STOP_RECORDING = "STOP_RECORDING\n";
    /// /private static final String CMD_GET_PREVIEW_FRAME = "get-preview-frame\n";

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
    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 101;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestNotificationPermission();
        mainHandler = new Handler(Looper.getMainLooper());
        initializeUI();
        setupListeners();
        setupObservers();

        updateUIStatus("Ready", "Connect to TennisGenius AP WiFi.");
        updateRecordingButtons(false);
        updateControlButtons(false);
    }

    private void requestNotificationPermission() {
        // This is only necessary for Android 13 (API 33) and higher.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // If permission is not granted, request it.
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE_POST_NOTIFICATIONS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted. You could add logic here if needed.
                Log.d(TAG, "POST_NOTIFICATIONS permission granted.");
            } else {
                // Permission denied. Inform the user that notifications are required.
                Toast.makeText(this, "Notification permission is required for the connection status.", Toast.LENGTH_LONG).show();
            }
        }
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
                isRecording = true;
                updateRecordingButtons(true);
                String command = buildStartRecordingCommand();
                sendCommand(command);
            } else {
                isRecording = false;
                updateRecordingButtons(false);
                sendCommand(CMD_STOP_RECORDING);
            }
        });

        btnCapturePhotos.setOnClickListener(v -> {
            ivImage1.setImageDrawable(null);
            ivImage2.setImageDrawable(null);
            sendCommand(buildCaptureCommand());
        });

        btnPowerOff.setOnClickListener(v -> showPowerOffDialog());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));
        ////ivImage1.setOnClickListener(v -> sendCommand(CMD_GET_PREVIEW_FRAME));
        ////ivImage2.setOnClickListener(v -> sendCommand(CMD_GET_PREVIEW_FRAME));
    }

    // New helper to manage recording button state
    private void updateRecordingButtons(boolean isCurrentlyRecording) {
        mainHandler.post(() -> {
            setSettingsEnabled(!isCurrentlyRecording);

            if (isCurrentlyRecording) {
                btnStartRecording.setText("Stop Recording");
                btnCapturePhotos.setEnabled(false); // Disable during recording
            } else {
                btnStartRecording.setText("Start Recording");
                btnCapturePhotos.setEnabled(true); // Re-enable when not recording
            }
        });
    }

    private void setSettingsEnabled(boolean enabled) {
        for (int i = 0; i < rgResolution.getChildCount(); i++) {
            rgResolution.getChildAt(i).setEnabled(enabled);
        }
        for (int i = 0; i < rgFormat.getChildCount(); i++) {
            rgFormat.getChildAt(i).setEnabled(enabled);
        }
        btnPowerOff.setEnabled(enabled);
    }

    // In MainActivity.java

    private void setupObservers() {
        CommunicationService.getStatusData().observe(this, statusPair -> {
            if (statusPair == null) return;

            // First, always update the status text fields
            updateUIStatus(statusPair.first, statusPair.second);

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
            } else if (status != null && status.startsWith("SERVER_STOP")) {

                isRecording = false;
                updateRecordingButtons(false);
                sendCommand(CMD_STOP_RECORDING);
            }
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

    private String buildCaptureCommand() {
        // --- Values from SettingsActivity ---
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean aeLock = prefs.getBoolean(SettingsActivity.KEY_AE_LOCK, false);
        boolean awbLock = prefs.getBoolean(SettingsActivity.KEY_AWB_LOCK, false);
        long exposureLow = prefs.getLong(SettingsActivity.KEY_EXPOSURE_LOW, 33333L);
        long exposureHigh = prefs.getLong(SettingsActivity.KEY_EXPOSURE_HIGH, 33333L);
        float gain = prefs.getFloat(SettingsActivity.KEY_GAIN, 1.0f);
        float digitalGain = prefs.getFloat(SettingsActivity.KEY_DIGITAL_GAIN, 1.0f);
        int expCompProgress = prefs.getInt(SettingsActivity.KEY_EXP_COMP_PROGRESS, 8);
        float expCompValue = (expCompProgress - 8) * 0.25f;

        // --- Values from MainActivity UI ---
        String mode = rb4K.isChecked() ? "4K" : "HD";
        String encoding = ((RadioButton) findViewById(rgFormat.getCheckedRadioButtonId())).getText().toString().toUpperCase();
        float scaleFactor = 0.25f; // This seems to be a fixed value in your example

        StringBuilder commandBuilder = new StringBuilder();
        commandBuilder.append("CAPTURE_PHOTO:");
        commandBuilder.append(mode);
        commandBuilder.append(",");
        commandBuilder.append(encoding);
        commandBuilder.append(",");
        commandBuilder.append(scaleFactor);
        commandBuilder.append(",exp_comp=");
        commandBuilder.append(expCompValue);
        commandBuilder.append(",gain=");
        commandBuilder.append(gain);
        commandBuilder.append(",digital_gain=");
        commandBuilder.append(digitalGain);
        commandBuilder.append(",exposureLow=");
        commandBuilder.append(exposureLow);
        commandBuilder.append(",exposureHigh=");
        commandBuilder.append(exposureHigh);
        commandBuilder.append(",aelock=");
        commandBuilder.append(aeLock ? 1 : 0);
        commandBuilder.append(",awblock=");
        commandBuilder.append(awbLock ? 1 : 0);
        commandBuilder.append("\n");
        return commandBuilder.toString();
    }

    private String buildStartRecordingCommand() {
        // --- Values from SettingsActivity ---
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean aeLock = prefs.getBoolean(SettingsActivity.KEY_AE_LOCK, false);
        boolean awbLock = prefs.getBoolean(SettingsActivity.KEY_AWB_LOCK, false);
        long exposureLow = prefs.getLong(SettingsActivity.KEY_EXPOSURE_LOW, 33333L);
        long exposureHigh = prefs.getLong(SettingsActivity.KEY_EXPOSURE_HIGH, 33333L);
        float gain = prefs.getFloat(SettingsActivity.KEY_GAIN, 1.0f);
        float digitalGain = prefs.getFloat(SettingsActivity.KEY_DIGITAL_GAIN, 1.0f);
        int expCompProgress = prefs.getInt(SettingsActivity.KEY_EXP_COMP_PROGRESS, 8);
        float expCompValue = (expCompProgress - 8) * 0.25f;

        // --- Values from MainActivity UI ---
        String mode = rb4K.isChecked() ? "4K" : "HD";
        String encoding = rbJPEG.isChecked() ? "JPEG" : "RAW";

        StringBuilder commandBuilder = new StringBuilder();
        commandBuilder.append("START_RECORDING:");
        commandBuilder.append(mode);
        commandBuilder.append(",");
        commandBuilder.append(encoding);
        commandBuilder.append(",exp_comp=");
        commandBuilder.append(expCompValue);
        commandBuilder.append(",gain=");
        commandBuilder.append(gain);
        commandBuilder.append(",digital_gain=");
        commandBuilder.append(digitalGain);
        commandBuilder.append(",exposureLow=");
        commandBuilder.append(exposureLow);
        commandBuilder.append(",exposureHigh=");
        commandBuilder.append(exposureHigh);
        commandBuilder.append(",aelock=");
        commandBuilder.append(aeLock ? 1 : 0);
        commandBuilder.append(",awblock=");
        commandBuilder.append(awbLock ? 1 : 0);
        commandBuilder.append("\n");
        return commandBuilder.toString();
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
        String networkName = prefs.getString(SettingsActivity.KEY_CONNECTION_TARGET, "TG_AP");
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
