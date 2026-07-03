package com.datousbt.btmodulepro.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.datousbt.btmodulepro.R;
import com.datousbt.btmodulepro.model.Config;
import com.datousbt.btmodulepro.storage.ConfigManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsActivity
        extends AppCompatActivity {

    private RadioGroup rssiModeGroup;
    private RadioButton modePassive, modeActive, modeMixed;
    private TextInputEditText pollIntervalInput;
    private Button saveBtn;
    private Config config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        rssiModeGroup = findViewById(R.id.rssi_mode_group);
        modePassive = findViewById(R.id.mode_passive);
        modeActive = findViewById(R.id.mode_active);
        modeMixed = findViewById(R.id.mode_mixed);
        pollIntervalInput = findViewById(R.id.poll_interval);
        saveBtn = findViewById(R.id.save);

        config = ConfigManager.load(this);

        // 恢复当前设置
        switch (config.rssiMode) {
            case 0: modePassive.setChecked(true); break;
            case 1: modeActive.setChecked(true);  break;
            default: modeMixed.setChecked(true);   break;
        }
        pollIntervalInput.setText(String.valueOf(config.pollInterval));

        saveBtn.setOnClickListener(v -> saveSettings());
    }

    private void saveSettings() {
        // 读取 RSSI 模式
        int checkedId = rssiModeGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.mode_passive) {
            config.rssiMode = 0;
        } else if (checkedId == R.id.mode_active) {
            config.rssiMode = 1;
        } else {
            config.rssiMode = 2;
        }

        // 读取轮询间隔
        String intervalStr = pollIntervalInput.getText() != null
                ? pollIntervalInput.getText().toString().trim() : "5000";
        try {
            int interval = Integer.parseInt(intervalStr);
            if (interval < 1000) interval = 1000; // 最小 1 秒
            if (interval > 60000) interval = 60000; // 最大 60 秒
            config.pollInterval = interval;
        } catch (NumberFormatException e) {
            config.pollInterval = 5000;
        }

        ConfigManager.save(this, config);
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
