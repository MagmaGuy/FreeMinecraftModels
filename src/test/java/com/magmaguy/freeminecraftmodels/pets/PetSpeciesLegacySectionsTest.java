package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The world interaction sections pet files had before behavior modules: each one still in a file is warned about once
 * as it loads, naming the module to list instead, or saying to remove it when it is off or its module is listed.
 */
class PetSpeciesLegacySectionsTest {
    private static final String FILE = "jellyfish.pet.yml";

    private static List<String> warnings(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        List<String> warnings = new ArrayList<>();
        PetSpecies.warnLegacySections(config, FILE, PetSpecies.listedModules(
                PetSpecies.listedBehaviors(config, FILE, warnings::add),
                PetSpecies.variants(config.get("variants"), FILE, warnings::add)), warnings::add);
        return warnings;
    }

    private static String ignored(String key, String kind) {
        return FILE + ": the " + key + " " + kind + " is ignored now that world interactions are behavior modules";
    }

    private static String list(String key, String kind, String module) {
        return ignored(key, kind) + "; list the " + module + " module under behaviors: instead (see"
                + " plugins/FreeMinecraftModels/pet_behaviors/" + module + ".lua for its settings).";
    }

    @Test
    void anOldSectionThatWasOnNamesTheModuleToList() throws InvalidConfigurationException {
        List<String> warnings = warnings("""
                model: em_pet_jellyfish
                campfire:
                  radius: 12
                rainBloom:
                  speedMultiplier: 1.4
                glow: true
                sting:
                  wild: true
                dayPerch:
                  height: 0.3
                drizzle:
                  chance: 0.1
                migration:
                  scale: 2
                """);
        assertEquals("jellyfish.pet.yml: the campfire section is ignored now that world interactions are behavior"
                + " modules; list the campfire module under behaviors: instead (see"
                + " plugins/FreeMinecraftModels/pet_behaviors/campfire.lua for its settings).", warnings.getFirst());
        // glow written as a plain value is called a setting.
        assertEquals(List.of(list("campfire", "section", "campfire"), list("rainBloom", "section", "rain_bloom"),
                list("glow", "setting", "night_glow"), list("sting", "section", "sting"),
                list("dayPerch", "section", "day_perch"), list("drizzle", "section", "crop_drizzle")), warnings);
    }

    @Test
    void anOldSectionThatIsOffOnlyNeedsRemoving() throws InvalidConfigurationException {
        assertEquals(List.of(
                        "jellyfish.pet.yml: the rainBloom section is ignored now that world interactions are behavior"
                                + " modules, and it is switched off anyway; remove the section.",
                        ignored("glow", "setting") + ", and it is switched off anyway; remove the setting."),
                warnings("""
                        model: em_pet_jellyfish
                        rainBloom:
                          enabled: false
                          speedMultiplier: 1.4
                        glow: false
                        """));
    }

    @Test
    void anOldSectionWhoseModuleIsListedOnlyNeedsRemoving() throws InvalidConfigurationException {
        // The species' list has sting; a variant's list has day_perch. A listed module wins over a switched-off one.
        assertEquals(List.of(
                        "jellyfish.pet.yml: the sting section is ignored now that world interactions are behavior"
                                + " modules, and a behaviors: list in this file already has sting; remove the section.",
                        ignored("dayPerch", "section") + ", and a behaviors: list in this file already has day_perch;"
                                + " remove the section.",
                        list("drizzle", "section", "crop_drizzle")),
                warnings("""
                        model: em_pet_jellyfish
                        behaviors:
                        - stay
                        - sting: { wild: true }
                        variants:
                        - model: em_pet_jellyfish_gold
                          behaviors:
                          - day_perch
                        sting:
                          wild: true
                        dayPerch:
                          enabled: false
                        drizzle:
                          chance: 0.1
                        """));
    }

    @Test
    void aFileWithoutOldSectionsIsQuiet() throws InvalidConfigurationException {
        assertEquals(List.of(), warnings("""
                model: em_pet_jellyfish
                behaviors:
                - stay
                - night_glow: { light: 12 }
                migration:
                  scale: 2
                """));
    }
}
