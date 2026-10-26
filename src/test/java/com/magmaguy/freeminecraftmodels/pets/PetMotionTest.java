package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How far from its center a walker's or swimmer's spot is drawn: evenly over the area, so as many spots land in the
 * outer ring as its share of the area says, and a catch-up spot keeps its distance from the owner, off their block.
 */
class PetMotionTest {
    private static final double EPSILON = 1.0E-9D;

    @Test
    void spotsSpreadEvenlyOverTheArea() {
        assertEquals(0D, PetMotion.spotDistance(5D, 0D, 0D), EPSILON);
        assertEquals(5D, PetMotion.spotDistance(5D, 0D, 1D), EPSILON);
        // A quarter of the draws land within half the radius, a quarter of the area; scaled directly it was half.
        assertEquals(2.5D, PetMotion.spotDistance(5D, 0D, 0.25D), EPSILON);
        // Half of them within the radius that halves the area.
        assertEquals(5D / Math.sqrt(2D), PetMotion.spotDistance(5D, 0D, 0.5D), EPSILON);
    }

    @Test
    void aCatchUpSpotKeepsItsDistanceFromTheOwner() {
        double clearance = PetMotion.CATCH_UP_CLEARANCE;
        assertEquals(clearance, PetMotion.spotDistance(2D, clearance, 0D), EPSILON);
        assertEquals(2D, PetMotion.spotDistance(2D, clearance, 1D), EPSILON);
        // Half of the ring from 1 to 2 by area lies inside sqrt(2.5).
        assertEquals(Math.sqrt(2.5D), PetMotion.spotDistance(2D, clearance, 0.5D), EPSILON);
        // A radius inside the clearance draws the radius; the column it lands in must still pass the clearance check.
        assertEquals(0.5D, PetMotion.spotDistance(0.5D, clearance, 0.7D), EPSILON);
        assertEquals(0D, PetMotion.spotDistance(0D, clearance, 0.7D), EPSILON);
    }

    @Test
    void theOwnersOwnBlockIsNeverClear() {
        double clearance = PetMotion.CATCH_UP_CLEARANCE;
        Location middle = new Location(null, 10.5D, 64D, 10.5D), edge = new Location(null, 10.9D, 64D, 10.5D);
        assertFalse(PetMotion.clear(middle, 10, 10, clearance));
        assertFalse(PetMotion.clear(edge, 10, 10, clearance));
        // The next column's middle is a block away from an owner standing in the middle of theirs.
        assertTrue(PetMotion.clear(middle, 11, 10, clearance));
        assertTrue(PetMotion.clear(middle, 9, 11, clearance));
        // From the edge of the block, the next column's middle is too close; the one after is not.
        assertFalse(PetMotion.clear(edge, 11, 10, clearance));
        assertTrue(PetMotion.clear(edge, 12, 10, clearance));
        // Without a clearance any column will do, the owner's own included.
        assertTrue(PetMotion.clear(middle, 10, 10, 0D));
    }
}
