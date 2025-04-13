package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Vector3f;

/**
 * Extension methods for ModeledEntity to support Oriented Bounding Boxes
 * This class provides methods that can be called directly on ModeledEntity instances
 * to work with the OBB system.
 */
public class ModeledEntityOBBExtension {

    /**
     * Get the OBB for a modeled entity
     */
    public static OrientedBoundingBox getOBB(ModeledEntity entity) {
        return OrientedBoundingBoxRayTracer.getOrCreateOBB(entity);
    }

    /**
     * Update the OBB for a modeled entity
     */
    public static OrientedBoundingBox updateOBB(ModeledEntity entity) {
        return OrientedBoundingBoxRayTracer.getOrCreateOBB(entity);
    }

    /**
     * Visualize the OBB for debugging purposes
     * Shows particle outlines of the oriented bounding box
     */
    public static BukkitTask visualizeOBB(ModeledEntity entity, int durationTicks) {
        return new BukkitRunnable() {
            private int ticksRemaining = durationTicks;

            @Override
            public void run() {
                if (ticksRemaining <= 0 || entity.getWorld() == null) {
                    this.cancel();
                    return;
                }

                // Get a fresh OBB every time
                OrientedBoundingBox obb = getOBB(entity);

                // Get the corners of the OBB
                Vector3f[] corners = obb.getCorners();

                // Define the edges of the cube (pairs of corner indices)
                int[][] edges = {
                        {0, 1}, {1, 3}, {3, 2}, {2, 0},  // Top face
                        {4, 5}, {5, 7}, {7, 6}, {6, 4},  // Bottom face
                        {0, 4}, {1, 5}, {2, 6}, {3, 7}   // Connecting edges
                };

                // Draw particles along each edge
                for (int[] edge : edges) {
                    Vector3f start = corners[edge[0]];
                    Vector3f end = corners[edge[1]];

                    // Number of particles to place along the edge
                    int particleCount = 10;

                    for (int i = 0; i <= particleCount; i++) {
                        float t = i / (float) particleCount;
                        float x = start.x + t * (end.x - start.x);
                        float y = start.y + t * (end.y - start.y);
                        float z = start.z + t * (end.z - start.z);

                        Location particleLoc = new Location(entity.getWorld(), x, y, z);
                        entity.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, particleLoc, 1, 0, 0, 0, 0);
                    }
                }

                ticksRemaining--;
            }
        }.runTaskTimer(MetadataHandler.PLUGIN, 0, 1);
    }

    /**
     * Creates or updates the OBB from a ModifiedEntity's hitbox dimensions
     */
    public static void setOBBFromHitboxProperties(ModeledEntity entity) {
        // Get hitbox dimensions from the entity blueprint
        float width = 1.0f;  // Default width
        float height = 2.0f; // Default height
        float depth = 1.0f;  // Default depth

        // If the entity has a skeleton blueprint with a hitbox, use those dimensions
        if (entity.getSkeletonBlueprint() != null &&
                entity.getSkeletonBlueprint().getHitbox() != null) {
            width = (float) entity.getSkeletonBlueprint().getHitbox().getWidth();
            height = (float) entity.getSkeletonBlueprint().getHitbox().getHeight();
            depth = width; // Use width for depth for backward compatibility
        }

        // Create a new OBB - adjust Y position to place bottom of box at entity's feet
        Location entityLoc = entity.getLocation();
        // Move center up by half height so bottom of box is at entity's feet
        Location adjustedLoc = entityLoc.clone().add(0, height / 2, 0);

        // Apply rotation if this is a dynamic entity
        if (entity.getSkeleton() != null && entity.getSkeleton().getCurrentLocation() != null) {
            adjustedLoc = entity.getSkeleton().getCurrentLocation().clone().add(0, height / 2, 0);
        }
    }
}