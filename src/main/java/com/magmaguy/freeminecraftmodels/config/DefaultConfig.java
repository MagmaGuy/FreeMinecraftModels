package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.freeminecraftmodels.customentity.core.FilmicMode;
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
    public static boolean registerCraftingRecipes;
    public static boolean skipUnchangedBoneUpdates;
    public static boolean useDeltaMetadataPackets;
    public static boolean displayBonesRideTheirEntity;
    public static int riddenBoneInterpolationTicks;
    public static boolean modelViewersTrackHiddenBodies;
    public static int idleWalkBlendTicks;
    public static int deathBlendTicks;
    public static boolean weightedTurning;
    public static double turnSpeedCap;
    public static double turnAcceleration;
    public static int maxModelsForProximityOverride;
    public static boolean filmicMode;
    public static boolean particleEffectsEnabled = true;
    public static int maxParticlesPerEmitter = 100;
    public static int maxParticlesPerViewer = 400;
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
        if (ConfigurationEngine.writeValue(value, instance.file, instance.getFileConfiguration(), "setupDone"))
            setupDone = value;
    }

    @Override
    public void initializeValues() {
        setupDone = ConfigurationEngine.setBoolean(
                List.of("Tracks whether the first-time setup guidance has been completed."),
                fileConfiguration, "setupDone", false);
        // Side effect only: seeds the nightbreak.autoDownloadPluginUpdates config
        // default. The updater reads the value from the configuration itself.
        NightbreakPluginUpdater.setAutoDownloadConfigDefault(fileConfiguration);
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
                        "The default value is 3, which is similar to vanilla defaults."),
                fileConfiguration, "maxInteractionAndAttackDistance", 3);
        maxInteractionAndAttackDistanceForProps = ConfigurationEngine.setInt(
                List.of("Sets the maximum distance in blocks that a prop entity can be interacted with or attacked from.",
                        "The default value is 6."),
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
        displayBonesRideTheirEntity = ConfigurationEngine.setBoolean(
                List.of("When true (default), the display bones of a model whose entity clients still track",
                        "(rideable models such as mounts) ride that entity as passengers on Java clients.",
                        "The client then places every bone from the same smoothed entity position it places",
                        "riders at, so the model, its rider and its animation stay together instead of trailing",
                        "or jerking, and bones send no position packets at all. Models whose entity is hidden",
                        "from clients keep the per-bone teleports. Set to false to restore the old behavior."),
                fileConfiguration, "displayBonesRideTheirEntity", true);
        riddenBoneInterpolationTicks = ConfigurationEngine.setInt(
                List.of("How many ticks Java clients take to glide a ridden bone to each new animation pose.",
                        "0 snaps every tick; 1 (default) glides over one tick, which also smooths rotations."),
                fileConfiguration, "riddenBoneInterpolationTicks", 1);
        modelViewersTrackHiddenBodies = ConfigurationEngine.setBoolean(
                List.of("When true (default), each Java client that can see a model whose entity is hidden also",
                        "tracks that entity as an invisible, bare body (its equipment is stripped on the way to",
                        "them), so the model's display bones can ride it the way a mount's bones do and move with",
                        "the entity's own client-side smoothing instead of trailing it. Everyone else, and Bedrock",
                        "clients, keep the entity hidden as before. Set to false to keep hidden bodies untracked",
                        "and their bones on per-bone teleports."),
                fileConfiguration, "modelViewersTrackHiddenBodies", true);
        idleWalkBlendTicks = ConfigurationEngine.setInt(
                List.of("Experimental. How many ticks a model takes to cross-fade between its idle and walk animations.",
                        "Both animations keep playing during the fade. 0 switches instantly, as older versions did.",
                        "Bedrock clients get the same time. Attacks and other one-shot animations never fade."),
                fileConfiguration, "idleWalkBlendTicks", 5);
        deathBlendTicks = ConfigurationEngine.setInt(
                List.of("Experimental. How many ticks a model takes to fade from whatever pose it holds into its",
                        "death animation. 0 switches instantly, as older versions did. Bedrock clients get the same time."),
                fileConfiguration, "deathBlendTicks", 4);
        weightedTurning = ConfigurationEngine.setBoolean(
                List.of("Experimental. When true (default), a model turns toward its mob's facing with inertia: it spins up,",
                        "cruises and brakes, and bigger models turn slower. Only the model turns this way; the mob itself",
                        "still turns and walks exactly as before. Ridden models turn instantly.",
                        "Set to false to make models turn instantly with their mob, as older versions did."),
                fileConfiguration, "weightedTurning", true);
        turnSpeedCap = ConfigurationEngine.setDouble(
                List.of("Experimental. Top turning speed, in degrees per tick, of a model one block in size.",
                        "Size is the cube root of the hitbox volume times the mob's scale; the cap is divided by the",
                        "square root of the size, and never exceeds the vanilla 90 degrees per tick."),
                fileConfiguration, "turnSpeedCap", 30);
        turnAcceleration = ConfigurationEngine.setDouble(
                List.of("Experimental. How many degrees per tick a model one block in size gains or loses in turning",
                        "speed each tick. It is divided by the size, so bigger models spin up and brake more slowly."),
                fileConfiguration, "turnAcceleration", 6);
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
        filmicMode = ConfigurationEngine.setBoolean(
                List.of("!!! WARNING: NOT RECOMMENDED. LEAVE THIS OFF UNLESS YOU ARE RECORDING A VIDEO. !!!",
                        "Filmic mode is for recording trailers and videos with ReplayMod or a similar recording mod.",
                        "Normally a player only receives a model while it is within maxModelViewDistance and in",
                        "that player's line of sight. Hidden models are removed from the client and stop animating,",
                        "which saves a lot of bandwidth and server work. A replay only contains what the recording",
                        "player's client received, so models that were hidden from that player show up missing or",
                        "frozen when the replay camera looks at them.",
                        "When true, every loaded model in the chunks a player has loaded is sent to that player and",
                        "keeps animating, even behind walls or past maxModelViewDistance. Models in unloaded chunks",
                        "still don't show.",
                        "This sends far more packets to every player and makes the server animate every loaded model.",
                        "Expect lag on busy servers. Turn it on only while recording, then turn it back off.",
                        "/fmm filmic on|off changes it temporarily without editing this file. That temporary setting",
                        "lasts until the server restarts or until this option is changed and FMM reloads."),
                fileConfiguration, "filmicMode", false);
        FilmicMode.onConfigLoaded(filmicMode);
        particleEffectsEnabled = ConfigurationEngine.setBoolean(
                List.of("Sets whether Blockbench particle effects play on models, props and held items.",
                        "Set to false to switch every particle effect off without editing models.",
                        "Bedrock players follow the change once the resource pack regenerates (/fmm reload or a restart)."),
                fileConfiguration, "particleEffectsEnabled", true);
        maxParticlesPerEmitter = ConfigurationEngine.setInt(
                List.of("Upper limit of live particles for one particle effect on one model.",
                        "A particle file's own max_particles is used when it is lower.",
                        "Each particle a Java player sees is a packet entity, so high values cost bandwidth."),
                fileConfiguration, "maxParticlesPerEmitter", 100);
        maxParticlesPerViewer = ConfigurationEngine.setInt(
                List.of("Upper limit of live particles one Java player is shown across all models.",
                        "Past it, new particles for that player are skipped until older ones expire."),
                fileConfiguration, "maxParticlesPerViewer", 400);
        preventPropPlacementInProtectedRegions = ConfigurationEngine.setBoolean(
                List.of("Sets whether players are prevented from placing props (custom model items) inside protected regions.",
                        "When true, FMM asks MagmaCore's registered protection providers whether the player may build in the target cell.",
                        "Players with the freeminecraftmodels.bypassregionprotection permission (default: op) are never blocked.",
                        "Built-in player-aware providers currently cover WorldGuard and GriefPrevention; other plugins can register a MagmaCore protection provider."),
                fileConfiguration, "preventPropPlacementInProtectedRegions", true);
        registerCraftingRecipes = ConfigurationEngine.setBoolean(
                List.of("Sets whether prop recipes are registered as actual crafting recipes.",
                        "Set to false for purchase-only economies: items stay visible and buyable in /fmm shop",
                        "and the recipe menus, but players can no longer craft them at a crafting table.",
                        "Individual recipes can still be disabled entirely with isEnabled: false in their",
                        "recipes/ file (which also removes them from the shop).",
                        "Restart the server after changing this so already-registered recipes are dropped."),
                fileConfiguration, "registerCraftingRecipes", true);
        // Bedrock display debug logging is intentionally NOT a config flag —
        // it's a runtime toggle via /fmm debug bedrock on|off. See
        // com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog for
        // the rationale (too verbose to forget on; resets on every reboot).
    }
}
