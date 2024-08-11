package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.config.ConfigurationFile;

import java.util.List;

public class DefaultConfig extends ConfigurationFile {

    public static boolean useDisplayEntitiesWhenPossible;

    public DefaultConfig() {
        super("config.yml");
    }

    @Override
    public void initializeValues() {
        useDisplayEntitiesWhenPossible = ConfigurationEngine.setBoolean(
                List.of("Sets whether display entities will be used over armor stands.",
                        "It is not always possible to use display entities as they do not exist for bedrock, nor do they exist for servers older than 1.19.4.",
                        "Free Minecraft Models automatically falls back to armor stand displays when it's not possible to use display entities!"),
                fileConfiguration, "useDisplayEntitiesWhenPossible", true);
    }
}
