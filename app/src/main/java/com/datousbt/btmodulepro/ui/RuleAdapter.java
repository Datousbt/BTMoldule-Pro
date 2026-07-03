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

public class RuleAdapter extends RecyclerView.Adapter<RuleAdapter.Holder> {

    public interface OnRuleListener {
        void onClick(int position);
        void onToggle(int position, boolean enabled);
    }

    private List<TriggerRule> rules = new ArrayList<>();
    private final OnRuleListener listener;

    public RuleAdapter(OnRuleListener l) { listener = l; }

    public void setRules(List<TriggerRule> r) { rules = r != null ? r : new ArrayList<>(); notifyDataSetChanged(); }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup p, int t) {
        return new Holder(LayoutInflater.from(p.getContext()).inflate(R.layout.item_rule, p, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int pos) { h.bind(rules.get(pos), pos); }
    @Override public int getItemCount() { return rules.size(); }

    class Holder extends RecyclerView.ViewHolder {
        TextView name, mac, commands;
        SwitchMaterial sw;

        Holder(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.rule_name);
            mac = v.findViewById(R.id.rule_mac);
            commands = v.findViewById(R.id.rule_commands);
            sw = v.findViewById(R.id.enable_switch);
        }

        void bind(TriggerRule rule, int pos) {
            name.setText(rule.name != null && !rule.name.isEmpty() ? rule.name :
                    itemView.getContext().getString(R.string.untitled_rule));
            mac.setText(rule.mac);
            StringBuilder cs = new StringBuilder();
            if (rule.aboveCommand != null && !rule.aboveCommand.isEmpty())
                cs.append("↑接近:").append(ellipsis(rule.aboveCommand, 20));
            if (rule.belowCommand != null && !rule.belowCommand.isEmpty()) {
                if (cs.length() > 0) cs.append("\n");
                cs.append("↓远离:").append(ellipsis(rule.belowCommand, 20));
            }
            commands.setText(cs.length() > 0 ? cs.toString() : "无命令");

            sw.setOnCheckedChangeListener(null);
            sw.setChecked(rule.enable);
            sw.setOnCheckedChangeListener((b, c) -> listener.onToggle(pos, c));
            itemView.setOnClickListener(v -> listener.onClick(pos));
        }

        String ellipsis(String s, int max) { return s.length() > max ? s.substring(0, max) + "…" : s; }
    }
}
