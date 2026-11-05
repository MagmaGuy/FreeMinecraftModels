package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a ridden pet that dives takes its diving body, and when it gets its own body back. */
class PetRidesDiveTest {
    private static final double EYES = 2.2D; // the rider's eyes above the pet's feet

    @Test
    void itDivesWhereTheWaterWouldReachItsRidersEyes() {
        assertTrue(PetRides.diving(false, 3, EYES, false));
        assertFalse(PetRides.diving(false, 2, EYES, false));
        assertFalse(PetRides.diving(false, 0, EYES, false));
    }

    @Test
    void itKeepsSwimmingAtTheSurfaceOfDeepWater() {
        assertTrue(PetRides.diving(true, 6, EYES, false));
    }

    @Test
    void itNeverSurfacesWhileItsRidersEyesAreUnderWater() {
        assertTrue(PetRides.diving(true, 1, EYES, true));
    }

    @Test
    void itSurfacesWithItsRidersHeadOutOnceTheWaterIsABlockShallower() {
        assertTrue(PetRides.diving(true, 2, EYES, false));
        assertFalse(PetRides.diving(true, 1, EYES, false));
        assertFalse(PetRides.diving(true, 0, EYES, false));
    }

    @Test
    void theDepthCountsTheWaterAboveAndBelowItsFeet() {
        IntPredicate column = dy -> dy >= -2 && dy <= 1; // water from 2 below the feet to 1 above
        assertEquals(4, PetRides.waterDepth(column, 8));
        assertEquals(0, PetRides.waterDepth(dy -> dy != 0, 8));
        assertEquals(3, PetRides.waterDepth(dy -> true, 1));
    }
}
