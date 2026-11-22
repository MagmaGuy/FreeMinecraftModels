package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.configuration.ConfigurationSection;

/**
 * A species' night migrations, from its pet file's migration section: how its flocks look and fly. It is off unless the
 * section is in the file, and the section can say {@code enabled: false} to stay off while keeping its values. What a pet does in the
 * world, campfires, rain, glow, stings, perching and drizzle among it, comes from the behavior modules its file lists.
 */
public final class PetInteractions {
    /**
     * The species' night migration flocks. model is a fixed model for every member, null to pick from the species'
     * variants; scale multiplies its size; weight is how often the species is picked among those that migrate.
     * chancePerNight is a player's chance of a flock of the species on a given night, a flock has minFlockSize to
     * maxFlockSize members, flies altitude blocks up at speed blocks a tick for durationTicks, and is seen from
     * viewDistance blocks. rareFlockChance is the chance of a flock all in one rare colour; fullBright lights the
     * members fully whatever the light around them.
     */
    public record Migration(String model, double scale, String animation, double weight, double chancePerNight,
                            int minFlockSize, int maxFlockSize, double altitude, double speed, int durationTicks,
                            int viewDistance, double rareFlockChance, boolean fullBright) {
        /** A migration section that sets nothing: the flocks pets.yml flew before these moved to the pet file. */
        public static final Migration DEFAULT = new Migration(null, 1D, null, 1D, 0.15D, 8, 14, 28D, 0.15D, 1800, 160,
                0.1D, true);
    }

    public static final PetInteractions NONE = new PetInteractions(null);

    private final Migration migration;

    private PetInteractions(Migration migration) {
        this.migration = migration;
    }

    /** Reads the migration section of a pet file. */
    public static PetInteractions read(ConfigurationSection config, String filename) {
        try {
            return new PetInteractions(migration(config));
        } catch (RuntimeException failure) {
            Logger.warn("Could not read the migration section in " + filename + ": " + failure.getMessage());
            return NONE;
        }
    }

    /** The section under the key when it is present and not switched off, otherwise null. */
    private static ConfigurationSection enabled(ConfigurationSection config, String key) {
        ConfigurationSection section = config.getConfigurationSection(key);
        if (section == null || !section.getBoolean("enabled", true)) return null;
        return section;
    }

    private static Migration migration(ConfigurationSection config) {
        ConfigurationSection section = enabled(config, "migration");
        if (section == null) return null;
        Migration plain = Migration.DEFAULT;
        String fixed = section.getString("model", "");
        String animation = section.getString("animation", "");
        int fewest = Math.max(1, section.getInt("minFlockSize", plain.minFlockSize()));
        return new Migration(fixed == null || fixed.isBlank() ? null : fixed,
                Math.max(0.05D, section.getDouble("scale", plain.scale())),
                animation == null || animation.isBlank() ? null : animation,
                Math.max(0D, section.getDouble("weight", plain.weight())),
                chance(section.getDouble("chancePerNight", plain.chancePerNight())),
                fewest, Math.max(fewest, section.getInt("maxFlockSize", plain.maxFlockSize())),
                Math.max(4D, section.getDouble("altitude", plain.altitude())),
                Math.max(0.02D, section.getDouble("speed", plain.speed())),
                (int) Math.max(100D, section.getDouble("durationSeconds", plain.durationTicks() / 20D) * 20D),
                Math.max(16, section.getInt("viewDistance", plain.viewDistance())),
                chance(section.getDouble("rareFlockChance", plain.rareFlockChance())),
                section.getBoolean("fullBright", plain.fullBright()));
    }

    private static double chance(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    public Migration migration() {
        return migration;
    }
}
