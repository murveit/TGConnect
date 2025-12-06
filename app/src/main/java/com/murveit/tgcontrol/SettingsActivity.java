package com.murveit.tgcontrol;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
// ADD THESE IMPORTS
import android.widget.ArrayAdapter;
import android.widget.Spinner;
// END IMPORTS
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

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
    public static final String KEY_JPEG_QUALITY = "jpeg_quality";
    public static final String KEY_EXP_COMP_PROGRESS = "exp_comp_progress";

    // 2. Declare all the widgets
    private Spinner spnConnectionTarget; // ADD THIS
    private CheckBox cbAeLock;
    private CheckBox cbAwbLock;
    private EditText etExposureLow;
    private EditText etExposureHigh;
    private EditText etGain;
    private EditText etDigitalGain;
    private SeekBar sbJpegQuality;
    private TextView tvJpegQualityLabel;
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
        etExposureHigh = findViewById(R.id.etExposureHigh);
        etGain = findViewById(R.id.etGain);
        etDigitalGain = findViewById(R.id.etDigitalGain);
        sbJpegQuality = findViewById(R.id.sbJpegQuality);
        tvJpegQualityLabel = findViewById(R.id.tvJpegQualityLabel);
        sbExpComp = findViewById(R.id.sbExpComp);
        tvExpCompLabel = findViewById(R.id.tvExpCompLabel);

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

        int jpegQuality = prefs.getInt(KEY_JPEG_QUALITY, 85);
        sbJpegQuality.setProgress(jpegQuality - 1);
        tvJpegQualityLabel.setText(String.format(Locale.US, "Quality: %d", jpegQuality));

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

        editor.putInt(KEY_JPEG_QUALITY, sbJpegQuality.getProgress() + 1);
        editor.putInt(KEY_EXP_COMP_PROGRESS, sbExpComp.getProgress());

        editor.apply(); // Save asynchronously
    }


    private void setupSeekBarListeners() {
        sbJpegQuality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // The progress is 0-99, but we want to display 1-100
                int quality = progress + 1;
                tvJpegQualityLabel.setText(String.format(Locale.US, "Quality: %d", quality));
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
