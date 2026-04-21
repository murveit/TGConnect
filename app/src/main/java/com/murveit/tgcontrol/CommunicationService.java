package com.murveit.tgcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkRequest;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Communication Service - Algorithmic Overview
 *
 * This foreground service manages the persistent TCP connection to the Jetson Orin Nano server,
 * ensuring communication stays alive while the app is in the background or screen is off.
 *
 * 1. INITIALIZATION (Parameters & Dependencies):
 * - Started via Intents.
 * - Acquires a `PowerManager.WakeLock` (CPU) and `WifiManager.WifiLock` (Radio) to prevent OS Doze.
 * - Dependencies: Android Network APIs, LiveData for UI syncing, TCP Sockets.
 *
 * 2. CALLING PROCEDURE:
 * - Start connection: Send Intent with `ACTION_CONNECT` and `EXTRA_SERVER_ADDRESS`.
 * - Send command: Send Intent with `ACTION_SEND_COMMAND` and `EXTRA_COMMAND`.
 * - Stop service: Send Intent with `ACTION_DISCONNECT`.
 *
 * 3. INTERNAL ALGORITHMIC LOGIC (Step-by-Step):
 * - Network Request: Requests a WiFi-only connection, explicitly removing the internet 
 * capability requirement to prevent Android from dropping the captive portal AP.
 * - Process Binding: Binds the entire app process to the Jetson's WiFi network (ignoring cellular).
 * - Socket Loop: Opens a TCP socket to port 8000. Continuously reads data chunks.
 * - Protocol Parsing: Routes string messages ("STATUS:", "STATUS_FRAMES:") and intercepts
 * binary image transfers by reading fixed-length headers.
 * - UI Delegation: Posts parsed data and Bitmaps to statically accessible `LiveData` objects.
 *
 * 4. EXPECTED OUTPUTS / SIDE EFFECTS:
 * - Keeps the Android WiFi radio locked to the Jetson.
 * - Streams live data to the MainActivity.
 * - Maintains a persistent Foreground Notification.
 */
public class CommunicationService extends Service {
    private static final String TAG = "CommunicationService";
    private static final String NOTIFICATION_CHANNEL_ID = "TGControlChannel";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_CONNECT = "com.murveit.tgcontrol.action.CONNECT";
    public static final String ACTION_DISCONNECT = "com.murveit.tgcontrol.action.DISCONNECT";
    public static final String ACTION_SEND_COMMAND = "com.murveit.tgcontrol.action.SEND_COMMAND";
    public static final String EXTRA_COMMAND = "com.murveit.tgcontrol.extra.COMMAND";
    public static final String EXTRA_SERVER_ADDRESS = "com.murveit.tgcontrol.extra.SERVER_ADDRESS";
    private static final int SIZE_HEADER_LENGTH = 10;
    private long recordingStartTime = 0;

    // --- LiveData for UI communication ---
    private static final MutableLiveData<Pair<String, String>> statusData = new MutableLiveData<>();
    private static final MutableLiveData<Pair<Bitmap, String>> imageData = new MutableLiveData<>();

    private Socket socket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private Thread communicationThread;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // --- Public accessors for MainActivity to observe LiveData ---
    public static LiveData<Pair<String, String>> getStatusData() {
        return statusData;
    }

    public static LiveData<Pair<Bitmap, String>> getImageData() {
        return imageData;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // 1. Initialize PowerManager WakeLock (CPU stays on)
        // Corrected the Context reference here
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TGControl:RecordingWakeLock");
        }

        // 2. Initialize WifiManager WifiLock (Wifi stays high-perf)
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            // Use WIFI_MODE_FULL_LOW_LATENCY for API 29+, WIFI_MODE_FULL_HIGH_PERF for older
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "TGControl:WifiLock");
            } else {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TGControl:WifiLock");
            }
            // Optional: some devices need this to ensure the lock isn't optimized away
            wifiLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            final String action = intent.getAction();
            if (ACTION_CONNECT.equals(action)) {
                String serverAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS);
                FileLogger.log(CommunicationService.this, "Connecting to: " + serverAddress);
                connect(serverAddress);
            } else if (ACTION_DISCONNECT.equals(action)) {
                disconnect();
                stopSelf();
            } else if (ACTION_SEND_COMMAND.equals(action)) {
                String command = intent.getStringExtra(EXTRA_COMMAND);
                sendCommand(command);
            }
        }
        return START_STICKY; // start_not_sticky
    }

    private void connect(String serverAddress) {
        FileLogger.log(CommunicationService.this, "Attempting to connect to: " + serverAddress);
        if (isRunning.get()) {
            FileLogger.log(CommunicationService.this, "Connection attempt while already running.");
            return;
        }
        isRunning.set(true);
        startRecordingLocks();

        // 1. Show Foreground Notification
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("TGControl")
                .setContentText("Connecting to WiFi...")
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentIntent(pendingIntent)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification);
        }

        // 2. Request WiFi specifically and bind to it
        requestWifiNetwork(serverAddress);
    }

    private void requestWifiNetwork(String serverAddress) {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        // Build a request for WiFi transport only
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                super.onAvailable(network);
                FileLogger.log(CommunicationService.this, "WiFi Network Available. Binding process to WiFi...");

                // FORCE the app to use this WiFi network, ignoring cellular data
                connectivityManager.bindProcessToNetwork(network);

                // Now that we are bound, start the actual communication thread
                // FIX: Wait a quarter-second for the OS routing to settle.
                // This prevents the "first-time drop" bug.
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (isRunning.get()) {
                        startCommunicationThread(serverAddress);
                    }
                }, 250);
            }

            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                FileLogger.log(CommunicationService.this, "WiFi Network Lost. Unbinding...");
                connectivityManager.bindProcessToNetwork(null);
                disconnect();
            }
        };

        connectivityManager.requestNetwork(request, networkCallback);
    }

    private void startCommunicationThread(String serverAddress) {
        communicationThread = new Thread(() -> {
            try {
                FileLogger.log(CommunicationService.this, "Connecting to " + serverAddress + " on port 8000...");
                statusData.postValue(new Pair<>("Status", "Connecting..."));

                socket = new Socket();
                // Because of bindProcessToNetwork, this will use WiFi even without internet
                socket.connect(new java.net.InetSocketAddress(serverAddress, 8000), 5000);

                FileLogger.log(CommunicationService.this, "Connection successful.");
                outputStream = socket.getOutputStream();
                inputStream = socket.getInputStream();
                socket.setSoTimeout(500);
                statusData.postValue(new Pair<>("Connected", "Ready for command."));

                while (isRunning.get() && socket != null && !socket.isClosed()) {
                    try {
                        String serverMessage = readLineFromStream(inputStream);
                        if (serverMessage != null && !serverMessage.isEmpty()) {
                            FileLogger.log(CommunicationService.this, "RECV: " + serverMessage);

                            if (serverMessage.startsWith("SERVER_STOP:")) {
                                // Server has forced the recording to stop
                                String reason = serverMessage.substring("SERVER_STOP:".length()).trim();
                                FileLogger.log(CommunicationService.this, "Server forced recording to stop. Reason: " + reason);
                                statusData.postValue(new Pair<>("SERVER_STOP", "Server stopped: " + reason));
                            } else if ("STATUS: CAPTURE_DONE; SENDING_IMAGES".equals(serverMessage)) {
                                statusData.postValue(new Pair<>("Status", "Receiving images..."));
                                try {
                                    // Increase timeout for potentially slow image transfer
                                    socket.setSoTimeout(5000);
                                    receiveImageFrame("image1"); // Receive first image
                                    receiveImageFrame("image2"); // Receive second image
                                } catch (IOException e) {
                                    FileLogger.log(CommunicationService.this, "Error during multi-image reception.", e);
                                    statusData.postValue(new Pair<>("Error", "Image transfer failed."));
                                    break; // Exit loop on critical error
                                } finally {
                                    // IMPORTANT: Reset the timeout back to normal
                                    socket.setSoTimeout(500);
                                }
                                statusData.postValue(new Pair<>("Status", "Image transfer complete."));
                            } else if (serverMessage.startsWith("STATUS_FRAMES:")) {
                                String data = serverMessage.substring("STATUS_FRAMES:".length()).trim();
                                String[] parts = data.split(",");
                                if (parts.length >= 3) {
                                    try {
                                        int framesProcessed = Integer.parseInt(parts[0].trim());
                                        int framesWritten = Integer.parseInt(parts[1].trim());
                                        float freeSpaceGb = Float.parseFloat(parts[2].trim()) / 1000.0f;

                                        // Note: Architectural Decoupling
                                        // The client-side MIN_FREE_DISK_GB logic has been removed here.
                                        // The Orin Nano server now autonomously monitors its own hardware limits.

                                        long elapsedSeconds = 0;
                                        if (parts.length >= 4) {
                                            // The server is the definitive source of truth for recording time
                                            elapsedSeconds = Long.parseLong(parts[3].trim());
                                        } else {
                                            // Fallback for older server versions
                                            long elapsedMillis = System.currentTimeMillis() - recordingStartTime;
                                            elapsedSeconds = elapsedMillis / 1000;
                                        }

                                        long seconds = elapsedSeconds % 60;
                                        long minutes = (elapsedSeconds / 60) % 60;
                                        long hours = elapsedSeconds / 3600;
                                        
                                        String elapsedTime;
                                        if (hours > 0) {
                                            elapsedTime = String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
                                        } else {
                                            elapsedTime = String.format(Locale.US, "%02d:%02d", minutes, seconds);
                                        }

                                        String framesStatus = String.format(Locale.US, "Frames: %5d %5d | Time %s | Free Disk %.1f Gb",
                                                framesProcessed, framesWritten, elapsedTime, freeSpaceGb);
                                        statusData.postValue(new Pair<>(null, framesStatus));

                                    } catch (NumberFormatException e) {
                                        FileLogger.log(CommunicationService.this, "Failed to parse STATUS_FRAMES data: " + data, e);
                                        statusData.postValue(new Pair<>(null, "Error parsing frame data"));
                                    }
                                }
                            } else if (serverMessage.startsWith("STATUS:")) {
                                String status = serverMessage.substring("STATUS:".length()).trim();
                                statusData.postValue(new Pair<>(null, status));
                            } else {
                                // For all other text messages, just post them to the UI
                                FileLogger.log(CommunicationService.this, "Service received: " + serverMessage);
                                String[] parts = serverMessage.split(":", 2);
                                String status = parts.length > 1 ? parts[0] : "Server";
                                String message = parts.length > 1 ? parts[1].trim() : serverMessage;
                                statusData.postValue(new Pair<>(status, message));
                            }
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        // --- THIS IS THE MISSING PIECE ---
                        // This is normal and expected. It allows the loop to check the isRunning flag.
                        // We simply 'continue' to the next iteration of the while loop.
                        continue;
                    } catch (IOException e) {
                        FileLogger.log(CommunicationService.this, "IO Error: Connection likely dropped by Orin.", e);
                        // Instead of just logging, trigger a full reset
                        statusData.postValue(new Pair<>("Error", "Server dropped connection."));
                        break;
                    }
                }
            } catch (Exception e) {
                if (isRunning.get()) {
                    FileLogger.log(CommunicationService.this, "Connection failed or lost", e);
                    statusData.postValue(new Pair<>("Error", "Connection lost: " + e.getMessage()));
                }
            } finally {
                disconnect();
            }
        });
        communicationThread.start();
    }

    private void handleServerMessage(String message) {
        FileLogger.log(CommunicationService.this, "Service received: " + message);
        statusData.postValue(new Pair<>("Server:", message));

        if ("START_IMG_1".equals(message)) {
            receiveImageFrame("image1");
        } else if ("START_IMG_2".equals(message)) {
            receiveImageFrame("image2");
        }
        // Add other message handlers here (e.g., for recording progress)
    }

    private void sendCommand(String command) {
        if (command.startsWith("START_RECORDING")) {
            recordingStartTime = System.currentTimeMillis();
        }
        if (outputStream == null || socket == null || !socket.isConnected()) {
            FileLogger.log(CommunicationService.this, "Cannot send command, not connected.");
            return;
        }
        new Thread(() -> {
            try {
                outputStream.write(command.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                FileLogger.log(CommunicationService.this, "Service sent command: " + command.trim());
            } catch (Exception e) {
                FileLogger.log(CommunicationService.this, "Service failed to send command", e);
            }
        }).start();
    }

    private void disconnect() {
        FileLogger.log(CommunicationService.this, "Disconnecting...");
        isRunning.set(false);
        
        // Crucial: Release locks when the connection drops or is closed
        stopRecordingLocks();

        // Release WiFi Binding
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.bindProcessToNetwork(null);
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                FileLogger.log(CommunicationService.this, "Callback already unregistered");
            }
        }
        try {
            if (socket != null) {
                socket.close();
            }
            if (communicationThread != null) {
                communicationThread.interrupt();
            }
        } catch (Exception e) {
            FileLogger.log(CommunicationService.this, "Error during disconnection", e);
        } finally {
            socket = null;
            outputStream = null;
            inputStream = null;
            communicationThread = null;
            stopForeground(true);
        }
    }

    // --- Data Reading Methods ---
    private String readLineFromStream(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int byteRead;
        while (isRunning.get()) {
            byteRead = is.read();
            if (byteRead == -1) throw new IOException("End of stream");
            if (byteRead == '\n') break;
            buffer.write(byteRead);
        }
        return buffer.toString(StandardCharsets.UTF_8.name()).trim();
    }

    private byte[] readFullData(InputStream is, int length) throws IOException {
        byte[] data = new byte[length];
        int bytesRead = 0;
        while (bytesRead < length) {
            int result = is.read(data, bytesRead, length - bytesRead);
            if (result == -1) throw new IOException("End of stream while reading data");
            bytesRead += result;
        }
        return data;
    }

    private void receiveImageFrame(String imageTarget) {
        try {
            byte[] headerBytes = readFullData(inputStream, SIZE_HEADER_LENGTH);
            String headerStr = new String(headerBytes, StandardCharsets.UTF_8).trim();
            int imageSize = Integer.parseInt(headerStr);

            if (imageSize > 0) {
                byte[] imageBytes = readFullData(inputStream, imageSize);
                Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                imageData.postValue(new Pair<>(bitmap, imageTarget)); // Post image to LiveData
            }
        } catch (Exception e) {
            FileLogger.log(CommunicationService.this, "Failed to receive image frame", e);
            statusData.postValue(new Pair<>("Error", "Failed to receive image."));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "TGControl Connection",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            serviceChannel.setDescription("Shows when connected to the Tennis Genius server.");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Call this when you start your socket recording
    private void startRecordingLocks() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(); // Keeps CPU awake
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            wifiLock.acquire(); // Keeps Wifi radio active
        }
    }

    // Call this when you stop recording or the socket closes
    private void stopRecordingLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    @Override
    public void onDestroy() {
        stopRecordingLocks(); // Safety check
        super.onDestroy();
        disconnect();
    }
}
