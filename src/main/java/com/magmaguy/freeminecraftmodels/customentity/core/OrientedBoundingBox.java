package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.magmacore.util.AttributeManager;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Optional;

/**
 * Represents a bounding box that can be rotated in any direction.
 * Unlike an axis-aligned bounding box, this can rotate with the model.
 * Optimized for performance with object reuse and dirty flag system.
 * Now supports dynamic scaling.
 */
public class OrientedBoundingBox {

    // Center point of the box
    @Getter
    private final Vector3d center = new Vector3d();
    // Base half-lengths of the box along its local axes (before scaling)
    @Getter
    private final Vector3d baseHalfExtents = new Vector3d();
    // Actual half-lengths after scaling
    @Getter
    private final Vector3d halfExtents = new Vector3d();
    // Rotation matrix representing the box's orientation
    @Getter
    private final Matrix3d rotation = new Matrix3d().identity();
    // Cached inverse rotation matrix
    private final Matrix3d inverseRotation = new Matrix3d().identity();
    // Pre-allocated vectors for axis calculations to avoid allocations
    private final Vector3d xAxis = new Vector3d();
    private final Vector3d yAxis = new Vector3d();
    private final Vector3d zAxis = new Vector3d();
    // Cached corner vectors
    private final Vector3d[] cornerCache = new Vector3d[8];
    // Temporary vectors for corner calculations
    private final Vector3d rightTemp = new Vector3d();
    private final Vector3d upTemp = new Vector3d();
    private final Vector3d forwardTemp = new Vector3d();
    // Reusable vectors for ray intersection
    private final Vector3d localOriginCache = new Vector3d();
    private final Vector3d localDirCache = new Vector3d();
    private final double height;
    // Current scale modifier
    @Getter
    private double scaleModifier = 1.0;
    private boolean inverseRotationDirty = true;
    // Cached axis vectors (the 3 principal axes of the OBB)
    private Vector3d[] axes;
    private boolean cornersDirty = true;
    private boolean scaleDirty = true;
    // Current rotation values for change detection
    private double currentYaw = 0d;
    private Location lastLocation = null;
    private ModeledEntity associatedEntity = null; // Reference to the entity for scale calculations

    /**
     * Creates an oriented bounding box
     *
     * @param center Center point of the box
     * @param width  Width of the box (X axis)
     * @param height Height of the box (Y axis)
     * @param depth  Depth of the box (Z axis)
     */
    public OrientedBoundingBox(Vector3d center, double width, double height, double depth) {
        this.height = height;
        this.center.set(center);
        this.baseHalfExtents.set(width / 2d, height / 2d, depth / 2d);
        this.halfExtents.set(baseHalfExtents);

        // Initialize corner cache
        for (int i = 0; i < 8; i++) {
            cornerCache[i] = new Vector3d();
        }

        updateAxes();
    }

    /**
     * Creates an oriented bounding box with scale support
     *
     * @param center Center point of the box
     * @param width  Width of the box (X axis)
     * @param height Height of the box (Y axis)
     * @param depth  Depth of the box (Z axis)
     * @param entity Associated ModeledEntity for scale calculations
     */
    public OrientedBoundingBox(Vector3d center, double width, double height, double depth, ModeledEntity entity) {
        this(center, width, height, depth);
        this.associatedEntity = entity;
        updateScale();
    }

    /**
     * Creates an oriented bounding box from a Bukkit location and dimensions
     */
    public OrientedBoundingBox(Location location, double width, double height, double depth) {
        this(new Vector3d(
                        location.getX(),
                        location.getY() + height / 2d,         // ← bump up by half-height
                        location.getZ()
                ),
                width,
                height,
                depth);
    }

    /**
     * Creates an oriented bounding box from a Bukkit location and dimensions with entity reference
     */
    public OrientedBoundingBox(Location location, double width, double height, double depth, ModeledEntity entity) {
        this(new Vector3d(
                        location.getX(),
                        location.getY() + height / 2d,         // ← bump up by half-height
                        location.getZ()
                ),
                width,
                height,
                depth,
                entity);
    }

    /**
     * Perform a ray trace from a player's eye location in the direction they're looking
     *
     * @param player The player performing the ray trace
     * @return The first modeled entity hit by the ray, if any
     */
    public static Optional<ModeledEntity> raytraceFromPlayer(Player player) {
        return raytraceFromPoint(player.getWorld().getName(), player.getEyeLocation(), Math.max(DefaultConfig.maxInteractionAndAttackDistanceForLivingEntities, DefaultConfig.maxInteractionAndAttackDistanceForProps));
    }

    public static void visualizeOBB(ModeledEntity entity, int durationTicks, Player player) {
        new BukkitRunnable() {
            private int ticksRemaining = durationTicks;

            @Override
            public void run() {
                if (ticksRemaining <= 0 || entity.getWorld() == null) {
                    this.cancel();
                    entity.hideUnderlyingEntity(player);
                    return;
                }

                // Get a fresh OBB every time
                OrientedBoundingBox obb = entity.getHitboxComponent().getObbHitbox();

                // Get the corners of the OBB
                Vector3d[] corners = obb.getCorners();

                // Define the edges of the cube (pairs of corner indices)
                int[][] edges = {
                        {0, 1}, {1, 3}, {3, 2}, {2, 0},  // Top face
                        {4, 5}, {5, 7}, {7, 6}, {6, 4},  // Bottom face
                        {0, 4}, {1, 5}, {2, 6}, {3, 7}   // Connecting edges
                };

                // Draw particles along each edge
                for (int[] edge : edges) {
                    Vector3d start = corners[edge[0]];
                    Vector3d end = corners[edge[1]];

                    // Number of particles to place along the edge
                    int particleCount = 10;

                    for (int i = 0; i <= particleCount; i++) {
                        float t = i / (float) particleCount;
                        double x = start.x + t * (end.x - start.x);
                        double y = start.y + t * (end.y - start.y);
                        double z = start.z + t * (end.z - start.z);

                        Location particleLoc = new Location(entity.getWorld(), x, y, z);
                        entity.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, particleLoc, 1, 0, 0, 0, 0);
                    }
                }

                ticksRemaining--;
            }
        }.runTaskTimer(MetadataHandler.PLUGIN, 0, 1);
    }

    /**
     * Perform a ray trace from a specific point in a specific direction
     *
     * @return The first modeled entity hit by the ray, if any
     */
    public static Optional<ModeledEntity> raytraceFromPoint(
            String worldName, Location location, float maxDistance) {

        // Get all modeled entities
        HashSet<ModeledEntity> entities = ModeledEntityManager.getAllEntities();

        // Filter entities to only include those in the same world
        entities.removeIf(entity -> entity.getWorld() == null ||
                !entity.getWorld().getName().equals(worldName));

        if (entities.isEmpty()) {
            return Optional.empty();
        }

        // Variables to track the closest hit
        ModeledEntity closestEntity = null;
        double closestDistance = maxDistance;

        // Check each entity for intersection
        for (ModeledEntity entity : entities) {
            // Get a fresh OBB for the entity every time
            OrientedBoundingBox obb = entity.getHitboxComponent().getObbHitbox();

            // Check for ray intersection
            double distance = obb.rayIntersection(location, maxDistance);

            // If there's an intersection and it's closer than any previous hit
            if (distance > 0 && distance < closestDistance) {
                if (entity instanceof PropEntity && distance > DefaultConfig.maxInteractionAndAttackDistanceForProps)
                    continue;
                else if (!(entity instanceof PropEntity) && distance > DefaultConfig.maxInteractionAndAttackDistanceForLivingEntities)
                    continue;
                closestEntity = entity;
                closestDistance = distance;
            }
        }

        return Optional.ofNullable(closestEntity);
    }

    /**
     * Updates the scale modifier based on the associated entity
     */
    private void updateScale() {
        if (associatedEntity == null) {
            if (scaleModifier != 1.0) {
                scaleModifier = 1.0;
                scaleDirty = true;
            }
            return;
        }

        double newScaleModifier = associatedEntity.getScaleModifier();

        Attribute scaleAttribute = AttributeManager.getAttribute("generic_scale");

        // Check for generic_scale attribute if entity has a living entity
        if (associatedEntity.getUnderlyingEntity() != null &&
                associatedEntity.getUnderlyingEntity() instanceof LivingEntity livingEntity &&
                scaleAttribute != null &&
                livingEntity.getAttribute(scaleAttribute) != null) {
            newScaleModifier *= livingEntity.getAttribute(scaleAttribute).getValue();
        }

        if (Math.abs(newScaleModifier - scaleModifier) > 0.001) {
            scaleModifier = newScaleModifier;
            scaleDirty = true;
        }
    }

    /**
     * Updates the half extents based on current scale
     */
    private void updateHalfExtents() {
        if (scaleDirty) {
            halfExtents.set(baseHalfExtents).mul(scaleModifier);
            cornersDirty = true; // Corners need to be recalculated when scale changes
            scaleDirty = false;
        }
    }

    /**
     * Sets the associated entity for scale calculations
     */
    public OrientedBoundingBox setAssociatedEntity(ModeledEntity entity) {
        this.associatedEntity = entity;
        updateScale();
        return this;
    }

    /**
     * Updates both position, rotation, and scale from a Location in one efficient call.
     * This is much more efficient than creating a new OrientedBoundingBox.
     *
     * @param location The location to update from
     * @return this OrientedBoundingBox for method chaining
     */
    public OrientedBoundingBox update(Location location) {
        if (lastLocation != null && lastLocation.equals(location)) {
            // Still check for scale updates even if location hasn't changed
            updateScale();
            updateHalfExtents();
            return this;
        }

        lastLocation = location;

        // Update scale first
        updateScale();
        updateHalfExtents();

        // Update position - account for scale in Y offset
        center.set(
                location.getX(),
                location.getY() + halfExtents.y,            // ← use scaled half-extents
                location.getZ()
        );

        // Update rotation
        double yaw = Math.toRadians(-location.getYaw() - 90);

        // Only update rotation if it has changed significantly
        if (Math.abs(currentYaw - yaw) > 0.01d) {
            currentYaw = yaw;
            rotation.identity().rotateY(yaw);
            inverseRotationDirty = true;
            updateAxes();
        }

        // Always mark corners as dirty when position changes
        cornersDirty = true;

        return this;
    }

    /**
     * Updates the axis vectors based on the current rotation matrix
     */
    private void updateAxes() {
        // Reuse existing vectors
        xAxis.set(1, 0, 0).mul(rotation);
        yAxis.set(0, 1, 0).mul(rotation);
        zAxis.set(0, 0, 1).mul(rotation);

        if (axes == null) {
            axes = new Vector3d[]{xAxis, yAxis, zAxis};
        }
    }

    /**
     * Updates the inverse rotation matrix if needed
     */
    private void updateInverseRotation() {
        if (inverseRotationDirty) {
            inverseRotation.set(rotation).transpose();
            inverseRotationDirty = false;
        }
    }

    /**
     * Gets the 8 corners of the OBB in world space
     *
     * @return Array of corner vectors
     */
    public Vector3d[] getCorners() {
        updateHalfExtents(); // Ensure scale is applied
        if (cornersDirty) {
            updateCorners();
        }
        return cornerCache;
    }

    /**
     * Updates the cached corner positions
     */
    private void updateCorners() {
        // Calculate basis vectors using scaled half extents
        rightTemp.set(axes[0]).mul(halfExtents.x);
        upTemp.set(axes[1]).mul(halfExtents.y);
        forwardTemp.set(axes[2]).mul(halfExtents.z);

        // Update corner positions without creating new objects
        cornerCache[0].set(center).add(rightTemp).add(upTemp).add(forwardTemp);      // top front right
        cornerCache[1].set(center).add(rightTemp).add(upTemp).sub(forwardTemp);      // top back right
        cornerCache[2].set(center).add(rightTemp).sub(upTemp).add(forwardTemp);      // bottom front right
        cornerCache[3].set(center).add(rightTemp).sub(upTemp).sub(forwardTemp);      // bottom back right
        cornerCache[4].set(center).sub(rightTemp).add(upTemp).add(forwardTemp);      // top front left
        cornerCache[5].set(center).sub(rightTemp).add(upTemp).sub(forwardTemp);      // top back left
        cornerCache[6].set(center).sub(rightTemp).sub(upTemp).add(forwardTemp);      // bottom front left
        cornerCache[7].set(center).sub(rightTemp).sub(upTemp).sub(forwardTemp);      // bottom back left

        cornersDirty = false;
    }

    /**
     * Checks if a ray from a player's eye location intersects with this OBB.
     * Optimized version that minimizes object creation.
     *
     * @param eyeLocation The eye location of the player
     * @param maxDistance The maximum distance to check
     * @return The distance to the intersection point, or -1 if no intersection
     */
    public double rayIntersection(Location eyeLocation, double maxDistance) {
        // Ensure scale and inverse rotation are up to date
        updateScale();
        updateHalfExtents();
        updateInverseRotation();

        // Set the local origin cache directly from the eye location
        localOriginCache.set(eyeLocation.getX(), eyeLocation.getY(), eyeLocation.getZ());

        // Get direction vector from the location and set the local direction cache
        Vector dir = eyeLocation.getDirection();
        localDirCache.set(dir.getX(), dir.getY(), dir.getZ());

        // Transform origin to local space
        Vector3d tempOrigin = localOriginCache.sub(center);
        tempOrigin.mul(inverseRotation);

        // Transform direction to local space (rotation only, no translation)
        localDirCache.mul(inverseRotation);

        // Standard AABB-ray intersection in local space using scaled half extents
        double tMin = -Double.MAX_VALUE;
        double tMax = Double.MAX_VALUE;

        // For each axis
        for (int i = 0; i < 3; i++) {
            double d = localDirCache.get(i);
            double o = tempOrigin.get(i);
            double e = halfExtents.get(i); // Use scaled half extents

            // Check if ray is parallel to slab
            if (Math.abs(d) < 1e-6) {
                // If origin is outside the slab, no intersection
                if (o > e || o < -e) {
                    return -1;
                }
            } else {
                // Calculate intersections with the axis-aligned slabs
                double t1 = (-e - o) / d;
                double t2 = (e - o) / d;

                // Ensure t1 <= t2
                if (t1 > t2) {
                    double temp = t1;
                    t1 = t2;
                    t2 = temp;
                }

                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);

                // No intersection if tMax < tMin
                if (tMax < tMin) {
                    return -1;
                }
            }
        }

        // Check if intersection is within maxDistance
        if (tMin > maxDistance || tMax < 0) {
            return -1;
        }

        // Return the nearest intersection distance
        return tMin > 0 ? tMin : tMax;
    }

    public boolean isAABBCollidingWithOBB(BoundingBox aabb, OrientedBoundingBox obb) {
        // Ensure both OBBs have updated scale
        updateHalfExtents();
        obb.updateScale();
        obb.updateHalfExtents();

        // Get AABB center and half-extents
        Vector3f aabbCenter = new Vector3f(
                (float) ((aabb.getMinX() + aabb.getMaxX()) / 2),
                (float) ((aabb.getMinY() + aabb.getMaxY()) / 2),
                (float) ((aabb.getMinZ() + aabb.getMaxZ()) / 2)
        );

        Vector3f aabbHalfExtents = new Vector3f(
                (float) ((aabb.getMaxX() - aabb.getMinX()) / 2),
                (float) ((aabb.getMaxY() - aabb.getMinY()) / 2),
                (float) ((aabb.getMaxZ() - aabb.getMinZ()) / 2)
        );

        // Get OBB center and scaled half-extents
        Vector3d obbCenter = obb.getCenter();
        Vector3d obbHalfExtents = obb.getHalfExtents(); // This will return scaled half extents

        // Calculate distance between centers
        double distX = Math.abs(obbCenter.x - aabbCenter.x);
        double distY = Math.abs(obbCenter.y - aabbCenter.y);
        double distZ = Math.abs(obbCenter.z - aabbCenter.z);

        // Check if distances are less than sum of half-extents
        // This is a simplified collision check that works well for most cases
        return distX < (obbHalfExtents.x + aabbHalfExtents.x) &&
                distY < (obbHalfExtents.y + aabbHalfExtents.y) &&
                distZ < (obbHalfExtents.z + aabbHalfExtents.z);
    }

    /**
     * Tests whether a world‐space point lies inside this OBB.
     */
    public boolean containsPoint(Location loc) {
        // ensure our scale, inverse rotation are up-to-date
        updateScale();
        updateHalfExtents();
        updateInverseRotation();

        // move point into OBB's local space
        Vector3d local = new Vector3d(loc.getX(), loc.getY(), loc.getZ());
        local.sub(center);              // translate
        local.mul(inverseRotation);     // rotate

        // AABB‐style test in local coords using scaled half extents
        return Math.abs(local.x) <= halfExtents.x &&
                Math.abs(local.y) <= halfExtents.y &&
                Math.abs(local.z) <= halfExtents.z;
    }
}