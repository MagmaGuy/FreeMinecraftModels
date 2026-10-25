package com.magmaguy.freeminecraftmodels.pets.behavior;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void aNameWithNoValueHasNoSettings() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(new PetBehaviorEntry("stay", Map.of())), PetBehaviorEntry.read(behaviors("""
                behaviors:
                - stay:
                """), "jellyfish.pet.yml", warnings::add));
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
        assertTrue(warnings.stream().allMatch(warning -> warning.contains("jellyfish.pet.yml")), warnings.toString());
    }

    @Test
    void badEntriesAreQuotedAsYamlReadThem() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(), PetBehaviorEntry.read(behaviors("""
                behaviors:
                - on
                -
                - ""
                """), "jellyfish.pet.yml", warnings::add));
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("skipping \"true\""), warnings.get(0));
        assertTrue(warnings.get(1).contains("skipping an empty entry"), warnings.get(1));
        assertTrue(warnings.get(2).contains("skipping an empty entry"), warnings.get(2));
    }

    @Test
    void settingsIndentedNoFurtherThanTheNameAreExplained() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(), PetBehaviorEntry.read(behaviors("""
                behaviors:
                - hover:
                  height: 1.5
                """), "jellyfish.pet.yml", warnings::add));
        assertEquals(List.of("jellyfish.pet.yml: hover and height ended up in one behaviors entry; indent hover's settings "
                + "two spaces past its name, or write \"- hover: { height: 1.5 }\"."), warnings);
    }

    @Test
    void settingsThatAreNotAMapWarnAndAreSkipped() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(new PetBehaviorEntry("stay", Map.of())), PetBehaviorEntry.read(behaviors("""
                behaviors:
                - drift: 8
                - stay
                """), "jellyfish.pet.yml", warnings::add));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("jellyfish.pet.yml") && warnings.getFirst().contains("\"drift: 8\""),
                warnings.getFirst());
    }

    @Test
    void aModuleListedTwiceKeepsTheFirst() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(new PetBehaviorEntry("drift", Map.of("radius", 8))), PetBehaviorEntry.read(behaviors("""
                behaviors:
                - drift: { radius: 8 }
                - drift
                """), "jellyfish.pet.yml", warnings::add));
        assertEquals(List.of("jellyfish.pet.yml: behaviors lists drift twice; keeping the first."), warnings);
    }

    @Test
    void namesDifferingOnlyInCaseAreOneModule() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of(new PetBehaviorEntry("drift", Map.of("radius", 8))), PetBehaviorEntry.read(behaviors("""
                behaviors:
                - Drift: { radius: 8 }
                - drift
                """), "jellyfish.pet.yml", warnings::add));
        assertEquals(1, warnings.size());
    }

    @Test
    void aNonListIsReportedAndIgnored() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertNull(PetBehaviorEntry.read(behaviors("behaviors: drift"), "x.pet.yml", warnings::add));
        assertNull(PetBehaviorEntry.read(behaviors("behaviors:\n  stay: {}\n"), "x.pet.yml", warnings::add));
        assertEquals(2, warnings.size());
        assertTrue(warnings.stream().allMatch(warning -> warning.contains("x.pet.yml") && warning.contains("ignored")),
                warnings.toString());
    }

    @Test
    void theListAndEachEntrysSettingsAreReadOnly() throws InvalidConfigurationException {
        List<PetBehaviorEntry> entries = PetBehaviorEntry.read(behaviors("""
                behaviors:
                - drift: { radius: 8 }
                """), "jellyfish.pet.yml", warning -> {
        });
        assertThrows(UnsupportedOperationException.class, () -> entries.add(new PetBehaviorEntry("stay", Map.of())));
        assertThrows(UnsupportedOperationException.class, () -> entries.getFirst().settings().put("radius", 9));
    }

    @Test
    void anEntryNeedsAModuleNameAndSettings() {
        assertThrows(NullPointerException.class, () -> new PetBehaviorEntry(null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new PetBehaviorEntry("  ", Map.of()));
        assertThrows(NullPointerException.class, () -> new PetBehaviorEntry("stay", null));
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

    @Test
    void aVariantSettingLeftEmptyKeepsTheSpeciesValue() {
        List<PetBehaviorEntry> species = List.of(new PetBehaviorEntry("night_glow", Map.of("light", 15)));
        List<PetBehaviorEntry> variant = List.of(new PetBehaviorEntry("night_glow", Collections.singletonMap("light", null)));
        assertEquals(species, PetBehaviorEntry.overlay(species, variant));
    }

    @Test
    void aVariantWithoutModulesKeepsTheSpeciesList() {
        List<PetBehaviorEntry> species = new ArrayList<>(List.of(new PetBehaviorEntry("stay", Map.of())));
        List<PetBehaviorEntry> overlaid = PetBehaviorEntry.overlay(species, List.of());
        assertEquals(species, overlaid);
        assertThrows(UnsupportedOperationException.class, () -> overlaid.add(new PetBehaviorEntry("drift", Map.of())));
    }
}
