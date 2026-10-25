package com.magmaguy.freeminecraftmodels.pets.behavior;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetBehaviorEntryTest {
    private static Object behaviors(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config.get("behaviors");
    }

    @Test
    void namesAndNamesWithSettings() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        List<PetBehaviorEntry> entries = PetBehaviorEntry.read(behaviors("""
                behaviors:
                - stay
                - Drift: { radius: 8 }
                - hover:
                    height: 1.5
                """), "jellyfish.pet.yml", warnings::add);
        assertEquals(List.of(new PetBehaviorEntry("stay", Map.of()), new PetBehaviorEntry("drift", Map.of("radius", 8)),
                new PetBehaviorEntry("hover", Map.of("height", 1.5))), entries);
        assertEquals(List.of(), warnings);
    }

    @Test
    void malformedEntriesWarnAndAreSkipped() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        List<PetBehaviorEntry> entries = PetBehaviorEntry.read(behaviors("""
                behaviors:
                - 12
                - { a: 1, b: 2 }
                - stay
                """), "jellyfish.pet.yml", warnings::add);
        assertEquals(List.of(new PetBehaviorEntry("stay", Map.of())), entries);
        assertEquals(2, warnings.size());
        assertTrue(warnings.getFirst().contains("jellyfish.pet.yml"), warnings.getFirst());
    }

    @Test
    void aModuleListedTwiceKeepsTheFirst() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(new PetBehaviorEntry("drift", Map.of("radius", 8))), PetBehaviorEntry.read(behaviors("""
                behaviors:
                - drift: { radius: 8 }
                - drift
                """), "jellyfish.pet.yml", warnings::add));
        assertEquals(1, warnings.size());
    }

    @Test
    void aNonListIsReportedAndEmpty() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(), PetBehaviorEntry.read(behaviors("behaviors: drift"), "x.pet.yml", warnings::add));
        assertEquals(1, warnings.size());
    }

    @Test
    void aVariantRetunesSharedModulesAndAddsItsOwn() {
        List<PetBehaviorEntry> species = List.of(new PetBehaviorEntry("stay", Map.of()),
                new PetBehaviorEntry("night_glow", Map.of("light", 15, "night_only", true)));
        List<PetBehaviorEntry> variant = List.of(new PetBehaviorEntry("night_glow", Map.of("light", 12)),
                new PetBehaviorEntry("owner_effect", Map.of("effect", "NIGHT_VISION")));
        assertEquals(List.of(new PetBehaviorEntry("stay", Map.of()),
                new PetBehaviorEntry("night_glow", Map.of("light", 12, "night_only", true)),
                new PetBehaviorEntry("owner_effect", Map.of("effect", "NIGHT_VISION"))),
                PetBehaviorEntry.overlay(species, variant));
    }
}
