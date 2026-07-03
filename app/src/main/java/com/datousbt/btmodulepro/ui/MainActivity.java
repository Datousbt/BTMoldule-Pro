package com.datousbt.btmodulepro.ui;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.datousbt.btmodulepro.R;
import com.datousbt.btmodulepro.model.Config;
import com.datousbt.btmodulepro.model.TriggerRule;
import com.datousbt.btmodulepro.service.RssiForegroundService;
import com.datousbt.btmodulepro.storage.ConfigManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.util.Iterator;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_EDIT = 1;

    private RecyclerView recycler;
    private TextView empty;
    private RuleAdapter adapter;
    private Config config;

    // RSSI 实时显示
    private View rssiPanel;
    private TextView rssiStatusText;
    private final Handler rssiHandler = new Handler(Looper.getMainLooper());
    private final Runnable rssiRefresher = new Runnable() {
        public void run() { refreshRssiPanel(); rssiHandler.postDelayed(this, 1500); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);
        rssiPanel = findViewById(R.id.rssi_panel);
        rssiStatusText = findViewById(R.id.rssi_status_text);
        rssiPanel.setVisibility(View.VISIBLE);
        FloatingActionButton addBtn = findViewById(R.id.add);

        recycler.setLayoutManager(new LinearLayoutManager(this));

        adapter = new RuleAdapter(new RuleAdapter.OnRuleListener() {
            @Override public void onClick(int position) {
                Intent i = new Intent(MainActivity.this, RuleEditActivity.class);
                i.putExtra("position", position);
                startActivityForResult(i, REQUEST_EDIT);
            }
            @Override public void onToggle(int position, boolean enabled) {
                config.rules.get(position).enable = enabled;
                ConfigManager.save(MainActivity.this, config);
            }
        });
        recycler.setAdapter(adapter);

        addBtn.setOnClickListener(v ->
                startActivityForResult(new Intent(this, RuleEditActivity.class), REQUEST_EDIT));

        // 启动前台服务
        Intent svc = new Intent(this, RssiForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadConfig();
        rssiHandler.post(rssiRefresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        rssiHandler.removeCallbacks(rssiRefresher);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadConfig() {
        config = ConfigManager.load(this);
        adapter.setRules(config.rules);
        boolean emptyList = config.rules.isEmpty();
        recycler.setVisibility(emptyList ? View.GONE : View.VISIBLE);
        empty.setVisibility(emptyList ? View.VISIBLE : View.GONE);
    }

    // --- RSSI 实时刷新 ---

    private void refreshRssiPanel() {
        try {
            // 先显示规则列表中的设备（即使还没 RSSI 数据）
            java.util.Map<String, String> rssiMap = new java.util.LinkedHashMap<>();
            long now = System.currentTimeMillis();

            File f = new File(getFilesDir(), "rssi_status.json");
            if (f.exists()) {
                // 读取 hook 写的 RSSI 状态文件
                StringBuilder sb = new StringBuilder();
                FileReader fr = new FileReader(f);
                char[] buf = new char[2048]; int n;
                while ((n = fr.read(buf)) != -1) sb.append(buf, 0, n);
                fr.close();

                org.json.JSONObject root = new org.json.JSONObject(sb.toString());
                org.json.JSONObject devs = root.getJSONObject("devices");
                java.util.Iterator<String> keys = devs.keys();
                while (keys.hasNext()) {
                    String mac = keys.next();
                    org.json.JSONObject d = devs.getJSONObject(mac);
                    String devName = d.optString("name", "");
                    int rssi = d.getInt("rssi");
                    long t = d.getLong("time");
                    boolean stale = (now - t) > 15000;
                    rssiMap.put(mac, (stale ? "STALE|" : "LIVE|") + devName + "|" + rssi);
                }
            }

            StringBuilder display = new StringBuilder();
            for (TriggerRule rule : config.rules) {
                if (!rule.enable) continue;
                String mac = rule.mac;
                String info = rssiMap.get(mac);
                if (info != null && info.startsWith("LIVE|")) {
                    String[] parts = info.split("\\|");
                    String name = parts[1];
                    int rssi = Integer.parseInt(parts[2]);
                    display.append("● ").append(name).append("\n")
                           .append("  ").append(mac).append("\n")
                           .append("  RSSI: ").append(rssi).append(" dBm  ")
                           .append(signalBar(rssi)).append("\n\n");
                } else {
                    display.append("○ ").append(rule.name).append("  [不在线]\n")
                           .append("  ").append(mac).append("\n\n");
                }
            }
            if (display.length() == 0) {
                rssiStatusText.setText("暂无已启用的设备规则\n添加规则后可在此查看蓝牙信号状态");
            } else {
                rssiStatusText.setText(display.toString().trim());
            }
        } catch (Throwable e) {
            rssiStatusText.setText("RSSI 数据读取中 ...");
        }
    }

    private String signalBar(int rssi) {
        if (rssi >= -50) return "████";
        if (rssi >= -60) return "███▌";
        if (rssi >= -70) return "███ ";
        if (rssi >= -80) return "██  ";
        return "█   ";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EDIT && resultCode == RESULT_OK) loadConfig();
    }
}
