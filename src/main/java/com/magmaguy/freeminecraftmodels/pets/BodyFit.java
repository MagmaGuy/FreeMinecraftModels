package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Finds where a pet's body fits when it gets a bigger box than the one it had. A ridden pet swims as a small nautilus
 * body and walks as a wider, taller horse body, and a body nobody steers carries the box of its model's hitbox bone,
 * bigger again. Each used to take its new box where it stood: pressed against a wall, the new box reached into the
 * wall, and Minecraft does not stop a box at a block it is already in, so the pet walked on into the wall with its
 * rider's head.
 * <p>
 * The search starts where the body stands and, while its box or its rider's head is in a block, tries the moves that
 * would take it out of each block it is in: straight along each axis, each way, just far enough, one after another
 * where one is not enough, as in a corner. The nearest spot found free wins. A spot only counts when the box the body had before could travel there in a straight line, so a
 * body is never put on the far side of a fence or a door.
 */
final class BodyFit {
    /** Blocks left between a fitted box and the block it was moved out of. */
    static final double CLEAR = 0.01D;
    /** The furthest a body is moved to fit, in blocks: further off it is left as it is. */
    static final double REACH = 1.5D;
    /**
     * The spots tried before the search gives up. A body against one wall is placed on the second try. One whose way
     * out starts through solid blocks, a rider's head under a ledge beside a wall, tries the spots inside those blocks
     * first, nearest first; BodyFitTest's ledge found nothing with 96 tries. Each try reads the few blocks round one
     * spot, and the search only runs when a body does not fit where it stands.
     */
    private static final int TRIES = 512;
    /** The longest step of the straight-line check, in blocks: shorter than the thinnest block a body cannot pass. */
    private static final double STEP = 0.1D;
    private static final double EDGE = 1.0E-4D;

    private BodyFit() {
    }

    /**
     * What has to fit: a body's box, {@code width} wide and {@code height} tall from its feet, and over it its rider's
     * head room, {@code riderWidth} wide from {@code riderFrom} to {@code riderTo} above the body's feet. A body
     * nobody rides has no rider width.
     */
    record Shape(double width, double height, double riderWidth, double riderFrom, double riderTo) {
        /** A body nobody rides. */
        static Shape of(double width, double height) {
            return new Shape(width, height, 0D, 0D, 0D);
        }

        /** Its boxes with the body's feet at these coordinates. */
        List<BoundingBox> at(double x, double y, double z) {
            List<BoundingBox> boxes = new ArrayList<>(2);
            boxes.add(new BoundingBox(x - width / 2D, y, z - width / 2D, x + width / 2D, y + height, z + width / 2D));
            if (riderWidth > 0D && riderTo > riderFrom)
                boxes.add(new BoundingBox(x - riderWidth / 2D, y + riderFrom, z - riderWidth / 2D,
                        x + riderWidth / 2D, y + riderTo, z + riderWidth / 2D));
            return boxes;
        }
    }

    /** Whether the shape is clear of every block with the body's feet at these coordinates. */
    static boolean fits(double x, double y, double z, Shape shape, RideCeiling.Blocks blocks) {
        for (BoundingBox box : shape.at(x, y, z)) if (anyCollider(box, blocks)) return false;
        return true;
    }

    /**
     * The spot nearest these coordinates where the shape fits, as the body's feet; the coordinates themselves when it
     * fits there; null when nothing within {@link #REACH} does. {@code before} is the box the body has now, which
     * must be able to travel to the spot in a straight line; null skips that, as does a box that is in a block
     * already.
     */
    static Vector nearest(double x, double y, double z, Shape shape, BoundingBox before, RideCeiling.Blocks blocks) {
        boolean travels = before != null && !anyCollider(before, blocks);
        PriorityQueue<double[]> open = new PriorityQueue<>(Comparator.comparingDouble(spot -> spot[3]));
        Set<String> seen = new HashSet<>();
        open.add(new double[]{x, y, z, 0D});
        seen.add(key(x, y, z));
        for (int tries = 0; tries < TRIES && !open.isEmpty(); tries++) {
            double[] spot = open.poll();
            // Each block it is in, left along each axis, each way, just far enough: +x, -x, +y, -y, +z, -z.
            List<double[]> moves = new ArrayList<>();
            for (BoundingBox box : shape.at(spot[0], spot[1], spot[2]))
                for (BoundingBox block : colliders(box, blocks)) {
                    moves.add(new double[]{block.getMaxX() - box.getMinX() + CLEAR, 0D, 0D});
                    moves.add(new double[]{block.getMinX() - box.getMaxX() - CLEAR, 0D, 0D});
                    moves.add(new double[]{0D, block.getMaxY() - box.getMinY() + CLEAR, 0D});
                    moves.add(new double[]{0D, block.getMinY() - box.getMaxY() - CLEAR, 0D});
                    moves.add(new double[]{0D, 0D, block.getMaxZ() - box.getMinZ() + CLEAR});
                    moves.add(new double[]{0D, 0D, block.getMinZ() - box.getMaxZ() - CLEAR});
                }
            if (moves.isEmpty()) {
                if (!travels || travels(before, spot[0] - x, spot[1] - y, spot[2] - z, blocks))
                    return new Vector(spot[0], spot[1], spot[2]);
                continue;
            }
            for (double[] move : moves) {
                double nextX = spot[0] + move[0], nextY = spot[1] + move[1], nextZ = spot[2] + move[2];
                double far = Math.sqrt((nextX - x) * (nextX - x) + (nextY - y) * (nextY - y) + (nextZ - z) * (nextZ - z));
                if (far > REACH || !seen.add(key(nextX, nextY, nextZ))) continue;
                open.add(new double[]{nextX, nextY, nextZ, far});
            }
        }
        return null;
    }

    /** Whether a box that is clear of blocks stays clear all the way when moved by this much in a straight line. */
    private static boolean travels(BoundingBox box, double dx, double dy, double dz, RideCeiling.Blocks blocks) {
        double far = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = (int) Math.ceil(far / STEP);
        for (int step = 1; step <= steps; step++) {
            double share = (double) step / steps;
            if (anyCollider(box.clone().shift(dx * share, dy * share, dz * share), blocks)) return false;
        }
        return true;
    }

    /** The collision boxes of the blocks this box reaches into; a box that only touches a block is not in it. */
    static List<BoundingBox> colliders(BoundingBox box, RideCeiling.Blocks blocks) {
        List<BoundingBox> inside = new ArrayList<>();
        for (int x = floor(box.getMinX() + EDGE); x <= floor(box.getMaxX() - EDGE); x++)
            // From the block below: a fence or a wall stands half a block taller than its own block.
            for (int y = floor(box.getMinY() + EDGE) - 1; y <= floor(box.getMaxY() - EDGE); y++)
                for (int z = floor(box.getMinZ() + EDGE); z <= floor(box.getMaxZ() - EDGE); z++)
                    for (BoundingBox block : blocks.boxes(x, y, z))
                        if (block.getMaxX() > box.getMinX() + EDGE && block.getMinX() < box.getMaxX() - EDGE
                                && block.getMaxY() > box.getMinY() + EDGE && block.getMinY() < box.getMaxY() - EDGE
                                && block.getMaxZ() > box.getMinZ() + EDGE && block.getMinZ() < box.getMaxZ() - EDGE)
                            inside.add(block);
        return inside;
    }

    /**
     * Whether {@link #colliders} would find any block: the same test, stopping at the first block the box reaches
     * into instead of collecting them all.
     */
    static boolean anyCollider(BoundingBox box, RideCeiling.Blocks blocks) {
        for (int x = floor(box.getMinX() + EDGE); x <= floor(box.getMaxX() - EDGE); x++)
            for (int y = floor(box.getMinY() + EDGE) - 1; y <= floor(box.getMaxY() - EDGE); y++)
                for (int z = floor(box.getMinZ() + EDGE); z <= floor(box.getMaxZ() - EDGE); z++)
                    for (BoundingBox block : blocks.boxes(x, y, z))
                        if (block.getMaxX() > box.getMinX() + EDGE && block.getMinX() < box.getMaxX() - EDGE
                                && block.getMaxY() > box.getMinY() + EDGE && block.getMinY() < box.getMaxY() - EDGE
                                && block.getMaxZ() > box.getMinZ() + EDGE && block.getMinZ() < box.getMaxZ() - EDGE)
                            return true;
        return false;
    }

    /**
     * Spots closer together than a 32nd of a block count as one. A move out of a block and back lands
     * {@link #CLEAR} off where it began; told apart, such spots would be tried over and over ahead of the real ones.
     */
    private static String key(double x, double y, double z) {
        return Math.round(x * 32D) + "," + Math.round(y * 32D) + "," + Math.round(z * 32D);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
