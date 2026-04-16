package com.murveit.tgcontrol;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
// ADD THESE IMPORTS
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
// END IMPORTS
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    // 1. Define keys for SharedPreferences
    public static final String KEY_CONNECTION_TARGET = "connection_target_name";
    public static final String KEY_AE_LOCK = "ae_lock";
    public static final String KEY_AWB_LOCK = "awb_lock";
    public static final String KEY_EXPOSURE_LOW = "exposure_low";
    public static final String KEY_EXPOSURE_HIGH = "exposure_high";
    public static final String KEY_GAIN = "gain";
    public static final String KEY_DIGITAL_GAIN = "digital_gain";
    public static final String KEY_EXP_COMP_PROGRESS = "exp_comp_progress";

    // 2. Declare all the widgets
    private Spinner spnConnectionTarget; // ADD THIS
    private CheckBox cbAeLock;
    private CheckBox cbAwbLock;
    private EditText etExposureLow;
    private EditText etExposureHigh;
    private EditText etGain;
    private EditText etDigitalGain;
    private SeekBar sbExpComp;
    private TextView tvExpCompLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("");
        }

        // 3. Find all the views
        spnConnectionTarget = findViewById(R.id.spnConnectionTarget); // ADD THIS
        cbAeLock = findViewById(R.id.cbAeLock);
        cbAwbLock = findViewById(R.id.cbAwbLock);
        etExposureLow = findViewById(R.id.etExposureLow);
        TextView tvExposureLowSeconds = findViewById(R.id.tvExposureLowSeconds);
        setupNanoToSecondsWatcher(etExposureLow, tvExposureLowSeconds);

        etExposureHigh = findViewById(R.id.etExposureHigh);
        TextView tvExposureHighSeconds = findViewById(R.id.tvExposureHighSeconds);
        setupNanoToSecondsWatcher(etExposureHigh, tvExposureHighSeconds);

        etGain = findViewById(R.id.etGain);
        etDigitalGain = findViewById(R.id.etDigitalGain);
        sbExpComp = findViewById(R.id.sbExpComp);
        tvExpCompLabel = findViewById(R.id.tvExpCompLabel);

        Button btnClearLogs = findViewById(R.id.btnClearLogs);
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

        Button btnShareLogs = findViewById(R.id.btnShareLogs);
        btnShareLogs.setOnClickListener(v -> shareLogFile());

        // ADD THIS TO SETUP THE SPINNER
        // Create an ArrayAdapter using the string array and a default spinner layout
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.connection_options, android.R.layout.simple_spinner_item);
        // Specify the layout to use when the list of choices appears
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner
        spnConnectionTarget.setAdapter(adapter);

        // 4. Load saved values when the activity starts
        loadSettings();
        setupSeekBarListeners();
    }

    private void setupNanoToSecondsWatcher(EditText editText, TextView outputTextView) {
        editText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (s == null || s.length() == 0) {
                    outputTextView.setText("0.000 sec");
                    return;
                }

                try {
                    // Parse nanoseconds (long)
                    long nanoseconds = Long.parseLong(s.toString());

                    // Convert to seconds (double)
                    double seconds = nanoseconds / 1_000_000_000.0;

                    if (seconds > 0 && seconds < 0.001) {
                        // For very small non-zero values, show more precision
                        outputTextView.setText(String.format(java.util.Locale.US, "%.6f sec", seconds));
                    } else {
                        // Standard precision
                        outputTextView.setText(String.format(java.util.Locale.US, "%.3f sec", seconds));
                    }
                } catch (NumberFormatException e) {
                    outputTextView.setText("- sec");
                }
            }
        });
    }
    private void loadSettings() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        String savedTarget = prefs.getString(KEY_CONNECTION_TARGET, "Chico");
        ArrayAdapter<CharSequence> adapter = (ArrayAdapter<CharSequence>) spnConnectionTarget.getAdapter();
        if (adapter != null) {
            // Find the position of the saved string in the adapter
            int position = adapter.getPosition(savedTarget);
            // Set the spinner to that position. If not found, it defaults to 0.
            spnConnectionTarget.setSelection(position >= 0 ? position : 0);
        }
        cbAeLock.setChecked(prefs.getBoolean(KEY_AE_LOCK, false));
        cbAwbLock.setChecked(prefs.getBoolean(KEY_AWB_LOCK, false));
        etExposureLow.setText(String.valueOf(prefs.getLong(KEY_EXPOSURE_LOW, 10000L))); // Use long for exposure

        etExposureHigh.setText(String.valueOf(prefs.getLong(KEY_EXPOSURE_HIGH, 10000L))); // Use long for exposure
        etGain.setText(String.format(Locale.US, "%.1f", prefs.getFloat(KEY_GAIN, 1.0f)));
        etDigitalGain.setText(String.format(Locale.US, "%.1f", prefs.getFloat(KEY_DIGITAL_GAIN, 1.0f)));

        int expCompProgress = prefs.getInt(KEY_EXP_COMP_PROGRESS, 8);
        sbExpComp.setProgress(expCompProgress);
        float expCompValue = (expCompProgress - 8) * 0.25f;
        tvExpCompLabel.setText(String.format(Locale.US, "Exp Comp: %+1.2f", expCompValue));
    }

    private void saveSettings() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();

        editor.putString(KEY_CONNECTION_TARGET, spnConnectionTarget.getSelectedItem().toString());
        editor.putBoolean(KEY_AE_LOCK, cbAeLock.isChecked());
        editor.putBoolean(KEY_AWB_LOCK, cbAwbLock.isChecked());

        // Safely parse numbers from EditTexts, with defaults
        try {
            editor.putLong(KEY_EXPOSURE_LOW, Long.parseLong(etExposureLow.getText().toString()));
        } catch (NumberFormatException e) {
            editor.putLong(KEY_EXPOSURE_LOW, 10000L); // Default value
        }
        try {
            editor.putLong(KEY_EXPOSURE_HIGH, Long.parseLong(etExposureHigh.getText().toString()));
        } catch (NumberFormatException e) {
            editor.putLong(KEY_EXPOSURE_HIGH, 10000L); // Default value
        }
        try {
            editor.putFloat(KEY_GAIN, Float.parseFloat(etGain.getText().toString()));
        } catch (NumberFormatException e) {
            editor.putFloat(KEY_GAIN, 1.0f); // Default value
        }
        try {
            editor.putFloat(KEY_DIGITAL_GAIN, Float.parseFloat(etDigitalGain.getText().toString()));
        } catch (NumberFormatException e) {
            editor.putFloat(KEY_DIGITAL_GAIN, 1.0f); // Default value
        }

        editor.putInt(KEY_EXP_COMP_PROGRESS, sbExpComp.getProgress());

        editor.apply(); // Save asynchronously
    }


    private void setupSeekBarListeners() {
        sbExpComp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // The progress is 0-16, with 8 being the center (0.0)
                float value = (progress - 8) * 0.25f;
                tvExpCompLabel.setText(String.format(Locale.US, "Exp Comp: %+1.2f", value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Not needed for this app
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Not needed for this app
            }
        });
    }

    private void shareLogFile() {
        java.io.File logFile = new java.io.File(getExternalFilesDir(null), "tgcontrol_logs.txt");

        if (!logFile.exists() || logFile.length() == 0) {
            android.widget.Toast.makeText(this, "Log file is empty or not found", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // Compress the file first
            java.io.File compressedFile = getCompressedLogFile(logFile);

            if (compressedFile == null) {
                android.widget.Toast.makeText(this, "Failed to compress logs", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            // Create the URI for the .gz file instead of the .txt file
            android.net.Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "com.murveit.tgcontrol.fileprovider",
                    compressedFile);

            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            // Change type to gzip
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

        // Buffer for reading
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
        // 5. Save the settings whenever the user leaves the activity
        saveSettings();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
