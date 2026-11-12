package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pet file's footsteps: on for a species that walks unless the file turns them off, and never for one that flies. */
class PetFootstepsTest {
    @Test
    void aWalkingSpeciesHasFootstepsUnlessItsFileTurnsThemOff() throws InvalidConfigurationException {
        YamlConfiguration plain = new YamlConfiguration(), off = new YamlConfiguration();
        off.loadFromString("footsteps: false");
        assertTrue(PetSpecies.footsteps(plain, MindBodyLocomotion.GROUNDED));
        assertTrue(PetSpecies.footsteps(plain, MindBodyLocomotion.AMPHIBIOUS));
        assertFalse(PetSpecies.footsteps(off, MindBodyLocomotion.GROUNDED));
    }

    @Test
    void aSpeciesThatFliesOrSwimsHasNone() throws InvalidConfigurationException {
        YamlConfiguration on = new YamlConfiguration();
        on.loadFromString("footsteps: true");
        for (MindBodyLocomotion locomotion : MindBodyLocomotion.values()) {
            if (locomotion == MindBodyLocomotion.GROUNDED || locomotion == MindBodyLocomotion.AMPHIBIOUS) continue;
            assertFalse(PetSpecies.footsteps(on, locomotion), locomotion.name());
        }
    }
}
