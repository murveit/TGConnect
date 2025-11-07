// =========================================================================
// ANDROID CLIENT ACTIVITY (JAVA)
// Connects to the TennisGenius AP, sends a command, receives status text,
// and decodes a JPEG image stream for display.
//
// CRITICAL REQUIREMENTS:
//  Ensure the phone is connected to the Jetson's AP network.
// =========================================================================

package com.murveit.tgcontrol;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
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

    private Handler mainHandler;
    private Button btnConnect;
    private Button btnDisconnect;
    private Button btnStartTracking;
    private TextView tvStatus;
    private ImageView ivImage;

    private Socket socket;
    private Thread communicationThread = null; // We will store our thread here
    private OutputStream outputStream;
    private InputStream inputStream;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnStartTracking = findViewById(R.id.btnStartTracking);
        tvStatus = findViewById(R.id.tvStatus);
        ivImage = findViewById(R.id.ivImage);
        mainHandler = new Handler(Looper.getMainLooper());

        btnConnect.setOnClickListener(v -> {
            // Start connection logic
            connectToServer();
        });

        btnDisconnect.setOnClickListener(v -> {
            // Start disconnection logic
            disconnectFromServer();
        });

        btnStartTracking.setOnClickListener(v -> {
            sendCommand("START_TRACKING");
        });

        updateUIStatus("Ready.\nConnect to TennisGenius AP WiFi.");
    }

    private void connectToServer() {
        // Prevent multiple connection attempts
        if (communicationThread != null && communicationThread.isAlive()) {
            updateUIStatus("Already connecting or connected.");
            return;
        }

        // Start a new thread for connection and listening
        communicationThread = new Thread(new CommunicationTask());
        communicationThread.start();
    }

    private void disconnectFromServer() {
        // This method will close the socket, which will cause the listening thread to exit.
        try {
            if (socket != null && !socket.isClosed()) {
                // Closing the socket will interrupt the blocking read() call in the communication thread
                socket.close();
                // The thread will then naturally terminate.
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing socket on disconnect", e);
        }
        // No need to manually stop the thread. Let it finish cleanly.
    }

    // Helper to safely update UI elements from the background thread
    private void updateUIStatus(final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                tvStatus.setText(message);
            }
        });
    }

    // Helper to safely display the received image on the main thread
    private void displayBitmap(final Bitmap bitmap) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (bitmap != null) {
                    ivImage.setImageBitmap(bitmap);
                    tvStatus.setText("SUCCESS: Image received and displayed.");
                } else {
                    tvStatus.setText("ERROR: Failed to decode image.");
                }
            }
        });
    }

    // Helper to disable the connect button on the main thread
    private void disableConnectButton() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                btnConnect.setEnabled(false);
                btnConnect.setText("CONNECTING..."); // Optional: Change text for better feedback
            }
        });
    }

    // Helper to enable the connect button on the main thread
    private void enableConnectButton() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                btnConnect.setEnabled(true);
                btnConnect.setText("CONNECT & START TRACKING"); // Optional: Reset text
            }
        });
    }

    public void sendCommand(String command) {
        if (outputStream == null) {
            updateUIStatus("Not connected. Cannot send command.");
            return;
        }

        // Sending data must also be on a background thread
        new Thread(() -> {
            try {
                outputStream.write(command.getBytes(StandardCharsets.UTF_8));
                Log.d(TAG, "Sent command: " + command);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send command", e);
                // This might mean the connection has dropped, you might want to trigger a disconnect/reconnect here.
            }
        }).start();
    }

    // This is the new, long-lived CommunicationTask
    private class CommunicationTask implements Runnable {
        @Override
        public void run() {
            disableConnectButton(); // Disable connect button, show disconnect button

            try {
                updateUIStatus("Connecting to TennisGenius...");
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(TennisGenius_HOST, TennisGenius_PORT), CONNECTION_TIMEOUT_MS);
                Log.d(TAG, "Connected to TennisGenius.");

                socket.setSoTimeout(15000);
                outputStream = socket.getOutputStream();
                inputStream = socket.getInputStream();

                // Successfully connected, update UI
                mainHandler.post(() -> {
                    btnConnect.setVisibility(View.GONE);
                    btnDisconnect.setVisibility(View.VISIBLE);
                    btnStartTracking.setVisibility(View.VISIBLE);
                    updateUIStatus("Connected. Ready to send commands.");
                });

                // This loop will run as long as the socket is connected
                while (socket != null && socket.isConnected() && !Thread.currentThread().isInterrupted()) {

                    // Example: Re-implementing your image receiving logic inside the loop
                    // This assumes the server sends status and then an image *every time*

                    byte[] statusBytes = new byte[1024];
                    int statusRead = inputStream.read(statusBytes);
                    if (statusRead == -1) {
                        Log.d(TAG, "Server closed the connection.");
                        break;
                    }
                    String statusMessage = new String(statusBytes, 0, statusRead, StandardCharsets.UTF_8);
                    Log.d(TAG, "Received Status: " + statusMessage);
                    updateUIStatus("Status: " + statusMessage);

                    // Now read the image as before...
                    byte[] headerBytes = new byte[SIZE_HEADER_LENGTH];
                    inputStream.read(headerBytes);
                    // ... and so on for the rest of your image reading logic ...

                    // For a real app, you would have a more complex loop here that can
                    // handle different types of messages from the server.
                }
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "Connection timed out. Assuming connection is lost.", e);
                updateUIStatus("Error: Connection lost (timeout).");
            } catch (Exception e) {
                // This will catch connection timeouts, or errors during the read loop (like if the socket is closed)
                if (!socket.isClosed()) {
                    Log.e(TAG, "Communication Error", e);
                    updateUIStatus("Connection Error: " + e.getMessage());
                } else {
                    // This happens when we called disconnectFromServer()
                    Log.d(TAG, "Socket was closed intentionally.");
                    updateUIStatus("Disconnected.");
                }
            } finally {
                // Cleanup: Close streams and socket if they are still open
                try {
                    if (outputStream != null) outputStream.close();
                    if (inputStream != null) inputStream.close();
                    if (socket != null && !socket.isClosed()) socket.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error during final cleanup", e);
                }

                // Reset UI to initial state
                mainHandler.post(() -> {
                    btnConnect.setVisibility(View.VISIBLE);
                    btnDisconnect.setVisibility(View.GONE);
                    btnStartTracking.setVisibility(View.GONE);
                    enableConnectButton(); // Re-enable the connect button
                    if (tvStatus.getText().toString().contains("Error")) {
                        // Don't overwrite the error message
                    } else {
                        updateUIStatus("Ready.\nConnect to TennisGenius AP WiFi.");
                    }
                });
                communicationThread = null; // Clear the thread reference
            }
        }
    }
}