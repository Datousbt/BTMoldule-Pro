package com.datousbt.btmodulepro.ui;

import android.content.Intent;
import android.os.Bundle;
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
import com.datousbt.btmodulepro.storage.ConfigManager;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

public class MainActivity
        extends AppCompatActivity {

    private static final int REQUEST_EDIT = 1;

    private RecyclerView recycler;
    private TextView empty;
    private RuleAdapter adapter;
    private Config config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);
        FloatingActionButton addBtn = findViewById(R.id.add);

        recycler.setLayoutManager(new LinearLayoutManager(this));

        adapter = new RuleAdapter(new RuleAdapter.OnRuleListener() {
            @Override
            public void onClick(int position) {
                Intent intent = new Intent(MainActivity.this, RuleEditActivity.class);
                intent.putExtra("position", position);
                startActivityForResult(intent, REQUEST_EDIT);
            }
            @Override
            public void onToggle(int position, boolean enabled) {
                config.rules.get(position).enable = enabled;
                ConfigManager.save(MainActivity.this, config);
            }
        });

        recycler.setAdapter(adapter);

        addBtn.setOnClickListener(v -> {
            startActivityForResult(new Intent(MainActivity.this, RuleEditActivity.class), REQUEST_EDIT);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadConfig();
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_EDIT && resultCode == RESULT_OK) loadConfig();
    }
}
