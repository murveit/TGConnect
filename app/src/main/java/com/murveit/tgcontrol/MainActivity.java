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

import androidx.appcompat.app.AppCompatActivity;

import java.io.EOFException;
import java.io.IOException;
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

        // Clear the image display when starting a new connection
        mainHandler.post(() -> ivImage.setImageDrawable(null));

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
        mainHandler.post(() -> tvStatus.setText(message));
    }

    // Helper to safely display the received image on the main thread
    private void displayBitmap(final Bitmap bitmap) {
        mainHandler.post(() -> {
            if (bitmap != null) {
                ivImage.setImageBitmap(bitmap);
                tvStatus.setText("SUCCESS: Image received and displayed.");
            } else {
                tvStatus.setText("ERROR: Failed to decode image.");
            }
        });
    }

    // Helper to disable the connect button on the main thread
    private void disableConnectButton() {
        mainHandler.post(() -> {
            btnConnect.setEnabled(false);
            btnConnect.setText("CONNECTING..."); // Optional: Change text for better feedback
        });
    }

    // Helper to enable the connect button on the main thread
    private void enableConnectButton() {
        mainHandler.post(() -> {
            btnConnect.setEnabled(true);
            btnConnect.setText("CONNECT & START TRACKING"); // Optional: Reset text
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
                // The command is sent as raw bytes
                outputStream.write(command.getBytes(StandardCharsets.UTF_8));
                outputStream.flush(); // Ensure data is sent immediately
                Log.d(TAG, "Sent command: " + command);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send command", e);
                // This might mean the connection has dropped, you might want to trigger a disconnect/reconnect here.
            }
        }).start();
    }

    /**
     * Utility method to ensure all requested bytes are read from the InputStream.
     * This is critical for reading the fixed-length header and the image data block.
     */
    private byte[] readFullData(InputStream is, int length) throws IOException {
        byte[] buffer = new byte[length];
        int totalRead = 0;
        
        // Loop until we have read 'length' bytes
        while (totalRead < length) {
            // Read into the buffer, starting from the last position read (totalRead), 
            // for the remaining number of bytes (length - totalRead).
            int bytesRead = is.read(buffer, totalRead, length - totalRead);
            
            if (bytesRead == -1) {
                // End of stream reached unexpectedly
                throw new EOFException("Connection closed prematurely while reading data.");
            }
            totalRead += bytesRead;
        }
        return buffer;
    }

    /**
     * Reads a line of text (until a newline '\n') from the InputStream.
     * This is needed to correctly consume the variable-length status message from the server.
     */
    private String readLineFromStream(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        // Read byte by byte until a newline is found or EOF is reached
        while ((c = is.read()) != -1) {
            if (c == '\n') {
                break;
            }
            // Ignore carriage returns in case the server sends \r\n
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        // If we hit EOF before reading anything
        if (sb.length() == 0 && c == -1) {
             throw new EOFException("Connection closed while waiting for status line.");
        }
        return sb.toString().trim();
    }

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

                // This loop will run as long as the socket is connected and listens for data
                // Note: Based on the current server logic, this loop will likely run once and then exit 
                // when the server closes the connection after sending one image.
                while (socket != null && socket.isConnected() && !Thread.currentThread().isInterrupted()) {

                    // === 1. CONSUME STATUS MESSAGE ===
                    // Read the variable-length status message first.
                    String statusMessage = readLineFromStream(inputStream);
                    Log.d(TAG, "Received Status: " + statusMessage);
                    updateUIStatus("Status: " + statusMessage);
                    
                    // === 2. READ IMAGE DATA ===

                    // 2a. READ IMAGE SIZE HEADER (10 bytes)
                    byte[] headerBytes = readFullData(inputStream, SIZE_HEADER_LENGTH);
                    String header = new String(headerBytes, StandardCharsets.UTF_8).trim();

                    // 2b. PARSE IMAGE SIZE
                    int imageSize = 0;
                    try {
                        imageSize = Integer.parseInt(header);
                        if (imageSize <= 0) {
                            Log.e(TAG, "Received invalid image size: " + imageSize);
                            updateUIStatus("Error: Received invalid image size header.");
                            continue; // Skip to next loop iteration
                        }
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Failed to parse size header: " + header, e);
                        updateUIStatus("Error: Malformed image size header received.");
                        // Unrecoverable synchronization error, break the loop
                        break; 
                    }

                    Log.d(TAG, "Received image size: " + imageSize + " bytes.");
                    updateUIStatus("Receiving image of " + imageSize + " bytes...");

                    // 2c. READ IMAGE DATA (raw JPEG bytes)
                    byte[] imageBytes = readFullData(inputStream, imageSize);
                    Log.d(TAG, "Successfully read " + imageBytes.length + " bytes of image data.");

                    // 2d. DECODE BYTES TO BITMAP
                    // Use BitmapFactory to decode the raw JPEG byte array into a displayable bitmap
                    Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

                    // 2e. DISPLAY BITMAP
                    displayBitmap(bitmap);
                    
                    // Since the server closes the connection after sending the image, 
                    // we break the loop here to handle the expected disconnect gracefully.
                    //////break;
                }
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "Connection timed out. Assuming connection is lost.", e);
                updateUIStatus("Error: Connection lost (timeout).");
            } catch (EOFException e) {
                // Connection closed while reading the stream
                Log.d(TAG, e.getMessage(), e);
                updateUIStatus("Server disconnected.");
            } catch (Exception e) {
                // This will catch connection errors or errors during the read loop
                if (socket == null || !socket.isClosed()) {
                    Log.e(TAG, "Communication Error", e);
                    updateUIStatus("Connection Error: " + e.getMessage());
                } else {
                    // This happens when we called disconnectFromServer()
                    Log.d(TAG, "Socket was closed intentionally.");
                    updateUIStatus("Disconnected.");
                }
            } finally {
                // Ensure all streams and the socket are closed
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
                    if (!tvStatus.getText().toString().contains("Error") && !tvStatus.getText().toString().contains("Disconnected")) {
                        // Only reset status if it wasn't an error or intentional disconnect message
                        updateUIStatus("Ready.\nConnect to TennisGenius AP WiFi.");
                    }
                });
                communicationThread = null;
            }
        }
    }
}
