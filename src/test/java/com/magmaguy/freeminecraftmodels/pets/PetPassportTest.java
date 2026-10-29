package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pet's passport (generation and rarity) and the cap on how many pets a player may own. */
class PetPassportTest {
    private static PetRecord record(int generation) {
        PetRecord record = new PetRecord("jellyfish", "em_pet_jellyfish_blue", false, 0L);
        record.generation = generation;
        return record;
    }

    @Test
    void aBabyIsOneGenerationPastItsOlderParent() {
        assertEquals(1, new PetRecord("jellyfish", "em_pet_jellyfish_blue", false, 0L).generation, "tamed or given: the first");
        assertEquals(2, PetRecord.childGeneration(record(1), record(1)));
        assertEquals(5, PetRecord.childGeneration(record(4), record(2)));
        // A record saved before generations were kept reads 0 and counts as a first generation.
        assertEquals(1, record(0).generationOrFirst());
        assertEquals(2, PetRecord.childGeneration(record(0), record(0)));
    }

    @Test
    void rarityReadsAsOneInHowManyWildOnes() {
        assertEquals("1 in 21 wild ones", PetsMenu.rarity(1D / 21D));
        assertEquals("1 in 4 wild ones", PetsMenu.rarity(0.25D));
        assertEquals("common", PetsMenu.rarity(0.5D));
        assertEquals("never found wild", PetsMenu.rarity(0D));
    }

    @Test
    void theOwnedCapStopsAtItsLimitAndZeroMeansNone() {
        assertFalse(PetManager.atCap(0, 500), "0 means no limit");
        assertFalse(PetManager.atCap(10, 9));
        assertTrue(PetManager.atCap(10, 10));
        assertTrue(PetManager.atCap(10, 12), "a lowered cap holds back players already past it");
    }
}
