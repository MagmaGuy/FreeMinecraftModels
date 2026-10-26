package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How far from its center a walker's or swimmer's spot is drawn: evenly over the area, so as many spots land in the
 * outer ring as its share of the area says, and a catch-up spot keeps its distance from the owner.
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
        // A radius inside the clearance draws the radius; the spot then fails the clearance check.
        assertEquals(0.5D, PetMotion.spotDistance(0.5D, clearance, 0.7D), EPSILON);
        assertEquals(0D, PetMotion.spotDistance(0D, clearance, 0.7D), EPSILON);
    }
}
