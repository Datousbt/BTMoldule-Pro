package com.datousbt.btmodulepro.ui;

import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.datousbt.btmodulepro.R;
import com.datousbt.btmodulepro.model.Config;
import com.datousbt.btmodulepro.storage.ConfigManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsActivity extends AppCompatActivity {

    // RSSI
    private RadioGroup rssiModeGroup;
    private RadioButton modePassive, modeActive, modeMixed;
    private TextInputEditText pollIntervalInput;
    private TextInputEditText aboveThresholdInput, belowThresholdInput;
    private TextInputEditText hysteresisInput, debounceInput;
    // 日志
    private SwitchMaterial logSwitch;
    private TextInputEditText logPathInput, logMaxSizeInput;

    private Button saveBtn;
    private Config config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        // RSSI
        rssiModeGroup = findViewById(R.id.rssi_mode_group);
        modePassive = findViewById(R.id.mode_passive);
        modeActive = findViewById(R.id.mode_active);
        modeMixed = findViewById(R.id.mode_mixed);
        pollIntervalInput = findViewById(R.id.poll_interval);
        aboveThresholdInput = findViewById(R.id.above_threshold);
        belowThresholdInput = findViewById(R.id.below_threshold);
        hysteresisInput = findViewById(R.id.hysteresis);
        debounceInput = findViewById(R.id.debounce);
        // 日志
        logSwitch = findViewById(R.id.log_switch);
        logPathInput = findViewById(R.id.log_path);
        logMaxSizeInput = findViewById(R.id.log_max_size);
        saveBtn = findViewById(R.id.save);

        config = ConfigManager.load(this);
        restore();

        saveBtn.setOnClickListener(v -> saveSettings());
    }

    private void restore() {
        switch (config.rssiMode) {
            case 0: modePassive.setChecked(true); break;
            case 1: modeActive.setChecked(true); break;
            default: modeMixed.setChecked(true); break;
        }
        pollIntervalInput.setText(String.valueOf(config.pollInterval));
        aboveThresholdInput.setText(String.valueOf(config.aboveThreshold));
        belowThresholdInput.setText(String.valueOf(config.belowThreshold));
        hysteresisInput.setText(String.valueOf(config.hysteresis));
        debounceInput.setText(String.valueOf(config.debounce));

        logSwitch.setChecked(config.logEnabled);
        logPathInput.setText(config.logPath);
        logMaxSizeInput.setText(String.valueOf(config.logMaxSizeKb));
    }

    private void saveSettings() {
        // RSSI 模式
        int id = rssiModeGroup.getCheckedRadioButtonId();
        if (id == R.id.mode_passive) config.rssiMode = 0;
        else if (id == R.id.mode_active) config.rssiMode = 1;
        else config.rssiMode = 2;

        config.pollInterval = clampInt(pollIntervalInput, 1000, 60000);
        config.aboveThreshold = parseInt(aboveThresholdInput, -60);
        config.belowThreshold = parseInt(belowThresholdInput, -80);
        config.hysteresis = clampInt(hysteresisInput, 0, 20);
        config.debounce = clampInt(debounceInput, 500, 300000);

        // 日志
        config.logEnabled = logSwitch.isChecked();
        String path = logPathInput.getText() != null ? logPathInput.getText().toString().trim() : "";
        if (!path.isEmpty()) config.logPath = path;
        config.logMaxSizeKb = clampInt(logMaxSizeInput, 100, 102400);

        ConfigManager.save(this, config);
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private int parseInt(TextInputEditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception ex) { return def; }
    }

    private int clampInt(TextInputEditText e, int min, int max) {
        int v = parseInt(e, min);
        if (v < min) v = min;
        if (v > max) v = max;
        return v;
    }
}
