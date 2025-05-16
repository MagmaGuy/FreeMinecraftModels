package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.config.ConfigurationFile;

import java.util.List;

public class DefaultConfig extends ConfigurationFile {

    public static boolean useDisplayEntitiesWhenPossible;
    public static int maxModelViewDistance;
    public static int maxInteractionAndAttackDistance;

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
        maxModelViewDistance = ConfigurationEngine.setInt(
                List.of("Sets the maximum distance in blocks that a modeled entity can be seen from.",
                        "This is to prevent the server and clients from lagging when a modeled entity is far away.",
                        "The default value is 60, which is similar to vanilla defaults."),
                fileConfiguration, "maxModelViewDistance", 60);
        maxInteractionAndAttackDistance = ConfigurationEngine.setInt(
                List.of("Sets the maximum distance in blocks that a modeled entity can be interacted with or attacked from.",
                        "The default value is 3, which is similar to vanilla defaults."),
                fileConfiguration, "maxInteractionAndAttackDistance", 3);
    }
}
