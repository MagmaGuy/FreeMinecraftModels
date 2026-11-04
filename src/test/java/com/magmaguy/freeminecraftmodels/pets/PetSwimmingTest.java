package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.customentity.core.TravelOrientation;
import com.magmaguy.magmacore.ai.MindSwimWave;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pet file's swimming section: what the model plays in water, how it leans there, and the wave it swims. */
class PetSwimmingTest {
    private static final String FILE = "penguin.pet.yml";

    private static PetSwimming read(String yaml, List<String> warnings) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return PetSwimming.read(config.getConfigurationSection("swimming"), FILE, warnings::add);
    }

    @Test
    void aSpeciesWithoutASwimmingSectionSwimsAsItWalks() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        assertNull(read("name: Penguin\n", warnings));
        assertEquals(List.of(), warnings);
    }

    @Test
    void anEmptySwimmingSectionLeadsWithItsFrontAndSwimsAGentleWave() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        PetSwimming swimming = read("swimming: {}\n", warnings);
        assertEquals("swim", swimming.animation());
        assertEquals(PetSwimming.ORIENTATION, swimming.orientation());
        assertEquals(TravelOrientation.Style.FRONT_FIRST, swimming.orientation().style());
        assertEquals(new MindSwimWave(0.5D, 40), swimming.wave());
        assertEquals(List.of(), warnings);
    }

    @Test
    void theSectionSetsEveryValue() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        PetSwimming swimming = read("""
                swimming:
                  animation: slide
                  orientation:
                    style: top_first
                    startSpeed: 0.5
                    fullSpeed: 3
                    maxTilt: 30
                    turnSpeed: 90
                  wave:
                    height: 0.8
                    seconds: 1.5
                """, warnings);
        assertEquals("slide", swimming.animation());
        assertEquals(new TravelOrientation(TravelOrientation.Style.TOP_FIRST, 0.5D, 3D, 30D, 90D), swimming.orientation());
        assertEquals(new MindSwimWave(0.8D, 30), swimming.wave());
        assertEquals(List.of(), warnings);
    }

    @Test
    void anOrientationWithoutAStyleStaysFrontFirst() throws InvalidConfigurationException {
        PetSwimming swimming = read("swimming:\n  orientation:\n    maxTilt: 20\n", new ArrayList<>());
        assertEquals(TravelOrientation.Style.FRONT_FIRST, swimming.orientation().style());
        assertEquals(20D, swimming.orientation().maxTilt());
        assertEquals(PetSwimming.ORIENTATION.fullSpeed(), swimming.orientation().fullSpeed());
    }

    @Test
    void anUnknownStyleWarnsAndStaysFrontFirst() throws InvalidConfigurationException {
        List<String> warnings = new ArrayList<>();
        PetSwimming swimming = read("swimming:\n  orientation:\n    style: sideways\n", warnings);
        assertEquals(TravelOrientation.Style.FRONT_FIRST, swimming.orientation().style());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("swimming.orientation.style") && warnings.getFirst().contains("sideways")
                && warnings.getFirst().contains(FILE), warnings.getFirst());
    }

    @Test
    void aWaveOfHeightZeroSwimsLevelAndOutOfRangeValuesAreClamped() throws InvalidConfigurationException {
        assertFalse(read("swimming:\n  wave:\n    height: 0\n", new ArrayList<>()).wave().waves());
        MindSwimWave wild = read("swimming:\n  wave:\n    height: 40\n    seconds: 0.01\n", new ArrayList<>()).wave();
        assertEquals(MindSwimWave.MAX_HEIGHT, wild.height());
        assertEquals(MindSwimWave.MIN_PERIOD_TICKS, wild.periodTicks());
        assertFalse(read("swimming:\n  wave:\n    height: -1\n", new ArrayList<>()).wave().waves());
    }
}
