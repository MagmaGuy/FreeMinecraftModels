package com.magmaguy.freeminecraftmodels.customentity.core;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Turns a model's travel into a tilt: each tick it reads where the body is, smooths that into a velocity, works out
 * the lean the {@link TravelOrientation} asks for at that speed and direction, and turns the current tilt toward it
 * at a limited rate, easing in at the end. A stopped model eases back upright. The tilt is a world-space rotation
 * applied to the whole model after its facing (see {@code ModeledEntity#setBodyTilt}).
 * <p>
 * One instance belongs to one model and is only stepped by its model tick.
 */
public final class TravelTilt {
    // A move this long in one tick is a teleport, not travel: it resets the velocity instead of feeding it.
    private static final double TELEPORT_BLOCKS = 2.5D;
    // Share of each tick's motion the smoothed velocity takes in; the model clock can see 0 or 2 ticks of motion.
    private static final double VELOCITY_SMOOTHING = 0.25D;
    // Share of the remaining turn taken per tick, so the tilt eases onto its target instead of snapping.
    private static final double EASE = 0.12D;
    // The smallest turn per tick, in degrees, so the ease settles in finite time.
    private static final double MIN_STEP = 0.2D;
    // Below this, in degrees, the model counts as upright.
    private static final double UPRIGHT_EPSILON = 0.05D;
    // Front-first bank: degrees of roll per degree per tick of turning at full speed, and the most it rolls.
    private static final double BANK_PER_TURN = 8D, MAX_BANK = 45D;
    private static final Vector3d UP = new Vector3d(0D, 1D, 0D);

    private final Vector3d velocity = new Vector3d();
    private final Quaterniond current = new Quaterniond();
    private final Quaterniond target = new Quaterniond();
    private final Vector3d scratch = new Vector3d();
    private double lastX, lastY, lastZ;
    private boolean tracking;
    private float lastYaw = Float.NaN;

    /** Forgets the motion seen so far and stands the model upright at once. */
    public void reset() {
        velocity.zero();
        current.identity();
        tracking = false;
        lastYaw = Float.NaN;
    }

    /** The smoothed velocity, in blocks per tick. */
    public Vector3d velocity(Vector3d result) {
        return result.set(velocity);
    }

    /**
     * Advances one tick.
     *
     * @param x        where the body is now, as are y and z
     * @param yaw      the model's facing in degrees (Minecraft yaw: 0 is south, 90 west)
     * @param settings the orientation to lean toward
     * @param free     false when the model must ease upright whatever it does: dying, ridden, frozen
     * @return the tilt to apply, or null once the model is upright
     */
    public Quaterniond step(double x, double y, double z, float yaw, TravelOrientation settings, boolean free) {
        trackVelocity(x, y, z);
        float yawRate = Float.isNaN(lastYaw) || Float.isNaN(yaw) ? 0F : (float) TurnInertia.wrap(yaw - lastYaw);
        lastYaw = yaw;
        if (!free || settings.style() == TravelOrientation.Style.UPRIGHT) target.identity();
        else if (settings.style() == TravelOrientation.Style.TOP_FIRST) topFirst(settings, yaw);
        else frontFirst(settings, yaw, yawRate);
        turnTowardTarget(settings.turnSpeed() / 20D);
        return degrees(current) < UPRIGHT_EPSILON ? null : new Quaterniond(current);
    }

    private void trackVelocity(double x, double y, double z) {
        if (!tracking) {
            tracking = true;
        } else {
            double dx = x - lastX, dy = y - lastY, dz = z - lastZ;
            if (dx * dx + dy * dy + dz * dz > TELEPORT_BLOCKS * TELEPORT_BLOCKS) velocity.zero();
            else velocity.add((dx - velocity.x) * VELOCITY_SMOOTHING, (dy - velocity.y) * VELOCITY_SMOOTHING,
                    (dz - velocity.z) * VELOCITY_SMOOTHING);
        }
        lastX = x;
        lastY = y;
        lastZ = z;
    }

    /** How much of the full lean the current speed earns: 0 at the start speed, 1 at full speed, smooth between. */
    private double weight(TravelOrientation settings) {
        double speed = velocity.length() * 20D;
        double share = (speed - settings.startSpeed()) / (settings.fullSpeed() - settings.startSpeed());
        share = Math.max(0D, Math.min(1D, share));
        return share * share * (3D - 2D * share);
    }

    /** The model's top turns toward its travel: horizontal travel lays it on its side, a dive turns it over. */
    private void topFirst(TravelOrientation settings, float yaw) {
        double weight = weight(settings);
        if (weight <= 0D) {
            target.identity();
            return;
        }
        Vector3d direction = scratch.set(velocity).normalize();
        double full = Math.toDegrees(Math.acos(Math.max(-1D, Math.min(1D, direction.y))));
        double angle = Math.min(full * weight, settings.maxTilt());
        // up × direction: the axis that turns up toward the travel.
        double axisX = direction.z, axisZ = -direction.x;
        double length = Math.sqrt(axisX * axisX + axisZ * axisZ);
        if (length < 1.0E-4D) {
            if (direction.y > 0D) {
                target.identity();
                return;
            }
            // Straight down: keep turning over the way it already leans, or over its front when it doesn't lean.
            Vector3d leaned = current.transform(new Vector3d(UP));
            axisX = leaned.z;
            axisZ = -leaned.x;
            length = Math.sqrt(axisX * axisX + axisZ * axisZ);
            if (length < 1.0E-4D) {
                double forwardX = -Math.sin(Math.toRadians(yaw)), forwardZ = Math.cos(Math.toRadians(yaw));
                axisX = forwardZ;
                axisZ = -forwardX;
                length = 1D;
            }
        }
        target.rotationAxis(Math.toRadians(angle), axisX / length, 0D, axisZ / length);
    }

    /** The model's front pitches toward its climb or dive and it banks into its turns. */
    private void frontFirst(TravelOrientation settings, float yaw, float yawRate) {
        double weight = weight(settings);
        if (weight <= 0D) {
            target.identity();
            return;
        }
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        double forwardX, forwardZ;
        if (horizontal > 1.0E-4D) {
            forwardX = velocity.x / horizontal;
            forwardZ = velocity.z / horizontal;
        } else {
            forwardX = -Math.sin(Math.toRadians(yaw));
            forwardZ = Math.cos(Math.toRadians(yaw));
        }
        double limit = settings.maxTilt();
        double pitch = Math.toDegrees(Math.atan2(velocity.y, horizontal)) * weight;
        pitch = Math.max(-Math.min(90D, limit), Math.min(Math.min(90D, limit), pitch));
        // forward × up turns the front upward for a positive angle.
        Quaterniond pitchTurn = new Quaterniond().rotationAxis(Math.toRadians(pitch), -forwardZ, 0D, forwardX);
        double bank = Math.max(-Math.min(MAX_BANK, limit), Math.min(Math.min(MAX_BANK, limit), yawRate * BANK_PER_TURN * weight));
        // Turning about its own front by a positive angle rolls its top toward its right: into a right turn.
        Vector3d front = pitchTurn.transform(new Vector3d(forwardX, 0D, forwardZ));
        target.rotationAxis(Math.toRadians(bank), front.x, front.y, front.z).mul(pitchTurn);
    }

    private void turnTowardTarget(double maxStepDegrees) {
        double remaining = Math.toDegrees(angleBetween(current, target));
        if (remaining <= 1.0E-6D) {
            current.set(target);
            return;
        }
        double turn = Math.min(remaining, Math.min(maxStepDegrees, Math.max(remaining * EASE, MIN_STEP)));
        if (turn >= remaining) current.set(target);
        else current.slerp(target, turn / remaining);
        current.normalize();
    }

    private static double angleBetween(Quaterniond a, Quaterniond b) {
        double dot = Math.abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w);
        return 2D * Math.acos(Math.min(1D, dot));
    }

    /** The rotation's angle in degrees, 0 to 180. */
    static double degrees(Quaterniond rotation) {
        return Math.toDegrees(2D * Math.acos(Math.min(1D, Math.abs(rotation.w))));
    }
}
