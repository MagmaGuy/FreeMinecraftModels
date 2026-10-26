package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.configuration.ConfigurationSection;

/**
 * A species' look in night migrations, from its pet file's migration section. It is off unless the section is in the
 * file, and the section can say {@code enabled: false} to stay off while keeping its values. What a pet does in the
 * world, campfires, rain, glow, stings, perching and drizzle among it, comes from the behavior modules its file lists.
 */
public final class PetInteractions {
    /** The species' look in a night migration flock. A bone draws each member as that one bone, the cheap way. */
    /** model is a fixed model for every member, null to pick from the species' variants; scale multiplies its size. */
    public record Migration(String model, double scale, String animation, double weight) {
    }

    public static final PetInteractions NONE = new PetInteractions(null);

    private final Migration migration;

    private PetInteractions(Migration migration) {
        this.migration = migration;
    }

    /** Reads the migration section of a pet file; {@code model} is the species' model, the migration default. */
    public static PetInteractions read(ConfigurationSection config, String filename, String model) {
        try {
            return new PetInteractions(migration(config, model));
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

    private static Migration migration(ConfigurationSection config, String model) {
        ConfigurationSection section = enabled(config, "migration");
        if (section == null) return null;
        String fixed = section.getString("model", "");
        String animation = section.getString("animation", "");
        return new Migration(fixed == null || fixed.isBlank() ? null : fixed,
                Math.max(0.05D, section.getDouble("scale", 1D)),
                animation == null || animation.isBlank() ? null : animation,
                Math.max(0D, section.getDouble("weight", 1D)));
    }

    public Migration migration() {
        return migration;
    }
}
