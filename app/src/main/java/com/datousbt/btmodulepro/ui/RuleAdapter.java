package com.datousbt.btmodulepro.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.datousbt.btmodulepro.R;
import com.datousbt.btmodulepro.model.TriggerRule;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;

public class RuleAdapter
        extends RecyclerView.Adapter<RuleAdapter.Holder> {

    public interface OnRuleListener {
        void onClick(int position);
        void onToggle(int position, boolean enabled);
    }

    private List<TriggerRule> rules = new ArrayList<>();
    private final OnRuleListener listener;

    public RuleAdapter(OnRuleListener listener) { this.listener = listener; }

    public void setRules(List<TriggerRule> rules) {
        this.rules = rules != null ? rules : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_rule, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(rules.get(position), position);
    }

    @Override public int getItemCount() { return rules.size(); }

    class Holder extends RecyclerView.ViewHolder {
        TextView name, mac, threshold, commands;
        SwitchMaterial enableSwitch;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.rule_name);
            mac = itemView.findViewById(R.id.rule_mac);
            threshold = itemView.findViewById(R.id.rule_threshold);
            commands = itemView.findViewById(R.id.rule_commands);
            enableSwitch = itemView.findViewById(R.id.enable_switch);
        }

        void bind(TriggerRule rule, int position) {
            name.setText(rule.name != null && !rule.name.isEmpty()
                    ? rule.name : itemView.getContext().getString(R.string.untitled_rule));
            mac.setText(rule.mac);

            // 双阈值显示
            StringBuilder th = new StringBuilder();
            if (rule.aboveThreshold < 0) th.append("↑ ≥").append(rule.aboveThreshold).append(" dBm");
            if (rule.belowThreshold < 0) {
                if (th.length() > 0) th.append("  ");
                th.append("↓ ≤").append(rule.belowThreshold).append(" dBm");
            }
            threshold.setText(th.toString());

            // 命令摘要
            StringBuilder cs = new StringBuilder();
            if (rule.aboveCommand != null && !rule.aboveCommand.isEmpty())
                cs.append("↑").append(ellipsis(rule.aboveCommand, 25));
            if (rule.belowCommand != null && !rule.belowCommand.isEmpty()) {
                if (cs.length() > 0) cs.append("  ");
                cs.append("↓").append(ellipsis(rule.belowCommand, 25));
            }
            commands.setText(cs.length() > 0 ? cs.toString() : "无命令");

            enableSwitch.setOnCheckedChangeListener(null);
            enableSwitch.setChecked(rule.enable);
            enableSwitch.setOnCheckedChangeListener((btn, checked) ->
                    listener.onToggle(position, checked));
            itemView.setOnClickListener(v -> listener.onClick(position));
        }

        private String ellipsis(String s, int max) {
            return s.length() > max ? s.substring(0, max) + "…" : s;
        }
    }
}
