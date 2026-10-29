package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetStatsTest {
    @Test
    void wildRollsStayInTheWildRange() {
        SplittableRandom random = new SplittableRandom(1);
        for (int i = 0; i < 1000; i++) {
            PetStats stats = PetStats.wild(random);
            for (double stat : new double[]{stats.health, stats.speed, stats.sting, stats.poison})
                assertTrue(stat >= PetStats.WILD_MIN && stat <= PetStats.WILD_MAX, "wild stat " + stat);
        }
    }

    @Test
    void aBabyGetsItsParentsAverageGiveOrTakeTheWobble() {
        SplittableRandom random = new SplittableRandom(2);
        PetStats mum = new PetStats(1.2D, 1.0D, 1.4D, 0.9D), dad = new PetStats(1.4D, 1.1D, 1.0D, 1.1D);
        for (int i = 0; i < 1000; i++) {
            PetStats baby = PetStats.inherit(mum, dad, 2D, random);
            assertEquals(1.3D, baby.health, PetStats.WOBBLE + 1e-9);
            assertEquals(1.05D, baby.speed, PetStats.WOBBLE + 1e-9);
            assertEquals(1.2D, baby.sting, PetStats.WOBBLE + 1e-9);
            assertEquals(1.0D, baby.poison, PetStats.WOBBLE + 1e-9);
        }
    }

    @Test
    void breedingStopsAtTheCapAndTheFloor() {
        SplittableRandom random = new SplittableRandom(3);
        PetStats top = new PetStats(2D, 2D, 2D, 2D), bottom = new PetStats(0.5D, 0.5D, 0.5D, 0.5D);
        for (int i = 0; i < 200; i++) {
            PetStats high = PetStats.inherit(top, top, 2D, random), low = PetStats.inherit(bottom, bottom, 2D, random);
            assertTrue(high.health <= 2D && high.speed <= 2D && high.sting <= 2D && high.poison <= 2D);
            assertTrue(low.health >= 0.5D && low.speed >= 0.5D && low.sting >= 0.5D && low.poison >= 0.5D);
        }
    }

    @Test
    void carefulBreedingClimbs() {
        // Always breeding the best two of each litter's parents and baby: the line gains on average.
        SplittableRandom random = new SplittableRandom(4);
        PetStats a = new PetStats(1D, 1D, 1D, 1D), b = new PetStats(1D, 1D, 1D, 1D);
        for (int generation = 0; generation < 200; generation++) {
            PetStats baby = PetStats.inherit(a, b, 2D, random);
            if (baby.mean() > Math.min(a.mean(), b.mean())) {
                if (a.mean() < b.mean()) a = baby;
                else b = baby;
            }
        }
        assertTrue(Math.max(a.mean(), b.mean()) > 1.5D, "a careful line reaches well past the base");
    }

    @Test
    void sizeFollowsTheStats() {
        assertEquals(1D, PetStats.size(new PetStats(), 2D), 1e-9);
        assertEquals(1.5D, PetStats.size(new PetStats(2D, 2D, 2D, 2D), 2D), 1e-9);
        assertEquals(0.5D, PetStats.size(new PetStats(0.5D, 0.5D, 0.5D, 0.5D), 2D), 1e-9);
        assertEquals(0.8D, PetStats.size(new PetStats(0.8D, 0.8D, 0.8D, 0.8D), 2D), 1e-9);
        assertEquals(1.25D, PetStats.size(new PetStats(1.5D, 1.5D, 1.5D, 1.5D), 2D), 1e-9);
        // Records from before stats were kept are base size.
        assertEquals(1D, PetStats.size(null, 2D), 1e-9);
    }
}
