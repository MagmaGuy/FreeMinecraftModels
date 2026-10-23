package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.customentity.core.TravelOrientation;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A species file's {@code orientation} section sets how its pets lean with their travel; without one they stay
 * upright. Loading a whole species needs a server's registries, so this reads the section the way the loader does.
 */
class PetSpeciesOrientationTest {
    private boolean installedServer;

    @AfterEach
    void releaseServer() throws ReflectiveOperationException {
        if (!installedServer) return;
        // Bukkit's singleton can only be set once; later test classes install their own, so give it back.
        Field server = Bukkit.class.getDeclaredField("server");
        server.setAccessible(true);
        server.set(null, null);
    }

    private static TravelOrientation read(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        ConfigurationSection section = config.getConfigurationSection("orientation");
        return PetSpecies.orientation(section == null ? config.createSection("orientation") : section, "jellyfish.pet.yml");
    }

    @Test
    void aSpeciesWithoutAnOrientationStaysUpright() throws InvalidConfigurationException {
        assertEquals(TravelOrientation.UPRIGHT, read("model: em_pet_jellyfish\n"));
    }

    @Test
    void theOrientationSectionSetsEveryValue() throws InvalidConfigurationException {
        assertEquals(new TravelOrientation(TravelOrientation.Style.TOP_FIRST, 0.5D, 3D, 120D, 90D), read("""
                orientation:
                  style: top_first
                  startSpeed: 0.5
                  fullSpeed: 3
                  maxTilt: 120
                  turnSpeed: 90
                """));
    }

    @Test
    void missingValuesTakeTheDefaults() throws InvalidConfigurationException {
        TravelOrientation defaults = TravelOrientation.UPRIGHT;
        assertEquals(new TravelOrientation(TravelOrientation.Style.FRONT_FIRST, defaults.startSpeed(), defaults.fullSpeed(),
                defaults.maxTilt(), defaults.turnSpeed()), read("orientation:\n  style: FRONT_FIRST\n"));
    }

    @Test
    void anUnknownStyleWarnsAndFallsBackToUpright() throws InvalidConfigurationException {
        if (Bukkit.getServer() == null) {
            Bukkit.setServer((Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
                    new Class<?>[]{Server.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getBukkitVersion" -> "26.3-R0.1-SNAPSHOT";
                        case "getLogger" -> java.util.logging.Logger.getLogger("FMM-pet-orientation-test");
                        default -> null;
                    }));
            installedServer = true;
        }
        List<String> warnings = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Bukkit.getLogger().addHandler(handler);
        try {
            assertEquals(TravelOrientation.Style.UPRIGHT, read("orientation:\n  style: sideways\n").style());
        } finally {
            Bukkit.getLogger().removeHandler(handler);
        }
        assertTrue(warnings.stream().anyMatch(message -> message.contains("sideways") && message.contains("jellyfish.pet.yml")),
                "the file's author is told which value was wrong: " + warnings);
    }

    @Test
    void outOfRangeValuesAreClamped() throws InvalidConfigurationException {
        TravelOrientation orientation = read("""
                orientation:
                  style: TOP_FIRST
                  startSpeed: 2
                  fullSpeed: 1
                  maxTilt: 400
                  turnSpeed: -5
                """);
        assertEquals(180D, orientation.maxTilt());
        assertEquals(180D, orientation.turnSpeed());
        assertEquals(2.1D, orientation.fullSpeed(), 1e-9, "full speed stays above the start speed");
    }
}
