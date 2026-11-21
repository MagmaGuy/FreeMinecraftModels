package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.util.Vector;

/**
 * The straight legs a march leader walks: how far a straight run of standing ground goes, which heading runs farthest,
 * and the smallest turn that clears something in the way. Pure geometry over {@link Ground}, so it needs no world.
 */
final class MarchRoute {
    /** A run is traced in steps this long, so it never skips a block. */
    static final double STEP = 0.5D;
    /** The farthest a straight leg is looked along. */
    static final double MAX_RUN = 24D;
    /** A leg shorter than this is not worth setting off on: the line would turn again at once. */
    static final double MIN_LEG = 6D;
    /** A run ahead shorter than this means the way is blocked. */
    static final double BLOCKED = 2.5D;
    /**
     * A run keeps this far clear of a wall on either side: half the width of a marcher's body, 0.35 for a penguin,
     * and a tenth of a block for the body straying off its line.
     */
    static final double CLEAR = 0.45D;
    /** The headings a leader looks along, all round. */
    static final int HEADINGS = 16;

    private MarchRoute() {
    }

    /** Where a body can stand in a column. */
    @FunctionalInterface
    interface Ground {
        /** No block in the column a body can stand in, or the column is not loaded. */
        int NO_FOOTING = Integer.MIN_VALUE;

        /**
         * The block a body stands in at this column, a step up from feet, level with it or a step down: room for its
         * feet and head over firm ground, not in liquid and not on a tree's leaves. NO_FOOTING when none of the three
         * fits.
         */
        int standing(int x, int feet, int z);

        /**
         * Whether a body with its feet at this height cannot have part of itself in this column: a block is where
         * its feet or its head would be, a wall, a tree's trunk, leaves at the height of its head, and it is not a
         * step of one block the body rises onto with room over it. False where the ground only drops away, whatever
         * is further down, and for ground that knows no walls, as a test's.
         */
        default boolean walled(int x, int feet, int z) {
            return false;
        }

        /**
         * Whether a block is where a body with its feet at this height would have its feet or its head in this
         * column, a block it could step onto included. False for ground that knows no walls, as a test's.
         */
        default boolean filled(int x, int feet, int z) {
            return false;
        }
    }

    /** A straight run: how long it is and where it ends, at the standing block's feet height. */
    record Run(double length, Vector end) {
    }

    /**
     * The straight run from {@code from} along the unit heading (dx, dz), up to {@code max} blocks: each step stands a
     * step up, level or a step down from the one before. It stops before a wall, a drop of two or more, liquid or an
     * unloaded column. Its end is {@code from} itself when the first step fails.
     * <p>
     * A run is a body wide, not a line: it also stops where a wall stands within {@link #CLEAR} of it on either side
     * (see {@link Ground#walled}). A line passing a tree's trunk a quarter block off was a clear run, and the leader
     * that walked it stood against the trunk until it counted as stuck. And it stops before a step up the body has
     * no room to rise onto: a body a little over a block tall needs the block over its head free where it stands,
     * as it rises, and the same one free in the columns beside the step.
     * <p>
     * Beside a step up, a column a block higher than the step is a wall too. On level ground a body rides up onto
     * such a column, a step of one block. A body that is itself rising a block cannot rise two: part of it would
     * have to stand on the higher column, and the leader stood at the foot of that step until the march gave up.
     */
    static Run run(Ground ground, Vector from, double dx, double dz, double max) {
        int feet = (int) Math.floor(from.getY());
        double length = 0D;
        Vector end = from.clone();
        for (double along = STEP; along <= max + 1.0E-9D; along += STEP) {
            double x = from.getX() + dx * along, z = from.getZ() + dz * along;
            int next = ground.standing((int) Math.floor(x), feet, (int) Math.floor(z));
            if (next == Ground.NO_FOOTING) break;
            // A step up: where it stood half a block back, the body rises a block.
            if (next > feet && ground.walled((int) Math.floor(x - dx * STEP), next, (int) Math.floor(z - dz * STEP))) break;
            // To the left of the heading and to its right: (-dz, dx) and (dz, -dx).
            int leftX = (int) Math.floor(x - dz * CLEAR), leftZ = (int) Math.floor(z + dx * CLEAR);
            int rightX = (int) Math.floor(x + dz * CLEAR), rightZ = (int) Math.floor(z - dx * CLEAR);
            if (next > feet ? ground.filled(leftX, next, leftZ) || ground.filled(rightX, next, rightZ)
                    : ground.walled(leftX, next, leftZ) || ground.walled(rightX, next, rightZ)) break;
            feet = next;
            length = along;
            end = new Vector(x, next, z);
        }
        return new Run(length, end);
    }

    /**
     * Of {@link #HEADINGS} headings turned by {@code offset} radians, the one whose run goes farthest, as a unit
     * (dx, dz); null when none reaches {@link #MIN_LEG}.
     */
    static double[] widest(Ground ground, Vector from, double offset) {
        double[] best = null;
        double bestLength = MIN_LEG - 1.0E-9D;
        for (int heading = 0; heading < HEADINGS; heading++) {
            double angle = offset + heading * 2D * Math.PI / HEADINGS;
            double dx = Math.cos(angle), dz = Math.sin(angle);
            double length = run(ground, from, dx, dz, MAX_RUN).length();
            if (length > bestLength) {
                bestLength = length;
                best = new double[]{dx, dz};
            }
        }
        return best;
    }

    /** Which way the unit heading (toX, toZ) lies from the unit heading (dx, dz): 1 to its left, -1 to its right. */
    static int side(double dx, double dz, double toX, double toZ) {
        return dx * toZ - dz * toX >= 0D ? 1 : -1;
    }

    /**
     * The way a line forming behind a leader at {@code from}, about to set off along the unit heading (dx, dz), lies
     * out: straight back when a run of {@code length} blocks of standing ground goes that way, else the heading
     * nearest straight back whose run does, else the one whose run goes farthest. Straight back off a shore runs into
     * the water, and the line formed there, its marchers given places they swam to or could not reach. Never along
     * the heading itself, the way the leader walks. The heading comes back as a unit (dx, dz).
     */
    static double[] tail(Ground ground, Vector from, double dx, double dz, double length) {
        double wanted = Math.min(Math.max(length, STEP), MAX_RUN);
        double base = Math.atan2(-dz, -dx);
        double[] longest = {-dx, -dz};
        double longestLength = -1D;
        for (int step = 0; step < HEADINGS / 2; step++) {
            for (int side : step == 0 ? new int[]{1} : new int[]{1, -1}) {
                double angle = base + side * step * 2D * Math.PI / HEADINGS;
                double tailX = Math.cos(angle), tailZ = Math.sin(angle);
                double run = run(ground, from, tailX, tailZ, wanted).length();
                if (run >= wanted - 1.0E-9D) return new double[]{tailX, tailZ};
                if (run > longestLength + 1.0E-9D) {
                    longestLength = run;
                    longest = new double[]{tailX, tailZ};
                }
            }
        }
        return longest;
    }

    /**
     * The smallest turn from the unit heading (dx, dz), either way in steps of a sixteenth of a circle up to a full
     * about-turn, whose run reaches {@code wanted} blocks; the straight heading itself counts as no turn unless
     * {@code mustTurn}. When none reaches it, the turn whose run goes farthest, as long as it reaches {@link #BLOCKED}.
     * Null when every way is blocked. The heading comes back as a unit (dx, dz).
     */
    static double[] turn(Ground ground, Vector from, double dx, double dz, double wanted, boolean mustTurn) {
        return turn(ground, from, dx, dz, wanted, mustTurn, 0);
    }

    /**
     * As {@link #turn(Ground, Vector, double, double, double, boolean)}, but with {@code side} 1 or -1 every turn to
     * that side comes first, up to a full about-turn, and only then the other side's. 1 turns from the heading toward
     * its left, (-dz, dx), the way a rising angle goes. A leader going round something long keeps to one side of it
     * this way: aimed back at its goal from beside a wall, the smallest turn that cleared was as often the one that led
     * back along the wall as the one that led on, and it walked to and fro along the wall until the march gave up.
     */
    static double[] turn(Ground ground, Vector from, double dx, double dz, double wanted, boolean mustTurn, int side) {
        if (side == 0) return turn(ground, from, dx, dz, wanted, mustTurn, new int[]{1, -1});
        double[] first = turn(ground, from, dx, dz, wanted, mustTurn, new int[]{side});
        if (first != null && run(ground, from, first[0], first[1], Math.max(wanted, BLOCKED)).length()
                >= wanted - 1.0E-9D) return first;
        double[] other = turn(ground, from, dx, dz, wanted, true, new int[]{-side});
        if (other != null && run(ground, from, other[0], other[1], Math.max(wanted, BLOCKED)).length()
                >= wanted - 1.0E-9D) return other;
        if (first == null) return other;
        if (other == null) return first;
        return run(ground, from, first[0], first[1], Math.max(wanted, BLOCKED)).length()
                >= run(ground, from, other[0], other[1], Math.max(wanted, BLOCKED)).length() ? first : other;
    }

    private static double[] turn(Ground ground, Vector from, double dx, double dz, double wanted, boolean mustTurn,
                                 int[] sides) {
        double base = Math.atan2(dz, dx);
        double[] longest = null;
        double longestLength = BLOCKED - 1.0E-9D;
        for (int step = mustTurn ? 1 : 0; step <= HEADINGS / 2; step++) {
            for (int side : step == 0 || step == HEADINGS / 2 ? new int[]{sides[0]} : sides) {
                double angle = base + side * step * 2D * Math.PI / HEADINGS;
                double turnX = Math.cos(angle), turnZ = Math.sin(angle);
                double length = run(ground, from, turnX, turnZ, Math.max(wanted, BLOCKED)).length();
                if (length >= wanted - 1.0E-9D) return new double[]{turnX, turnZ};
                if (length > longestLength) {
                    longestLength = length;
                    longest = new double[]{turnX, turnZ};
                }
            }
        }
        return longest;
    }
}
