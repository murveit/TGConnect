package com.murveit.tgcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.util.Pair;

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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            final String action = intent.getAction();
            if (ACTION_CONNECT.equals(action)) {
                String serverAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS);
                Log.d(TAG, "Connecting to: " + serverAddress);
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
        if (isRunning.get()) {
            Log.w(TAG, "Connection attempt while already running.");
            return;
        }
        isRunning.set(true);

        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);


        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("TGControl")
                .setContentText("Connected to TG Server")
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentIntent(pendingIntent)
                .build();
        if (notification == null) {
            return; // Can't continue if notification failed
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            // For older Android versions, the old method is still used.
            startForeground(1, notification);
        }

        communicationThread = new Thread(() -> {
            try {
                Log.d(TAG, "Connecting to " + serverAddress + " on port 8000...");
                statusData.postValue(new Pair<>("Status", "Connecting..."));
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(serverAddress, 8000), 5000);
                Log.d(TAG, "Connection successful.");

                outputStream = socket.getOutputStream();
                inputStream = socket.getInputStream();
                socket.setSoTimeout(500);
                statusData.postValue(new Pair<>("Connected", "Ready for command."));

                while (isRunning.get() && socket != null && !socket.isClosed()) {
                    try {
                    String serverMessage = readLineFromStream(inputStream);
                    if (serverMessage != null && !serverMessage.isEmpty()) {
                        if (serverMessage.startsWith("SERVER_STOP:")) {
                            // Server has forced the recording to stop
                            String reason = serverMessage.substring("SERVER_STOP:".length()).trim();
                            Log.w(TAG, "Server forced recording to stop. Reason: " + reason);
                            statusData.postValue(new Pair<>("SERVER_STOP", "Server stopped: " + reason));
                        } else if ("STATUS: CAPTURE_DONE; SENDING_IMAGES".equals(serverMessage)) {
                            statusData.postValue(new Pair<>("Status", "Receiving images..."));
                            try {
                                // Increase timeout for potentially slow image transfer
                                socket.setSoTimeout(5000);
                                receiveImageFrame("image1"); // Receive first image
                                receiveImageFrame("image2"); // Receive second image
                            } catch (IOException e) {
                                Log.e(TAG, "Error during multi-image reception.", e);
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
                            if (parts.length == 3) {
                                try {
                                    int framesProcessed = Integer.parseInt(parts[0].trim());
                                    int framesWritten = Integer.parseInt(parts[1].trim());
                                    float freeSpaceGb = Float.parseFloat(parts[2].trim()) / 1000.0f;

                                    // Hack to prevent us from filling up the disk.
                                    final int MIN_FREE_DISK_GB = 100;
                                    if (freeSpaceGb < MIN_FREE_DISK_GB) {
                                        Log.e(TAG, "Stopped the recording because the free disk space is low");
                                        statusData.postValue(new Pair<>("SERVER_STOP", "Stopping Server: Disk too full"));
                                        return;
                                    }

                                    long elapsedMillis = System.currentTimeMillis() - recordingStartTime;
                                    long seconds = (elapsedMillis / 1000) % 60;
                                    long minutes = (elapsedMillis / (1000 * 60)) % 60;
                                    String elapsedTime = String.format(Locale.US, "%02d:%02d", minutes, seconds);

                                    String framesStatus = String.format(Locale.US, "Frames: %5d %5d | Time %s | Free Disk %.1f Gb",
                                            framesProcessed, framesWritten, elapsedTime, freeSpaceGb);
                                    statusData.postValue(new Pair<>(null, framesStatus));

                                } catch (NumberFormatException e) {
                                    Log.e(TAG, "Failed to parse STATUS_FRAMES data: " + data, e);
                                    statusData.postValue(new Pair<>(null, "Error parsing frame data"));
                                }
                            }
                        } else if (serverMessage.startsWith("STATUS:")) {
                            String status = serverMessage.substring("STATUS:".length()).trim();
                            statusData.postValue(new Pair<>(null, status));
                        } else {
                            // For all other text messages, just post them to the UI
                            Log.d(TAG, "Service received: " + serverMessage);
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
                }
                }
            } catch (Exception e) {
                if (isRunning.get()) {
                    Log.e(TAG, "Connection failed or lost", e);
                    statusData.postValue(new Pair<>("Error", "Connection lost: " + e.getMessage()));
                }
            } finally {
                disconnect();
            }
        });
        communicationThread.start();
    }

    private void handleServerMessage(String message) {
        Log.d(TAG, "Service received: " + message);
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
            Log.e(TAG, "Cannot send command, not connected.");
            return;
        }
        new Thread(() -> {
            try {
                outputStream.write(command.getBytes(StandardCharsets.UTF_8));
                Log.d(TAG, "Service sent command: " + command.trim());
            } catch (Exception e) {
                Log.e(TAG, "Service failed to send command", e);
            }
        }).start();
    }

    private void disconnect() {
        Log.d(TAG, "Disconnecting...");
        isRunning.set(false);
        try {
            if (socket != null) {
                socket.close();
            }
            if (communicationThread != null) {
                communicationThread.interrupt();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during disconnection", e);
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
            Log.e(TAG, "Failed to receive image frame", e);
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnect();
    }
}
