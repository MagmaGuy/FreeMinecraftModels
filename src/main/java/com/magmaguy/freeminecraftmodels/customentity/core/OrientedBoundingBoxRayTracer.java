package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

import java.util.List;
import java.util.Optional;

/**
 * Handles ray tracing for Oriented Bounding Boxes attached to modeled entities
 */
public class OrientedBoundingBoxRayTracer {
    // Remove the cache map to stop caching OBBs

    // The maximum distance to check for ray intersections
    private static final float MAX_DISTANCE = 5.0f;

    /**
     * Get a fresh OBB for a modeled entity
     */
    public static OrientedBoundingBox createOBB(ModeledEntity entity) {
        // Get the entity's dimensions from its Skeleton
        float width = 1.0f; // Default width
        float height = 2.0f; // Default height
        float depth = 1.0f; // Default depth

        if (entity.getSkeletonBlueprint() != null && entity.getSkeletonBlueprint().getHitbox() != null) {
            width = (float) entity.getSkeletonBlueprint().getHitbox().getWidthX();
            height = (float) entity.getSkeletonBlueprint().getHitbox().getHeight();
            depth = (float) entity.getSkeletonBlueprint().getHitbox().getWidthZ();
        }

        // Create a new OBB - adjust Y position to place bottom of box at entity's feet
        Location entityLoc = entity.getLocation();
        // Move center up by half height so bottom of box is at entity's feet
        Location adjustedLoc = entityLoc.clone().add(0, height / 2, 0);
        OrientedBoundingBox obb = new OrientedBoundingBox(adjustedLoc, depth, height, width);

        // Apply rotation if this is a dynamic entity
        if (entity.getSkeleton() != null && entity.getSkeleton().getCurrentLocation() != null) {
            obb.setRotationFromLocation(entity.getSkeleton().getCurrentLocation());
        }

        return obb;
    }

    /**
     * Perform a ray trace from a player's eye location in the direction they're looking
     *
     * @param player The player performing the ray trace
     * @return The first modeled entity hit by the ray, if any
     */
    public static Optional<ModeledEntity> raytraceFromPlayer(Player player) {
        Location eyeLocation = player.getEyeLocation();
        Vector direction = eyeLocation.getDirection();

        return raytraceFromPoint(
                eyeLocation.getWorld().getName(),
                new Vector3f((float) eyeLocation.getX(), (float) eyeLocation.getY(), (float) eyeLocation.getZ()),
                new Vector3f((float) direction.getX(), (float) direction.getY(), (float) direction.getZ()),
                MAX_DISTANCE
        );
    }

    /**
     * Perform a ray trace from a specific point in a specific direction
     *
     * @param worldName   The name of the world
     * @param origin      The origin point of the ray
     * @param direction   The direction of the ray
     * @param maxDistance The maximum distance to check for intersections
     * @return The first modeled entity hit by the ray, if any
     */
    public static Optional<ModeledEntity> raytraceFromPoint(
            String worldName, Vector3f origin, Vector3f direction, float maxDistance) {

        // Get all modeled entities
        List<ModeledEntity> entities = ModeledEntityManager.getAllEntities();

        // Filter entities to only include those in the same world
        entities.removeIf(entity -> entity.getWorld() == null ||
                !entity.getWorld().getName().equals(worldName));

        if (entities.isEmpty()) {
            return Optional.empty();
        }

        // Variables to track the closest hit
        ModeledEntity closestEntity = null;
        float closestDistance = maxDistance;

        // Check each entity for intersection
        for (ModeledEntity entity : entities) {
            // Get a fresh OBB for the entity every time
            OrientedBoundingBox obb = createOBB(entity);

            // Check for ray intersection
            float distance = obb.rayIntersection(origin, direction, maxDistance);

            // If there's an intersection and it's closer than any previous hit
            if (distance > 0 && distance < closestDistance) {
                closestEntity = entity;
                closestDistance = distance;
            }
        }

        return Optional.ofNullable(closestEntity);
    }

    public static void visualizeOBB(ModeledEntity entity, int durationTicks) {
        new BukkitRunnable() {
            private int ticksRemaining = durationTicks;

            @Override
            public void run() {
                if (ticksRemaining <= 0 || entity.getWorld() == null) {
                    this.cancel();
                    return;
                }

                // Get a fresh OBB every time
                OrientedBoundingBox obb = createOBB(entity);

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
}