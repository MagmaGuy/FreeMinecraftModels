package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import org.bukkit.Location;
import org.bukkit.entity.Player;
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
    public static OrientedBoundingBox getOrCreateOBB(ModeledEntity entity) {
        // Get the entity's dimensions from its Skeleton
        float width = 1.0f; // Default width
        float height = 2.0f; // Default height
        float depth = 1.0f; // Default depth

        if (entity.getSkeletonBlueprint() != null && entity.getSkeletonBlueprint().getHitbox() != null) {
            width = (float) entity.getSkeletonBlueprint().getHitbox().getWidth();
            height = (float) entity.getSkeletonBlueprint().getHitbox().getHeight();
            depth = width; // Using width for depth for backward compatibility
        }

        // Create a new OBB - adjust Y position to place bottom of box at entity's feet
        Location entityLoc = entity.getLocation();
        // Move center up by half height so bottom of box is at entity's feet
        Location adjustedLoc = entityLoc.clone().add(0, height / 2, 0);
        OrientedBoundingBox obb = new OrientedBoundingBox(adjustedLoc, width, height, depth);

        // Apply rotation if this is a dynamic entity
        if (entity.getSkeleton() != null && entity.getSkeleton().getCurrentLocation() != null) {
            obb.setRotationFromLocation(entity.getSkeleton().getCurrentLocation());
        }

        return obb;
    }

    /**
     * Update the OBB for a modeled entity - now just creates a fresh OBB
     */
    public static OrientedBoundingBox updateOBB(ModeledEntity entity) {
        return getOrCreateOBB(entity);
    }

    /**
     * Remove an entity's OBB from the cache - now a no-op since we don't cache
     */
    public static void removeOBB(ModeledEntity entity) {
        // No-op since we don't cache anymore
    }

    /**
     * Clear the entire OBB cache - now a no-op since we don't cache
     */
    public static void clearCache() {
        // No-op since we don't cache anymore
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
            OrientedBoundingBox obb = getOrCreateOBB(entity);

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
}