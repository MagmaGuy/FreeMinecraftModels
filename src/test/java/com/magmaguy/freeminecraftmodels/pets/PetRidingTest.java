package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pet file's riding section: how fast, how high and on which blocks a saddled adult goes faster. */
class PetRidingTest {
    private static final String FILE = "penguin.pet.yml";

    private static PetRiding read(String yaml, List<String> warnings) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return PetRiding.read(config.getConfigurationSection("riding"), FILE, warnings::add);
    }

    @Test
    void aSpeciesWithoutARidingSectionCannotBeRidden() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertNull(read("name: Penguin\n", warnings));
        assertEquals(List.of(), warnings);
    }

    @Test
    void anEmptyRidingSectionTakesTheDefaults() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        PetRiding riding = read("riding: {}\n", warnings);
        assertEquals(0.2D, riding.speed());
        assertEquals(0.4D, riding.jump());
        assertEquals(10D, riding.seatHeight());
        assertEquals("slide", riding.animation());
        assertEquals(1.5D, riding.multiplier(Material.AIR, Material.SNOW_BLOCK));
        assertEquals(1.2D, riding.multiplier(Material.AIR, Material.BLUE_ICE));
        assertEquals(List.of(), warnings);
    }

    @Test
    void writtenSurfacesReplaceTheDefaults() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        PetRiding riding = read("""
                riding:
                  speed: 0.3
                  jump: 0.6
                  seatHeight: 12
                  animation: glide
                  surfaces:
                    sand: 2
                """, warnings);
        assertEquals(0.3D, riding.speed());
        assertEquals(0.6D, riding.jump());
        assertEquals(12D, riding.seatHeight());
        assertEquals("glide", riding.animation());
        assertEquals(Map.of(Material.SAND, 2D), riding.surfaces());
        assertEquals(1D, riding.multiplier(Material.AIR, Material.SNOW_BLOCK), "only the written surfaces count");
        assertEquals(List.of(), warnings);
    }

    @Test
    void aSnowLayerAtTheFeetCountsBeforeTheBlockBelow() throws InvalidConfigurationException {
        PetRiding riding = read("riding: {}\n", new ArrayList<>());
        assertEquals(1.5D, riding.multiplier(Material.SNOW, Material.PACKED_ICE));
        assertEquals(1.2D, riding.multiplier(Material.AIR, Material.PACKED_ICE));
        assertEquals(1D, riding.multiplier(Material.AIR, Material.GRASS_BLOCK));
        assertEquals(1D, riding.multiplier(Material.SHORT_GRASS, Material.GRASS_BLOCK));
    }

    @Test
    void anUnknownSurfaceIsReportedAndSkipped() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        PetRiding riding = read("""
                riding:
                  surfaces:
                    snow_block: 1.5
                    slush: 3
                """, warnings);
        assertEquals(Map.of(Material.SNOW_BLOCK, 1.5D), riding.surfaces());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("slush"), warnings.getFirst());
    }

    @Test
    void onlyAWalkingHorseBodyCanBeRidden() {
        List<String> warnings = new ArrayList<>();
        assertTrue(PetRiding.supports(EntityType.HORSE, MindBodyLocomotion.AMPHIBIOUS, FILE, warnings::add));
        assertTrue(PetRiding.supports(EntityType.DONKEY, MindBodyLocomotion.GROUNDED, FILE, warnings::add));
        assertEquals(List.of(), warnings);
        assertFalse(PetRiding.supports(EntityType.ALLAY, MindBodyLocomotion.FLYING, FILE, warnings::add));
        assertFalse(PetRiding.supports(EntityType.HORSE, MindBodyLocomotion.FLYING, FILE, warnings::add));
        assertFalse(PetRiding.supports(EntityType.PIG, MindBodyLocomotion.GROUNDED, FILE, warnings::add));
        assertEquals(3, warnings.size(), warnings.toString());
    }
}
