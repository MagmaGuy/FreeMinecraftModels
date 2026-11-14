package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Keeps the rider of a diving pet under the blocks over them. The rider's client steers the pet's nautilus body, and
 * vanilla stops only that body at a block: a body scaled to put its saddle on the pet's seat is about half a block
 * tall, and its rider's head is about 1.3 blocks above its top. Swum up under a sheet of ice, the body stopped at the
 * ice with its rider's head inside it, where they suffocated.
 * <p>
 * The server cannot move a body its rider's client steers without that client seeing a correction, so this works in
 * two steps. As the rider's head nears a block from below, the body is sent a velocity that slows its climb so the
 * head stops just under the block; the client takes the velocity as its own and keeps swimming, sideways included.
 * A head that still ends up in a block, as after a dash straight up, has its body moved down until the head is out,
 * and its rider's client told where the body now is.
 */
final class RideCeiling {
    /** Blocks kept between the top of the rider's head and the block over it. */
    static final double MARGIN = 0.05D;
    /** The share of the room left under a block that the body may climb in a tick, so it settles under the block. */
    static final double GAIN = 0.25D;
    /** The most a body under a block is sent down in a tick, in blocks: a quick duck, not a throw. */
    static final double MAX_PUSH = 0.4D;
    /** How far above the rider's head a block is looked for, in blocks: past it nothing needs slowing yet. */
    static final double REACH = 6D;
    /** How far above its feet a seated player's seat point is, as vanilla seats every player. */
    static final double PLAYER_SEAT = 0.6D;
    /** The share of its speed a nautilus keeps each tick in water, as vanilla moves it. */
    static final double WATER_DRAG = 0.9D;
    /** A ridden nautilus's thrust in water, in blocks per tick per point of movement speed, as vanilla moves it. */
    static final double THRUST = 0.0325D;
    private static final double EDGE = 1.0E-3D;
    /** The step the way ahead is walked in, in blocks: under the body's own width, so no full block is stepped over. */
    private static final double STRIDE = 0.25D;

    private RideCeiling() {
    }

    /** The collision boxes of the block at these coordinates, in world coordinates; empty for one nothing collides with. */
    @FunctionalInterface
    interface Blocks {
        Collection<BoundingBox> boxes(int x, int y, int z);
    }

    /** The blocks of a world. Passable blocks, water among them, have no boxes. */
    static Blocks of(World world) {
        return (x, y, z) -> {
            if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return List.of();
            Block block = world.getBlockAt(x, y, z);
            if (block.isPassable()) return List.of();
            List<BoundingBox> boxes = new ArrayList<>(1);
            for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) boxes.add(box.clone().shift(x, y, z));
            return boxes;
        };
    }

    /**
     * The underside of the lowest block over a body, and over each place it gets to on its way ({@code aheadX},
     * {@code aheadZ}) from where it is: see {@link #over}. Positive infinity when nothing is there.
     * <p>
     * The way ahead ends at the first place the body's own box does not fit. A bank or a wall in its path stops the
     * body, as vanilla keeps its box out of blocks, so it is not a ceiling, and neither is anything past it. Counted
     * as one, the bank a pet dashed at out of the water had its rider's head "in" it from several blocks off, and the
     * pet was sent down at {@link #MAX_PUSH} a tick, into the foot of the bank it would have leapt onto.
     */
    static double above(BoundingBox body, double aheadX, double aheadZ, double upTo, Blocks blocks) {
        return above(body, body, aheadX, aheadZ, upTo, blocks);
    }

    /**
     * {@link #above(BoundingBox, double, double, double, Blocks)} with the blocks looked for over {@code footprint}
     * in place of the body's own box: its rider's head is a little wider than a diving body (see
     * {@link RideColumn#footprint}), so a block can hang over the rider's head beside the body. The footprint
     * travels with the body; the way ahead still ends where the body's own box does not fit.
     */
    static double above(BoundingBox body, BoundingBox footprint, double aheadX, double aheadZ, double upTo, Blocks blocks) {
        double lowest = over(footprint, upTo, blocks);
        double far = Math.sqrt(aheadX * aheadX + aheadZ * aheadZ);
        int strides = (int) Math.ceil(far / STRIDE);
        for (int stride = 1; stride <= strides; stride++) {
            double share = (double) stride / strides;
            BoundingBox there = body.clone().shift(aheadX * share, 0D, aheadZ * share);
            if (BodyFit.anyCollider(there, blocks)) break;
            lowest = Math.min(lowest, over(footprint.clone().shift(aheadX * share, 0D, aheadZ * share), upTo, blocks));
        }
        return lowest;
    }

    /**
     * The lower of {@link #above(BoundingBox, double, double, double, Blocks)} and
     * {@link #above(BoundingBox, BoundingBox, double, double, double, Blocks)} for the same body and way ahead, in one
     * walk: both end the way ahead at the same place, so each stride's fit is tested once and both are looked over.
     */
    static double aboveBoth(BoundingBox body, BoundingBox footprint, double aheadX, double aheadZ, double upTo, Blocks blocks) {
        double lowest = Math.min(over(body, upTo, blocks), over(footprint, upTo, blocks));
        double far = Math.sqrt(aheadX * aheadX + aheadZ * aheadZ);
        int strides = (int) Math.ceil(far / STRIDE);
        for (int stride = 1; stride <= strides; stride++) {
            double share = (double) stride / strides;
            BoundingBox there = body.clone().shift(aheadX * share, 0D, aheadZ * share);
            if (BodyFit.anyCollider(there, blocks)) break;
            lowest = Math.min(lowest, Math.min(over(there, upTo, blocks),
                    over(footprint.clone().shift(aheadX * share, 0D, aheadZ * share), upTo, blocks)));
        }
        return lowest;
    }

    /**
     * The underside of the lowest block over a body: the lowest bottom of a collision box that starts at or above the
     * body's top, below {@code upTo}, and lies over the body's footprint. Positive infinity when nothing is there.
     * Blocks beside the body are not over it: vanilla keeps the body's own box out of them.
     */
    static double over(BoundingBox body, double upTo, Blocks blocks) {
        double minX = body.getMinX() + EDGE, maxX = body.getMaxX() - EDGE;
        double minZ = body.getMinZ() + EDGE, maxZ = body.getMaxZ() - EDGE;
        double from = body.getMaxY() - EDGE;
        double lowest = Double.POSITIVE_INFINITY;
        for (int x = floor(minX); x <= floor(maxX); x++)
            for (int z = floor(minZ); z <= floor(maxZ); z++)
                for (int y = floor(from); y <= floor(upTo); y++)
                    for (BoundingBox box : blocks.boxes(x, y, z)) {
                        if (box.getMaxX() <= minX || box.getMinX() >= maxX || box.getMaxZ() <= minZ || box.getMinZ() >= maxZ)
                            continue;
                        if (box.getMinY() >= from && box.getMinY() < upTo) lowest = Math.min(lowest, box.getMinY());
                    }
        return lowest;
    }

    /**
     * The top of the highest block under a body within {@code depth} blocks: the floor it can be moved down to.
     * Negative infinity when nothing is there.
     */
    static double below(BoundingBox body, double depth, Blocks blocks) {
        double minX = body.getMinX() + EDGE, maxX = body.getMaxX() - EDGE;
        double minZ = body.getMinZ() + EDGE, maxZ = body.getMaxZ() - EDGE;
        double to = body.getMinY() + EDGE;
        double highest = Double.NEGATIVE_INFINITY;
        for (int x = floor(minX); x <= floor(maxX); x++)
            for (int z = floor(minZ); z <= floor(maxZ); z++)
                for (int y = floor(to - depth); y <= floor(to); y++)
                    for (BoundingBox box : blocks.boxes(x, y, z)) {
                        if (box.getMaxX() <= minX || box.getMinX() >= maxX || box.getMaxZ() <= minZ || box.getMinZ() >= maxZ)
                            continue;
                        if (box.getMaxY() <= to) highest = Math.max(highest, box.getMaxY());
                    }
        return highest;
    }

    /**
     * The upward velocity to send a body whose rider's head has {@code room} blocks under the block over it, or NaN
     * when it needs none. {@code climbed} is how far up the body moved in the last tick, {@code thrust} how much its
     * rider's steering adds to its upward speed each tick, and {@code delay} the ticks until the rider's client acts
     * on what is sent, during which it keeps climbing.
     * <p>
     * The body may climb {@link #GAIN} of the room left once that delay's climb and {@link #MARGIN} are taken off, so
     * it closes on the block ever more slowly and never reaches it; with no room left it is sent down, at most
     * {@link #MAX_PUSH} a tick. A body already climbing no faster than that is left alone. The client adds its thrust
     * to the velocity it is sent before it moves, so the thrust is taken off here.
     */
    static double brake(double room, double climbed, double thrust, int delay) {
        if (room == Double.POSITIVE_INFINITY) return Double.NaN;
        double climbing = WATER_DRAG * climbed + thrust;
        double left = room - MARGIN - Math.max(0D, climbing) * Math.max(0, delay);
        double allowed = Math.max(-MAX_PUSH, GAIN * left);
        if (climbing <= allowed) return Double.NaN;
        return allowed - thrust;
    }

    /**
     * How far down a body must move for its rider's head, {@code room} blocks under the block over it, to be
     * {@link #MARGIN} clear of that block; 0 for a head that is clear, and never further than {@code floorRoom}, the
     * room between the body and the floor under it.
     */
    static double duck(double room, double floorRoom) {
        if (room >= 0D) return 0D;
        return Math.max(0D, Math.min(MARGIN - room, floorRoom));
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
