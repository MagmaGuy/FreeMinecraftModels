package com.magmaguy.freeminecraftmodels.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;

public class DefaultConfig {
    public static void initializeConfig() {
        File file = ConfigurationEngine.fileCreator("config.yml");
        FileConfiguration fileConfiguration = ConfigurationEngine.fileConfigurationCreator(file);
        ConfigurationEngine.fileSaverOnlyDefaults(fileConfiguration, file);
    }
}
