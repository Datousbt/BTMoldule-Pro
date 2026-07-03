package com.datousbt.btmodulepro.storage;

import com.datousbt.btmodulepro.model.Config;
import com.google.gson.Gson;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class ConfigManager {

    private static final Gson gson = new Gson();

    public static Config load(android.content.Context context) {
        try {
            File f = getFile(context);
            if (!f.exists()) return new Config();
            return gson.fromJson(new FileReader(f), Config.class);
        } catch (Exception e) {
            return new Config();
        }
    }

    public static Config loadFromPath(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return new Config();
            return gson.fromJson(new FileReader(f), Config.class);
        } catch (Exception e) {
            return new Config();
        }
    }

    public static void save(android.content.Context context, Config config) {
        try {
            FileWriter fw = new FileWriter(getFile(context));
            gson.toJson(config, fw);
            fw.close();
        } catch (Exception ignored) {
        }
    }

    private static File getFile(android.content.Context context) {
        return new File(context.getFilesDir(), "rssi_config.json");
    }
}
