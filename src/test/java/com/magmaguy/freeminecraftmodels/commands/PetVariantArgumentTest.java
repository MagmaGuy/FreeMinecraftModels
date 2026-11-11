package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.commands.arguments.PetVariantArgument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The variants a pet command suggests follow the species typed before them. */
class PetVariantArgumentTest {
    private static final Map<String, List<String>> VARIANTS = Map.of(
            "penguin", List.of("fmm_penguin", "fmm_penguin_blue"),
            "driftjelly", List.of("fmm_driftjelly_pink"));
    private static final Function<String, List<String>> VARIANTS_OF = species -> VARIANTS.getOrDefault(species, List.of());

    private static List<String> petAdmin(String... earlier) {
        return PetVariantArgument.suggestions(List.of(earlier), PetAdminCommand::speciesTyped, VARIANTS_OF);
    }

    @Test
    void petAdminSuggestsOnlyTheVariantsOfTheSpeciesTyped() {
        assertEquals(List.of("fmm_penguin", "fmm_penguin_blue"), petAdmin("spawn", "penguin"));
        assertEquals(List.of("fmm_penguin", "fmm_penguin_blue"), petAdmin("give", "penguin"));
        assertEquals(List.of("fmm_driftjelly_pink"), petAdmin("cluster", "driftjelly"));
        assertEquals(List.of("fmm_driftjelly_pink"), petAdmin("SPAWN", "driftjelly"));
    }

    @Test
    void petAdminSuggestsNoVariantWhereNoneBelongs() {
        assertEquals(List.of(), petAdmin("spawn", "moth"), "a species that is not installed");
        assertEquals(List.of(), petAdmin("mode", "creatures"));
        assertEquals(List.of(), petAdmin("march", "penguin"), "march takes a species and no variant");
        assertNull(PetAdminCommand.speciesTyped(List.of("spawn")));
    }

    @Test
    void givePetReadsTheSpeciesAfterThePlayer() {
        Function<List<String>, String> afterPlayer = earlier -> earlier.size() < 2 ? null : earlier.get(1);
        assertEquals(List.of("fmm_driftjelly_pink"),
                PetVariantArgument.suggestions(List.of("Frost", "driftjelly"), afterPlayer, VARIANTS_OF));
    }
}
