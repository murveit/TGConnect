package com.murveit.tgcontrol;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.appbar.MaterialToolbar;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // 3. Now get the action bar and configure it
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true); // This shows the back arrow
        }
        // We will add logic here later to read values from these controls.
        // For now, it just displays them.
    }
    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed(); // This is the standard action to take (which means "go back")
        return true;
    }
}
