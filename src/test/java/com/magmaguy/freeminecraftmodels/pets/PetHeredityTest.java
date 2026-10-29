package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which variant a baby takes: one parent's, half and half, unless that variant breeds true only sometimes, and then a
 * pick by weight as a wild one would get.
 */
class PetHeredityTest {
    private static final String SPECIES = "em_pet_jellyfish", BLUE = "em_pet_jellyfish_blue",
            GOLD = "em_pet_jellyfish_gold", WAR = "em_pet_jellyfish_man_o_war";
    // Weights 3 + 1 + 0.2: a wild pick's roll of 0 lands on blue, 0.8 on gold and 0.99 on the man o' war.
    private static final List<PetSpecies.Variant> VARIANTS = List.of(new PetSpecies.Variant(BLUE, 3D, List.of()),
            new PetSpecies.Variant(GOLD, 1D, List.of()),
            new PetSpecies.Variant(WAR, 0.2D, List.of(), null, null, Set.of(), 0.25D));

    /** A Random whose coin always lands on the mother (true) or the father, and whose doubles come in this order. */
    private static Random scripted(boolean mother, double... doubles) {
        return new Random() {
            private int next;

            @Override
            public boolean nextBoolean() {
                return mother;
            }

            @Override
            public double nextDouble() {
                return doubles[next++];
            }
        };
    }

    @Test
    void aBabyTakesTheMothersOrTheFathersVariant() {
        assertEquals(BLUE, PetSpecies.inherit(BLUE, GOLD, VARIANTS, SPECIES, scripted(true)));
        assertEquals(GOLD, PetSpecies.inherit(BLUE, GOLD, VARIANTS, SPECIES, scripted(false)));
    }

    @Test
    void aVariantThatAlwaysBreedsTrueRollsNothingMore() {
        // No doubles scripted: a roll would throw.
        assertEquals(BLUE, PetSpecies.inherit(BLUE, WAR, VARIANTS, SPECIES, scripted(true)));
    }

    @Test
    void aRareVariantBreedsTrueOnlyAtItsInheritChanceAndOtherwiseTakesAWildPick() {
        assertEquals(WAR, PetSpecies.inherit(WAR, WAR, VARIANTS, SPECIES, scripted(true, 0.2D)));
        assertEquals(BLUE, PetSpecies.inherit(WAR, WAR, VARIANTS, SPECIES, scripted(true, 0.3D, 0D)));
        assertEquals(GOLD, PetSpecies.inherit(WAR, WAR, VARIANTS, SPECIES, scripted(true, 0.3D, 0.8D)));
        assertEquals(WAR, PetSpecies.inherit(WAR, WAR, VARIANTS, SPECIES, scripted(true, 0.3D, 0.99D)),
                "the wild pick can still land on the rare variant");
    }

    @Test
    void anInheritChanceOfZeroNeverBreedsTrue() {
        List<PetSpecies.Variant> variants = List.of(new PetSpecies.Variant(BLUE, 1D, List.of()),
                new PetSpecies.Variant(WAR, 0D, List.of(), null, null, Set.of(), 0D));
        assertEquals(BLUE, PetSpecies.inherit(WAR, WAR, variants, SPECIES, scripted(true, 0D, 0.5D)));
    }

    @Test
    void aParentWhoseModelIsNoVariantPassesItOn() {
        // An old record (the species' model, or a variant since removed from the file); spawning settles the look.
        assertEquals("em_pet_jellyfish_old", PetSpecies.inherit("em_pet_jellyfish_old", BLUE, VARIANTS, SPECIES, scripted(true)));
        assertEquals(SPECIES, PetSpecies.inherit(SPECIES, BLUE, VARIANTS, SPECIES, scripted(true)));
    }

    @Test
    void aParentWithNoRecordedModelPassesOnAWildPick() {
        assertEquals(GOLD, PetSpecies.inherit(null, BLUE, VARIANTS, SPECIES, scripted(true, 0.8D)));
    }

    @Test
    void aSpeciesWithoutVariantsBreedsItsOwnModel() {
        assertEquals(SPECIES, PetSpecies.inherit(null, SPECIES, List.of(), SPECIES, scripted(true)));
    }
}
