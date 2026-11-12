package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.util.BoundingBox;

/**
 * The hop a ridden pet makes as it leaves the water: surfacing onto its walking body in the shallows, it keeps the
 * speed it swam in with and is given a lift, so pet and rider jump out onto the bank instead of stopping dead and
 * wading. The body is steered by its rider's client, which takes a velocity the server sets on it as its own.
 * <p>
 * A hop is only given where its whole flight is clear. The body's box and the room its rider's head needs are
 * followed along the path the velocity would take them through the air. Anything in the way of the rider's head, a
 * ledge or a wall, means no hop at all, and so does a wall in the way of the body. A block the body could step onto
 * anyway, a bank no more than {@link #STEP_UP} above where it stands, only ends its travel sideways: it rises on
 * beside the bank as it would after walking into it. The flight is worked out as through air, where the body goes
 * furthest; water slows the real one, which so stays inside it.
 */
final class RideHop {
    /** How much a mob's upward speed falls each tick in the air, and the share of its speed it then keeps, as vanilla moves it. */
    private static final double GRAVITY = 0.08D, FALL_DRAG = 0.98D;
    /** The share of its sideways speed a mob keeps each tick in the air, as vanilla moves it. */
    private static final double AIR_DRAG = 0.91D;
    /** The height a ridden horse steps up without jumping, in blocks, as vanilla moves it. */
    static final double STEP_UP = 1D;
    /** The longest flight followed, in ticks: a hop is down again well inside it. */
    private static final int TICKS = 40;
    /** The longest step followed at once, in blocks, so a fast hop is not checked only at either side of a block. */
    private static final double STEP = 0.25D;
    private static final double EDGE = 1.0E-3D;

    private RideHop() {
    }

    /**
     * The pace a diving pet carries after a step of ({@code stepX}, {@code stepZ}) blocks, given the pace it carried
     * before: the step's length, or what a tick of coasting through water leaves of the earlier pace when that is
     * more. A beach's steps stop a swimming body block by block, so by the tick it surfaces its last step is a tenth
     * of a block however fast it came in (0.11 measured, from 0.6); the pace remembers the swim for about a second.
     */
    static double pace(double before, double stepX, double stepZ) {
        return Math.max(Math.sqrt(stepX * stepX + stepZ * stepZ), before * RideCeiling.WATER_DRAG);
    }

    /**
     * The lift for a pet that swam in at {@code step} blocks a tick, of the {@code lift} its species gives one that
     * swam in at {@code full}: its share of full speed, so a pet that drifts in barely lifts and one at full speed
     * gets it all.
     */
    static double lift(double lift, double step, double full) {
        if (lift <= 0D || step <= 0D || full <= 0D) return 0D;
        return lift * Math.min(1D, step / full);
    }

    /**
     * Whether a body that fits at these coordinates and sets off at this velocity flies clear until it comes down
     * (see the class comment for what may be in its way).
     */
    static boolean clear(double x, double y, double z, double vx, double vy, double vz, BodyFit.Shape shape,
                         RideCeiling.Blocks blocks) {
        double ground = y;
        for (int tick = 0; tick < TICKS; tick++) {
            double far = Math.sqrt(vx * vx + vy * vy + vz * vz);
            int steps = Math.max(1, (int) Math.ceil(far / STEP));
            double stepX = vx / steps, stepY = vy / steps, stepZ = vz / steps;
            for (int step = 0; step < steps; step++) {
                if (BodyFit.fits(x + stepX, y + stepY, z + stepZ, shape, blocks)) {
                    x += stepX;
                    y += stepY;
                    z += stepZ;
                    continue;
                }
                if (!BodyFit.fits(x, y + stepY, z, shape, blocks))
                    // Straight up or down from here is blocked too: the ground it comes down on, or a block over it.
                    return stepY < 0D;
                // Only its travel sideways is blocked. A step it could walk onto stops that and no more.
                if (!steppable(x + stepX, y + stepY, z + stepZ, ground, shape, blocks)) return false;
                vx = vz = stepX = stepZ = 0D;
                y += stepY;
            }
            vy = (vy - GRAVITY) * FALL_DRAG;
            vx *= AIR_DRAG;
            vz *= AIR_DRAG;
        }
        return true;
    }

    /**
     * Whether what the shape meets at these coordinates is something the body walks onto: no block in the way of its
     * box, or of the room over it for its rider, that stands taller than a step above the ground it set off from.
     * The top edge of a bank it rises beside is in the lower part of that room for a moment and is the same step.
     */
    private static boolean steppable(double x, double y, double z, double ground, BodyFit.Shape shape, RideCeiling.Blocks blocks) {
        for (BoundingBox part : shape.at(x, y, z))
            for (BoundingBox block : BodyFit.colliders(part, blocks))
                if (block.getMaxY() > ground + STEP_UP + EDGE) return false;
        return true;
    }
}
