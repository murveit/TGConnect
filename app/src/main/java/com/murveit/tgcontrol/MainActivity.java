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

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TGClient";

    // **************************************************** FIX !!!!
    // *** IMPORTANT: Update this IP address to match your TennisGenius AP's IP ***
    // **************************************************** FIX !!!!
    private static final String TennisGenius_HOST = "10.42.0.1";
    private static final int TennisGenius_PORT = 8000;
    private static final int CONNECTION_TIMEOUT_MS = 5000; // 5 seconds
    private static final int SIZE_HEADER_LENGTH = 10;
    
    // Commands used for server communication (must end with \n for readLineFromStream)
    private static final String CMD_START_TRACKING = "START_TRACKING\n"; 
    private static final String CMD_STOP_TRACKING = "STOP_TRACKING\n";
    private static final String CMD_SEND_SINGLE_IMAGE = "SEND_IMAGE_SINGLE\n";

    private Handler mainHandler;
    private Button btnConnect;
    private Button btnDisconnect;
    private Button btnStartTracking;
    private Button btnStopTracking;  // New
    private Button btnSendSingleImage; // New
    private TextView tvStatus;
    private ImageView ivImage;

    private Socket socket;
    private Thread communicationThread = null;
    private OutputStream outputStream;
    private InputStream inputStream;
    private volatile boolean isRunning = false;
    private volatile boolean isStreaming = false; // Flag to track if the server is sending continuous frames

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnStartTracking = findViewById(R.id.btnStartTracking);
        btnStopTracking = findViewById(R.id.btnStopTracking);
        btnSendSingleImage = findViewById(R.id.btnSendSingleImage);
        tvStatus = findViewById(R.id.tvStatus);
        ivImage = findViewById(R.id.ivImage);
        mainHandler = new Handler(Looper.getMainLooper());

        btnConnect.setOnClickListener(v -> connectToServer());
        btnDisconnect.setOnClickListener(v -> disconnectFromServer());
        btnStartTracking.setOnClickListener(v -> sendCommand(CMD_START_TRACKING));
        btnStopTracking.setOnClickListener(v -> sendCommand(CMD_STOP_TRACKING));
        btnSendSingleImage.setOnClickListener(v -> sendCommand(CMD_SEND_SINGLE_IMAGE));

        updateUIStatus("Ready.\nConnect to TennisGenius AP WiFi.");
    }

    private void connectToServer() {
        if (communicationThread != null && communicationThread.isAlive()) {
            updateUIStatus("Already connecting or connected.");
            return;
        }

        // Clear the image display
        mainHandler.post(() -> ivImage.setImageDrawable(null));

        isRunning = true;
        isStreaming = false; // Not streaming yet
        communicationThread = new Thread(new CommunicationTask());
        communicationThread.start();
    }

    private void disconnectFromServer() {
        isRunning = false;
        isStreaming = false; // Stop streaming flag
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing socket on disconnect", e);
        }
    }

    // Helper to safely update UI elements from the background thread
    private void updateUIStatus(final String message) {
        mainHandler.post(() -> tvStatus.setText(message));
    }

    // Updates the state of the control buttons
    private void updateControlButtons(boolean isConnected, boolean isTracking) {
        mainHandler.post(() -> {
            // Connection buttons
            btnConnect.setVisibility(isConnected ? View.GONE : View.VISIBLE);
            btnDisconnect.setVisibility(isConnected ? View.VISIBLE : View.GONE);
            
            // Streaming/Single-shot controls
            btnStartTracking.setVisibility(isConnected ? View.VISIBLE : View.GONE);
            btnStopTracking.setVisibility(isConnected ? View.VISIBLE : View.GONE);
            btnSendSingleImage.setVisibility(isConnected ? View.VISIBLE : View.GONE);

            // Enable/Disable logic
            btnStartTracking.setEnabled(isConnected && !isTracking);
            btnStopTracking.setEnabled(isConnected && isTracking);
            btnSendSingleImage.setEnabled(isConnected && !isTracking);
        });
    }

    // Helper to safely display the received image on the main thread
    private void displayBitmap(final Bitmap bitmap) {
        mainHandler.post(() -> {
            if (bitmap != null) {
                ivImage.setImageBitmap(bitmap);
                tvStatus.setText(isStreaming ? "SUCCESS: Streaming frame..." : "SUCCESS: Single frame received.");
            } else {
                tvStatus.setText("ERROR: Failed to decode image (null bitmap).");
            }
        });
    }

    // Sends a command to the server (must be called from a background thread or new thread)
    public void sendCommand(String command) {
        if (outputStream == null || socket == null || !socket.isConnected()) {
            updateUIStatus("Not connected. Cannot send command.");
            return;
        }

        new Thread(() -> {
            try {
                // Special handling for START/STOP/SINGLE commands
                if (command.equals(CMD_START_TRACKING)) {
                    isStreaming = true;
                    updateControlButtons(true, true);
                    updateUIStatus("Command: START_TRACKING sent. Awaiting stream.");
                } else if (command.equals(CMD_STOP_TRACKING)) {
                    isStreaming = false;
                    updateControlButtons(true, false);
                    updateUIStatus("Command: STOP_TRACKING sent. Streaming stopping.");
                } else if (command.equals(CMD_SEND_SINGLE_IMAGE)) {
                    // Do not change isStreaming flag, just send command
                    updateUIStatus("Command: SEND_IMAGE_SINGLE sent. Awaiting frame.");
                }

                outputStream.write(command.getBytes(StandardCharsets.UTF_8));
                Log.d(TAG, "Sent command: " + command.trim());
            } catch (Exception e) {
                Log.e(TAG, "Failed to send command", e);
                // Trigger disconnect on send failure
                disconnectFromServer();
            }
        }).start();
    }
    
    // Reads exactly 'length' bytes from the input stream.
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

    // Reads a newline-terminated string from the input stream.
    private String readLineFromStream(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int byteRead;
        // Use isRunning check to allow clean exit during blocking read
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
                // 1. Connection setup and UI update
                mainHandler.post(() -> tvStatus.setText("Connecting..."));
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(TennisGenius_HOST, TennisGenius_PORT), CONNECTION_TIMEOUT_MS);
                outputStream = socket.getOutputStream();
                inputStream = socket.getInputStream();
                
                // Set a small read timeout to prevent permanent blocking in the waiting state
                // This allows the thread to check `isStreaming` flag periodically.
                socket.setSoTimeout(500); 

                Log.d(TAG, "Connected to TennisGenius.");
                updateUIStatus("Connected. Ready for commands.");
                updateControlButtons(true, false);

                // 2. MAIN COMMUNICATION LOOP
                while (isRunning && socket != null && socket.isConnected() && !Thread.currentThread().isInterrupted()) {
                    
                    if (isStreaming) {
                        // STATE 1: CONTINUOUS STREAMING
                        // 2a. Read the single status line sent after START_TRACKING.
                        // We must read it here to consume it, but only if we just started streaming.
                        try {
                            String streamStatus = readLineFromStream(inputStream);
                            Log.d(TAG, "Received Stream Status: " + streamStatus);
                            updateUIStatus("Status: " + streamStatus);
                        } catch (SocketTimeoutException ignored) {
                            // If we time out here, it means the server hasn't sent the status yet,
                            // or it was consumed by a previous single-shot read. 
                            // We can proceed to try reading the image data stream.
                        } catch (IOException e) {
                            Log.w(TAG, "Error reading expected status line during streaming setup: " + e.getMessage());
                        }

                        // Enter the tight image reading loop for streaming
                        while (isStreaming) {
                            receiveImageFrame(true);
                        }
                        
                        // Once isStreaming becomes false (via STOP_TRACKING), the inner loop breaks
                        updateControlButtons(true, false);
                        updateUIStatus("Streaming stopped by command.");

                    } else {
                        // STATE 2: WAITING FOR COMMANDS / SINGLE-SHOT RESPONSE
                        
                        // We use a timeout to prevent permanent blocking while waiting for data 
                        // and allow the loop to check the `isRunning` flag.
                        try {
                            // Attempt to read status line (only expected after a SEND_IMAGE_SINGLE command)
                            String singleShotStatus = readLineFromStream(inputStream);
                            Log.d(TAG, "Received Single-Shot Status: " + singleShotStatus);
                            
                            // If we received a status, we now expect the single image frame
                            updateUIStatus("Status: " + singleShotStatus + " Awaiting single frame...");
                            receiveImageFrame(false); // Read the image for the single shot
                            
                        } catch (SocketTimeoutException ignored) {
                            // Expected when waiting for data; loop continues to check flags
                        } catch (IOException e) {
                             if(isRunning) throw e; // Propagate error if not intentional disconnect
                        }
                    }
                } // End MAIN COMMUNICATION LOOP

            } catch (Exception e) {
                // Catch connection setup errors, and I/O errors from the loop
                if (isRunning) {
                     Log.e(TAG, "Critical Connection/Communication Error", e);
                     updateUIStatus("CRITICAL Error: " + e.getMessage());
                } else {
                     Log.d(TAG, "Socket closed intentionally.");
                }
            } finally {
                // 3. Cleanup and UI Reset
                isRunning = false;
                isStreaming = false;
                try {
                    if (outputStream != null) outputStream.close();
                    if (inputStream != null) inputStream.close();
                    if (socket != null && !socket.isClosed()) socket.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error during final cleanup", e);
                }

                // Reset UI
                mainHandler.post(() -> {
                    if (!tvStatus.getText().toString().contains("Error") && !tvStatus.getText().toString().contains("CRITICAL")) {
                        updateUIStatus("Disconnected.");
                    }
                    updateControlButtons(false, false);
                });
                communicationThread = null;
            }
        }
    }

    // Handles reading the 10-byte header and the full image frame.
    private void receiveImageFrame(boolean isContinuous) throws IOException {
        // Use a shorter timeout for continuous reading to quickly detect server disconnect
        if (isContinuous) {
            try {
                socket.setSoTimeout(2000); // 2 seconds timeout for continuous frames
            } catch (Exception e) { /* ignore */ }
        } else {
            // Use no timeout for single shot, rely on the status read timeout from main loop
            try {
                socket.setSoTimeout(0); // No timeout for blocking read
            } catch (Exception e) { /* ignore */ }
        }

        // Read the fixed-length size header
        byte[] headerBytes = readFullData(inputStream, SIZE_HEADER_LENGTH);
        String headerStr = new String(headerBytes, StandardCharsets.UTF_8).trim();
        int imageSize;
        
        try {
            imageSize = Integer.parseInt(headerStr);
        } catch (NumberFormatException e) {
            // This is the malformed header error!
            throw new IOException("Malformed image size header received: " + headerStr);
        }

        if (imageSize <= 0) {
            Log.w(TAG, "Received zero or negative image size, skipping frame.");
            return; 
        }

        // Read the actual image data
        byte[] imageBytes = readFullData(inputStream, imageSize);
        
        // Decode and display the image (on main thread)
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        displayBitmap(bitmap);
        
        // If single shot, explicitly reset timeout and isStreaming flag
        if (!isContinuous) {
            try {
                socket.setSoTimeout(500); // Back to waiting state timeout
            } catch (Exception e) { /* ignore */ }
        }
    }
}
