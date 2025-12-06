package com.murveit.tgcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import java.util.concurrent.atomic.AtomicBoolean;

public class CommunicationService extends Service {
    private static final String TAG = "CommunicationService";
    public static final String ACTION_CONNECT = "com.murveit.tgcontrol.action.CONNECT";
    public static final String ACTION_DISCONNECT = "com.murveit.tgcontrol.action.DISCONNECT";
    public static final String ACTION_SEND_COMMAND = "com.murveit.tgcontrol.action.SEND_COMMAND";
    public static final String EXTRA_COMMAND = "com.murveit.tgcontrol.extra.COMMAND";
    public static final String EXTRA_SERVER_ADDRESS = "com.murveit.tgcontrol.extra.SERVER_ADDRESS";
    public static final String NOTIFICATION_CHANNEL_ID = "CommunicationChannel";

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
                connect(serverAddress);
            } else if (ACTION_DISCONNECT.equals(action)) {
                disconnect();
                stopSelf();
            } else if (ACTION_SEND_COMMAND.equals(action)) {
                String command = intent.getStringExtra(EXTRA_COMMAND);
                sendCommand(command);
            }
        }
        return START_NOT_STICKY;
    }

    private void connect(String serverAddress) {
        if (isRunning.get()) {
            Log.w(TAG, "Connection attempt while already running.");
            return;
        }

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("TGControl")
                .setContentText("Connecting to server...")
                .setSmallIcon(R.drawable.ic_launcher_foreground) // Use your app's icon
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            // For older Android versions, the old method is still used.
            startForeground(1, notification);
        }

        isRunning.set(true);
        communicationThread = new Thread(() -> {
            try {
                Log.d(TAG, "Connecting to " + serverAddress + " on port 8000...");
                socket = new Socket(serverAddress, 8000);
                outputStream = socket.getOutputStream();
                inputStream = socket.getInputStream();
                statusData.postValue(new Pair<>("Connected", "Ready for command."));

                while (isRunning.get() && socket != null && !socket.isClosed()) {
                    String serverMessage = readLineFromStream(inputStream);
                    if (serverMessage != null && !serverMessage.isEmpty()) {
                        handleServerMessage(serverMessage);
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
            byte[] headerBytes = readFullData(inputStream, 10);
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
                    "TGControl Communication Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            getSystemService(NotificationManager.class).createNotificationChannel(serviceChannel);
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
