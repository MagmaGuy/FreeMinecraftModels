package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parts of wild spawning that follow vanilla's numbers: pack steps, weighted picks, spawn areas and despawning. */
class WildSpawnerTest {
    /** A random whose doubles are fixed, to land a despawn roll on either side of its chance. */
    private static Random rolling(double value) {
        return new Random() {
            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    @Test
    void packStepsStayWithinFiveBlocksAndCentreOnZero() {
        Random random = new Random(7);
        Map<Integer, Long> counts = IntStream.range(0, 36_000).map(ignored -> WildSpawner.packStep(random)).boxed()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertEquals(IntStream.rangeClosed(-5, 5).boxed().collect(Collectors.toSet()), counts.keySet());
        // nextInt(6) - nextInt(6) is a triangle: 0 comes up 6 times in 36, each end once.
        assertEquals(6_000D, counts.get(0), 400D);
        assertEquals(1_000D, counts.get(5), 200D);
        assertEquals(1_000D, counts.get(-5), 200D);
    }

    @Test
    void weightedPicksFollowTheirShareOfTheTotal() {
        Random random = new Random(11);
        Map<String, Integer> weights = Map.of("jellyfish", 10, "moth", 30, "ghost", 0);
        List<String> entries = List.of("jellyfish", "moth", "ghost");
        Map<String, Long> picks = IntStream.range(0, 40_000).mapToObj(ignored -> WildSpawner.pickWeighted(entries, weights::get, random))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertEquals(10_000D, picks.get("jellyfish"), 500D);
        assertEquals(30_000D, picks.get("moth"), 500D);
        assertFalse(picks.containsKey("ghost"), "a species of weight 0 never spawns");
    }

    @Test
    void nothingIsPickedWhenNothingWeighsAnything() {
        assertNull(WildSpawner.pickWeighted(List.<String>of(), ignored -> 10, new Random(1)));
        assertNull(WildSpawner.pickWeighted(List.of("ghost"), ignored -> 0, new Random(1)));
    }

    @Test
    void theSpawnAreaIsTheChunksWithinEightOfThePlayers() {
        // A player at x 0 stands in chunk 0; chunk 8 ends at x 143 and chunk 9 starts at 144.
        assertTrue(WildSpawner.inSpawnArea(0, 0, 143, 0));
        assertFalse(WildSpawner.inSpawnArea(0, 0, 144, 0));
        assertTrue(WildSpawner.inSpawnArea(15, 0, -128, 0), "chunk -8 is eight chunks from chunk 0");
        assertFalse(WildSpawner.inSpawnArea(15, 0, -129, 0));
        assertTrue(WildSpawner.inSpawnArea(-1, -1, -129, -129), "chunk -9 is eight chunks from chunk -1");
        assertFalse(WildSpawner.inSpawnArea(0, 0, 100, 200));
    }

    @Test
    void chunkKeysKeepNegativeCoordinates() {
        for (int[] chunk : new int[][]{{0, 0}, {-1, 5}, {7, -3}, {-30_000, -30_000}, {Integer.MAX_VALUE, Integer.MIN_VALUE}}) {
            long key = WildSpawner.chunkKey(chunk[0], chunk[1]);
            assertEquals(chunk[0], (int) (key >> 32));
            assertEquals(chunk[1], (int) key);
        }
    }

    @Test
    void wildPetsPastTheHardDistanceDespawnAtOnce() {
        assertTrue(WildSpawner.shouldDespawn(128.5D, 128D, 0, 20, rolling(0.99D)));
    }

    @Test
    void wildPetsNearAPlayerNeverDespawn() {
        assertFalse(WildSpawner.shouldDespawn(32D, 128D, 100_000, 20, rolling(0D)));
    }

    @Test
    void theRandomDespawnWaitsSixHundredTicksAlone() {
        assertFalse(WildSpawner.shouldDespawn(60D, 128D, 600, 20, rolling(0D)));
        assertTrue(WildSpawner.shouldDespawn(60D, 128D, 620, 20, rolling(0D)));
        assertFalse(WildSpawner.shouldDespawn(60D, 128D, 620, 20, rolling(0.99D)));
    }

    @Test
    void aCheckEveryTwentyTicksRollsLikeTwentyOneInEightHundredRolls() {
        assertEquals(1D / 800D, WildSpawner.despawnChance(1), 1e-12);
        assertEquals(1D - Math.pow(799D / 800D, 20D), WildSpawner.despawnChance(20), 1e-12);
        assertEquals(0.0247D, WildSpawner.despawnChance(20), 1e-4);
        // A pet left alone lasts 800 ticks on average once the wait is over, as vanilla's do.
        assertEquals(800D, 20D / WildSpawner.despawnChance(20), 15D);
    }
}
