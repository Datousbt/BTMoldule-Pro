package com.datousbt.btmodulepro.ui;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

public class RuleEditActivity extends AppCompatActivity {

    private TextInputEditText nameInput, deviceNameInput, macInput;
    private TextInputLayout nameLayout, macLayout;
    private TextInputEditText aboveCommandInput, belowCommandInput;
    private SwitchMaterial enableSwitch;
    private Button saveBtn, deleteBtn;
    private MaterialButton importPairedBtn, scanNearbyBtn;

    private int editPosition = -1;
    private Config config;

    // 蓝牙扫描相关
    private BluetoothAdapter btAdapter;
    private final List<BluetoothDevice> scannedDevices = new ArrayList<>();
    private AlertDialog scanDialog;
    private boolean scanning = false;
    private boolean scanActuallyStarted = false;

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
        aboveCommandInput = findViewById(R.id.above_command);
        belowCommandInput = findViewById(R.id.below_command);
        enableSwitch = findViewById(R.id.enable_switch);
        saveBtn = findViewById(R.id.save);
        deleteBtn = findViewById(R.id.delete);
        importPairedBtn = findViewById(R.id.import_paired);
        scanNearbyBtn = findViewById(R.id.scan_nearby);

        btAdapter = BluetoothAdapter.getDefaultAdapter();

        config = ConfigManager.load(this);
        editPosition = getIntent().getIntExtra("position", -1);

        if (editPosition >= 0 && editPosition < config.rules.size()) {
            TriggerRule rule = config.rules.get(editPosition);
            toolbar.setTitle(R.string.edit_rule);
            nameInput.setText(rule.name);
            deviceNameInput.setText(rule.deviceName);
            macInput.setText(rule.mac);
            aboveCommandInput.setText(rule.aboveCommand);
            belowCommandInput.setText(rule.belowCommand);
            enableSwitch.setChecked(rule.enable);
            deleteBtn.setVisibility(View.VISIBLE);
            deleteBtn.setOnClickListener(v -> confirmDelete());
        }

        saveBtn.setOnClickListener(v -> save());
        importPairedBtn.setOnClickListener(v -> doImportPaired());
        scanNearbyBtn.setOnClickListener(v -> doScanNearby());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
    }

    // ==================== 导入已配对设备 ====================

    private void doImportPaired() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            return;
        }
        showPaired();
    }

    private void showPaired() {
        if (btAdapter == null || !btAdapter.isEnabled()) {
            Toast.makeText(this, "蓝牙未开启", Toast.LENGTH_SHORT).show(); return;
        }
        Set<BluetoothDevice> bonded;
        try { bonded = btAdapter.getBondedDevices(); } catch (SecurityException e) {
            Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_SHORT).show(); return;
        }
        if (bonded == null || bonded.isEmpty()) {
            Toast.makeText(this, R.string.no_paired_devices, Toast.LENGTH_SHORT).show(); return;
        }
        showDeviceDialog(new ArrayList<>(bonded), R.string.select_paired_device);
    }

    // ==================== 扫描附近设备 ====================

    private void doScanNearby() {
        if (btAdapter == null || !btAdapter.isEnabled()) {
            Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show(); return;
        }

        // Android 12+ 需要 BLUETOOTH_SCAN，某些设备还需 BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean hasScan = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean hasConnect = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            if (!hasScan || !hasConnect) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                }, 2);
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, 2);
                return;
            }
        }
        startScan();
    }

    private void startScan() {
        if (scanning) stopScan();
        scannedDevices.clear();

        // 先确保蓝牙不在扫描状态
        try {
            if (btAdapter.isDiscovering()) {
                btAdapter.cancelDiscovery();
                // 等待取消生效
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
        } catch (SecurityException ignored) {}

        scanDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.scanning)
                .setMessage("正在扫描附近蓝牙设备 (最长15秒) ...")
                .setPositiveButton(R.string.cancel, (d, w) -> stopScan())
                .setCancelable(false)
                .create();
        scanDialog.show();

        // 15秒超时
        new android.os.Handler().postDelayed(() -> {
            if (scanning) stopScan();
        }, 15000);

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(scanReceiver, filter);

        scanning = true;
        try {
            boolean ok = btAdapter.startDiscovery();
            if (!ok) {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                ok = btAdapter.startDiscovery();
            }
            if (ok) {
                scanActuallyStarted = true;
            } else {
                stopScanNoResult();
                Toast.makeText(this, "扫描启动失败：蓝牙可能正忙，请稍后再试", Toast.LENGTH_LONG).show();
            }
        } catch (SecurityException e) {
            stopScanNoResult();
            Toast.makeText(this, "缺少蓝牙扫描权限，请在系统设置中授权", Toast.LENGTH_LONG).show();
        }
    }

    private void stopScanNoResult() {
        // 仅关闭扫描，不弹结果
        scanning = false;
        scanActuallyStarted = false;
        try { btAdapter.cancelDiscovery(); } catch (SecurityException ignored) {}
        try { unregisterReceiver(scanReceiver); } catch (IllegalArgumentException ignored) {}
        if (scanDialog != null && scanDialog.isShowing()) scanDialog.dismiss();
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        boolean hadRealScan = scanActuallyStarted;
        scanActuallyStarted = false;
        try { btAdapter.cancelDiscovery(); } catch (SecurityException ignored) {}
        try { unregisterReceiver(scanReceiver); } catch (IllegalArgumentException ignored) {}
        if (scanDialog != null && scanDialog.isShowing()) scanDialog.dismiss();

        if (!hadRealScan) return; // 没真正开始扫描，不弹结果

        if (!scannedDevices.isEmpty()) {
            showDeviceDialog(new ArrayList<>(scannedDevices), R.string.select_scanned_device);
        } else {
            Toast.makeText(this, R.string.no_devices_found, Toast.LENGTH_SHORT).show();
        }
    }

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && !scannedDevices.contains(device)) {
                    scannedDevices.add(device);
                    // 更新对话框消息显示已发现数量
                    if (scanDialog != null && scanDialog.isShowing()) {
                        scanDialog.setMessage("已发现 " + scannedDevices.size() + " 个设备 ...");
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                stopScan();
            }
        }
    };

    // ==================== 设备选择对话框 ====================

    private void showDeviceDialog(List<BluetoothDevice> devices, int titleRes) {
        String[] items = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice d = devices.get(i);
            String n = d.getName();
            items[i] = (n != null && !n.isEmpty() ? n : "未命名设备") + "\n" + d.getAddress();
        }
        new AlertDialog.Builder(this).setTitle(titleRes)
                .setItems(items, (d, w) -> {
                    BluetoothDevice sel = devices.get(w);
                    String n = sel.getName();
                    if (n != null && !n.isEmpty()) { nameInput.setText(n); deviceNameInput.setText(n); }
                    macInput.setText(sel.getAddress());
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    // ==================== 权限回调 ====================

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean allGranted = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
        }
        if (allGranted) {
            if (requestCode == 1) showPaired();
            else if (requestCode == 2) startScan();
        } else {
            Toast.makeText(this, "需要蓝牙权限才能使用此功能", Toast.LENGTH_LONG).show();
        }
    }

    // ==================== 保存/删除 ====================

    private void save() {
        String name = getText(nameInput), mac = getText(macInput);
        boolean ok = true;
        if (name.isEmpty()) { nameLayout.setError(getString(R.string.name_required)); ok = false; }
        else nameLayout.setError(null);
        if (mac.isEmpty()) { macLayout.setError(getString(R.string.mac_required)); ok = false; }
        else macLayout.setError(null);
        if (!ok) return;

        TriggerRule rule;
        if (editPosition >= 0) rule = config.rules.get(editPosition);
        else { rule = new TriggerRule(); config.rules.add(rule); }

        rule.name = name;
        rule.mac = mac;
        rule.deviceName = getText(deviceNameInput);
        rule.aboveCommand = getText(aboveCommandInput);
        rule.belowCommand = getText(belowCommandInput);
        rule.enable = enableSwitch.isChecked();

        ConfigManager.save(this, config);
        setResult(RESULT_OK);
        finish();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this).setTitle(R.string.delete).setMessage(R.string.confirm_delete)
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

    private static String getText(TextInputEditText e) {
        return e.getText() != null ? e.getText().toString().trim() : "";
    }
}
