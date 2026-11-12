package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetStepsTest {
    /** Walks a pet along +x at {@code speed} blocks a tick for {@code ticks} ticks on the ground; the ticks it steps in. */
    private static int steps(double speed, int ticks) {
        PetSteps.Walk walk = new PetSteps.Walk();
        int steps = 0;
        for (int tick = 0; tick <= ticks; tick++) if (PetSteps.advance(walk, tick * speed, 0D, true)) steps++;
        return steps;
    }

    @Test
    void aWalkingPetStepsOnceEveryStride() {
        // A walking player's pace, 0.216 blocks a tick, for 100 ticks: 21.6 blocks, a step every 1.667.
        assertEquals(12, steps(0.216D, 100));
        // Twice as fast, twice the steps.
        assertEquals(25, steps(0.432D, 100));
    }

    @Test
    void aPetStandingStillMakesNoSound() {
        assertEquals(0, steps(0D, 200));
    }

    @Test
    void thePlaceItIsFirstSeenAtIsNotAStep() {
        PetSteps.Walk walk = new PetSteps.Walk();
        assertFalse(PetSteps.advance(walk, 1000D, 1000D, true));
    }

    @Test
    void aPetInTheAirOrInWaterMakesNoneAndDoesNotCountTheDistance() {
        PetSteps.Walk walk = new PetSteps.Walk();
        PetSteps.advance(walk, 0D, 0D, true);
        // 10 blocks through the air: no step, and none owed on landing.
        for (int tick = 1; tick <= 10; tick++) assertFalse(PetSteps.advance(walk, tick, 0D, false));
        assertFalse(PetSteps.advance(walk, 10.5D, 0D, true));
        assertFalse(PetSteps.advance(walk, 11D, 0D, true));
        assertFalse(PetSteps.advance(walk, 11.5D, 0D, true));
        assertTrue(PetSteps.advance(walk, 12D, 0D, true));
    }

    @Test
    void aPetMovedALongWayInOneTickStepsOnce() {
        PetSteps.Walk walk = new PetSteps.Walk();
        PetSteps.advance(walk, 0D, 0D, true);
        assertTrue(PetSteps.advance(walk, 30D, 0D, true));
        assertFalse(PetSteps.advance(walk, 30.2D, 0D, true));
    }

    @Test
    void aSnowLayerOrACarpetSoundsInPlaceOfTheBlockUnderIt() {
        assertTrue(PetSteps.soundsAtFeet(Material.SNOW));
        assertTrue(PetSteps.soundsAtFeet(Material.POWDER_SNOW));
        assertTrue(PetSteps.soundsAtFeet(Material.RED_CARPET));
        assertTrue(PetSteps.soundsAtFeet(Material.MOSS_CARPET));
        assertFalse(PetSteps.soundsAtFeet(Material.AIR));
        assertFalse(PetSteps.soundsAtFeet(Material.SHORT_GRASS));
        assertFalse(PetSteps.soundsAtFeet(Material.WATER));
    }
}
