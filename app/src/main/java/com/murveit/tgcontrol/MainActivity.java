package com.murveit.tgcontrol;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TGClient";


    // Use 10.0.2.2 to test with an emulator and running the server on the laptop.
    private static final String Emulator_HOST = "10.0.2.2";
    // Use 10.42.0.1 when the phone is on the TG_AP network.
    private static final String TG_AP_HOST = "10.42.0.1";
    // Use localhost when debugging with a mock server and client on same machine.
    private static final String MOCK_HOST = "localhost";
    private static final String TennisGenius_HOST = TG_AP_HOST;
    private static final int TennisGenius_PORT = 8000;
    private static final int CONNECTION_TIMEOUT_MS = 5000; // 5 seconds
    private static final int SIZE_HEADER_LENGTH = 10;

    // Commands for server communication
    private static final String CMD_START_RECORDING = "START_RECORDING:%s,%s,ExpComp=%.2f\n";
    private static final String CMD_STOP_RECORDING = "STOP_RECORDING\n";
    private static final String CMD_CAPTURE_PHOTO = "CAPTURE_PHOTO:%s,%s,0.25,ExpComp=%.2f\n";
    private static final String CMD_SHUTDOWN_SYSTEM = "SHUTDOWN_SYSTEM\n";


    private Handler mainHandler;

    // UI Elements
    private Button btnConnect;
    private Button btnDisconnect;
    private RadioGroup rgResolution, rgFormat;
    private RadioButton rb4K;
    private TextView tvJpegQualityLabel;
    private SeekBar sbJpegQuality;
    private TextView tvStatus1, tvStatus2;
    private Button btnStartRecording;
    private Button btnCapturePhotos;
    private ImageView ivImage1, ivImage2;
    private ImageButton btnPowerOff;
    private SeekBar sbExpComp;
    private TextView tvExpCompLabel;


    private Socket socket;
    private Thread communicationThread = null;
    private OutputStream outputStream;
    private InputStream inputStream;
    private volatile boolean isRunning = false;
    private volatile boolean isRecording = false; // Flag to track recording state
    private long recordingStartTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize all UI elements
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        rgResolution = findViewById(R.id.rgResolution);
        rgFormat = findViewById(R.id.rgFormat);
        rb4K = findViewById(R.id.rb4K);
        tvJpegQualityLabel = findViewById(R.id.tvJpegQualityLabel);
        sbJpegQuality = findViewById(R.id.sbJpegQuality);
        tvStatus1 = findViewById(R.id.tvStatus1);
        tvStatus2 = findViewById(R.id.tvStatus2);
        btnStartRecording = findViewById(R.id.btnStartRecording);
        btnCapturePhotos = findViewById(R.id.btnCapturePhotos);
        ivImage1 = findViewById(R.id.ivImage1);
        ivImage2 = findViewById(R.id.ivImage2);
        btnPowerOff = findViewById(R.id.btnPowerOff);
        sbExpComp = findViewById(R.id.sbExpComp);
        tvExpCompLabel = findViewById(R.id.tvExpCompLabel);

        mainHandler = new Handler(Looper.getMainLooper());

        // Set listeners
        btnConnect.setOnClickListener(v -> connectToServer());
        btnDisconnect.setOnClickListener(v -> disconnectFromServer());
        btnPowerOff.setOnClickListener(v -> showPowerOffDialog());

        // --- NEW BUTTON LOGIC ---
        btnStartRecording.setOnClickListener(v -> {
            if (!isRecording) {
                // Currently stopped, so START recording
                isRecording = true; // Set state immediately for UI responsiveness
                recordingStartTime = System.currentTimeMillis(); // CAPTURE START TIME
                updateRecordingButtons(true);

                // Get settings from UI
                String resolution = rb4K.isChecked() ? "4K" : "HD";
                String format = ((RadioButton) findViewById(rgFormat.getCheckedRadioButtonId())).getText().toString().toUpperCase();
                // Get ExpComp value from slider (progress 0-16 -> -2.0 to +2.0)
                float expCompValue = (sbExpComp.getProgress() - 8) * 0.25f;

                // Format and send command
                String command = String.format(Locale.US, CMD_START_RECORDING, resolution, format, expCompValue);
                sendCommand(command);

            } else {
                // Currently recording, so STOP
                isRecording = false; // Set state immediately
                updateRecordingButtons(false);
                sendCommand(CMD_STOP_RECORDING);
            }
        });

        btnCapturePhotos.setOnClickListener(v -> {
            // Get settings from UI
            String resolution = rb4K.isChecked() ? "4K" : "HD";
            String format = ((RadioButton) findViewById(rgFormat.getCheckedRadioButtonId())).getText().toString().toUpperCase();
            // Get ExpComp value from slider (progress 0-16 -> -2.0 to +2.0)
            float expCompValue = (sbExpComp.getProgress() - 8) * 0.25f;

            // Format and send command
            String command = String.format(Locale.US, CMD_CAPTURE_PHOTO, resolution, format, expCompValue);
            sendCommand(command);

            // Clear the image views for immediate feedback
            ivImage1.setImageDrawable(null);
            ivImage2.setImageDrawable(null);
        });
        // --- END NEW BUTTON LOGIC ---

        sbJpegQuality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvJpegQualityLabel.setText("Quality: " + (progress + 1));
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbExpComp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // progress is 0-16, maps to -2.0 to +2.0 in 0.25 steps
                float value = (progress - 8) * 0.25f;
                tvExpCompLabel.setText(String.format(Locale.US, "Exp Comp: %.2f", value));
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        updateUIStatus("Ready", "Connect to TennisGenius AP WiFi.");
        updateControlButtons(false); // Initial state is disconnected
    }

    private void showPowerOffDialog() {
        if (socket == null || !socket.isConnected()) {
            Toast.makeText(this, "Not connected to server.", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Power Down Tennis Genius?")
                .setMessage("Are you sure you want to shut down the server?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    Log.d(TAG, "User confirmed power off. Sending command.");
                    sendCommand(CMD_SHUTDOWN_SYSTEM);
                    // Add a small delay for the command to be sent before disconnecting
                    mainHandler.postDelayed(this::disconnectFromServer, 500);
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    Log.d(TAG, "User cancelled power off.");
                    dialog.dismiss();
                })
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private void connectToServer() {
        if (communicationThread != null && communicationThread.isAlive()) {
            updateUIStatus("Status", "Already connecting or connected.");
            return;
        }
        // UPDATED: Clear both image views
        mainHandler.post(() -> {
            ivImage1.setImageDrawable(null);
            ivImage2.setImageDrawable(null);
        });
        isRunning = true;
        isRecording = false; // Reset recording state on new connection
        communicationThread = new Thread(new CommunicationTask());
        communicationThread.start();
    }

    private void disconnectFromServer() {
        isRunning = false;
        isRecording = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing socket on disconnect", e);
        }
    }

    // UPDATED to only use 2 lines
    private void updateUIStatus(final String line1, final String line2) {
        mainHandler.post(() -> {
            if (line1 != null) tvStatus1.setText(line1);
            if (line2 != null) tvStatus2.setText(line2);
        });
    }

    // Simplified method to control button visibility and enabled state
    private void updateControlButtons(boolean isConnected) {
        mainHandler.post(() -> {
            btnConnect.setVisibility(isConnected ? View.GONE : View.VISIBLE);
            btnDisconnect.setVisibility(isConnected ? View.VISIBLE : View.GONE);

            // Show/hide action buttons based on connection status
            int visibility = isConnected ? View.VISIBLE : View.GONE;
            btnStartRecording.setVisibility(visibility);
            btnCapturePhotos.setVisibility(visibility);

            btnStartRecording.setEnabled(isConnected);
            btnCapturePhotos.setEnabled(isConnected);

            // On disconnect, we must re-enable the settings.
            if (!isConnected) {
                setSettingsEnabled(true);
            }
        });
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

    /**
     * Helper method to enable or disable all the settings controls at once.
     * @param enabled true to enable, false to disable.
     */
    private void setSettingsEnabled(boolean enabled) {
        for (int i = 0; i < rgResolution.getChildCount(); i++) {
            rgResolution.getChildAt(i).setEnabled(enabled);
        }
        for (int i = 0; i < rgFormat.getChildCount(); i++) {
            rgFormat.getChildAt(i).setEnabled(enabled);
        }
        sbJpegQuality.setEnabled(enabled);
        sbExpComp.setEnabled(enabled);
        btnPowerOff.setEnabled(enabled);
    }

    // UPDATED: Now draws the bitmap on the specified ImageView
    private void displayBitmap(final Bitmap bitmap, final ImageView targetImageView) {
        mainHandler.post(() -> {
            if (bitmap != null) {
                targetImageView.setImageBitmap(bitmap); // Draw on the specified image view
                String msg = isRecording ? "SUCCESS: Recording frame..." : "SUCCESS: Photo received.";
                updateUIStatus("Status: OK", msg);
            } else {
                updateUIStatus("Status: ERROR", "Failed to decode image (null bitmap).");
            }
        });
    }

    public void sendCommand(String command) {
        if (outputStream == null || socket == null || !socket.isConnected()) {
            updateUIStatus("Status: Error", "Not connected. Cannot send command.");
            return;
        }

        new Thread(() -> {
            try {
                outputStream.write(command.getBytes(StandardCharsets.UTF_8));
                Log.d(TAG, "Sent command: " + command.trim());
                mainHandler.post(() -> updateUIStatus("Status: Sent", "Command: " + command.trim()));
            } catch (Exception e) {
                Log.e(TAG, "Failed to send command", e);
                disconnectFromServer();
            }
        }).start();
    }

    private byte[] readFullData(InputStream is, int length) throws IOException {
        byte[] buffer = new byte[length];
        int bytesRead = 0;

        while (bytesRead < length) {
            int result = is.read(buffer, bytesRead, length - bytesRead);
            if (result == -1) {
                throw new IOException("Connection closed by peer during data read.");
            }
            bytesRead += result;
        }
        return buffer;
    }

    private String readLineFromStream(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int byteRead;
        while (isRunning) {
            byteRead = is.read();
            if (byteRead == -1) {
                throw new IOException("End of stream reached while reading status.");
            }

            if (byteRead == '\n') {
                break;
            }
            buffer.write(byteRead);
        }
        return buffer.toString(StandardCharsets.UTF_8.name()).trim();
    }

    private class CommunicationTask implements Runnable {
        @Override
        public void run() {
            try {
                mainHandler.post(() -> updateUIStatus("Status:", "Connecting..."));
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(TennisGenius_HOST, TennisGenius_PORT), CONNECTION_TIMEOUT_MS);
                outputStream = socket.getOutputStream();
                inputStream = socket.getInputStream();
                socket.setSoTimeout(500);

                Log.d(TAG, "Connected to TennisGenius.");

                // All UI updates are now batched in a single post to the main thread
                mainHandler.post(() -> {
                    updateUIStatus("Status: Connected", "Ready for commands.");
                    updateControlButtons(true);
                    updateRecordingButtons(false); // Set initial button state
                });


                while (isRunning && socket != null && socket.isConnected() && !Thread.currentThread().isInterrupted()) {
                    try {
                        String serverMessage = readLineFromStream(inputStream);
                        Log.d(TAG, "Received from server: " + serverMessage);

                        // --- UPDATED MESSAGE HANDLING LOGIC ---
                        if (serverMessage.equals("STATUS: CAPTURE_DONE; SENDING_IMAGES")) {
                            // This specific status means two images are coming.
                            updateUIStatus(null, "Receiving captured images...");
                            receiveImageFrame(ivImage1); // Receive first image for the top view
                            receiveImageFrame(ivImage2); // Receive second image for the bottom view
                        } else if (serverMessage.startsWith("STATUS_FRAMES:")) {
                            String data = serverMessage.substring("STATUS_FRAMES:".length()).trim();
                            String[] parts = data.split(",");
                            if (parts.length == 3) {
                                try {
                                    int framesProcessed = Integer.parseInt(parts[0].trim());
                                    int framesWritten = Integer.parseInt(parts[1].trim());
                                    float freeSpaceGb = Float.parseFloat(parts[2].trim()) / 1000.0f;

                                    long elapsedMillis = System.currentTimeMillis() - recordingStartTime;
                                    long seconds = (elapsedMillis / 1000) % 60;
                                    long minutes = (elapsedMillis / (1000 * 60)) % 60;
                                    String elapsedTime = String.format(Locale.US, "%02d:%02d", minutes, seconds);

                                    String framesStatus = String.format(Locale.US, "Frames: %5d %5d | Time %s | Free Disk %.1f Gb",
                                            framesProcessed, framesWritten, elapsedTime, freeSpaceGb);
                                    updateUIStatus(null, framesStatus);

                                } catch (NumberFormatException e) {
                                    Log.e(TAG, "Failed to parse STATUS_FRAMES data: " + data, e);
                                    updateUIStatus(null, "Error parsing frame data");
                                }
                            }
                        } else if (serverMessage.startsWith("STATUS:")) {
                            String status = serverMessage.substring("STATUS:".length()).trim();
                            updateUIStatus(null, status);
                        } else if (serverMessage.startsWith("IMAGE_SIZE")) {
                            // This handles the case of a single image being sent during recording
                            receiveImageFrame(ivImage1);
                        } else {
                            // Only if it matches none of the above, treat it as a general message.
                            if (!serverMessage.isEmpty()) {
                                updateUIStatus(null, serverMessage);
                            }
                        }
                        // --- END OF LOGIC ---

                    } catch (SocketTimeoutException ignored) {
                        // This is expected and normal. Allows the loop to check the isRunning flag.
                    } catch (IOException e) {
                        if(isRunning) throw e;
                    }
                }

            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "Critical Connection/Communication Error", e);
                    updateUIStatus("Status: CRITICAL Error", e.getMessage());
                } else {
                    Log.d(TAG, "Socket closed intentionally.");
                }
            } finally {
                isRunning = false;
                isRecording = false; // Ensure state is reset
                try {
                    if (outputStream != null) outputStream.close();
                    if (inputStream != null) inputStream.close();
                    if (socket != null && !socket.isClosed()) socket.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error during final cleanup", e);
                }

                mainHandler.post(() -> {
                    String status2Text = tvStatus2.getText().toString();
                    if (!status2Text.contains("Error") && !status2Text.contains("CRITICAL")) {
                        updateUIStatus("Status: Ready", "Disconnected.");
                    }
                    updateControlButtons(false);
                });
                communicationThread = null;
            }
        }
    }

    private void receiveImageFrame(final ImageView targetImageView) throws IOException {
        try { socket.setSoTimeout(5000); } catch (Exception e) { /* ignore */ }

        // READ THE 10-BYTE HEADER DIRECTLY. DO NOT READ A LINE.
        byte[] headerBytes = readFullData(inputStream, SIZE_HEADER_LENGTH);
        String headerStr = new String(headerBytes, StandardCharsets.UTF_8).trim();
        int imageSize;

        try {
            imageSize = Integer.parseInt(headerStr);
        } catch (NumberFormatException e) {
            String errorMsg = "Malformed image size header received: '" + headerStr + "'";
            Log.e(TAG, errorMsg, e);
            throw new IOException(errorMsg);
        }

        if (imageSize <= 0) {
            Log.w(TAG, "Received zero or negative image size, skipping frame.");
            return;
        }

        byte[] imageBytes = readFullData(inputStream, imageSize);
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        displayBitmap(bitmap, targetImageView); // Pass the target view to displayBitmap

        try { socket.setSoTimeout(500); } catch (Exception e) { /* ignore */ }
    }
}
