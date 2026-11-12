package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * The room a diving pet's rider's head takes up over its body, and how far that room is from the blocks beside it.
 * <p>
 * The rider's client steers the pet's nautilus body and stops only that body at a block. The body is 0.44 blocks
 * wide and half a block tall; its rider sits in its middle with their head 1.3 blocks over its top. Two things
 * follow. Swum against a wall, the body stops with its side on the wall, and the rider's head, 0.48 wide as the
 * client tests it, reaches 0.02 blocks into that wall. And the body fits through gaps its rider does not: under an
 * overhang, into a slot along the floor, through a tunnel's mouth from above its ceiling. Either way the client
 * finds one of the eight points it tests round the rider's eyes (0.24 blocks either side, 0.05 above and below)
 * inside a block, and draws that block over the whole screen.
 * <p>
 * So the rider's head, the top {@link #HEAD} blocks of them and as wide as the client's test, is kept out of blocks
 * as the body is: a gap that fits the pet but not its rider's head is closed to them both. {@link PetRides} stops
 * the body along one axis as the head reaches a block ({@link #brake}), {@link #MARGIN} short of it, and the pet
 * keeps the rest of its travel, sliding on along the block.
 * <p>
 * The head alone, not the whole rider: what is lower than the rider's head is no taller than a bank or a beach's
 * step, which the body meets itself, and a pet slowed toward every such block lost the pace it leaps out of the
 * water with. And only at the last moment: a pet slowed from blocks away fell short of the bank it dashed at.
 */
final class RideColumn {
    /** Blocks kept between the rider's head and a block beside it: the client's test must stay clear of the block. */
    static final double MARGIN = RideCeiling.MARGIN;
    /** How much of the rider counts as their head, from the top of it down, in blocks: a player's head is half a block. */
    static final double HEAD = 0.5D;
    /**
     * How far short of the margin a body may still be heading at full speed, in blocks: closer, it settles onto the
     * margin a share of the room left each tick ({@link RideCeiling#GAIN}), so it neither bounces nor creeps in
     * from blocks away.
     */
    static final double SETTLE = 0.15D;
    /**
     * How far in from its sides a column counts as deep in a block: further in than the brake gets it out of by
     * itself within a tick or two, so the body is moved out.
     */
    static final double DEEP = 0.1D;
    /**
     * Ticks of travel further ahead than before that a ceiling is looked for over where the body is heading: the
     * brake under a ceiling lets the body cover {@link RideCeiling#GAIN} of the room left each tick, so it needs
     * 1 / GAIN ticks to take a body down under a tunnel's mouth before its rider's head gets there.
     */
    static final int LOOK_TICKS = (int) Math.round(1D / RideCeiling.GAIN);
    /** The share of a movement key's push a rider's client hands the body it steers, as vanilla scales it. */
    static final double KEY = 0.98D;
    private static final double EDGE = 1.0E-3D;

    private RideColumn() {
    }

    /**
     * The rider's head over a body: {@code half} blocks either side of the body's middle, the top {@link #HEAD}
     * blocks under {@code headTop}, and never lower than the body's own top.
     */
    static BoundingBox head(BoundingBox body, double half, double headTop) {
        double x = body.getCenterX(), z = body.getCenterZ();
        double top = Math.max(body.getMaxY(), headTop);
        return new BoundingBox(x - half, Math.max(body.getMaxY(), top - HEAD), z - half, x + half, top, z + half);
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
     * How far a rider's head can travel along one horizontal axis before it reaches a block: along x or else z, the way
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
     * The velocity to send a body along one horizontal axis so its rider's head stops {@link #MARGIN} short of the
     * block it is heading for, or NaN when it needs none. {@code step} is how far the body moved along the axis in
     * the last tick, {@code thrust} what its rider's steering adds along it each tick, {@code sideways} how far it
     * moved along the other horizontal axis, and {@code delay} the ticks until the rider's client acts on what is
     * sent. A head at rest closer to a block than the margin is eased back out to it.
     */
    static double brake(BoundingBox head, boolean alongX, double step, double thrust, double sideways, int delay,
                        RideCeiling.Blocks blocks) {
        double heading = RideCeiling.WATER_DRAG * step + thrust;
        int wait = Math.max(0, delay);
        for (int sign = 1; sign >= -1; sign -= 2) {
            double toward = sign * heading;
            // Heading the other way, it is leaving whatever is on this side.
            if (toward < -EDGE) continue;
            double reach = MARGIN + SETTLE + Math.max(0D, toward) * (wait + 1);
            double room = room(head, alongX, sign, sideways * (wait + 1), reach, blocks);
            double allowed = allowed(room, toward, wait);
            if (toward > allowed) return sign * (allowed - sign * thrust);
        }
        return Double.NaN;
    }

    /**
     * How far a body may move toward a block in its next tick with {@code room} blocks between its rider's head
     * and the block, heading for it at {@code toward} blocks a tick, when what it is sent takes {@code delay}
     * ticks to act on. Never further than the room left short of the margin once that delay's travel is taken
     * off, so it does not reach the block; all of that room but {@link #SETTLE} while it is far, so it keeps its
     * speed to the last tick; and {@link RideCeiling#GAIN} of it once it is close, so it settles. With no room
     * left it is sent back, at most {@link RideCeiling#MAX_PUSH} a tick. Positive infinity with no block there.
     */
    static double allowed(double room, double toward, int delay) {
        if (room == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY;
        double left = room - MARGIN - Math.max(0D, toward) * Math.max(0, delay);
        return Math.max(-RideCeiling.MAX_PUSH, Math.max(RideCeiling.GAIN * left, left - SETTLE));
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
