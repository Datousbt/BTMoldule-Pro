package com.datousbt.btmodulepro.ui;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.datousbt.btmodulepro.R;
import com.datousbt.btmodulepro.model.Config;
import com.datousbt.btmodulepro.model.TriggerRule;
import com.datousbt.btmodulepro.storage.ConfigManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class RuleEditActivity
        extends AppCompatActivity {

    private TextInputEditText nameInput, deviceNameInput, macInput;
    private TextInputLayout nameLayout, macLayout;
    private TextInputEditText aboveThresholdInput, belowThresholdInput;
    private TextInputEditText hysteresisInput, debounceInput;
    private TextInputEditText aboveCommandInput, belowCommandInput;
    private SwitchMaterial enableSwitch;
    private Button saveBtn, deleteBtn;
    private MaterialButton importPairedBtn;

    private int editPosition = -1;
    private Config config;

    private final ActivityResultLauncher<String> requestBluetoothPermission =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) showPairedDevicesDialog();
                        else Toast.makeText(this,
                                R.string.bluetooth_permission_required,
                                Toast.LENGTH_SHORT).show();
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rule);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        nameInput = findViewById(R.id.name);
        nameLayout = findViewById(R.id.name_layout);
        deviceNameInput = findViewById(R.id.device_name_filter);
        macInput = findViewById(R.id.mac);
        macLayout = findViewById(R.id.mac_layout);
        aboveThresholdInput = findViewById(R.id.above_threshold);
        belowThresholdInput = findViewById(R.id.below_threshold);
        hysteresisInput = findViewById(R.id.hysteresis);
        debounceInput = findViewById(R.id.debounce);
        aboveCommandInput = findViewById(R.id.above_command);
        belowCommandInput = findViewById(R.id.below_command);
        enableSwitch = findViewById(R.id.enable_switch);
        saveBtn = findViewById(R.id.save);
        deleteBtn = findViewById(R.id.delete);
        importPairedBtn = findViewById(R.id.import_paired);

        config = ConfigManager.load(this);
        editPosition = getIntent().getIntExtra("position", -1);

        if (editPosition >= 0 && editPosition < config.rules.size()) {
            TriggerRule rule = config.rules.get(editPosition);
            toolbar.setTitle(R.string.edit_rule);
            nameInput.setText(rule.name);
            deviceNameInput.setText(rule.deviceName);
            macInput.setText(rule.mac);
            aboveThresholdInput.setText(String.valueOf(rule.aboveThreshold));
            belowThresholdInput.setText(String.valueOf(rule.belowThreshold));
            hysteresisInput.setText(String.valueOf(rule.hysteresis));
            debounceInput.setText(String.valueOf(rule.debounce));
            aboveCommandInput.setText(rule.aboveCommand);
            belowCommandInput.setText(rule.belowCommand);
            enableSwitch.setChecked(rule.enable);
            deleteBtn.setVisibility(View.VISIBLE);
            deleteBtn.setOnClickListener(v -> confirmDelete());
        }

        saveBtn.setOnClickListener(v -> save());
        importPairedBtn.setOnClickListener(v -> onImportPairedClick());
    }

    // --- 导入 ---

    private void onImportPairedClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT);
                return;
            }
        }
        showPairedDevicesDialog();
    }

    private void showPairedDevicesDialog() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "蓝牙未开启或不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<BluetoothDevice> bonded;
        try { bonded = adapter.getBondedDevices(); }
        catch (SecurityException e) {
            Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_SHORT).show();
            return;
        }
        if (bonded == null || bonded.isEmpty()) {
            Toast.makeText(this, R.string.no_paired_devices, Toast.LENGTH_SHORT).show();
            return;
        }
        List<BluetoothDevice> deviceList = new ArrayList<>(bonded);
        String[] items = new String[deviceList.size()];
        for (int i = 0; i < deviceList.size(); i++) {
            BluetoothDevice dev = deviceList.get(i);
            String n = dev.getName();
            items[i] = (n != null && !n.isEmpty() ? n : getString(R.string.untitled_rule)) + "\n" + dev.getAddress();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.select_paired_device)
                .setItems(items, (dialog, which) -> {
                    BluetoothDevice selected = deviceList.get(which);
                    String n = selected.getName();
                    if (n != null && !n.isEmpty()) { nameInput.setText(n); deviceNameInput.setText(n); }
                    macInput.setText(selected.getAddress());
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    // --- 保存 ---

    private void save() {
        String name = getText(nameInput);
        String mac = getText(macInput);
        boolean valid = true;
        if (name.isEmpty()) { nameLayout.setError(getString(R.string.name_required)); valid = false; }
        else nameLayout.setError(null);
        if (mac.isEmpty()) { macLayout.setError(getString(R.string.mac_required)); valid = false; }
        else macLayout.setError(null);
        if (!valid) return;

        TriggerRule rule;
        if (editPosition >= 0) { rule = config.rules.get(editPosition); }
        else { rule = new TriggerRule(); config.rules.add(rule); }

        rule.name = name;
        rule.mac = mac;
        rule.deviceName = getText(deviceNameInput);
        rule.aboveThreshold = parseInt(aboveThresholdInput, -60);
        rule.belowThreshold = parseInt(belowThresholdInput, -80);
        rule.hysteresis = parseInt(hysteresisInput, 5);
        rule.debounce = parseInt(debounceInput, 5000);
        rule.aboveCommand = getText(aboveCommandInput);
        rule.belowCommand = getText(belowCommandInput);
        rule.enable = enableSwitch.isChecked();

        ConfigManager.save(this, config);
        setResult(RESULT_OK);
        finish();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete).setMessage(R.string.confirm_delete)
                .setPositiveButton(R.string.delete, (d, w) -> doDelete())
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void doDelete() {
        if (editPosition >= 0 && editPosition < config.rules.size()) {
            config.rules.remove(editPosition);
            ConfigManager.save(this, config);
        }
        setResult(RESULT_OK);
        finish();
    }

    private static String getText(TextInputEditText input) {
        return input.getText() != null ? input.getText().toString().trim() : "";
    }

    private static int parseInt(TextInputEditText input, int def) {
        try { return Integer.parseInt(getText(input)); }
        catch (NumberFormatException e) { return def; }
    }
}
