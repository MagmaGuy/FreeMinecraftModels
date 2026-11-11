package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The ground under one column of a body's footprint (PetMotion.groundInColumn). */
class PetMotionGroundTest {
    private static final double NONE = -99D;

    private static double ground(double y, boolean middle, Integer... solid) {
        Set<Integer> blocks = Set.of(solid);
        return PetMotion.groundInColumn(y, 12, -64, middle, NONE, blocks::contains);
    }

    @Test
    void theMiddleColumnReadsDownFromTheBlockThePointIsIn() {
        // Hovering at 66.4 over a floor block at 63: its top.
        assertEquals(64D, ground(66.4D, true, 63));
        // A point inside a block reads that block, as before.
        assertEquals(67D, ground(66.4D, true, 66, 63));
    }

    @Test
    void aBlockBesideTheBodyAtItsOwnHeightIsAWallNotGround() {
        // The body's feet at 66.4: a block filling 66 to 67 in the column beside it stands next to it.
        assertEquals(64D, ground(66.4D, false, 66, 63));
        assertEquals(NONE, ground(66.4D, false, 66));
    }

    @Test
    void aBlockTheBodyRestsOnBesideItsMiddleIsGround() {
        // Feet exactly on the top of a block at 65, the body's middle past its edge: that block holds it up.
        assertEquals(66D, ground(66D, false, 65, 63));
    }

    @Test
    void aBlockUnderTheBodyBesideItsMiddleIsGround() {
        assertEquals(65D, ground(66.4D, false, 64));
    }

    @Test
    void groundPastTheDepthIsNotFound() {
        assertEquals(NONE, ground(66.4D, true, 40));
        assertEquals(NONE, ground(66.4D, false, 40));
    }
}
