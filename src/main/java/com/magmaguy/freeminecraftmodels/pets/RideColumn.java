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
 * <p>
 * A dash is never slowed toward a wall the body meets itself. It covers 2 blocks a tick, further than any brake can
 * stop it in once the trip to the rider's client and back is counted, and the wall stops it anyway with the head
 * only 0.02 blocks in; slowed, a pet that dashed out of the water at a wall on the bank was thrown back into the
 * water in mid-air. It is eased back out to the margin once it is there.
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
    /** The most a body outside the margin is sent back from a block in a tick, in blocks: a nudge, not a throw. */
    static final double NUDGE = 0.05D;
    /**
     * How many times its top swimming speed a body must be heading at to count as dashing: a swim tops out at
     * {@link #swim}, and the dash throws it at three times that.
     */
    static final double DASH = 1.25D;
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
     * moved along the other horizontal axis, {@code delay} the ticks until the rider's client acts on what is
     * sent, and {@code swim} the body's top swimming speed in blocks a tick (see {@link #swim}).
     * <p>
     * A head heading for a block is stopped short of it, and one closer to it than the margin is eased back out,
     * unless the body meets a block of its own first and is dashing: see the class comment.
     */
    static double brake(BoundingBox head, BoundingBox body, boolean alongX, double step, double thrust, double sideways,
                        int delay, double swim, RideCeiling.Blocks blocks) {
        double heading = RideCeiling.WATER_DRAG * step + thrust;
        int wait = Math.max(0, delay);
        for (int sign = 1; sign >= -1; sign -= 2) {
            double toward = sign * heading;
            // Heading the other way, it is leaving whatever is on this side.
            if (toward < -EDGE) continue;
            double reach = MARGIN + SETTLE + Math.max(0D, toward) * (wait + 1);
            double travel = sideways * (wait + 1);
            double room = room(head, alongX, sign, travel, reach, blocks);
            if (room == Double.POSITIVE_INFINITY) continue;
            double allowed = room >= MARGIN && toward > DASH * swim
                    && meets(room, room(body, alongX, sign, travel, reach + DEEP, blocks))
                    ? Double.POSITIVE_INFINITY : allowed(room, toward, wait);
            if (toward > allowed) return sign * (allowed - sign * thrust);
        }
        return Double.NaN;
    }

    /**
     * How far a body may move toward a block in its next tick with {@code room} blocks between its rider's head
     * and the block, heading for it at {@code toward} blocks a tick, when what it is sent takes {@code delay}
     * ticks to act on. Never further than the room left short of the margin once that delay's travel is taken
     * off, so it does not reach the block; all of that room but {@link #SETTLE} while it is far, so it keeps its
     * speed to the last tick; and {@link RideCeiling#GAIN} of it once it is close, so it settles.
     * <p>
     * With no room left it is sent back, the same share of what it is short by. That is what holds a rider who
     * keeps pushing at the block a little outside the margin, whatever the rider's push is misjudged by; held at
     * exactly nothing, the head crept into the block a hundredth of a block at a time. Outside the margin it is
     * sent back {@link #NUDGE} a tick at most: a body that cannot stop in time is stopped, not thrown back. Inside
     * the margin, up to {@link RideCeiling#MAX_PUSH}.
     */
    static double allowed(double room, double toward, int delay) {
        double left = room - MARGIN - Math.max(0D, toward) * Math.max(0, delay);
        return Math.max(room < MARGIN ? -RideCeiling.MAX_PUSH : -NUDGE, Math.max(RideCeiling.GAIN * left, left - SETTLE));
    }

    /**
     * Whether the body meets a block of its own before its rider's head is deep in one: with {@code headRoom}
     * blocks ahead of the head and {@code bodyRoom} ahead of the body, the head is {@code bodyRoom - headRoom} into
     * its block when the body stops at its own. A wall the body swims against leaves that at 0.02; under an
     * overhang the body has nothing ahead and its rider's head goes all the way in.
     */
    static boolean meets(double headRoom, double bodyRoom) {
        return bodyRoom - headRoom <= DEEP;
    }

    /**
     * The velocity a swimming body will have along an axis it is not being slowed on, by the time a velocity sent
     * now reaches its rider's client: it moved {@code step} along the axis in the last tick the server saw, its
     * rider's steering adds {@code thrust} each tick, and the client acts {@code delay} ticks on. Each tick the
     * client adds the thrust, moves, and keeps {@link RideCeiling#WATER_DRAG} of its speed.
     * <p>
     * A velocity sent to slow one axis replaces all three. Handed back as it was when the server last saw the
     * body, the others were set back by those ticks every time, and a pet sliding along a wall its rider pushed
     * at covered a third of the distance it had.
     */
    static double carried(double step, double thrust, int delay) {
        double velocity = RideCeiling.WATER_DRAG * step;
        for (int tick = 0; tick < Math.max(0, delay); tick++) velocity = RideCeiling.WATER_DRAG * (velocity + thrust);
        return velocity;
    }

    /** A ridden nautilus's top swimming speed in blocks a tick: its thrust each tick against the water's drag. */
    static double swim(double speed) {
        return RideCeiling.THRUST * speed / (1D - RideCeiling.WATER_DRAG);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
