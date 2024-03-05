package com.magmaguy.freeminecraftmodels.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.List;

public class DefaultConfig {

    public static boolean useDisplayEntitiesWhenPossible;

    public static void initializeConfig() {
        File file = ConfigurationEngine.fileCreator("config.yml");
        FileConfiguration fileConfiguration = ConfigurationEngine.fileConfigurationCreator(file);
        useDisplayEntitiesWhenPossible = ConfigurationEngine.setBoolean(
                List.of("Sets whether display entities will be used over armor stands.",
                        "It is not always possible to use display entities as they do not exist for bedrock, nor do they exist for servers older than 1.19.4.",
                        "Free Minecraft Models automatically falls back to armor stand displays when it's not possible to use display entities!"),
                fileConfiguration, "useDisplayEntitiesWhenPossible", true);
        ConfigurationEngine.fileSaverOnlyDefaults(fileConfiguration, file);
    }
}
