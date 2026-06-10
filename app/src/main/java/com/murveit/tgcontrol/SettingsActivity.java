package com.murveit.tgcontrol;

/**
 * Settings Activity - Algorithmic Overview
 *
 * This module provides a UI for configuring hardware-level camera parameters and network targets.
 * Note: Calibration launch functionality has been moved to the Tennis Menu in MainActivity.
 *
 * 1. INITIALIZATION:
 * - Inflates `activity_settings.xml` and binds UI widgets including the new audio controls.
 * - Loads persistent user preferences using `SharedPreferences`.
 *
 * 2. CALLING PROCEDURE:
 * - Launched via an Intent from `MainActivity` when the gear icon is tapped.
 *
 * 3. INTERNAL ALGORITHMIC LOGIC:
 * - UI Sync: Translates raw numeric values from disk into user-readable strings (e.g., converting
 *   nanoseconds to seconds dynamically via a TextWatcher).
 * - Volatile Storage: Holds state changes in memory while the user interacts with EditTexts and
 *   SeekBars.
 * - Audio Controls (all modes):
 *     Nano Audio checkbox: when on, the Nano generates audio; app audio is suppressed.
 *     Voice Calls checkbox: master on/off for spoken calls ("Out.", "Fault.", "Let."). Applies
 *       to both SERVE_PRACTICE and SINGLES/DOUBLES.
 *     In Serves radio (MPH / Beep / Mute): controls In-serve audio in SERVE_PRACTICE only.
 *       In SINGLES/DOUBLES, in-serves are treated as in-calls (no MPH readout).
 *   SINGLES/DOUBLES-specific controls:
 *     End of Point Beeps checkbox: when on, plays a double-beep at the end of a point whenever
 *       no voice call was spoken (i.e. the terminal event was an In bounce such as a double
 *       bounce, or Voice Calls is off).
 *     In-Point Beeps checkbox: when on, plays a single beep each time a new mid-rally bounce
 *       is confirmed (driven by POINT_UPDATE_JSON from the server). Android-side only; no
 *       Nano counterpart.
 * - Live Sync: setupAudioListeners() attaches onChange callbacks to all audio controls so that
 *   sendAudioSettings() fires immediately on any change, sending SET_NANO_AUDIO to the server
 *   without requiring a tracking restart. The command carries:
 *     nano_audio=0/1, voice_calls=0/1, in_serve=mph/beep/mute, end_of_point_beeps=0/1.
 * - Disk Persistence: onPause() asynchronously commits all UI states to SharedPreferences.
 * - Log Management: Provides utility functions to read, compress (GZIP), and share the app's
 *   debug text logs using Android's FileProvider system.
 *
 * 4. EXPECTED OUTPUTS / SIDE EFFECTS:
 * - Writes configuration data to device storage, mutating the parameters used by `MainActivity`.
 * - Immediately sends SET_NANO_AUDIO to the Nano whenever any audio control changes.
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
    // Unified In-serve audio mode for both phone and Nano: "mph", "beep", or "mute".
    public static final String KEY_IN_SERVE_AUDIO = "in_serve_audio";
    // When enabled, the Nano speaks calls directly via USB audio; app audio is suppressed.
    public static final String KEY_NANO_AUDIO = "nano_audio";
    // SINGLES/DOUBLES: plays a double-beep when a point ends without a spoken Out/Fault/Let.
    public static final String KEY_END_OF_POINT_BEEPS = "end_of_point_beeps";
    // SINGLES/DOUBLES: plays a single beep each time a new mid-rally bounce is confirmed.
    public static final String KEY_IN_CALLS = "in_calls";
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
    private CheckBox cbVoiceCalls;
    private CheckBox cbEndOfPointBeeps;
    private CheckBox cbInCalls;
    private android.widget.RadioGroup rgInServe;
    private CheckBox cbEnableLogging;
    private boolean isFakeCallsActive = false;
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
        cbVoiceCalls = findViewById(R.id.cbVoiceCalls);
        cbEndOfPointBeeps = findViewById(R.id.cbEndOfPointBeeps);
        cbInCalls = findViewById(R.id.cbInCalls);
        rgInServe = findViewById(R.id.rgInServe);
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

        Button btnInsertFakeCalls = findViewById(R.id.btnInsertFakeCalls);
        if (btnInsertFakeCalls != null) {
            btnInsertFakeCalls.setEnabled(CommunicationService.isTracking);
            btnInsertFakeCalls.setOnClickListener(v -> {
                isFakeCallsActive = !isFakeCallsActive;
                sendCommand("SET_AUDIO_TEST:" + (isFakeCallsActive ? "1" : "0") + "\n");
                btnInsertFakeCalls.setText(isFakeCallsActive ? "Stop Fake Calls" : "Insert Fake Calls");
            });
        }

        TextView tvAudioStatus = findViewById(R.id.tvAudioStatus);
        if (tvAudioStatus != null) {
            String last = CommunicationService.lastAudioStatus;
            tvAudioStatus.setText("Nano audio: " + (last != null ? last : "unknown"));
            CommunicationService.getStatusData().observe(this, pair -> {
                if (pair != null && "AUDIO_STATUS".equals(pair.first)) {
                    tvAudioStatus.setText("Nano audio: " + pair.second);
                }
                // Re-evaluate button states whenever connection or tracking status may have changed.
                if (btnTestAudio != null) {
                    btnTestAudio.setEnabled(CommunicationService.isServerConnected && !CommunicationService.isTracking);
                }
                if (btnInsertFakeCalls != null) {
                    boolean tracking = CommunicationService.isTracking;
                    btnInsertFakeCalls.setEnabled(tracking);
                    if (!tracking && isFakeCallsActive) {
                        // Tracking stopped — reset button state
                        isFakeCallsActive = false;
                        btnInsertFakeCalls.setText("Insert Fake Calls");
                    }
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
        setupAudioListeners();
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
                    outputTextView.setText("- s");
                    return;
                }
                try {
                    long nanoseconds = Long.parseLong(s.toString());
                    if (nanoseconds <= 0) {
                        outputTextView.setText("- s");
                    } else if (nanoseconds >= 1_000_000_000L) {
                        long wholeSeconds = nanoseconds / 1_000_000_000L;
                        outputTextView.setText(wholeSeconds + " s");
                    } else {
                        long denom = Math.round(1_000_000_000.0 / nanoseconds);
                        outputTextView.setText("1/" + denom + " s");
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
        if (cbVoiceCalls != null) cbVoiceCalls.setChecked(prefs.getBoolean(KEY_VOICE_CALLS, false));
        if (cbEndOfPointBeeps != null) cbEndOfPointBeeps.setChecked(prefs.getBoolean(KEY_END_OF_POINT_BEEPS, false));
        if (cbInCalls != null) cbInCalls.setChecked(prefs.getBoolean(KEY_IN_CALLS, false));
        if (rgInServe != null) {
            String inServeAudio = prefs.getString(KEY_IN_SERVE_AUDIO, "mute");
            if ("mph".equals(inServeAudio))       rgInServe.check(R.id.rbInServeMph);
            else if ("beep".equals(inServeAudio)) rgInServe.check(R.id.rbInServeBeep);
            else                                   rgInServe.check(R.id.rbInServeMute);
        }
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
        if (cbVoiceCalls != null) editor.putBoolean(KEY_VOICE_CALLS, cbVoiceCalls.isChecked());
        if (cbEndOfPointBeeps != null) editor.putBoolean(KEY_END_OF_POINT_BEEPS, cbEndOfPointBeeps.isChecked());
        if (cbInCalls != null) editor.putBoolean(KEY_IN_CALLS, cbInCalls.isChecked());
        if (rgInServe != null) {
            String inServeAudio = "mute";
            int checked = rgInServe.getCheckedRadioButtonId();
            if (checked == R.id.rbInServeMph)       inServeAudio = "mph";
            else if (checked == R.id.rbInServeBeep) inServeAudio = "beep";
            editor.putString(KEY_IN_SERVE_AUDIO, inServeAudio);
        }
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

    private void sendAudioSettings() {
        boolean nanoAudio        = cbNanoAudio != null && cbNanoAudio.isChecked();
        boolean voiceCalls       = cbVoiceCalls != null && cbVoiceCalls.isChecked();
        boolean endOfPointBeeps  = cbEndOfPointBeeps != null && cbEndOfPointBeeps.isChecked();
        String inServeAudio = "mute";
        if (rgInServe != null) {
            int checked = rgInServe.getCheckedRadioButtonId();
            if (checked == R.id.rbInServeMph)       inServeAudio = "mph";
            else if (checked == R.id.rbInServeBeep) inServeAudio = "beep";
        }
        // Keep the in-process flag in sync immediately; SharedPreferences are only
        // written on onPause(), so MainActivity's pref-listener fires too late.
        CommunicationService.nanoAudioActive = nanoAudio;
        if (!CommunicationService.isServerConnected) return;
        sendCommand("SET_NANO_AUDIO:" + (nanoAudio ? "1" : "0")
                + ",voice_calls=" + (voiceCalls ? "1" : "0")
                + ",in_serve=" + inServeAudio
                + ",end_of_point_beeps=" + (endOfPointBeeps ? "1" : "0") + "\n");
    }

    private void setupAudioListeners() {
        // Listeners fire immediately when a control changes so the server reflects
        // the new state without requiring a tracking restart.
        // Set up AFTER loadSettings() to avoid spurious sends during initialisation.
        android.widget.CompoundButton.OnCheckedChangeListener audioListener =
                (btn, isChecked) -> sendAudioSettings();
        if (cbNanoAudio != null)         cbNanoAudio.setOnCheckedChangeListener(audioListener);
        if (cbVoiceCalls != null)        cbVoiceCalls.setOnCheckedChangeListener(audioListener);
        if (cbEndOfPointBeeps != null)   cbEndOfPointBeeps.setOnCheckedChangeListener(audioListener);
        if (rgInServe != null)           rgInServe.setOnCheckedChangeListener((group, checkedId) -> sendAudioSettings());
    }

    private void sendCommand(String command) {
        android.content.Intent intent = new android.content.Intent(this, CommunicationService.class);
        intent.setAction(CommunicationService.ACTION_SEND_COMMAND);
        intent.putExtra(CommunicationService.EXTRA_COMMAND, command);
        startService(intent);
    }
}
