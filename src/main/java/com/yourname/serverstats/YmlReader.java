package com.yourname.serverstats;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;

public class YmlReader {
    public static YamlConfiguration load(String path) {
        File file = new File(path);
        if (!file.exists()) return new YamlConfiguration();
        return YamlConfiguration.loadConfiguration(file);
    }
}
