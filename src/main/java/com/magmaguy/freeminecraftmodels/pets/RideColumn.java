package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * The room a diving pet's rider takes up over its body, and how far that room is from the blocks beside it.
 * <p>
 * The rider's client steers the pet's nautilus body and stops only that body at a block. The body is 0.44 blocks
 * wide and half a block tall; its rider sits in its middle with their head 1.3 blocks over its top. Two things
 * follow. Swum against a wall, the body stops with its side on the wall, and the rider's head, 0.48 wide as the
 * client tests it, reaches 0.02 blocks into that wall. And the body fits through gaps its rider does not: under an
 * overhang, into a slot along the floor, through a tunnel's mouth from above its ceiling. Either way the client
 * finds one of the eight points it tests round the rider's eyes (0.24 blocks either side, 0.05 above and below)
 * inside a block, and draws that block over the whole screen.
 * <p>
 * So the rider's column, from the body's top to the top of their head and as wide as the client's test, is kept
 * out of blocks as the body is: a gap that fits the pet but not its rider is closed to them both.
 * {@link PetRides} slows the body toward a block the column is heading for, with the brake that keeps the rider's
 * head under a ceiling ({@link RideCeiling#brake}), so the column comes to rest {@link #MARGIN} short of the block
 * and the pet keeps moving along it.
 */
final class RideColumn {
    /** Blocks kept between the rider's column and a block beside it: the client's test must stay clear of the block. */
    static final double MARGIN = RideCeiling.MARGIN;
    /**
     * How far in from its sides a column counts as deep in a block: further in than the brake gets it out of by
     * itself within a tick or two, so the body is moved out.
     */
    static final double DEEP = 0.1D;
    /**
     * Ticks of travel ahead at which the brake starts to bite: it lets the body cover {@link RideCeiling#GAIN} of
     * the room left each tick, so a body closes on a block over 1 / GAIN ticks of its speed.
     */
    static final int LOOK_TICKS = (int) Math.round(1D / RideCeiling.GAIN);
    /** The share of a movement key's push a rider's client hands the body it steers, as vanilla scales it. */
    static final double KEY = 0.98D;
    private static final double EDGE = 1.0E-3D;

    private RideColumn() {
    }

    /** The column over a body: {@code half} blocks either side of its middle, from its top up to {@code headTop}. */
    static BoundingBox over(BoundingBox body, double half, double headTop) {
        double x = body.getCenterX(), z = body.getCenterZ();
        return new BoundingBox(x - half, body.getMaxY(), z - half, x + half, Math.max(body.getMaxY(), headTop), z + half);
    }

    /**
     * The rider's footprint, {@code half} blocks either side of the body's middle and no narrower than the body,
     * as a box from the body's feet up to {@code headTop}: what {@link RideCeiling#over} looks for blocks over the
     * rider's head with. It finds the blocks that start at or above the box's top, so beside the body it counts
     * only what hangs over the rider's head, and never the wall their column is held beside, which starts lower.
     */
    static BoundingBox footprint(BoundingBox body, double half, double headTop) {
        double x = body.getCenterX(), z = body.getCenterZ();
        return new BoundingBox(Math.min(body.getMinX(), x - half), body.getMinY(), Math.min(body.getMinZ(), z - half),
                Math.max(body.getMaxX(), x + half), Math.max(body.getMaxY(), headTop), Math.max(body.getMaxZ(), z + half));
    }

    /**
     * How far a column can travel along one horizontal axis before it reaches a block: along x or else z, the way
     * {@code sign} points (1 or -1). Negative when it already reaches into a block ahead of its middle; positive
     * infinity with nothing within {@code reach} blocks. {@code sideways} is how far it travels along the other
     * horizontal axis meanwhile, signed: the blocks in the way of that travel count too, so a block it nears
     * corner first is seen before the corner is in it. A block its middle is already past, or inside, is not
     * ahead of it.
     */
    static double room(BoundingBox column, boolean alongX, int sign, double sideways, double reach, RideCeiling.Blocks blocks) {
        double minY = column.getMinY() + EDGE, maxY = column.getMaxY() - EDGE;
        double sideMin = (alongX ? column.getMinZ() : column.getMinX()) + Math.min(0D, sideways) + EDGE;
        double sideMax = (alongX ? column.getMaxZ() : column.getMaxX()) + Math.max(0D, sideways) - EDGE;
        double middle = alongX ? column.getCenterX() : column.getCenterZ();
        double front = sign > 0 ? (alongX ? column.getMaxX() : column.getMaxZ()) : (alongX ? column.getMinX() : column.getMinZ());
        double far = front + sign * Math.max(0D, reach);
        double room = Double.POSITIVE_INFINITY;
        for (int along = floor(Math.min(middle, far)); along <= floor(Math.max(middle, far)); along++)
            for (int side = floor(sideMin); side <= floor(sideMax); side++)
                // From the block below: a fence or a wall stands half a block taller than its own block.
                for (int y = floor(minY) - 1; y <= floor(maxY); y++)
                    for (BoundingBox box : blocks.boxes(alongX ? along : side, y, alongX ? side : along)) {
                        if (box.getMaxY() <= minY || box.getMinY() >= maxY) continue;
                        double boxSideMin = alongX ? box.getMinZ() : box.getMinX(), boxSideMax = alongX ? box.getMaxZ() : box.getMaxX();
                        if (boxSideMax <= sideMin || boxSideMin >= sideMax) continue;
                        // The face of the block the column would meet, and how far the column's own face is from it.
                        double face = sign > 0 ? (alongX ? box.getMinX() : box.getMinZ()) : (alongX ? box.getMaxX() : box.getMaxZ());
                        if (sign * (face - middle) < 0D) continue;
                        room = Math.min(room, sign * (face - front));
                    }
        return room;
    }

    /**
     * What a ridden nautilus adds to its velocity in a tick, in blocks a tick along the world's axes, as vanilla
     * works it out on its rider's client: its rider's keys ({@code forward} 1, 0 or -1; {@code strafe} 1 for left,
     * 0, or -1 for right), turned to their view. Forward swims along the view, pitch included; backward swims half
     * as hard the other way; strafing is level; together they share one push. {@code speed} is the body's movement
     * speed.
     */
    static Vector thrust(double speed, float yaw, float pitch, int forward, int strafe) {
        double x = KEY * strafe, y = 0D, z = 0D;
        if (forward != 0) {
            double share = forward > 0 ? 1D : -0.5D, tilt = Math.toRadians(pitch);
            z = Math.cos(tilt) * share;
            y = -Math.sin(tilt) * share;
        }
        double length = x * x + y * y + z * z;
        if (length < 1.0E-7D) return new Vector();
        double scale = RideCeiling.THRUST * speed / (length > 1D ? Math.sqrt(length) : 1D);
        double turn = Math.toRadians(yaw), sin = Math.sin(turn), cos = Math.cos(turn);
        return new Vector((x * cos - z * sin) * scale, y * scale, (z * cos + x * sin) * scale);
    }

    /**
     * The velocity to send a body along one horizontal axis so its rider's column stops {@link #MARGIN} short of
     * the block it is heading for, or NaN when it needs none: {@link RideCeiling#brake} turned sideways.
     * {@code step} is how far the body moved along the axis in the last tick, {@code thrust} what its rider's
     * steering adds along it each tick, {@code sideways} how far it moved along the other horizontal axis, and
     * {@code delay} the ticks until the rider's client acts on what is sent. A column at rest closer to a block
     * than the margin is eased back out to it.
     */
    static double brake(BoundingBox column, boolean alongX, double step, double thrust, double sideways, int delay,
                        RideCeiling.Blocks blocks) {
        double heading = RideCeiling.WATER_DRAG * step + thrust;
        for (int sign = 1; sign >= -1; sign -= 2) {
            double toward = sign * heading;
            // Heading the other way, it is leaving whatever is on this side.
            if (toward < -EDGE) continue;
            double reach = MARGIN + Math.max(0D, toward) * (LOOK_TICKS + Math.max(0, delay) + 1);
            double room = room(column, alongX, sign, sideways * (Math.max(0, delay) + 1), reach, blocks);
            double sent = RideCeiling.brake(room, sign * step, sign * thrust, delay);
            if (!Double.isNaN(sent)) return sign * sent;
        }
        return Double.NaN;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
