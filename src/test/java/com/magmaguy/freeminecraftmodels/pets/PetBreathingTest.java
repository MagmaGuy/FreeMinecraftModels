package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pet file's breathesUnderwater: off unless the file turns it on, as for a jellyfish that flies through water. */
class PetBreathingTest {
    @Test
    void aPetRunsOutOfAirUnderWaterUnlessItsFileSaysItBreathesThere() throws InvalidConfigurationException {
        assertFalse(PetSpecies.breathesUnderwater(new YamlConfiguration()));
        YamlConfiguration on = new YamlConfiguration();
        on.loadFromString("breathesUnderwater: true");
        assertTrue(PetSpecies.breathesUnderwater(on));
    }
}
