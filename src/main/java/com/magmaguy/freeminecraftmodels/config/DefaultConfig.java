package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.config.ConfigurationFile;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginUpdater;

import java.util.List;

public class DefaultConfig extends ConfigurationFile {

    public static boolean useDisplayEntitiesWhenPossible;
    public static int maxModelViewDistance;
    public static int maxInteractionAndAttackDistanceForLivingEntities;
    public static int maxInteractionAndAttackDistanceForProps;
    public static boolean sendCustomModelsToBedrockClientsV2;
    public static boolean preventPropPlacementInProtectedRegions;
    public static boolean skipUnchangedBoneUpdates;
    public static boolean useDeltaMetadataPackets;
    public static int maxModelsForProximityOverride;
    private static DefaultConfig instance;
    public static boolean setupDone;
    public static boolean autoDownloadPluginUpdates;

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
        autoDownloadPluginUpdates = NightbreakPluginUpdater.setAutoDownloadConfigDefault(fileConfiguration);
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
        skipUnchangedBoneUpdates = ConfigurationEngine.setBoolean(
                List.of("When true (default), a model bone only sends its per-tick move/metadata packets",
                        "to viewers when its position, rotation or scale actually changed since the last tick.",
                        "Bones that are perfectly still (static props, idle parts of a model) send nothing,",
                        "which massively cuts per-client packet load in dense areas (NPC hubs, towns).",
                        "The periodic full resync still runs, so any rare client drift self-heals.",
                        "Set to false only to restore the old 'always resend every bone every tick' behavior",
                        "for debugging."),
                fileConfiguration, "skipUnchangedBoneUpdates", true);
        useDeltaMetadataPackets = ConfigurationEngine.setBoolean(
                List.of("When true (default), the per-tick metadata packet for display-entity model bones",
                        "sends only the values that changed (the bone's transformation) instead of re-serializing",
                        "the entire item-display blob (item model + custom model data) every tick.",
                        "This dramatically shrinks per-client bandwidth for animating models in dense areas.",
                        "Full state is still sent when a player first sees a model and on the periodic resync.",
                        "Set to false to restore the old full-snapshot-every-tick behavior (debugging/comparison)."),
                fileConfiguration, "useDeltaMetadataPackets", true);
        // Push the value down to the NMS packet layer (config-free by design).
        com.magmaguy.easyminecraftgoals.internal.PacketEntityTuning.useDeltaMetadataUpdates = useDeltaMetadataPackets;
        maxModelsForProximityOverride = ConfigurationEngine.setInt(
                List.of("Pseudo load balancer for the close-range visibility override.",
                        "Normally a model within 10 blocks of a player is always shown, skipping the",
                        "line-of-sight raytrace to avoid pop-in. In a dense area (e.g. a multi-floor NPC",
                        "hub) that means dozens of models the player can't actually see — on other floors,",
                        "behind walls — all become viewers and send packets every tick.",
                        "When a WORLD holds at least this many loaded models, the close-range override is",
                        "disabled there, so the occlusion raytrace runs even up close and culls models the",
                        "player has no line of sight to. Sparse worlds keep the override (no pop-in).",
                        "Default 25. Raise very high to effectively restore the always-override behavior;",
                        "set to 0 to always require line-of-sight (never override)."),
                fileConfiguration, "maxModelsForProximityOverride", 25);
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
