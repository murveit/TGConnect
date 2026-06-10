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
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkRequest;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

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
 *   capability requirement to prevent Android from dropping the captive portal AP.
 * - Process Binding: Binds the entire app process to the Jetson's WiFi network (ignoring cellular).
 * - Socket Loop: Opens a TCP socket to port 8000. Continuously reads data chunks utilizing a
 *   persistent ByteArrayOutputStream to safely handle fragmented TCP packets and socket timeouts.
 * - Protocol Parsing: Routes string messages ("STATUS:", "STATUS_FRAMES:", "TRACK_EVENT_JSON:",
 *   "POINT_UPDATE_JSON:") and intercepts binary image transfers by reading fixed-length headers.
 *   POINT_UPDATE_JSON carries a mid-point build_point_summary() payload (partial=true) for
 *   real-time court graphics; it is always a full replacement, never a delta.
 * - Early Audio (tryPlayEarlyAudio): Called on the network receive thread when TRACK_EVENT_JSON
 *   arrives, before the LiveData post reaches the main thread. Handles two modes:
 *     SERVE_PRACTICE: In-serve → speaks MPH if in_serve=mph; Out/Fault → "Fault"; Let → "Let".
 *     SINGLES/DOUBLES: Out → "Out"; Fault → "Fault"; Let → "Let" if voice_calls on.
 *       However, for SINGLES/DOUBLES the preferred path is MainActivity.processInPointUpdate(),
 *       which fires audio concurrently with the PointVectorView update (on POINT_UPDATE_JSON,
 *       ~0.5–1.0 s earlier). When that path fires it stamps lastEarlyAudioFiredMs; this method
 *       skips if lastEarlyAudioFiredMs was set within the last 2 s to avoid double-play.
 *       Double-beep for non-Out terminals is deferred to the main thread in MainActivity.
 *   lastEarlyAudioFiredMs is stamped so the main-thread fallback can suppress duplicates.
 * - UI Delegation: Posts parsed data and Bitmaps to statically accessible `LiveData` objects.
 * - WiFi Grace Period: When onLost fires, a WIFI_LOSS_GRACE_PERIOD_MS timer starts instead of
 *   immediately disconnecting. If onAvailable fires within the window, the timer is cancelled
 *   and the socket is re-established automatically. If the timer expires, a full disconnect
 *   fires. During the grace period, disconnect() performs a partial teardown (closes socket
 *   only) so the network callback remains registered for the auto-reconnect.
 * - Lifecycle Management: onTaskRemoved ensures that swiping the app kills the service and
 *   closes the socket immediately, preventing "Zombie" connections.
 * - Data Invalidation: Explicitly clears imageData LiveData when starting new calibration or
 *   capture sequences to prevent "sticky" stale images from being shown to the user.
 *
 * 4. EXPECTED OUTPUTS / SIDE EFFECTS:
 * - Keeps the Android WiFi radio locked to the Jetson.
 * - Streams live data to the MainActivity via LiveData (statusData, imageData).
 * - Fires low-latency audio on the network receive thread via FastSpeechEngine.
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
    // Duration to wait for WiFi to recover before declaring a full disconnect.
    // Chosen to absorb transient blips (observed at ~12s) without forcing the user
    // through the full reconnect flow.
    private static final long WIFI_LOSS_GRACE_PERIOD_MS = 13000;

    private long recordingStartTime = 0;

    // --- Direct state exposure for UI gating ---
    public static boolean isServerConnected = false;
    public static boolean isRecording = false;
    public static boolean isTracking = false;
    public static String activeTennisMode = "SINGLES";
    public static String activeTennisTitle = "Singles Match";

    // --- Early audio: fires speech from the socket-reader thread, bypassing the 194ms UI-thread
    //     scheduling lag. Nulled on Activity destroy so no audio plays with no visible screen. ---
    private static volatile FastSpeechEngine earlyAudioEngine = null;
    // Tracks the last time the "miles per hour" suffix was spoken; avoids saying it every serve.
    // Package-private so the main-thread fallback in MainActivity can share the same tracker.
    static volatile long lastEarlySpokenMphTimeMs = 0;
    static final long EARLY_AUDIO_MPH_COOLDOWN_MS = 60000;
    // Set to currentTimeMillis() when tryPlayEarlyAudio fires so processTrackEventJson() can
    // detect that early audio already handled the event and skip its fallback speech call.
    static volatile long lastEarlyAudioFiredMs = 0;
    // True when the Nano is handling audio output directly; app audio is suppressed.
    static volatile boolean nanoAudioActive = false;

    // Last AUDIO_STATUS received from the Nano; persists across activity restarts.
    public static volatile String lastAudioStatus = null;

    public static void setEarlyAudioEngine(FastSpeechEngine engine) {
        earlyAudioEngine = engine;
    }

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
    
    // Algorithmic Fix: Persistent buffer to survive SocketTimeoutExceptions during slow TCP transfers
    private final ByteArrayOutputStream currentLineBuffer = new ByteArrayOutputStream();

    // Thread-safety lock for concurrent command dispatching
    private final Object sendLock = new Object();

    // Last server address saved for auto-reconnect after a WiFi grace period recovery.
    private String lastServerAddress = null;
    // Grace period: scheduled runnable fires a full disconnect if WiFi does not return.
    private final Handler wifiGraceHandler = new Handler(Looper.getMainLooper());
    private Runnable wifiGraceRunnable = null;
    // True during the window between onLost and either onAvailable or grace expiry.
    // Causes disconnect() to perform only a partial teardown (close socket, keep callback).
    private volatile boolean isInGracePeriod = false;

    // --- Public accessors for MainActivity to observe LiveData ---
    public static LiveData<Pair<String, String>> getStatusData() {
        return statusData;
    }

    public static LiveData<Pair<Bitmap, String>> getImageData() {
        return imageData;
    }

    /**
     * Returns true if the device's active network has a link-local address on the same /24
     * subnet as {@code serverAddress} (e.g. "10.42.0.1" → prefix "10.42.0."). Used by
     * MainActivity to gate the Connect button before a connection attempt is made.
     */
    public static boolean isOnServerNetwork(ConnectivityManager cm, String serverAddress) {
        if (cm == null || serverAddress == null || serverAddress.isEmpty()) return false;
        int lastDot = serverAddress.lastIndexOf('.');
        if (lastDot <= 0) return false;
        String expectedPrefix = serverAddress.substring(0, lastDot + 1);
        // Check all WiFi networks, not just the active network.  When the hotspot
        // has no internet, Android makes LTE the active network even though the
        // phone is connected to the hotspot WiFi, so getActiveNetwork() returns LTE.
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            LinkProperties lp = cm.getLinkProperties(network);
            if (lp == null) continue;
            for (LinkAddress la : lp.getLinkAddresses()) {
                String addr = la.getAddress().getHostAddress();
                if (addr != null && addr.startsWith(expectedPrefix)) return true;
            }
        }
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // 1. Initialize PowerManager WakeLock (CPU stays on)
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
                cancelGracePeriod();
                disconnect();
                stopSelf();
            } else if (ACTION_SEND_COMMAND.equals(action)) {
                String command = intent.getStringExtra(EXTRA_COMMAND);
                sendCommand(command);
            }
        }
        return START_STICKY; // start_not_sticky
    }

    /**
     * Triggered when the user swipes the app away from the Recents screen.
     * Overriding this ensures the Foreground Service doesn't become a "zombie".
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        FileLogger.log(this, "App swiped away. Shutting down service and connection.");
        cancelGracePeriod();
        disconnect();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    private void connect(String serverAddress) {
        FileLogger.log(CommunicationService.this, "Attempting to connect to: " + serverAddress);

        // Always cancel any active grace period first so the following cleanup uses
        // the full-disconnect path (isInGracePeriod=false).
        cancelGracePeriod();

        if (isRunning.get()) {
            FileLogger.log(CommunicationService.this, "Connection attempt while already running. Forcing reset.");
            disconnect();
        } else if (networkCallback != null) {
            // A grace-period partial disconnect left the old callback registered and
            // the locks held. Unregister and release before registering a new callback,
            // otherwise onAvailable fires twice and we get two communication threads.
            FileLogger.log(CommunicationService.this, "Cleaning up stale network callback from grace period.");
            if (connectivityManager != null) {
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback);
                } catch (Exception e) {
                    FileLogger.log(CommunicationService.this, "Stale callback already unregistered.");
                }
            }
            networkCallback = null;
            stopRecordingLocks();
        }

        isRunning.set(true);
        lastServerAddress = serverAddress;
        startRecordingLocks();

        // 1. Show Foreground Notification
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int pendingFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);

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

                // Log the IP addresses on this new network so we have a diagnostic trail
                // regardless of whether we proceed with reconnect.
                StringBuilder linkAddrLog = new StringBuilder("onAvailable link addresses:");
                LinkProperties lp = connectivityManager.getLinkProperties(network);
                if (lp != null) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        linkAddrLog.append(" ").append(la.getAddress().getHostAddress());
                    }
                } else {
                    linkAddrLog.append(" (LinkProperties null)");
                }
                FileLogger.log(CommunicationService.this, linkAddrLog.toString());

                // Derive the expected subnet prefix from the server address
                // (e.g. "10.42.0.1" → "10.42.0.") so the check adapts if the IP changes.
                String expectedPrefix = "";
                if (lastServerAddress != null) {
                    int lastDot = lastServerAddress.lastIndexOf('.');
                    if (lastDot > 0) {
                        expectedPrefix = lastServerAddress.substring(0, lastDot + 1);
                    }
                }

                // Verify the new network actually contains the server's subnet.
                // The NetworkRequest fires for ANY WiFi network; if the phone joined a
                // different AP (e.g. home router) during the grace period, we must not
                // attempt a reconnect on the wrong network.
                boolean isCorrectNetwork = false;
                if (lp != null && !expectedPrefix.isEmpty()) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        String addr = la.getAddress().getHostAddress();
                        if (addr != null && addr.startsWith(expectedPrefix)) {
                            isCorrectNetwork = true;
                            break;
                        }
                    }
                }
                FileLogger.log(CommunicationService.this,
                        "onAvailable: expectedPrefix=" + expectedPrefix
                        + " isCorrectNetwork=" + isCorrectNetwork);

                boolean wasInGracePeriod = isInGracePeriod;

                if (!isCorrectNetwork && wasInGracePeriod) {
                    // The phone joined a different AP, which means the Nano's hotspot is
                    // genuinely gone (not a brief blip). Waiting out the full grace period
                    // is pointless; cancel it and disconnect now so the user gets the
                    // disconnected UI immediately and can take action.
                    FileLogger.log(CommunicationService.this,
                            "onAvailable: wrong network during grace period — Nano hotspot gone. "
                            + "Cancelling grace period and disconnecting. server=" + lastServerAddress);
                    cancelGracePeriod(); // sets isInGracePeriod=false before disconnect()
                    disconnect();
                    stopSelf();
                    return;
                }

                // Cancel any pending grace period timer; the correct WiFi has returned.
                if (wifiGraceRunnable != null) {
                    wifiGraceHandler.removeCallbacks(wifiGraceRunnable);
                    wifiGraceRunnable = null;
                    FileLogger.log(CommunicationService.this,
                            "WiFi restored within grace period. Cancelling disconnect timer.");
                }
                isInGracePeriod = false;

                FileLogger.log(CommunicationService.this,
                        "WiFi Network Available. Binding process to WiFi. wasInGracePeriod=" + wasInGracePeriod);
                connectivityManager.bindProcessToNetwork(network);

                if (wasInGracePeriod) {
                    // Notify the UI so the disconnect overlay can be dismissed.
                    statusData.postValue(new Pair<>("WIFI_RESTORED", null));
                }

                // Wait a quarter-second for OS routing to settle, then start (or restart) the socket.
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (wasInGracePeriod && !isRunning.get() && lastServerAddress != null) {
                        // Socket died during grace period; re-establish connection.
                        FileLogger.log(CommunicationService.this,
                                "Reconnecting after WiFi restored. Server: " + lastServerAddress);
                        isRunning.set(true);
                        startRecordingLocks();
                        startCommunicationThread(lastServerAddress);
                    } else if (!wasInGracePeriod && isRunning.get()) {
                        startCommunicationThread(serverAddress);
                    }
                }, 250);
            }

            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                FileLogger.log(CommunicationService.this, "WiFi Network Lost. Starting " +
                        (WIFI_LOSS_GRACE_PERIOD_MS / 1000) + "s grace period before disconnecting.");
                connectivityManager.bindProcessToNetwork(null);

                // Enter grace period: socket will die via IOException but callback stays registered.
                isInGracePeriod = true;
                statusData.postValue(new Pair<>("WIFI_LOST", null));

                // Schedule a full disconnect if WiFi does not return within the grace window.
                wifiGraceRunnable = () -> {
                    FileLogger.log(CommunicationService.this, "WiFi grace period expired. Performing full disconnect.");
                    isInGracePeriod = false;
                    // Notify MainActivity so it exits the overlay and goes to STATE_DISCONNECTED.
                    // Use a message starting with "Disconnected" to hit the disconnect handler
                    // without triggering the AlertDialog (which "Error" status would do).
                    statusData.postValue(new Pair<>("Status", "Disconnected: WiFi not restored within grace period."));
                    disconnect();
                    stopSelf();
                };
                wifiGraceHandler.postDelayed(wifiGraceRunnable, WIFI_LOSS_GRACE_PERIOD_MS);
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
                
                // Track explicitly that we have fully connected hardware
                isServerConnected = true;
                currentLineBuffer.reset(); // Guarantee clean slate on new connection
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
                                
                            } else if ("STATUS: CALIBRATION_STARTED; SENDING_IMAGE".equals(serverMessage)) {
                                statusData.postValue(new Pair<>("Status", "Receiving baseline image..."));
                                try {
                                    socket.setSoTimeout(5000);
                                    receiveImageFrame("calibration_baseline");
                                } catch (IOException e) {
                                    FileLogger.log(CommunicationService.this, "Error receiving baseline image.", e);
                                } finally {
                                    socket.setSoTimeout(500);
                                }
                            } else if ("STATUS: PROCESS_COMPLETE; SENDING_VALIDATION_IMAGE".equals(serverMessage)) {
                                statusData.postValue(new Pair<>("Status", "Receiving validation image..."));
                                try {
                                    socket.setSoTimeout(5000);
                                    receiveImageFrame("calibration_validation");
                                } catch (IOException e) {
                                    FileLogger.log(CommunicationService.this, "Error receiving validation image.", e);
                                } finally {
                                    socket.setSoTimeout(500);
                                }
                                
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
                            } else if (serverMessage.startsWith("TRACK_EVENT_JSON:")) {
                                // Fire speech audio immediately on this background thread to bypass
                                // the ~194ms LiveData→UI-thread scheduling lag.
                                String jsonStr = serverMessage.substring("TRACK_EVENT_JSON:".length()).trim();
                                tryPlayEarlyAudio(jsonStr);
                                statusData.postValue(new Pair<>("TRACK_EVENT_JSON", jsonStr));
                            } else if (serverMessage.startsWith("POINT_UPDATE_JSON:")) {
                                // Mid-point trajectory update for SINGLES/DOUBLES court graphics.
                                // Sent whenever a new bounce is resolved; Android always replaces
                                // prior state (no merge).
                                String jsonStr = serverMessage.substring("POINT_UPDATE_JSON:".length()).trim();
                                statusData.postValue(new Pair<>("POINT_UPDATE_JSON", jsonStr));
                            } else {
                                // For all other text messages, just post them to the UI
                                String[] parts = serverMessage.split(":", 2);
                                String status = parts.length > 1 ? parts[0] : "Server";
                                String message = parts.length > 1 ? parts[1].trim() : serverMessage;
                                if ("AUDIO_STATUS".equals(status)) lastAudioStatus = message;
                                statusData.postValue(new Pair<>(status, message));
                            }
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        // This allows the loop to check the isRunning flag harmlessly.
                        // Because currentLineBuffer is now persistent, we don't lose fragmented streams.
                        continue;
                    } catch (IOException e) {
                        if (isRunning.get()) {
                            FileLogger.log(CommunicationService.this, "IO Error: Connection likely dropped by Orin.", e);
                            // During a WiFi grace period the socket death is expected — the overlay
                            // is already shown and we are waiting for the network to return.
                            // Posting "Error" here would trigger the AlertDialog and route the UI
                            // to STATE_DISCONNECTED, defeating the grace period entirely.
                            if (!isInGracePeriod) {
                                statusData.postValue(new Pair<>("Error", "Server dropped connection."));
                            }
                        } else {
                            FileLogger.log(CommunicationService.this, "IO Error: Socket closed intentionally during disconnect.");
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                if (isRunning.get()) {
                    FileLogger.log(CommunicationService.this, "Connection failed or lost", e);
                    if (!isInGracePeriod) {
                        statusData.postValue(new Pair<>("Error", "Connection lost: " + e.getMessage()));
                    }
                }
            } finally {
                disconnect();
            }
        });
        communicationThread.start();
    }

    private void tryPlayEarlyAudio(String jsonStr) {
        if (nanoAudioActive) return;  // Nano is speaking; suppress app audio.
        FastSpeechEngine engine = earlyAudioEngine;
        if (engine == null) return;

        boolean isServePractice   = "SERVE_PRACTICE".equals(activeTennisMode);
        boolean isSinglesDoubles  = "SINGLES".equals(activeTennisMode) || "DOUBLES".equals(activeTennisMode);
        if (!isServePractice && !isSinglesDoubles) return;

        try {
            org.json.JSONObject json = new org.json.JSONObject(jsonStr);
            String callStr    = json.optString("call_str", "").trim();
            String strikeType = json.optString("strike_type", "").trim();
            double mph = json.optDouble("speed_mph", 0.0);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean playVoice    = prefs.getBoolean(SettingsActivity.KEY_VOICE_CALLS, false);

            if (isServePractice) {
                String inServeAudio  = prefs.getString(SettingsActivity.KEY_IN_SERVE_AUDIO, "mute");
                // Only serves use the In-serve setting; non-serve In calls are always muted.
                boolean isServeCall  = "Serve".equalsIgnoreCase(strikeType);

                if ("In".equalsIgnoreCase(callStr)) {
                    if (isServeCall && "mph".equals(inServeAudio) && playVoice) {
                        int mphInt = (int) Math.round(mph);
                        String ttsMphStr;
                        long now = System.currentTimeMillis();
                        if (now - lastEarlySpokenMphTimeMs > EARLY_AUDIO_MPH_COOLDOWN_MS) {
                            ttsMphStr = mphInt + " miles per hour";
                            lastEarlySpokenMphTimeMs = now;
                        } else {
                            ttsMphStr = String.valueOf(mphInt);
                        }
                        lastEarlyAudioFiredMs = System.currentTimeMillis();
                        engine.speak(ttsMphStr);
                    }
                    // Beep mode stays on the main thread via toneGenerator — not handled here
                } else if ("Out".equalsIgnoreCase(callStr) || "Fault".equalsIgnoreCase(callStr)) {
                    if (playVoice) {
                        lastEarlyAudioFiredMs = System.currentTimeMillis();
                        playRawAudio(R.raw.fault);
                    }
                } else if ("Let".equalsIgnoreCase(callStr)) {
                    if (playVoice) {
                        lastEarlyAudioFiredMs = System.currentTimeMillis();
                        playRawAudio(R.raw.let);
                    }
                }
            } else {
                // SINGLES/DOUBLES: play pre-recorded WAV for Out/Fault/Let (zero TTS latency).
                // Double-beep for non-Out terminals stays on the main thread in processTrackEventJson.
                // Skip if processInPointUpdate already fired audio concurrently with the display
                // update — it sets lastEarlyAudioFiredMs to suppress this path as well.
                if (System.currentTimeMillis() - lastEarlyAudioFiredMs < 2000) return;
                if (playVoice) {
                    if ("Out".equalsIgnoreCase(callStr)) {
                        lastEarlyAudioFiredMs = System.currentTimeMillis();
                        playRawAudio(R.raw.out);
                    } else if ("Fault".equalsIgnoreCase(callStr)) {
                        lastEarlyAudioFiredMs = System.currentTimeMillis();
                        playRawAudio(R.raw.fault);
                    } else if ("Let".equalsIgnoreCase(callStr)) {
                        lastEarlyAudioFiredMs = System.currentTimeMillis();
                        playRawAudio(R.raw.let);
                    }
                }
            }
        } catch (Exception e) {
            FileLogger.log(this, "Early audio error", e);
        }
    }

    private void playRawAudio(int resId) {
        try {
            android.media.MediaPlayer mp = android.media.MediaPlayer.create(this, resId);
            if (mp == null) return;
            mp.setOnCompletionListener(android.media.MediaPlayer::release);
            mp.start();
        } catch (Exception e) {
            FileLogger.log(this, "playRawAudio error", e);
        }
    }

    private void sendCommand(String command) {
        if (command.startsWith("START_RECORDING")) {
            recordingStartTime = System.currentTimeMillis();
        }

        // Clear the image buffer whenever a command that requests a new image
        // is sent. This prevents "Sticky LiveData" from showing a stale bitmap from
        // the previous session while the new one is loading.
        if (command.startsWith("START_CALIBRATION") || command.startsWith("CAPTURE_PHOTO")) {
            imageData.postValue(null);
        }

        if (outputStream == null || socket == null || !socket.isConnected()) {
            FileLogger.log(CommunicationService.this, "Cannot send command, not connected.");
            return;
        }
        new Thread(() -> {
            try {
                // Ensure atomic string writing to prevent byte interleaving on the TCP socket
                synchronized (sendLock) {
                    outputStream.write(command.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                }
                FileLogger.log(CommunicationService.this, "Service sent command: " + command.trim());
            } catch (Exception e) {
                FileLogger.log(CommunicationService.this, "Service failed to send command", e);
            }
        }).start();
    }

    private void disconnect() {
        FileLogger.log(CommunicationService.this, "Disconnecting... (gracePeriod=" + isInGracePeriod + ")");
        isRunning.set(false);
        isServerConnected = false;

        // Clear the images on disconnect to ensure a clean slate for the next connection.
        imageData.postValue(null);

        if (!isInGracePeriod) {
            // Full disconnect: reset all state and release everything.
            isRecording = false;
            isTracking = false;
            stopRecordingLocks();

            if (connectivityManager != null && networkCallback != null) {
                connectivityManager.bindProcessToNetwork(null);
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback);
                } catch (Exception e) {
                    FileLogger.log(CommunicationService.this, "Callback already unregistered");
                }
            }
        }
        // Both partial and full: close the socket so the read loop exits.
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
            if (!isInGracePeriod) {
                stopForeground(true);
            }
        }
    }

    // --- Data Reading Methods ---
    private String readLineFromStream(InputStream is) throws IOException {
        int byteRead;
        while (isRunning.get()) {
            byteRead = is.read(); // Can throw SocketTimeoutException
            if (byteRead == -1) throw new IOException("End of stream");
            if (byteRead == '\n') break;
            currentLineBuffer.write(byteRead);
        }
        String completeLine = currentLineBuffer.toString(StandardCharsets.UTF_8.name()).trim();
        currentLineBuffer.reset(); // Wipe buffer only upon confirming a complete newline payload
        return completeLine;
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
            if (!isInGracePeriod) {
                statusData.postValue(new Pair<>("Error", "Failed to receive image."));
            }
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

    // Cancels any pending grace period runnable and resets the grace period flag.
    // Call before any intentional disconnect to ensure a full (not partial) teardown.
    private void cancelGracePeriod() {
        if (wifiGraceRunnable != null) {
            wifiGraceHandler.removeCallbacks(wifiGraceRunnable);
            wifiGraceRunnable = null;
            FileLogger.log(this, "Grace period cancelled (intentional disconnect).");
        }
        isInGracePeriod = false;
    }

    // Call this when you start your socket recording
    private void startRecordingLocks() {
        FileLogger.log(this, "startRecordingLocks: wakeLockHeld=" + (wakeLock != null && wakeLock.isHeld())
                + " wifiLockHeld=" + (wifiLock != null && wifiLock.isHeld()));
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(); // Keeps CPU awake
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            wifiLock.acquire(); // Keeps Wifi radio active
        }
    }

    // Call this when you stop recording or the socket closes
    private void stopRecordingLocks() {
        FileLogger.log(this, "stopRecordingLocks: wakeLockHeld=" + (wakeLock != null && wakeLock.isHeld())
                + " wifiLockHeld=" + (wifiLock != null && wifiLock.isHeld()));
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    @Override
    public void onDestroy() {
        cancelGracePeriod();
        stopRecordingLocks(); // Safety check
        super.onDestroy();
        disconnect();
    }
}
