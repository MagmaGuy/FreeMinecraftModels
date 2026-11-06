package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of wild spawning that follow vanilla's numbers: pack steps, stocking steps, weighted picks and spawn areas;
 * and the clusters a new chunk now and then gets in place of a group.
 */
class WildSpawnerTest {
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
    void stockingStepsStayInTheChunkAndNearTheGroup() {
        Random random = new Random(3);
        int minX = -32, minZ = 48, startX = -31, startZ = 62;
        int x = startX, z = startZ;
        for (int i = 0; i < 5_000; i++) {
            int[] next = WildSpawner.generationStep(startX, startZ, x, z, minX, minZ, random);
            assertTrue(next[0] >= minX && next[0] < minX + 16 && next[1] >= minZ && next[1] < minZ + 16,
                    "step " + i + " left the chunk: " + next[0] + "," + next[1]);
            boolean fromLast = Math.abs(next[0] - x) <= 4 && Math.abs(next[1] - z) <= 4;
            boolean fromStart = Math.abs(next[0] - startX) <= 4 && Math.abs(next[1] - startZ) <= 4;
            assertTrue(fromLast || fromStart, "a step moves at most 4 blocks, from the last spot or the group's first");
            x = next[0];
            z = next[1];
        }
    }

    @Test
    void aSpeciesOfWeightEightInGroupsOfFourIsAsCommonAsCows() {
        // Vanilla 26.3 plains: 0.1 creature chance rolled again, cow weight 8 of 46, groups of 4 = 7.73 cows per 100 chunks.
        double cowsPerChunk = 0.1D / 0.9D * 8D / 46D * 4D;
        assertEquals(cowsPerChunk, WildSpawner.groupsPerChunk(8, 1D) * 4D, 1E-12);
        assertEquals(7.73D, WildSpawner.groupsPerChunk(8, 1D) * 4D * 100D, 0.01D);
        // A penguin, weight 10 in groups of 2 to 4, comes to about 7.2 per 100 chunks of its biomes.
        assertEquals(7.25D, WildSpawner.groupsPerChunk(10, 1D) * 3D * 100D, 0.01D);
    }

    @Test
    void theRollAgainChanceGivesThoseGroupsOnAverage() {
        for (int weight : new int[]{1, 8, 10, 40}) {
            double chance = WildSpawner.generationChance(weight, 1D);
            assertEquals(WildSpawner.groupsPerChunk(weight, 1D), chance / (1D - chance), 1E-12);
        }
        Random random = new Random(7L);
        double chance = WildSpawner.generationChance(8, 1D);
        long groups = 0;
        int chunks = 2_000_000;
        for (int chunk = 0; chunk < chunks; chunk++) while (random.nextDouble() < chance) groups++;
        assertEquals(WildSpawner.groupsPerChunk(8, 1D), (double) groups / chunks, 0.0005D);
    }

    /** A penguin's wildSpawn: groups of 2 to 4, with this cluster. */
    private static PetSpecies.WildSpawn penguin(PetSpecies.Cluster cluster) {
        return new PetSpecies.WildSpawn(true, 10, 2, 4, PetSpecies.Placement.GROUND, 9, 15, 6, List.of(), List.of(),
                Set.of(), PetSpecies.TimeOfDay.ANY, -64, 320, cluster);
    }

    @Test
    void aSpeciesWithoutClustersGetsOnlyItsUsualGroups() {
        Random random = new Random(19);
        for (int group = 0; group < 10_000; group++) {
            WildSpawner.Gathering gathering = WildSpawner.gathering(penguin(PetSpecies.Cluster.NONE),
                    () -> { throw new AssertionError("no cluster, so no rare colour"); }, random);
            assertTrue(gathering.size() >= 2 && gathering.size() <= 4, "size " + gathering.size());
            assertNull(gathering.colour());
        }
    }

    @Test
    void clustersComeAtTheirChanceAndSizeAndSomeWearOneRareColour() {
        Random random = new Random(23);
        PetSpecies.WildSpawn wild = penguin(new PetSpecies.Cluster(0.1D, 8, 14, 0.1D));
        int groups = 100_000, clusters = 0, rare = 0;
        Set<Integer> clusterSizes = new HashSet<>();
        for (int group = 0; group < groups; group++) {
            WildSpawner.Gathering gathering = WildSpawner.gathering(wild, () -> "penguin_lutino", random);
            if (gathering.size() <= 4) {
                assertTrue(gathering.size() >= 2);
                assertNull(gathering.colour(), "a usual group never wears one colour");
                continue;
            }
            clusters++;
            clusterSizes.add(gathering.size());
            if (gathering.colour() != null) {
                assertEquals("penguin_lutino", gathering.colour());
                rare++;
            }
        }
        assertEquals(0.1D, (double) clusters / groups, 0.005D);
        assertEquals(Set.of(8, 9, 10, 11, 12, 13, 14), clusterSizes);
        assertEquals(0.1D, (double) rare / clusters, 0.015D);
    }

    @Test
    void aClusterWithNoRareColourToWearLetsEachMemberPick() {
        Random random = new Random(29);
        PetSpecies.WildSpawn wild = penguin(new PetSpecies.Cluster(1D, 8, 8, 1D));
        WildSpawner.Gathering gathering = WildSpawner.gathering(wild, () -> null, random);
        assertEquals(new WildSpawner.Gathering(8, null), gathering);
    }

    @Test
    void abundanceScalesEverySpeciesAndZeroStopsStocking() {
        assertEquals(2D * WildSpawner.groupsPerChunk(10, 1D), WildSpawner.groupsPerChunk(10, 2D), 1E-12);
        assertEquals(0D, WildSpawner.generationChance(10, 0D));
        assertEquals(0D, WildSpawner.generationChance(10, -1D));
        assertEquals(0D, WildSpawner.generationChance(0, 1D));
        assertEquals(WildSpawner.MAX_GENERATION_CHANCE, WildSpawner.generationChance(10, 1000D));
    }
}
