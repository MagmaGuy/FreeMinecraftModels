package com.magmaguy.freeminecraftmodels.customentity.core;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Represents a bounding box that can be rotated in any direction.
 * Unlike an axis-aligned bounding box, this can rotate with the model.
 */
public class OrientedBoundingBox {
    // Center point of the box
    private Vector3f center;

    // Half-lengths of the box along its local axes
    private Vector3f halfExtents;

    // Rotation matrix representing the box's orientation
    private Matrix3f rotation;

    // Cached axis vectors (the 3 principal axes of the OBB)
    private Vector3f[] axes;

    /**
     * Creates an oriented bounding box
     *
     * @param center Center point of the box
     * @param width  Width of the box (X axis)
     * @param height Height of the box (Y axis)
     * @param depth  Depth of the box (Z axis)
     */
    public OrientedBoundingBox(Vector3f center, float width, float height, float depth) {
        this.center = new Vector3f(center);
        this.halfExtents = new Vector3f(width / 2f, height / 2f, depth / 2f);
        this.rotation = new Matrix3f().identity(); // Initially aligned with world axes
        this.axes = new Vector3f[3];
        updateAxes();
    }

    /**
     * Creates an oriented bounding box from a Bukkit location and dimensions
     */
    public OrientedBoundingBox(Location location, float width, float height, float depth) {
        this(new Vector3f((float) location.getX(), (float) location.getY(), (float) location.getZ()),
                width, height, depth);
    }

    /**
     * Get the center point of the box
     */
    public Vector3f getCenter() {
        return new Vector3f(center);
    }

    /**
     * Update the box's center position
     */
    public void setCenter(Vector3f center) {
        this.center = new Vector3f(center);
    }

    /**
     * Update the box's center position from a Bukkit location
     */
    public void setCenter(Location location) {
        this.center = new Vector3f((float) location.getX(), (float) location.getY(), (float) location.getZ());
    }

    /**
     * Get the half-extents of the box
     */
    public Vector3f getHalfExtents() {
        return new Vector3f(halfExtents);
    }

    /**
     * Get the rotation matrix
     */
    public Matrix3f getRotation() {
        return new Matrix3f(rotation);
    }

    /**
     * Set the rotation of the box using a rotation matrix
     */
    public void setRotation(Matrix3f rotation) {
        this.rotation = new Matrix3f(rotation);
        updateAxes();
    }

    /**
     * Set the rotation of the box using Euler angles (in radians)
     */
    public void setRotation(float yaw, float pitch, float roll) {
        // Apply rotations in ZYX order
        this.rotation = new Matrix3f().identity()
                .rotateY(yaw)    // Y-axis rotation (yaw)
                .rotateX(pitch)  // X-axis rotation (pitch)
                .rotateZ(roll);  // Z-axis rotation (roll)
        updateAxes();
    }

    /**
     * Set the rotation from a location's yaw and pitch (in degrees)
     */
    public void setRotationFromLocation(Location location) {
        float yaw = (float) Math.toRadians(-location.getYaw() - 90);
        float pitch = (float) Math.toRadians(-location.getPitch());
        setRotation(yaw, pitch, 0);
    }

    /**
     * Apply a transformation matrix to this OBB
     */
    public void applyTransformation(Matrix4f transform) {
        // Extract and apply rotation
        Matrix3f newRotation = new Matrix3f();
        transform.get3x3(newRotation);
        this.rotation.mul(newRotation);

        // Apply translation to center
        Vector3f translation = new Vector3f();
        transform.getTranslation(translation);
        this.center.add(translation);

        updateAxes();
    }

    /**
     * Updates the axis vectors based on the current rotation matrix
     */
    private void updateAxes() {
        this.axes[0] = new Vector3f(1, 0, 0).mul(rotation);
        this.axes[1] = new Vector3f(0, 1, 0).mul(rotation);
        this.axes[2] = new Vector3f(0, 0, 1).mul(rotation);
    }

    /**
     * Converts a world-space point to the local space of this OBB
     */
    public Vector3f worldToLocal(Vector3f worldPoint) {
        Vector3f localPoint = new Vector3f(worldPoint).sub(center);

        // Transform by inverse rotation (transpose for orthogonal matrices)
        Matrix3f inverseRotation = new Matrix3f(rotation).transpose();
        return localPoint.mul(inverseRotation);
    }

    /**
     * Gets the 8 corners of the OBB in world space
     */
    public Vector3f[] getCorners() {
        Vector3f[] corners = new Vector3f[8];

        // Calculate the 8 corners based on center, extents, and axes
        Vector3f right = new Vector3f(axes[0]).mul(halfExtents.x);
        Vector3f up = new Vector3f(axes[1]).mul(halfExtents.y);
        Vector3f forward = new Vector3f(axes[2]).mul(halfExtents.z);

        corners[0] = new Vector3f(center).add(right).add(up).add(forward);      // top front right
        corners[1] = new Vector3f(center).add(right).add(up).sub(forward);      // top back right
        corners[2] = new Vector3f(center).add(right).sub(up).add(forward);      // bottom front right
        corners[3] = new Vector3f(center).add(right).sub(up).sub(forward);      // bottom back right
        corners[4] = new Vector3f(center).sub(right).add(up).add(forward);      // top front left
        corners[5] = new Vector3f(center).sub(right).add(up).sub(forward);      // top back left
        corners[6] = new Vector3f(center).sub(right).sub(up).add(forward);      // bottom front left
        corners[7] = new Vector3f(center).sub(right).sub(up).sub(forward);      // bottom back left

        return corners;
    }

    /**
     * Checks if a ray intersects with this OBB
     *
     * @param origin      The origin point of the ray
     * @param direction   The direction vector of the ray (normalized)
     * @param maxDistance The maximum distance to check
     * @return The distance to the intersection point, or -1 if no intersection
     */
    public float rayIntersection(Vector3f origin, Vector3f direction, float maxDistance) {
        // Convert ray to OBB's local space
        Vector3f localOrigin = worldToLocal(origin);

        // Convert direction to local space (rotation only, no translation)
        Matrix3f inverseRotation = new Matrix3f(rotation).transpose();
        Vector3f localDirection = new Vector3f(direction).mul(inverseRotation);

        // Standard AABB-ray intersection in local space
        float tMin = -Float.MAX_VALUE;
        float tMax = Float.MAX_VALUE;

        // For each axis
        for (int i = 0; i < 3; i++) {
            float d = localDirection.get(i);
            float o = localOrigin.get(i);
            float e = halfExtents.get(i);

            // Check if ray is parallel to slab
            if (Math.abs(d) < 1e-6) {
                // If origin is outside the slab, no intersection
                if (o > e || o < -e) {
                    return -1;
                }
            } else {
                // Calculate intersections with the axis-aligned slabs
                float t1 = (-e - o) / d;
                float t2 = (e - o) / d;

                // Ensure t1 <= t2
                if (t1 > t2) {
                    float temp = t1;
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

    /**
     * Checks if a ray from Bukkit vectors intersects with this OBB
     */
    public float rayIntersection(Vector origin, Vector direction, float maxDistance) {
        return rayIntersection(
                new Vector3f((float) origin.getX(), (float) origin.getY(), (float) origin.getZ()),
                new Vector3f((float) direction.getX(), (float) direction.getY(), (float) direction.getZ()).normalize(),
                maxDistance
        );
    }
}