package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A species file's {@code wildSpawn} section, shaped like a vanilla biome spawn entry. Loading a whole species needs a
 * server's registries, so this reads the section the way the loader does.
 */
class PetSpeciesWildSpawnTest {
    private static PetSpecies.WildSpawn read(String yaml, MindBodyLocomotion locomotion) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        ConfigurationSection section = config.getConfigurationSection("wildSpawn");
        return PetSpecies.readWildSpawn(section == null ? config.createSection("wildSpawn") : section, locomotion, "jellyfish.pet.yml");
    }

    @Test
    void placementFollowsHowTheSpeciesMoves() throws InvalidConfigurationException {
        assertEquals(PetSpecies.Placement.AIR, read("", MindBodyLocomotion.FLYING).placement());
        assertEquals(PetSpecies.Placement.WATER, read("", MindBodyLocomotion.AQUATIC).placement());
        assertEquals(PetSpecies.Placement.GROUND, read("", MindBodyLocomotion.GROUNDED).placement());
        assertEquals(PetSpecies.Placement.GROUND, read("", MindBodyLocomotion.AMPHIBIOUS).placement());
    }

    @Test
    void anEmptySectionSpawnsSinglesEverywhere() throws InvalidConfigurationException {
        PetSpecies.WildSpawn wild = read("", MindBodyLocomotion.FLYING);
        assertEquals(true, wild.enabled());
        assertEquals(10, wild.weight());
        assertEquals(1, wild.minGroup());
        assertEquals(1, wild.maxGroup());
        assertEquals(0, wild.minLight());
        assertEquals(15, wild.maxLight());
        assertEquals(4, wild.cap());
        assertEquals(List.of(), wild.worlds());
        assertEquals(List.of(), wild.environments());
        assertEquals(Set.of(), wild.biomes());
    }

    @Test
    void theSectionSetsEveryValue() throws InvalidConfigurationException {
        PetSpecies.WildSpawn wild = read("""
                wildSpawn:
                  enabled: false
                  weight: 25
                  minGroup: 2
                  maxGroup: 4
                  placement: water_surface
                  minLight: 3
                  maxLight: 12
                  cap: 6
                  worlds: [world]
                  environments: [NORMAL, the_end]
                  time: night
                  minY: 40
                  maxY: 90
                """, MindBodyLocomotion.FLYING);
        assertEquals(new PetSpecies.WildSpawn(false, 25, 2, 4, PetSpecies.Placement.WATER_SURFACE, 3, 12, 6, List.of("world"),
                List.of(World.Environment.NORMAL, World.Environment.THE_END), Set.of(), PetSpecies.TimeOfDay.NIGHT, 40, 90), wild);
    }

    @Test
    void aFileFromBeforeWeightsKeepsItsChanceAsAWeightOutOfTen() throws InvalidConfigurationException {
        assertEquals(5, read("wildSpawn:\n  chance: 0.5\n", MindBodyLocomotion.FLYING).weight());
        assertEquals(10, read("wildSpawn:\n  chance: 3\n", MindBodyLocomotion.FLYING).weight());
        assertEquals(7, read("wildSpawn:\n  chance: 0.5\n  weight: 7\n", MindBodyLocomotion.FLYING).weight(),
                "a weight wins over an old chance");
    }

    @Test
    void groupsAndLightStayInRange() throws InvalidConfigurationException {
        PetSpecies.WildSpawn wild = read("""
                wildSpawn:
                  weight: -4
                  minGroup: 0
                  maxGroup: -2
                  minLight: -3
                  maxLight: 40
                  cap: -1
                """, MindBodyLocomotion.FLYING);
        assertEquals(0, wild.weight());
        assertEquals(1, wild.minGroup());
        assertEquals(1, wild.maxGroup(), "the largest group is never smaller than the smallest");
        assertEquals(0, wild.minLight());
        assertEquals(15, wild.maxLight());
        assertEquals(0, wild.cap());
        assertEquals(9, read("wildSpawn:\n  minLight: 9\n  maxLight: 4\n", MindBodyLocomotion.FLYING).maxLight());
    }
}
