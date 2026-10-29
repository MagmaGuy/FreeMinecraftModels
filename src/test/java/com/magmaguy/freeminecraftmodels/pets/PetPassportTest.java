package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pet's passport (generation, rarity and its book's pages) and the cap on how many pets a player may own. */
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
    void rarityIsATierAgainstTheCommonestColour() {
        assertEquals("common", PetSpecies.rarityTier(10D, 10D));
        assertEquals("common", PetSpecies.rarityTier(5D, 10D));
        assertEquals("uncommon", PetSpecies.rarityTier(2D, 10D));
        assertEquals("rare", PetSpecies.rarityTier(1D, 10D));
        assertEquals("never wild", PetSpecies.rarityTier(0D, 10D));
    }

    @Test
    void aBarFillsFromTheFloorToTheCap() {
        assertEquals("§2§7" + "|".repeat(20), PetPassport.bar(0.5D, 2D));
        assertEquals("§2" + "|".repeat(20) + "§7", PetPassport.bar(2D, 2D));
        assertEquals("§2" + "|".repeat(7) + "§7" + "|".repeat(13), PetPassport.bar(1D, 2D));
    }

    private static PetPassport.Facts facts(List<PetPassport.Parent> family, List<String> legacy, List<PetPassport.Kin> babies,
                                           String origin) {
        return new PetPassport.Facts("Bubbles", "Driftjelly", "Tidesurge", "common", 3, origin, "29 Oct 2026", "MagmaGuy",
                18D, 27D, new PetStats(1.35D, 1.04D, 1.2D, 1.2D), 2D, 4D, 5D, "POISON", 1.1D, false, 0L, "With you", 3, 0L,
                family, legacy, babies);
    }

    @Test
    void thePagesShowIdentityStatsLifeFamilyAndBabies() {
        List<PetPassport.Parent> family = List.of(
                new PetPassport.Parent(new PetPassport.Kin("Wave", "Tidesurge"),
                        List.of(new PetPassport.Kin("Kelp", "Tidesurge"), new PetPassport.Kin("Sunflare", "Sunflare"))),
                new PetPassport.Parent(new PetPassport.Kin("Frostveil", "Frostveil"), List.of()));
        List<PetPassport.Kin> babies = List.of(new PetPassport.Kin("Tidesurge", "Tidesurge"), new PetPassport.Kin("Pip", "Frostveil"));
        List<String> pages = PetPassport.pages(facts(family, List.of(), babies, "Bred"));
        assertEquals(5, pages.size());
        assertTrue(pages.get(0).contains("§0Name §1Bubbles") && pages.get(0).contains("§0Bred §829 Oct 2026"));
        assertTrue(pages.get(1).contains("§0Health §118/27") && pages.get(1).contains("§0Sting §14.8")
                && pages.get(1).contains("§0Poison §16.0s") && pages.get(1).contains("§0Size §1110%"));
        assertTrue(pages.get(2).contains("§2ready") && pages.get(2).contains("§0Babies §13"));
        assertTrue(pages.get(3).contains("§1Wave §8Tidesurge\n§8 from Kelp & Sunflare") && pages.get(3).contains("§1Frostveil"));
        // Newest first; an unnamed baby shows its colour once.
        assertTrue(pages.get(4).indexOf("§1Pip §8Frostveil") < pages.get(4).indexOf("§1Tidesurge"));
        for (String page : pages) assertTrue(page.split("\n").length <= 14, "a page fits the book: " + page);
    }

    @Test
    void olderPetsShowWhatTheyKept() {
        assertTrue(PetPassport.pages(facts(List.of(), List.of("Wave", "Fizz"), List.of(), "Bred")).get(3)
                .contains("Bred before family"));
        assertTrue(PetPassport.pages(facts(List.of(), List.of(), List.of(), "Tamed")).get(3).contains("Tamed in the wild:"));
        assertEquals(4, PetPassport.pages(facts(List.of(), List.of(), List.of(), "Given")).size(), "no babies, no babies page");
    }

    @Test
    void theOwnedCapStopsAtItsLimitAndZeroMeansNone() {
        assertFalse(PetManager.atCap(0, 500), "0 means no limit");
        assertFalse(PetManager.atCap(10, 9));
        assertTrue(PetManager.atCap(10, 10));
        assertTrue(PetManager.atCap(10, 12), "a lowered cap holds back players already past it");
    }
}
