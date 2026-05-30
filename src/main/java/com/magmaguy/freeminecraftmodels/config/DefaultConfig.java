package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.config.ConfigurationFile;

import java.util.List;

public class DefaultConfig extends ConfigurationFile {

    public static boolean useDisplayEntitiesWhenPossible;
    public static int maxModelViewDistance;
    public static int maxInteractionAndAttackDistanceForLivingEntities;
    public static int maxInteractionAndAttackDistanceForProps;
    public static boolean sendCustomModelsToBedrockClientsV2;
    public static boolean preventPropPlacementInProtectedRegions;
    private static DefaultConfig instance;
    public static boolean setupDone;

    public DefaultConfig() {
        super("config.yml");
        instance = this;
    }

    public static boolean isSetupDone() {
        return setupDone;
    }

    public static void toggleSetupDone(boolean value) {
        setupDone = value;
        ConfigurationEngine.writeValue(setupDone, instance.file, instance.getFileConfiguration(), "setupDone");
    }

    @Override
    public void initializeValues() {
        setupDone = ConfigurationEngine.setBoolean(
                List.of("Tracks whether the first-time setup guidance has been completed."),
                fileConfiguration, "setupDone", false);
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
        maxInteractionAndAttackDistanceForLivingEntities = ConfigurationEngine.setInt(
                List.of("Sets the maximum distance in blocks that a static or dynamic entity can be interacted with or attacked from.",
                        "The default value is 6, which is similar to vanilla defaults."),
                fileConfiguration, "maxInteractionAndAttackDistance", 3);
        maxInteractionAndAttackDistanceForProps = ConfigurationEngine.setInt(
                List.of("Sets the maximum distance in blocks that a prop entity can be interacted with or attacked from.",
                        "The default value is 3, which is similar to vanilla defaults."),
                fileConfiguration, "maxInteractionAndAttackDistanceForProps", 6);
        sendCustomModelsToBedrockClientsV2 = ConfigurationEngine.setBoolean(
                List.of("Sets whether custom models should be sent to bedrock clients.",
                        "If you can't convert the resource pack, you will not be able to send disguises to the players",
                        "If false, players will not see the custom models, but for dynamic models (bosses and such) they will see the minecraft creature the are based on.",
                        "Renamed from sendCustomModelsToBedrockClients (default false) to V2 (default true) to override the old default — Bedrock players should see models by default.",
                        "If you set the old key to false explicitly you can delete it; this V2 key takes over."),
                fileConfiguration, "sendCustomModelsToBedrockClientsV2", true);
        preventPropPlacementInProtectedRegions = ConfigurationEngine.setBoolean(
                List.of("Sets whether players are prevented from placing props (custom model items) inside protected regions.",
                        "When true, a player right-clicking to place a prop inside a WorldGuard region or GriefPrevention claim is blocked.",
                        "Players with the freeminecraftmodels.bypassregionprotection permission (default: op) are never blocked.",
                        "This relies on WorldGuard (plus WorldEdit) or GriefPrevention being installed; with no protection plugin present it has no effect."),
                fileConfiguration, "preventPropPlacementInProtectedRegions", true);
        // Bedrock display debug logging is intentionally NOT a config flag —
        // it's a runtime toggle via /fmm debug bedrock on|off. See
        // com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog for
        // the rationale (too verbose to forget on; resets on every reboot).
    }
}
