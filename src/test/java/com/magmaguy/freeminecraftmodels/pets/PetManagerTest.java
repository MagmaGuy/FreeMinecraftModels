package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The pet manager's decisions that need no server: how its warnings name a species' program, and which models a
 * migrant may wear and whether a species' night flocks fly.
 */
class PetManagerTest {
    private static final String FILE = "jellyfish.pet.yml";
    private static final String SPECIES = "em_pet_jellyfish", BLUE = "em_pet_jellyfish_blue",
            GOLD = "em_pet_jellyfish_gold", BIG = "em_pet_jellyfish_big";
    private static final Predicate<String> ALL_LOADED = model -> true;

    private static PetSpecies.Variant variant(String model, double weight) {
        return new PetSpecies.Variant(model, weight, List.of());
    }

    @Test
    void warningsSayWhenAListIsTheLocomotionsDefault() {
        assertEquals("jellyfish.pet.yml", PetManager.where("jellyfish.pet.yml", true, MindBodyLocomotion.FLYING,
                "em_pet_jellyfish_gold", true));
        assertEquals("jellyfish.pet.yml (default behaviors for FLYING)",
                PetManager.where("jellyfish.pet.yml", false, MindBodyLocomotion.FLYING, "em_pet_jellyfish", false));
        assertEquals("fox.pet.yml (default behaviors for GROUNDED)",
                PetManager.where("fox.pet.yml", false, MindBodyLocomotion.GROUNDED, "fox", false));
        // A variant with a list of its own adds to the defaults; warnings about its entries say whose they are.
        assertEquals("jellyfish.pet.yml (default behaviors for FLYING, plus variant em_pet_jellyfish_gold's list)",
                PetManager.where("jellyfish.pet.yml", false, MindBodyLocomotion.FLYING, "em_pet_jellyfish_gold", true));
    }

    @Test
    void migrantsWearTheVariantsThatArePickedAndLoaded() {
        List<PetSpecies.Variant> variants = List.of(variant(BLUE, 3D), variant(GOLD, 0D), variant(SPECIES, 1D));
        // A variant with no share of the picks never wears a migrant.
        assertEquals(Set.of(BLUE, SPECIES), PetManager.migrantModels(SPECIES, variants, null, ALL_LOADED));
        // One whose model is not loaded wears the species' model instead.
        assertEquals(Set.of(SPECIES), PetManager.migrantModels(SPECIES, variants, null, model -> !model.equals(BLUE)));
        // No variant with a share, or none at all: the species' model.
        assertEquals(Set.of(SPECIES), PetManager.migrantModels(SPECIES, List.of(variant(GOLD, 0D)), null, ALL_LOADED));
        assertEquals(Set.of(SPECIES), PetManager.migrantModels(SPECIES, List.of(), null, ALL_LOADED));
    }

    @Test
    void theMigrationLooksOwnModelWinsWhenLoaded() {
        List<PetSpecies.Variant> variants = List.of(variant(BLUE, 3D), variant(GOLD, 1D));
        assertEquals(Set.of(BIG), PetManager.migrantModels(SPECIES, variants, BIG, ALL_LOADED));
        // Not loaded: the species' model, not the variants.
        assertEquals(Set.of(SPECIES), PetManager.migrantModels(SPECIES, variants, BIG, model -> !model.equals(BIG)));
    }

    @Test
    void aFlyingSpeciesWhoseMigrantsAllRunMigrateFlies() {
        assertNull(PetManager.migrationProblem(FILE, MindBodyLocomotion.FLYING, List.of(variant(BLUE, 1D)),
                Set.of(BLUE), List.of()));
    }

    @Test
    void aSpeciesThatCannotFlyTheRouteIsToldWhy() {
        String end = ", so its night flocks are not launched; ";
        assertEquals("jellyfish.pet.yml: has a migration section, but its behaviors don't run migrate" + end
                        + "list migrate under behaviors: to launch them.",
                PetManager.migrationProblem(FILE, MindBodyLocomotion.FLYING, List.of(variant(BLUE, 1D)),
                        Set.of(BLUE), List.of(BLUE)));
        // Some variants add migrate in lists of their own and others do not.
        assertEquals("jellyfish.pet.yml: has a migration section, but the behaviors of variant em_pet_jellyfish_blue"
                        + " don't run migrate" + end + "list migrate under behaviors: to launch them.",
                PetManager.migrationProblem(FILE, MindBodyLocomotion.FLYING,
                        List.of(variant(BLUE, 1D), variant(GOLD, 1D)), Set.of(BLUE, GOLD), List.of(BLUE)));
        // A variant whose model is not loaded settled to the species' model, which is no variant: named as a model.
        assertEquals("jellyfish.pet.yml: has a migration section, but the behaviors of model em_pet_jellyfish don't"
                        + " run migrate" + end + "list migrate under behaviors: to launch them.",
                PetManager.migrationProblem(FILE, MindBodyLocomotion.FLYING,
                        List.of(variant(BLUE, 1D), variant(GOLD, 1D)), Set.of(SPECIES, GOLD), List.of(SPECIES)));
        // A species that does not fly cannot, whatever it lists.
        assertEquals("fox.pet.yml: has a migration section, but a GROUNDED species cannot fly a migration route" + end
                        + "remove the migration section.",
                PetManager.migrationProblem("fox.pet.yml", MindBodyLocomotion.GROUNDED, List.of(), Set.of("fox"),
                        List.of()));
    }
}
