package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-world modeled-entity density snapshot, refreshed once per model-clock tick.
 *
 * <p>Acts as a pseudo load balancer for the close-range proximity override in
 * {@link SkeletonWatchers}. In a sparse world a model within {@code MIN_VIEW_DISTANCE} is
 * always shown — the line-of-sight raytrace is skipped to avoid pop-in on models you're
 * standing next to. But in a dense world (a multi-floor NPC hub) that override means every
 * nearby model — including ones on other floors or behind walls that the player cannot
 * actually see — becomes a viewer and sends packets every tick.</p>
 *
 * <p>Once a world crosses {@link DefaultConfig#maxModelsForProximityOverride} loaded models,
 * the override is disabled there, so the occlusion raytrace runs even up close and culls the
 * models the player has no line of sight to. The decision is per world (not global) so a
 * crowded hub world doesn't penalize sparse worlds, which matches the AG-world-only symptom.</p>
 */
public final class ModelDensity {

    private ModelDensity() {
    }

    // worldUID -> loaded model count. Rebuilt fresh each refresh(); reference swapped atomically.
    private static volatile Map<UUID, Integer> countsByWorld = Map.of();

    /**
     * Rebuilds the per-world counts. Called once per tick on the model-clock thread, before
     * models tick (and thus before {@link SkeletonWatchers#tick()} reads the snapshot).
     */
    public static void refresh() {
        Map<UUID, Integer> counts = new HashMap<>();
        for (ModeledEntity entity : ModeledEntity.getLoadedModeledEntities()) {
            World world = entity.getWorld();
            if (world == null) continue;
            counts.merge(world.getUID(), 1, Integer::sum);
        }
        countsByWorld = counts;
    }

    public static int countInWorld(World world) {
        if (world == null) return 0;
        return countsByWorld.getOrDefault(world.getUID(), 0);
    }

    /**
     * @return true if the close-range proximity override (always-show within
     * {@code MIN_VIEW_DISTANCE}, skipping the raytrace) should apply in this world. False in
     * worlds dense enough that occlusion culling should run even up close.
     */
    public static boolean proximityOverrideActive(World world) {
        int threshold = DefaultConfig.maxModelsForProximityOverride;
        if (threshold <= 0) return false; // 0 / negative => always require line of sight
        return countInWorld(world) < threshold;
    }
}
