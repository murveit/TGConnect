package com.murveit.tgcontrol;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final String TennisGenius_HOST = "10.0.2.2";
    private static final int TennisGenius_PORT = 8000;
    private static final int CONNECTION_TIMEOUT_MS = 5000; // 5 seconds
    private static final int SIZE_HEADER_LENGTH = 10;

    // NEW: Commands for server communication
    private static final String CMD_START_RECORDING = "START_RECORDING:%s,%s,%d\n";
    private static final String CMD_STOP_RECORDING = "STOP_RECORDING\n";
    private static final String CMD_CAPTURE_PHOTO = "CAPTURE_PHOTO:%s,%s,0.25\n";


    private Handler mainHandler;

    // UI Elements
    private Button btnConnect;
    private Button btnDisconnect;
    private RadioGroup rgResolution, rgFormat;
    private RadioButton rb4K; // rbHD, rbJPEG, rbRAW are not needed as member variables
    private TextView tvJpegQualityLabel;
    private SeekBar sbJpegQuality;
    private TextView tvStatus1, tvStatus2, tvStatus3;
    private Button btnStartRecording;
    private Button btnCapturePhotos;
    private ImageView ivImage;


    private Socket socket;
    private Thread communicationThread = null;
    private OutputStream outputStream;
    private InputStream inputStream;
    private volatile boolean isRunning = false;
    private volatile boolean isRecording = false; // Flag to track recording state

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
        tvStatus3 = findViewById(R.id.tvStatus3);
        btnStartRecording = findViewById(R.id.btnStartRecording);
        btnCapturePhotos = findViewById(R.id.btnCapturePhotos);
        ivImage = findViewById(R.id.ivImage);

        mainHandler = new Handler(Looper.getMainLooper());

        // Set listeners
        btnConnect.setOnClickListener(v -> connectToServer());
        btnDisconnect.setOnClickListener(v -> disconnectFromServer());

        // --- NEW BUTTON LOGIC ---
        btnStartRecording.setOnClickListener(v -> {
            if (!isRecording) {
                // Currently stopped, so START recording
                isRecording = true; // Set state immediately for UI responsiveness
                updateRecordingButtons(true);

                // Get settings from UI
                String resolution = rb4K.isChecked() ? "4K" : "HD";
                String format = ((RadioButton) findViewById(rgFormat.getCheckedRadioButtonId())).getText().toString().toUpperCase();
                int quality = sbJpegQuality.getProgress() + 1; // 1-100

                // Format and send command
                String command = String.format(Locale.US, CMD_START_RECORDING, resolution, format, quality);
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

            // Format and send command
            String command = String.format(Locale.US, CMD_CAPTURE_PHOTO, resolution, format);
            sendCommand(command);
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

        updateUIStatus("Ready", "Connect to TennisGenius AP WiFi.", "");
        updateControlButtons(false); // Initial state is disconnected
    }

    private void connectToServer() {
        if (communicationThread != null && communicationThread.isAlive()) {
            updateUIStatus("Status", "Already connecting or connected.", "");
            return;
        }
        mainHandler.post(() -> ivImage.setImageDrawable(null));
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

    private void updateUIStatus(final String line1, final String line2, final String line3) {
        mainHandler.post(() -> {
            if (line1 != null) tvStatus1.setText(line1);
            if (line2 != null) tvStatus2.setText(line2);
            if (line3 != null) tvStatus3.setText(line3);
        });
    }

    // Simplified method to control button visibility
    private void updateControlButtons(boolean isConnected) {
        mainHandler.post(() -> {
            btnConnect.setVisibility(isConnected ? View.GONE : View.VISIBLE);
            btnDisconnect.setVisibility(isConnected ? View.VISIBLE : View.GONE);

            // Show/hide action buttons based on connection status
            btnStartRecording.setVisibility(isConnected ? View.VISIBLE : View.GONE);
            btnCapturePhotos.setVisibility(isConnected ? View.VISIBLE : View.GONE);

            // *** THE FIX IS HERE: The call to updateRecordingButtons is removed from this method ***
            // if (isConnected) {
            //    updateRecordingButtons(false);
            // }

            // Enable/disable settings when not connected
            for (int i = 0; i < rgResolution.getChildCount(); i++) {
                rgResolution.getChildAt(i).setEnabled(!isConnected);
            }
            for (int i = 0; i < rgFormat.getChildCount(); i++) {
                rgFormat.getChildAt(i).setEnabled(!isConnected);
            }
            sbJpegQuality.setEnabled(!isConnected);
        });
    }

    // New helper to manage recording button state
    private void updateRecordingButtons(boolean isCurrentlyRecording) {
        mainHandler.post(() -> {
            if (isCurrentlyRecording) {
                btnStartRecording.setText("Stop Recording");
                btnCapturePhotos.setEnabled(false); // Disable during recording
            } else {
                btnStartRecording.setText("Start Recording");
                btnCapturePhotos.setEnabled(true); // Re-enable when not recording
            }
        });
    }

    private void displayBitmap(final Bitmap bitmap) {
        mainHandler.post(() -> {
            if (bitmap != null) {
                ivImage.setImageBitmap(bitmap);
                String msg = isRecording ? "SUCCESS: Recording frame..." : "SUCCESS: Photo received.";
                updateUIStatus("Status: OK", msg, "");
            } else {
                updateUIStatus("Status: ERROR", "Failed to decode image (null bitmap).", "");
            }
        });
    }

    public void sendCommand(String command) {
        if (outputStream == null || socket == null || !socket.isConnected()) {
            updateUIStatus("Status: Error", "Not connected. Cannot send command.", "");
            return;
        }

        new Thread(() -> {
            try {
                outputStream.write(command.getBytes(StandardCharsets.UTF_8));
                Log.d(TAG, "Sent command: " + command.trim());
                mainHandler.post(() -> updateUIStatus("Status: Sent", "Command: " + command.trim(), ""));
            } catch (Exception e) {
                Log.e(TAG, "Failed to send command", e);
                disconnectFromServer();
            }
        }).start();
    }

    // ... (readFullData and readLineFromStream methods remain the same) ...
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
                mainHandler.post(() -> updateUIStatus("Status:", "Connecting...", ""));
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(TennisGenius_HOST, TennisGenius_PORT), CONNECTION_TIMEOUT_MS);
                outputStream = socket.getOutputStream();
                inputStream = socket.getInputStream();
                socket.setSoTimeout(500);

                Log.d(TAG, "Connected to TennisGenius.");

                // *** THE FIX IS HERE: All UI updates are now batched in a single post to the main thread ***
                mainHandler.post(() -> {
                    updateUIStatus("Status: Connected", "Ready for commands.", "");
                    updateControlButtons(true);
                    updateRecordingButtons(false); // Set initial button state
                });


                while (isRunning && socket != null && socket.isConnected() && !Thread.currentThread().isInterrupted()) {
                    try {
                        // The loop now just listens for any incoming data (like images or status lines)
                        // It no longer needs to manage an 'isStreaming' state itself
                        String serverMessage = readLineFromStream(inputStream);
                        Log.d(TAG, "Received from server: " + serverMessage);

                        if (serverMessage.startsWith("IMAGE_SIZE")) {
                            // This is a simplified example; your server might send a header then the image
                            receiveImageFrame(false); // Assuming an image follows this message
                        } else {
                            // It's a general status message
                            updateUIStatus("Status: Server", serverMessage, "");
                        }
                    } catch (SocketTimeoutException ignored) {
                        // This is expected. The timeout allows the loop to check the isRunning flag.
                    } catch (IOException e) {
                        if(isRunning) throw e; // Re-throw error if we weren't trying to shut down
                    }
                }

            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "Critical Connection/Communication Error", e);
                    updateUIStatus("Status: CRITICAL Error", e.getMessage(), "");
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
                        updateUIStatus("Status: Ready", "Disconnected.", "");
                    }
                    updateControlButtons(false);
                });
                communicationThread = null;
            }
        }
    }

    private void receiveImageFrame(boolean isContinuous) throws IOException {
        // This method can be simplified now if the server logic is consistent
        try { socket.setSoTimeout(5000); } catch (Exception e) { /* ignore */ }

        byte[] headerBytes = readFullData(inputStream, SIZE_HEADER_LENGTH);
        String headerStr = new String(headerBytes, StandardCharsets.UTF_8).trim();
        int imageSize;

        try {
            imageSize = Integer.parseInt(headerStr);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed image size header received: " + headerStr);
        }

        if (imageSize <= 0) {
            Log.w(TAG, "Received zero or negative image size, skipping frame.");
            return;
        }

        byte[] imageBytes = readFullData(inputStream, imageSize);
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        displayBitmap(bitmap);

        // Reset timeout to the short-polling value
        try { socket.setSoTimeout(500); } catch (Exception e) { /* ignore */ }
    }
}
