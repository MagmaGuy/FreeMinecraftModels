package com.magmaguy.freeminecraftmodels.particles.held;

import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Estimates where a point of a held item model is in the world, following the 26.x client's
 * third-person chain: living-entity body transform, player model arm pose, ItemInHandLayer's
 * hand offset and the item's display transform. The server cannot see walking swing, idle arm
 * bob, attack swings or the camera perspective, so this is the pose of a player standing or
 * sneaking with the item held out.
 */
public final class HandTipEstimator {
    private static final double PLAYER_MODEL_SCALE = 0.9375;
    private static final double RENDER_LIFT = -1.501;

    /**
     * An item model display transform as written in its JSON: rotation in degrees, translation
     * in pixels, scale as a factor.
     */
    public record ItemTransform(Vector3d rotation, Vector3d translation, Vector3d scale) {
    }

    private HandTipEstimator() {
    }

    /** Converts a free-format Blockbench position (pixels) to Java item model pixels. */
    public static Vector3d itemSpace(Vector3d blockbenchPosition) {
        return new Vector3d(blockbenchPosition).add(8, 0, 8);
    }

    /**
     * @param feet       player position
     * @param bodyYaw    body yaw in Minecraft degrees
     * @param leftArm    true when the item is drawn in the left arm
     * @param scale      the player's scale attribute
     * @param transform  the item's third-person display transform for that arm, or null
     * @param itemPoint  point in item model pixels (0 to 16 spans the model)
     */
    public static void estimate(Vector3d feet, float bodyYaw, boolean sneaking, boolean leftArm, double scale,
                                ItemTransform transform, Vector3d itemPoint, Vector3d position, Quaterniond rotation) {
        Matrix4d matrix = new Matrix4d()
                .translation(feet.x, feet.y + (sneaking ? scale * -2.0 / 16.0 : 0), feet.z)
                .scale(scale)
                .rotateY(Math.toRadians(180 - bodyYaw))
                .scale(-1, -1, 1)
                .scale(PLAYER_MODEL_SCALE)
                .translate(0, RENDER_LIFT, 0);

        // HumanoidModel: arm pivot, the ITEM arm pose, and the crouch adjustments.
        matrix.translate((leftArm ? 5 : -5) / 16.0, (2 + (sneaking ? 3.2 : 0)) / 16.0, 0)
                .rotateX(-Math.PI / 10 + (sneaking ? 0.4 : 0));

        // ItemInHandLayer.submitArmWithItem.
        matrix.rotateX(Math.toRadians(-90))
                .rotateY(Math.toRadians(180))
                .translate((leftArm ? -1 : 1) / 16.0, 2 / 16.0, -10 / 16.0);

        // ItemTransform.apply, with the left-hand mirror.
        if (transform != null) {
            double sign = leftArm ? -1 : 1;
            matrix.translate(sign * clamp(transform.translation().x, 80) / 16.0,
                    clamp(transform.translation().y, 80) / 16.0, clamp(transform.translation().z, 80) / 16.0);
            matrix.rotate(new Quaterniond().rotationXYZ(Math.toRadians(transform.rotation().x),
                    Math.toRadians(sign * transform.rotation().y), Math.toRadians(sign * transform.rotation().z)));
            matrix.scale(clamp(transform.scale().x, 4), clamp(transform.scale().y, 4), clamp(transform.scale().z, 4));
        }
        matrix.getNormalizedRotation(rotation);
        matrix.translate(-0.5, -0.5, -0.5);
        matrix.transformPosition(itemPoint.x / 16.0, itemPoint.y / 16.0, itemPoint.z / 16.0, position);
    }

    private static double clamp(double value, double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }
}
