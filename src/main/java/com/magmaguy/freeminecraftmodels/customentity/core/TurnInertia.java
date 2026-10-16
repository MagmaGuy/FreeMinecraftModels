package com.magmaguy.freeminecraftmodels.customentity.core;

/**
 * Turns a model's facing toward a target yaw with inertia: an angular velocity that may change by a limited
 * acceleration per tick, up to a speed cap. It spins up, cruises, and brakes onto the target without overshooting.
 * Both limits fall with the model's size: the cap by its square root, the acceleration by the size itself.
 */
public final class TurnInertia {
    private static final double VANILLA_MAX_TURN = 90;
    private static final double MIN_SIZE = 0.25;
    private float yaw = Float.NaN;
    private double velocity = 0;

    /** The current yaw in degrees, in [-180, 180), or NaN before the first step or reset. */
    public float getYaw() {
        return yaw;
    }

    /** Faces {@code target} at once and drops any turning speed. */
    public void reset(float target) {
        yaw = (float) wrap(target);
        velocity = 0;
    }

    /**
     * Advances one tick toward {@code target}.
     *
     * @param size         the model's size in blocks
     * @param speedCap     degrees per tick for a model one block in size
     * @param acceleration degrees per tick per tick for a model one block in size
     * @return the new yaw
     */
    public float step(float target, double size, double speedCap, double acceleration) {
        if (Float.isNaN(yaw)) {
            reset(target);
            return yaw;
        }
        double clampedSize = Math.max(MIN_SIZE, size);
        double cap = Math.min(VANILLA_MAX_TURN, Math.max(0, speedCap) / Math.sqrt(clampedSize));
        double accel = Math.max(0, acceleration) / clampedSize;
        if (cap <= 0 || accel <= 0) {
            reset(target);
            return yaw;
        }
        double remaining = wrap(target - yaw);
        // The fastest speed that can still brake to a stop on the target.
        double desired = Math.signum(remaining) * Math.min(cap, Math.sqrt(2 * accel * Math.abs(remaining)));
        double next = velocity + Math.max(-accel, Math.min(accel, desired - velocity));
        boolean crosses = Math.signum(next) == Math.signum(remaining) && Math.abs(next) >= Math.abs(remaining);
        boolean settled = Math.abs(remaining) <= accel && Math.abs(velocity) <= accel;
        if (crosses || settled) {
            reset(target);
            return yaw;
        }
        velocity = next;
        yaw = (float) wrap(yaw + next);
        return yaw;
    }

    /** Wraps degrees to [-180, 180). */
    public static double wrap(double degrees) {
        double wrapped = degrees % 360;
        if (wrapped >= 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }
}
