package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The world interaction sections pet files had before behavior modules: each one still in a file is warned about once
 * as it loads, naming the module that does the same now.
 */
class PetSpeciesLegacySectionsTest {
    private static final String FILE = "jellyfish.pet.yml";

    private static List<String> warnings(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        List<String> warnings = new ArrayList<>();
        PetSpecies.warnLegacySections(config, FILE, warnings::add);
        return warnings;
    }

    private static String warning(String key, String kind, String module) {
        return FILE + ": the " + key + " " + kind + " no longer does anything; world interactions are behavior modules"
                + " now. List the " + module + " module under behaviors: instead (see"
                + " plugins/FreeMinecraftModels/pet_behaviors/" + module + ".lua for its settings); this " + kind
                + " is ignored.";
    }

    @Test
    void eachOldSectionIsWarnedAboutWithItsModule() throws InvalidConfigurationException {
        List<String> warnings = warnings("""
                model: em_pet_jellyfish
                behaviors:
                - stay
                campfire:
                  radius: 12
                rainBloom:
                  enabled: false
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
        assertEquals("jellyfish.pet.yml: the campfire section no longer does anything; world interactions are behavior"
                + " modules now. List the campfire module under behaviors: instead (see"
                + " plugins/FreeMinecraftModels/pet_behaviors/campfire.lua for its settings); this section is ignored.",
                warnings.getFirst());
        // A section switched off still says so, and glow written as a plain setting is called one.
        assertEquals(List.of(warning("campfire", "section", "campfire"), warning("rainBloom", "section", "rain_bloom"),
                warning("glow", "setting", "night_glow"), warning("sting", "section", "sting"),
                warning("dayPerch", "section", "day_perch"), warning("drizzle", "section", "crop_drizzle")), warnings);
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
