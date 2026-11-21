package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colony's water on made-up land: flat ground whose top block is at 63 (feet at 64), with open water 3 deep whose
 * top is at 63 where a test puts it, ice over water where it says, and unloaded columns where it says.
 */
class ShoreSearchTest {
    private static final int FEET = 64, TOP = 63;
    private static final Vector HOME = new Vector(0.5, FEET, 0.5);

    private static ShoreSearch.Terrain terrain(BiPredicate<Integer, Integer> water, BiPredicate<Integer, Integer> ice,
                                               BiPredicate<Integer, Integer> unloaded) {
        return new ShoreSearch.Terrain() {
            @Override
            public int water(int x, int z, int depth) {
                if (unloaded.test(x, z) || ice.test(x, z) || !water.test(x, z)) return NO_WATER;
                return depth <= 3 ? TOP : NO_WATER;
            }

            @Override
            public boolean loaded(int x, int z) {
                return !unloaded.test(x, z);
            }

            @Override
            public int standing(int x, int feet, int z) {
                if (unloaded.test(x, z) || water.test(x, z) || Math.abs(feet - FEET) > 1) return NO_FOOTING;
                return FEET;
            }
        };
    }

    private static final BiPredicate<Integer, Integer> NONE = (x, z) -> false;

    @Test
    void theNearestOpenWaterWithTheShoreSpotOnTheHomeSide() {
        // A lake from x 20 to 30, and a nearer one at -40.
        ShoreSearch.Result result = ShoreSearch.nearest(terrain((x, z) -> x >= 20 && x <= 30 && Math.abs(z) <= 6
                || x <= -40 && x >= -50, NONE, NONE), HOME, 48);
        ShoreSearch.Shore shore = result.shore();
        assertNotNull(shore);
        assertEquals(20.5, shore.water().getX(), 1.0E-9, "the lake's near edge");
        assertEquals(TOP, shore.water().getY(), 1.0E-9);
        assertEquals(19.5, shore.stand().getX(), 1.0E-9, "standing on the bank, on the home side");
        assertEquals(0.5, shore.stand().getZ(), 1.0E-9);
        assertEquals(FEET, shore.stand().getY(), 1.0E-9);
    }

    @Test
    void waterUnderIceOrTooSmallOrOutOfReachIsNoShore() {
        BiPredicate<Integer, Integer> river = (x, z) -> x >= 10 && x <= 14;
        assertNull(ShoreSearch.nearest(terrain(river, river, NONE), HOME, 48).shore(), "a frozen river");
        assertTrue(ShoreSearch.nearest(terrain(river, river, NONE), HOME, 48).complete());
        assertNull(ShoreSearch.nearest(terrain((x, z) -> x == 10 && z == 0, NONE, NONE), HOME, 48).shore(),
                "a single block of water is no place to swim");
        assertNull(ShoreSearch.nearest(terrain((x, z) -> x >= 60, NONE, NONE), HOME, 48).shore(), "out of reach");
        // A frozen river with a hole in the ice a few blocks across.
        ShoreSearch.Shore hole = ShoreSearch.nearest(terrain(river, (x, z) -> !(z >= 4 && z <= 7), NONE), HOME, 48)
                .shore();
        assertNotNull(hole, "open water in a hole in the ice");
        assertTrue(hole.water().getZ() >= 4 && hole.water().getZ() <= 8, "in the hole: " + hole.water());
    }

    @Test
    void aSearchThatSawUnloadedColumnsIsNotComplete() {
        ShoreSearch.Result result = ShoreSearch.nearest(terrain(NONE, NONE, (x, z) -> x > 30), HOME, 48);
        assertNull(result.shore());
        assertFalse(result.complete(), "water may lie in the unloaded columns");
    }
}
