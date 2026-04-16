package com.murveit.tgcontrol;

/*****************************************************************
When you go to the tennis court:

1.Clear old logs (or just take note of the timestamp) before you start.
2.If it fails, do not panic. Just note the time.
3.When you get home, use the Device Explorer in Android Studio to pull
/sdcard/Android/data/com.murveit.tgcontrol/files/tgcontrol_logs.txt.

What you are looking for in that log:

•If you see SENT COMMAND: START_RECORDING followed immediately by IO Error
or Connection dropped, the Orin is likely crashing.

•If you see a long gap of RECV: STATUS_FRAMES... and then nothing,
but the phone still thinks it's recording, then Android's power management might
be "sleeping" your background thread despite the locks.
 *******************************************************************/

import android.content.Context;
import android.util.Log;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileLogger {
    private static final String LOG_FILE_NAME = "tgcontrol_logs.txt";

    // Standard log
    public static void log(Context context, String message) {
        log(context, message, null);
    }

    public static void log(Context context, String message, Throwable throwable) {
        String tag = "TGControl_Persistent";
        if (throwable != null) {
            Log.e(tag, message, throwable);
        } else {
            Log.d(tag, message);
        }

        File logFile = new File(context.getExternalFilesDir(null), LOG_FILE_NAME);
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            writer.append(timestamp).append(" : ").append(message);
            if (throwable != null) {
                writer.append(" | Error: ").append(throwable.toString());
            }
            writer.newLine();
        } catch (IOException e) {
            Log.e("FileLogger", "Failed to write log to file", e);
        }
    }
}
