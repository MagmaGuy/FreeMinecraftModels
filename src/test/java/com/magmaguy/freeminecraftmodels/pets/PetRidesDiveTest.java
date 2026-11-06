package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a ridden pet that dives takes its diving body, and when it gets its own body back. */
class PetRidesDiveTest {
    // The rider's eyes above the pet's feet: the seat 0.6 up, less the 0.6 a seated player sinks, plus 1.62.
    private static final double EYES = 1.6D;

    @Test
    void itDivesWhereTheWaterWouldReachItsRidersEyes() {
        assertTrue(PetRides.diving(false, 2, EYES, false, false));
        assertFalse(PetRides.diving(false, 1, EYES, false, false));
        assertFalse(PetRides.diving(false, 0, EYES, false, false));
    }

    @Test
    void itKeepsSwimmingAtTheSurfaceOfDeepWater() {
        assertTrue(PetRides.diving(true, 6, EYES, false, false));
    }

    @Test
    void itNeverSurfacesWhileItsRidersEyesAreUnderWater() {
        assertTrue(PetRides.diving(true, 1, EYES, true, false));
    }

    @Test
    void itSurfacesWithItsRidersHeadOutInTheShallowsOfABeach() {
        assertTrue(PetRides.diving(true, 2, EYES, false, false));
        assertFalse(PetRides.diving(true, 1, EYES, false, false));
        assertFalse(PetRides.diving(true, 0, EYES, false, false));
    }

    @Test
    void aLeapOutOfTheWaterStaysADiveUntilItLands() {
        assertTrue(PetRides.diving(true, 0, EYES, false, true));
        assertFalse(PetRides.diving(true, 0, EYES, false, false));
        assertFalse(PetRides.diving(false, 0, EYES, false, true));
    }

    @Test
    void noDepthFlipsItBetweenBodiesWhenItsTwoBodiesSeatTheRiderABitApart() {
        for (double eyes = 1.2D; eyes <= 3D; eyes += 0.05D)
            for (int depth = 0; depth <= 4; depth++)
                if (PetRides.diving(false, depth, eyes, false, false))
                    for (double other = eyes - 0.4D; other <= eyes + 0.4D; other += 0.05D)
                        assertTrue(PetRides.diving(true, depth, other, false, false), depth + " " + eyes + " " + other);
    }

    @Test
    void theDepthCountsTheWaterAboveAndBelowItsFeet() {
        IntPredicate column = dy -> dy >= -2 && dy <= 1; // water from 2 below the feet to 1 above
        assertEquals(4, PetRides.waterDepth(column, 8));
        assertEquals(0, PetRides.waterDepth(dy -> dy != 0, 8));
        assertEquals(3, PetRides.waterDepth(dy -> true, 1));
    }
}
