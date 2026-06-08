package com.murveit.tgcontrol;

/**
 * Settings Activity - Algorithmic Overview
 *
 * This module provides a UI for configuring hardware-level camera parameters and network targets.
 * Note: Calibration launch functionality has been moved to the Tennis Menu in MainActivity.
 *
 * 1. INITIALIZATION:
 * - Inflates `activity_settings.xml` and binds UI widgets.
 * - Loads persistent user preferences using `SharedPreferences`.
 *
 * 2. CALLING PROCEDURE:
 * - Launched via an Intent from `MainActivity` when the gear icon is tapped.
 *
 * 3. INTERNAL ALGORITHMIC LOGIC:
 * - UI Sync: Translates raw numeric values from disk into user-readable strings (e.g., converting 
 * nanoseconds to seconds dynamically via a TextWatcher).
 * - Volatile Storage: Holds state changes in memory while the user interacts with EditTexts and SeekBars.
 * - Audio Toggles: Stores user preferences for voice-assisted Line Calls and Beeps.
 * - Disk Persistence: Overrides the `onPause()` lifecycle method to asynchronously commit (`apply()`) 
 * all active UI states back into `SharedPreferences` the moment the user backgrounds the activity.
 * - Log Management: Provides utility functions to read, compress (GZIP), and share the app's debug 
 * text logs using Android's FileProvider system.
 *
 * 4. EXPECTED OUTPUTS / SIDE EFFECTS:
 * - Writes configuration data to device storage, mutating the parameters used by `MainActivity`.
 * - Launches intents for external applications (e.g., Email, Drive) to share `.gz` log files.
 */

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    public static final String KEY_CONNECTION_TARGET = "connection_target_name";
    public static final String KEY_AE_LOCK = "ae_lock";
    public static final String KEY_AWB_LOCK = "awb_lock";
    public static final String KEY_EXPOSURE_LOW = "exposure_low";
    public static final String KEY_EXPOSURE_HIGH = "exposure_high";
    public static final String KEY_GAIN = "gain";
    public static final String KEY_DIGITAL_GAIN = "digital_gain";
    public static final String KEY_EXP_COMP_PROGRESS = "exp_comp_progress";
    
    // --- Audio Feedback Constants ---
    public static final String KEY_VOICE_CALLS = "voice_calls";
    public static final String KEY_BEEP_IN = "beep_in";
    public static final String KEY_SPEAK_MPH = "speak_mph";
    // When enabled, the Nano speaks calls directly via USB audio; app audio is suppressed.
    public static final String KEY_NANO_AUDIO = "nano_audio";
    public static final String KEY_NANO_AUDIO_SPEAK_MPH = "nano_audio_speak_mph";
    // Represents the user preference to enable verbose algorithmic tracking logs on the server
    public static final String KEY_ENABLE_LOGGING = "enable_logging";
    // Bypass the 12-hour TTL for offline or home testing
    public static final String KEY_DEBUG_CALIBRATION = "debug_calibration";
    // Use pre-captured canned images instead of live capture during calibration
    public static final String KEY_USE_CANNED_CALIBRATION = "use_canned_calibration";
    public static final String KEY_DEBUG_AUDIO = "debug_audio";
    public static final String KEY_DET_THRESH = "det_thresh";
    public static final String KEY_SERVE_THRESH = "serve_thresh";
    
    // Default algorithmic constant for serve pruning (90 = 0.90 threshold)
    private static final int DEFAULT_SERVE_THRESH = 90;


    private Spinner spnConnectionTarget;
    private CheckBox cbAeLock;
    private CheckBox cbAwbLock;
    private CheckBox cbNanoAudio;
    private CheckBox cbNanoAudioSpeakMph;
    private CheckBox cbVoiceCalls;
    private CheckBox cbBeepIn;
    private CheckBox cbSpeakMph;
    private CheckBox cbEnableLogging;
    private CheckBox cbDebugCalibration;
    private CheckBox cbUseCannedCalibration;
    private CheckBox cbDebugAudio;
    private EditText etExposureLow;
    private EditText etExposureHigh;
    private EditText etGain;
    private EditText etDigitalGain;
    private EditText etDetThresh;
    private EditText etServeThresh;
    private SeekBar sbExpComp;
    private TextView tvExpCompLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Bind the toolbar explicitly using its full path to avoid import errors
        // and set the navigation icon (the left arrow) to trigger the back action.
        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> onBackPressed());
        }

        spnConnectionTarget = findViewById(R.id.spnConnectionTarget);
        cbAeLock = findViewById(R.id.cbAeLock);
        cbAwbLock = findViewById(R.id.cbAwbLock);
        cbNanoAudio = findViewById(R.id.cbNanoAudio);
        cbNanoAudioSpeakMph = findViewById(R.id.cbNanoAudioSpeakMph);
        cbVoiceCalls = findViewById(R.id.cbVoiceCalls);
        cbBeepIn = findViewById(R.id.cbBeepIn);
        cbSpeakMph = findViewById(R.id.cbSpeakMph);
        cbEnableLogging = findViewById(R.id.cbEnableLogging);
        cbDebugCalibration = findViewById(R.id.cbDebugCalibration);
        cbUseCannedCalibration = findViewById(R.id.cbUseCannedCalibration);
        cbDebugAudio = findViewById(R.id.cbDebugAudio);

        etExposureLow = findViewById(R.id.etExposureLow);
        TextView tvExposureLowSeconds = findViewById(R.id.tvExposureLowSeconds);
        setupNanoToSecondsWatcher(etExposureLow, tvExposureLowSeconds);

        etExposureHigh = findViewById(R.id.etExposureHigh);
        TextView tvExposureHighSeconds = findViewById(R.id.tvExposureHighSeconds);
        setupNanoToSecondsWatcher(etExposureHigh, tvExposureHighSeconds);

        etGain = findViewById(R.id.etGain);
        etDigitalGain = findViewById(R.id.etDigitalGain);
        etDetThresh = findViewById(R.id.etDetThresh);
        etServeThresh = findViewById(R.id.etServeThresh);
        sbExpComp = findViewById(R.id.sbExpComp);
        tvExpCompLabel = findViewById(R.id.tvExpCompLabel);

        Button btnTestAudio = findViewById(R.id.btnTestAudio);
        if (btnTestAudio != null) {
            btnTestAudio.setEnabled(CommunicationService.isServerConnected && !CommunicationService.isTracking);
            btnTestAudio.setOnClickListener(v -> sendCommand("TEST_AUDIO\n"));
        }

        TextView tvAudioStatus = findViewById(R.id.tvAudioStatus);
        if (tvAudioStatus != null) {
            String last = CommunicationService.lastAudioStatus;
            tvAudioStatus.setText("Nano audio: " + (last != null ? last : "unknown"));
            CommunicationService.getStatusData().observe(this, pair -> {
                if (pair != null && "AUDIO_STATUS".equals(pair.first)) {
                    tvAudioStatus.setText("Nano audio: " + pair.second);
                }
                // Re-evaluate button state whenever connection or tracking status may have changed.
                if (btnTestAudio != null) {
                    btnTestAudio.setEnabled(CommunicationService.isServerConnected && !CommunicationService.isTracking);
                }
            });
        }

        Button btnClearLogs = findViewById(R.id.btnClearLogs);
        if (btnClearLogs != null) {
            btnClearLogs.setOnClickListener(v -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Clear Logs")
                        .setMessage("Are you sure you want to delete the debug log file? This cannot be undone.")
                        .setPositiveButton("Clear", (dialog, which) -> {
                            clearLogs();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }

        Button btnShareLogs = findViewById(R.id.btnShareLogs);
        if (btnShareLogs != null) {
            btnShareLogs.setOnClickListener(v -> shareLogFile());
        }

        if (spnConnectionTarget != null) {
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                    R.array.connection_options, android.R.layout.simple_spinner_item);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spnConnectionTarget.setAdapter(adapter);
        }

        loadSettings();
        setupSeekBarListeners();
    }

    private void setupNanoToSecondsWatcher(EditText editText, TextView outputTextView) {
        if (editText == null || outputTextView == null) return;
        
        editText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (s == null || s.length() == 0) {
                    outputTextView.setText("0.000 s");
                    return;
                }
                try {
                    long nanoseconds = Long.parseLong(s.toString());
                    double seconds = nanoseconds / 1_000_000_000.0;
                    if (seconds > 0 && seconds < 0.001) {
                        outputTextView.setText(String.format(java.util.Locale.US, "%.6f s", seconds));
                    } else {
                        outputTextView.setText(String.format(java.util.Locale.US, "%.3f s", seconds));
                    }
                } catch (NumberFormatException e) {
                    outputTextView.setText("- s");
                }
            }
        });
    }

    private void loadSettings() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (spnConnectionTarget != null) {
            String savedTarget = prefs.getString(KEY_CONNECTION_TARGET, "Chico");
            ArrayAdapter<CharSequence> adapter = (ArrayAdapter<CharSequence>) spnConnectionTarget.getAdapter();
            if (adapter != null) {
                int position = adapter.getPosition(savedTarget);
                spnConnectionTarget.setSelection(position >= 0 ? position : 0);
            }
        }
        
        if (cbAeLock != null) cbAeLock.setChecked(prefs.getBoolean(KEY_AE_LOCK, false));
        if (cbAwbLock != null) cbAwbLock.setChecked(prefs.getBoolean(KEY_AWB_LOCK, false));
        if (cbNanoAudio != null) cbNanoAudio.setChecked(prefs.getBoolean(KEY_NANO_AUDIO, false));
        if (cbNanoAudioSpeakMph != null) cbNanoAudioSpeakMph.setChecked(prefs.getBoolean(KEY_NANO_AUDIO_SPEAK_MPH, true));
        if (cbVoiceCalls != null) cbVoiceCalls.setChecked(prefs.getBoolean(KEY_VOICE_CALLS, false));
        if (cbBeepIn != null) cbBeepIn.setChecked(prefs.getBoolean(KEY_BEEP_IN, false));
        if (cbSpeakMph != null) cbSpeakMph.setChecked(prefs.getBoolean(KEY_SPEAK_MPH, false));
        if (cbEnableLogging != null) cbEnableLogging.setChecked(prefs.getBoolean(KEY_ENABLE_LOGGING, false));
        if (cbDebugCalibration != null) cbDebugCalibration.setChecked(prefs.getBoolean(KEY_DEBUG_CALIBRATION, false));
        if (cbUseCannedCalibration != null) cbUseCannedCalibration.setChecked(prefs.getBoolean(KEY_USE_CANNED_CALIBRATION, false));
        if (cbDebugAudio != null) cbDebugAudio.setChecked(prefs.getBoolean(KEY_DEBUG_AUDIO, false));

        if (etExposureLow != null) etExposureLow.setText(String.valueOf(prefs.getLong(KEY_EXPOSURE_LOW, 10000L)));
        if (etExposureHigh != null) etExposureHigh.setText(String.valueOf(prefs.getLong(KEY_EXPOSURE_HIGH, 10000L)));
        if (etGain != null) etGain.setText(String.format(Locale.US, "%.1f", prefs.getFloat(KEY_GAIN, 1.0f)));
        if (etDigitalGain != null) etDigitalGain.setText(String.format(Locale.US, "%.1f", prefs.getFloat(KEY_DIGITAL_GAIN, 1.0f)));
        if (etDetThresh != null) etDetThresh.setText(String.valueOf(prefs.getInt(KEY_DET_THRESH, 50)));
        if (etServeThresh != null) etServeThresh.setText(String.valueOf(prefs.getInt(KEY_SERVE_THRESH, DEFAULT_SERVE_THRESH)));

        if (sbExpComp != null && tvExpCompLabel != null) {
            int expCompProgress = prefs.getInt(KEY_EXP_COMP_PROGRESS, 8);
            sbExpComp.setProgress(expCompProgress);
            float expCompValue = (expCompProgress - 8) * 0.25f;
            tvExpCompLabel.setText(String.format(Locale.US, "Exp Comp: %+1.2f", expCompValue));
        }
    }

    private void saveSettings() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();

        if (spnConnectionTarget != null && spnConnectionTarget.getSelectedItem() != null) {
            editor.putString(KEY_CONNECTION_TARGET, spnConnectionTarget.getSelectedItem().toString());
        }
        if (cbAeLock != null) editor.putBoolean(KEY_AE_LOCK, cbAeLock.isChecked());
        if (cbAwbLock != null) editor.putBoolean(KEY_AWB_LOCK, cbAwbLock.isChecked());
        if (cbNanoAudio != null) editor.putBoolean(KEY_NANO_AUDIO, cbNanoAudio.isChecked());
        if (cbNanoAudioSpeakMph != null) editor.putBoolean(KEY_NANO_AUDIO_SPEAK_MPH, cbNanoAudioSpeakMph.isChecked());
        if (cbVoiceCalls != null) editor.putBoolean(KEY_VOICE_CALLS, cbVoiceCalls.isChecked());
        if (cbBeepIn != null) editor.putBoolean(KEY_BEEP_IN, cbBeepIn.isChecked());
        if (cbSpeakMph != null) editor.putBoolean(KEY_SPEAK_MPH, cbSpeakMph.isChecked());
        if (cbEnableLogging != null) editor.putBoolean(KEY_ENABLE_LOGGING, cbEnableLogging.isChecked());
        if (cbDebugCalibration != null) editor.putBoolean(KEY_DEBUG_CALIBRATION, cbDebugCalibration.isChecked());
        if (cbUseCannedCalibration != null) editor.putBoolean(KEY_USE_CANNED_CALIBRATION, cbUseCannedCalibration.isChecked());
        if (cbDebugAudio != null) editor.putBoolean(KEY_DEBUG_AUDIO, cbDebugAudio.isChecked());

        if (etExposureLow != null) {
            try {
                editor.putLong(KEY_EXPOSURE_LOW, Long.parseLong(etExposureLow.getText().toString()));
            } catch (NumberFormatException e) {
                editor.putLong(KEY_EXPOSURE_LOW, 10000L);
            }
        }
        
        if (etExposureHigh != null) {
            try {
                editor.putLong(KEY_EXPOSURE_HIGH, Long.parseLong(etExposureHigh.getText().toString()));
            } catch (NumberFormatException e) {
                editor.putLong(KEY_EXPOSURE_HIGH, 10000L);
            }
        }
        
        if (etGain != null) {
            try {
                editor.putFloat(KEY_GAIN, Float.parseFloat(etGain.getText().toString()));
            } catch (NumberFormatException e) {
                editor.putFloat(KEY_GAIN, 1.0f);
            }
        }
        
        if (etDigitalGain != null) {
            try {
                editor.putFloat(KEY_DIGITAL_GAIN, Float.parseFloat(etDigitalGain.getText().toString()));
            } catch (NumberFormatException e) {
                editor.putFloat(KEY_DIGITAL_GAIN, 1.0f);
            }
        }

        if (etDetThresh != null) {
            try {
                int thresh = Integer.parseInt(etDetThresh.getText().toString());
                editor.putInt(KEY_DET_THRESH, Math.max(0, Math.min(100, thresh)));
            } catch (NumberFormatException e) {
                editor.putInt(KEY_DET_THRESH, 50);
            }
        }

        if (etServeThresh != null) {
            try {
                int serveThresh = Integer.parseInt(etServeThresh.getText().toString());
                editor.putInt(KEY_SERVE_THRESH, Math.max(0, Math.min(100, serveThresh)));
            } catch (NumberFormatException e) {
                editor.putInt(KEY_SERVE_THRESH, DEFAULT_SERVE_THRESH);
            }
        }

        if (sbExpComp != null) {
            editor.putInt(KEY_EXP_COMP_PROGRESS, sbExpComp.getProgress());
        }
        
        editor.apply();
    }

    private void setupSeekBarListeners() {
        if (sbExpComp == null || tvExpCompLabel == null) return;
        
        sbExpComp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = (progress - 8) * 0.25f;
                tvExpCompLabel.setText(String.format(Locale.US, "Exp Comp: %+1.2f", value));
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void shareLogFile() {
        java.io.File logFile = new java.io.File(getExternalFilesDir(null), "tgcontrol_logs.txt");
        if (!logFile.exists() || logFile.length() == 0) {
            android.widget.Toast.makeText(this, "Log file is empty or not found", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            java.io.File compressedFile = getCompressedLogFile(logFile);
            if (compressedFile == null) {
                android.widget.Toast.makeText(this, "Failed to compress logs", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            android.net.Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                    this, "com.murveit.tgcontrol.fileprovider", compressedFile);

            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            intent.setType("application/gzip");
            intent.putExtra(android.content.Intent.EXTRA_SUBJECT, "TGControl Debug Logs (Compressed)");
            intent.putExtra(android.content.Intent.EXTRA_STREAM, contentUri);
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(android.content.Intent.createChooser(intent, "Send logs via..."));
        } catch (Exception e) {
            android.util.Log.e("Settings", "Error sharing log file", e);
            android.widget.Toast.makeText(this, "Error sharing logs", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private java.io.File getCompressedLogFile(java.io.File sourceFile) {
        java.io.File GzipFile = new java.io.File(getExternalFilesDir(null), "tgcontrol_logs.txt.gz");
        byte[] buffer = new byte[1024];
        try (FileInputStream fis = new FileInputStream(sourceFile);
             FileOutputStream fos = new FileOutputStream(GzipFile);
             GZIPOutputStream gzos = new GZIPOutputStream(fos)) {
            int len;
            while ((len = fis.read(buffer)) > 0) {
                gzos.write(buffer, 0, len);
            }
            gzos.finish();
            return GzipFile;
        } catch (IOException e) {
            android.util.Log.e("Settings", "Gzip compression failed", e);
            return null;
        }
    }

    private void clearLogs() {
        try {
            java.io.File logFile = new java.io.File(getExternalFilesDir(null), "tgcontrol_logs.txt");
            java.io.File gzipFile = new java.io.File(getExternalFilesDir(null), "tgcontrol_logs.txt.gz");
            if (logFile.exists()) logFile.delete();
            if (gzipFile.exists()) gzipFile.delete();
            android.widget.Toast.makeText(this, "Logs cleared", android.widget.Toast.LENGTH_SHORT).show();
            FileLogger.log(this, "--- Log cleared by user ---");
        } catch (Exception e) {
            android.util.Log.e("SettingsActivity", "Error clearing logs", e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettings();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    private void sendCommand(String command) {
        android.content.Intent intent = new android.content.Intent(this, CommunicationService.class);
        intent.setAction(CommunicationService.ACTION_SEND_COMMAND);
        intent.putExtra(CommunicationService.EXTRA_COMMAND, command);
        startService(intent);
    }
}
