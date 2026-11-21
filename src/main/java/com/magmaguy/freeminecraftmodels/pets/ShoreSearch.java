package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.util.Vector;

import java.util.Arrays;

/**
 * Where a colony goes fishing: the open water nearest its home, within reach, and the spot on the shore beside it
 * where the colony stands. Water counts when its top is open to the sky (not under ice), it is deep enough to dive in
 * and there is enough of it round about to swim from spot to spot. The shore spot is standing ground next to that
 * water, at most a step above its top. Columns are looked at nearest first, so the search stops at the first that fits
 * and costs little where water is near. Pure, over a {@link Terrain}, so it needs no world. Server thread only.
 */
final class ShoreSearch {
    /** The farthest a search looks, in blocks: the most a pet file may set. */
    static final int MAX_REACH = 64;
    /** Water at least this deep, in blocks, so a dive of 1 to 3 blocks fits. */
    static final int MIN_DEPTH = 2;
    /** Open water in at least this many columns of the 5 by 5 round a find, the find included. */
    static final int MIN_POOL = 6;
    /** The water's top at most this many blocks above or below home, so the walk there is a walk. */
    static final int MAX_RISE = 16;

    // Every column offset within MAX_REACH, nearest first: x, z pairs.
    private static final int[] OFFSETS = offsets();

    private ShoreSearch() {
    }

    /** The world's columns as the search sees them; {@link MarchRoute.Ground#standing} says where a body stands. */
    interface Terrain extends MarchRoute.Ground {
        int NO_WATER = Integer.MIN_VALUE;

        /**
         * The height of the top block of open water in this column: water is the column's highest block that blocks
         * movement or holds a fluid, and at least {@code depth} blocks of it run down from there. NO_WATER otherwise:
         * dry land, ice over the water, or a column that is not loaded.
         */
        int water(int x, int z, int depth);

        /** Whether this column is loaded, so a search that found nothing knows whether it saw everything. */
        boolean loaded(int x, int z);
    }

    /**
     * A shore: {@code stand} is the standing spot on the shore, at its feet height, and {@code water} the middle of
     * the top water block beside it.
     */
    record Shore(Vector stand, Vector water) {
    }

    /**
     * What a search found: the shore, or null for none, and whether it saw every column within reach. A search that
     * found none with columns unloaded may find one once they load.
     */
    record Result(Shore shore, boolean complete) {
    }

    /** The nearest shore within {@code reach} blocks (up to {@link #MAX_REACH}) of home, across. */
    static Result nearest(Terrain terrain, Vector home, double reach) {
        double limit = Math.min(Math.max(0D, reach), MAX_REACH);
        int homeX = (int) Math.floor(home.getX()), homeZ = (int) Math.floor(home.getZ());
        int homeY = (int) Math.floor(home.getY());
        boolean complete = true;
        for (int index = 0; index < OFFSETS.length; index += 2) {
            int dx = OFFSETS[index], dz = OFFSETS[index + 1];
            if (dx * dx + dz * dz > limit * limit) break;
            int x = homeX + dx, z = homeZ + dz;
            if (!terrain.loaded(x, z)) {
                complete = false;
                continue;
            }
            int top = terrain.water(x, z, MIN_DEPTH);
            if (top == Terrain.NO_WATER || Math.abs(top - homeY) > MAX_RISE || !pool(terrain, x, z)) continue;
            Vector stand = stand(terrain, x, top, z, home);
            if (stand == null) continue;
            return new Result(new Shore(stand, new Vector(x + 0.5D, top, z + 0.5D)), true);
        }
        return new Result(null, complete);
    }

    /** Whether at least {@link #MIN_POOL} columns of the 5 by 5 round this one hold open water. */
    private static boolean pool(Terrain terrain, int x, int z) {
        int open = 0;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                if (terrain.water(x + dx, z + dz, 1) != Terrain.NO_WATER && ++open >= MIN_POOL) return true;
        return false;
    }

    /**
     * The standing spot beside the water at (x, top, z) nearest home: a neighbouring column, sides before corners
     * when they are as near, where a body stands with its feet level with the water's top, a step above it or a step
     * below it. Null when none is.
     */
    private static Vector stand(Terrain terrain, int x, int top, int z, Vector home) {
        Vector best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                // The column a body stands in sits on ground a block under its feet: feet level with the water's top
                // are on a bank that is the water's own height.
                int feet = terrain.standing(x + dx, top + 1, z + dz);
                if (feet == MarchRoute.Ground.NO_FOOTING) continue;
                double standX = x + dx + 0.5D, standZ = z + dz + 0.5D;
                double distance = Math.pow(standX - home.getX(), 2) + Math.pow(standZ - home.getZ(), 2)
                        + (dx != 0 && dz != 0 ? 0.01D : 0D);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new Vector(standX, feet, standZ);
                }
            }
        return best;
    }

    private static int[] offsets() {
        int side = 2 * MAX_REACH + 1;
        Integer[] order = new Integer[side * side];
        for (int index = 0; index < order.length; index++) order[index] = index;
        Arrays.sort(order, (a, b) -> {
            int ax = a / side - MAX_REACH, az = a % side - MAX_REACH, bx = b / side - MAX_REACH, bz = b % side - MAX_REACH;
            return Integer.compare(ax * ax + az * az, bx * bx + bz * bz);
        });
        int[] pairs = new int[order.length * 2];
        for (int index = 0; index < order.length; index++) {
            pairs[2 * index] = order[index] / side - MAX_REACH;
            pairs[2 * index + 1] = order[index] % side - MAX_REACH;
        }
        return pairs;
    }
}
