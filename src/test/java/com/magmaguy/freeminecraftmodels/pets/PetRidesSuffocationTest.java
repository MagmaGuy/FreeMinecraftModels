package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a ridden pet's rider suffocates: with their head in a block, not beside a wall their body swims against. */
class PetRidesSuffocationTest {
    /** The rider's eyes above the body's feet, as measured seated on either of the penguin's bodies. */
    private static final double EYES = 1.62D;

    /** A wall of full blocks at x 3, from y 58 to 66, and a full block at each of the given coordinates. */
    private static RideCeiling.Blocks wallAnd(int[]... full) {
        List<int[]> blocks = new ArrayList<>(List.of(full));
        for (int y = 58; y <= 66; y++) blocks.add(new int[]{3, y, 0});
        return (x, y, z) -> {
            for (int[] at : blocks)
                if (at[0] == x && at[1] == y && at[2] == z) return List.of(new BoundingBox(x, y, z, x + 1, y + 1, z + 1));
            return List.of();
        };
    }

    private static BoundingBox body(double x, double y, double width, double height) {
        return new BoundingBox(x - width / 2D, y, 0.5D - width / 2D, x + width / 2D, y + height, 0.5D + width / 2D);
    }

    /** Vanilla's suffocation box round the eyes of a 0.6 wide player seated in the middle of a body at x. */
    private static BoundingBox probe(double x, double y) {
        return new BoundingBox(x - 0.24D, y + EYES - 0.01D, 0.26D, x + 0.24D, y + EYES + 0.01D, 0.74D);
    }

    @Test
    void besideAWallTheNarrowDivingBodySwimsAgainstTheRiderDoesNotSuffocate() {
        // The nautilus body, 0.438 wide, flat against the wall at x 3: vanilla's box reaches 0.021 into the wall.
        double x = 3D - 0.438D / 2D;
        assertTrue(probe(x, 60D).getMaxX() > 3D);
        assertFalse(PetRides.headInBlock(probe(x, 60D), body(x, 60D, 0.438D, 0.476D), wallAnd()));
    }

    @Test
    void underABlockOverTheBodyTheRiderSuffocates() {
        // In open water away from the wall, a block over the body at the height of its rider's eyes.
        assertTrue(PetRides.headInBlock(probe(0.5D, 60D), body(0.5D, 60D, 0.438D, 0.476D), wallAnd(new int[]{0, 61, 0})));
    }

    @Test
    void aBodyWiderThanTheTestChangesNothing() {
        // The horse body, 0.55 wide, against the wall: vanilla's box stops 0.035 short of it, and a block over the
        // body is one over the rider.
        double x = 3D - 0.55D / 2D;
        assertFalse(PetRides.headInBlock(probe(x, 60D), body(x, 60D, 0.55D, 0.63D), wallAnd()));
        assertTrue(PetRides.headInBlock(probe(x, 60D), body(x, 60D, 0.55D, 0.63D), wallAnd(new int[]{2, 61, 0})));
    }

    @Test
    void aRiderWhoIsNotOverTheirBodyIsLeftToVanilla() {
        assertTrue(PetRides.headInBlock(probe(10D, 60D), body(0.5D, 60D, 0.438D, 0.476D), wallAnd()));
    }
}
