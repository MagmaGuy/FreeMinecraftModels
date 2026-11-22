package com.magmaguy.freeminecraftmodels.pets.migration;

import com.magmaguy.freeminecraftmodels.pets.interactions.PetInteractions;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A species' flock settings come from its pet file's migration section; pets.yml keeps the server-wide ones. */
class PetMigrationSettingsTest {
    private static PetInteractions.Migration migration(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return PetInteractions.read(config, "jellyfish.pet.yml").migration();
    }

    private static DoubleSupplier draws(Double... values) {
        Iterator<Double> next = List.of(values).iterator();
        return next::next;
    }

    @Test
    void aSectionThatSetsNoFlockSettingsFliesTheOldPetsYmlDefaults() throws InvalidConfigurationException {
        PetInteractions.Migration migration = migration("""
                migration:
                  model: ''
                  scale: 2.0
                  animation: ''
                  weight: 1
                """);
        assertEquals(new PetInteractions.Migration(null, 2D, null, 1D, 0.15D, 8, 14, 28D, 0.15D, 1800, 160, 0.1D, true),
                migration);
        // The defaults are the ones pets.yml had: a 15% chance a night, 8 to 14 members, 28 blocks up at 0.15 blocks a
        // tick for 90 seconds, seen from 160 blocks, a 10% rare flock, lit fully bright.
        PetInteractions.Migration plain = PetInteractions.Migration.DEFAULT;
        assertEquals(List.of(0.15D, 8, 14, 28D, 0.15D, 1800, 160, 0.1D, true), List.of(plain.chancePerNight(),
                plain.minFlockSize(), plain.maxFlockSize(), plain.altitude(), plain.speed(), plain.durationTicks(),
                plain.viewDistance(), plain.rareFlockChance(), plain.fullBright()));
    }

    @Test
    void aSectionSetsItsOwnFlocks() throws InvalidConfigurationException {
        assertEquals(new PetInteractions.Migration("em_pet_jellyfish_gold", 1D, "glide", 3D, 0.5D, 3, 5, 40D, 0.3D, 1200,
                        96, 0D, false),
                migration("""
                        migration:
                          model: em_pet_jellyfish_gold
                          animation: glide
                          weight: 3
                          chancePerNight: 0.5
                          minFlockSize: 3
                          maxFlockSize: 5
                          altitude: 40
                          speed: 0.3
                          durationSeconds: 60
                          viewDistance: 96
                          rareFlockChance: 0
                          fullBright: false
                        """));
    }

    @Test
    void outOfRangeFlockSettingsAreBroughtIn() throws InvalidConfigurationException {
        PetInteractions.Migration migration = migration("""
                migration:
                  chancePerNight: 2
                  minFlockSize: 0
                  maxFlockSize: -3
                  altitude: 1
                  speed: 0
                  durationSeconds: 1
                  viewDistance: 2
                  rareFlockChance: -1
                """);
        assertEquals(1D, migration.chancePerNight());
        assertEquals(1, migration.minFlockSize());
        assertEquals(1, migration.maxFlockSize(), "never fewer than the fewest");
        assertEquals(4D, migration.altitude());
        assertEquals(0.02D, migration.speed());
        assertEquals(100, migration.durationTicks());
        assertEquals(16, migration.viewDistance());
        assertEquals(0D, migration.rareFlockChance());
        // Switched off, or missing, the species does not migrate.
        assertNull(migration("migration:\n  enabled: false\n  chancePerNight: 1\n"));
        assertNull(migration("model: em_pet_jellyfish\n"));
    }

    @Test
    void petsYmlKeepsOnlyTheServerWideSettings() throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                migrations:
                  enabled: false
                  chancePerNight: 0.5
                  minFlockSize: 2
                  maxFlockSize: 4
                  altitude: 30
                  speed: 0.2
                  durationSeconds: 60
                  viewDistance: 120
                  maxActiveFlocks: 5
                  species:
                  - Driftjelly
                  rareFlockChance: 0.3
                """);
        PetMigrations.loadSettings(config);
        assertEquals(new PetMigrations.Settings(false, 5, List.of("driftjelly")), PetMigrations.settings());
        for (String key : PetMigrations.MOVED_KEYS)
            assertFalse(config.contains("migrations." + key), "pets.yml still has migrations." + key);
        assertEquals(Map.of("enabled", false, "maxActiveFlocks", 5, "species", List.of("Driftjelly")),
                config.getConfigurationSection("migrations").getValues(false));
        // A new file gets the server-wide keys only.
        YamlConfiguration fresh = new YamlConfiguration();
        PetMigrations.loadSettings(fresh);
        assertEquals(new PetMigrations.Settings(true, 3, List.of()), PetMigrations.settings());
        assertEquals(Set.of("enabled", "maxActiveFlocks", "species"),
                fresh.getDefaults().getConfigurationSection("migrations").getKeys(false));
    }

    @Test
    void eachSpeciesIsRolledAtItsOwnChanceAndAWinnerPickedByWeight() {
        PetInteractions.Migration common = new PetInteractions.Migration(null, 1D, null, 1D, 0.15D, 8, 14, 28D, 0.15D,
                1800, 160, 0.1D, true);
        PetInteractions.Migration often = new PetInteractions.Migration(null, 1D, null, 3D, 0.6D, 8, 14, 28D, 0.15D,
                1800, 160, 0.1D, true);
        Map<String, PetInteractions.Migration> species = Map.of("common", common, "often", often);
        List<String> both = List.of("common", "often");
        // One species alone: won below its chance, as the one pets.yml roll was.
        assertEquals("common", PetMigrations.rollNight(List.of("common"), species::get, draws(0.1D, 0.5D)));
        assertNull(PetMigrations.rollNight(List.of("common"), species::get, draws(0.2D)));
        // Only often won.
        assertEquals("often", PetMigrations.rollNight(both, species::get, draws(0.2D, 0.5D, 0.0D)));
        // Both won: picked by weight, 1 to 3.
        assertEquals("common", PetMigrations.rollNight(both, species::get, draws(0.1D, 0.5D, 0.2D)));
        assertEquals("often", PetMigrations.rollNight(both, species::get, draws(0.1D, 0.5D, 0.3D)));
        assertNull(PetMigrations.rollNight(both, species::get, draws(0.9D, 0.9D)));
    }
}
