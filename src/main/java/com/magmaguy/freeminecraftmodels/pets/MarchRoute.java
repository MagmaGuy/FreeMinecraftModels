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
         * Whether a body with its feet at this height would be in a block of this column that it cannot step onto: a
         * wall, a tree's trunk, leaves at the height of its head. False where the ground only steps or drops away,
         * and for ground that knows no walls, as a test's.
         */
        default boolean walled(int x, int feet, int z) {
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
     * that walked it stood against the trunk until it counted as stuck.
     */
    static Run run(Ground ground, Vector from, double dx, double dz, double max) {
        int feet = (int) Math.floor(from.getY());
        double length = 0D;
        Vector end = from.clone();
        for (double along = STEP; along <= max + 1.0E-9D; along += STEP) {
            double x = from.getX() + dx * along, z = from.getZ() + dz * along;
            int next = ground.standing((int) Math.floor(x), feet, (int) Math.floor(z));
            if (next == Ground.NO_FOOTING) break;
            // To the left of the heading and to its right: (-dz, dx) and (dz, -dx).
            if (ground.walled((int) Math.floor(x - dz * CLEAR), next, (int) Math.floor(z + dx * CLEAR))
                    || ground.walled((int) Math.floor(x + dz * CLEAR), next, (int) Math.floor(z - dx * CLEAR))) break;
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

    /**
     * The smallest turn from the unit heading (dx, dz), either way in steps of a sixteenth of a circle up to a full
     * about-turn, whose run reaches {@code wanted} blocks; the straight heading itself counts as no turn unless
     * {@code mustTurn}. When none reaches it, the turn whose run goes farthest, as long as it reaches {@link #BLOCKED}.
     * Null when every way is blocked. The heading comes back as a unit (dx, dz).
     */
    static double[] turn(Ground ground, Vector from, double dx, double dz, double wanted, boolean mustTurn) {
        double base = Math.atan2(dz, dx);
        double[] longest = null;
        double longestLength = BLOCKED - 1.0E-9D;
        for (int step = mustTurn ? 1 : 0; step <= HEADINGS / 2; step++) {
            for (int side : step == 0 || step == HEADINGS / 2 ? new int[]{1} : new int[]{1, -1}) {
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
