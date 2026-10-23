package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * How a species touches the world, from its pet file: gathering at campfires, blooming in the rain, glowing at
 * night, stinging on contact, perching by day, drizzling on crops, and its look in night migrations. Each part is off
 * unless its key is in the file, and a section can say {@code enabled: false} to stay off while keeping its values.
 */
public final class PetInteractions {
    /** Wild pets, and owned pets settled near their owner, drift to a lit campfire and circle in its smoke. */
    public record Campfire(double radius, double height, double ring, boolean nightOnly, boolean wild, boolean pets,
                           Set<Material> blocks, Set<String> soulVariants, int searchTicks) {
        /** Whether a pet wearing this model may gather at this kind of fire. */
        public boolean accepts(Material fire, String model) {
            if (!blocks.contains(fire)) return false;
            if (soulVariants.isEmpty()) return true;
            return (fire == Material.SOUL_CAMPFIRE) == soulVariants.contains(model);
        }
    }

    /** While rain falls on it, the pet swells, drifts more often and a little higher, and moves faster. */
    public record RainBloom(double speedMultiplier, double scaleMultiplier, double driftFrequency, int scaleTicks,
                            double rise) {
    }

    /** A fixed light level on the model's displays at night, and the lantern bone's glow animation. */
    public record Glow(boolean nightOnly, int light, String lanternBone, String animation) {
    }

    /** A contact sting: wild pets sting players, owned pets sting hostile mobs, never players. */
    public record Sting(boolean wild, boolean pets, PotionEffectType effect, int durationTicks, int amplifier,
                        double damage, int cooldownTicks, double reach) {
    }

    /** By day the pet settles on leaves or the ground below it, and lifts off again at dusk. */
    public record DayPerch(double height, int searchRadius, boolean wild, boolean pets) {
    }

    /** Hovering over crops now and then grows them a stage. */
    public record Drizzle(double chance, int intervalTicks, int depth) {
    }

    /** The species' look in a night migration flock. A bone draws each member as that one bone, the cheap way. */
    public record Migration(String model, String bone, double scale, String animation, double weight) {
    }

    public static final PetInteractions NONE = new PetInteractions(null, null, null, null, null, null, null);

    private final Campfire campfire;
    private final RainBloom rainBloom;
    private final Glow glow;
    private final Sting sting;
    private final DayPerch dayPerch;
    private final Drizzle drizzle;
    private final Migration migration;

    private PetInteractions(Campfire campfire, RainBloom rainBloom, Glow glow, Sting sting, DayPerch dayPerch,
                            Drizzle drizzle, Migration migration) {
        this.campfire = campfire;
        this.rainBloom = rainBloom;
        this.glow = glow;
        this.sting = sting;
        this.dayPerch = dayPerch;
        this.drizzle = drizzle;
        this.migration = migration;
    }

    /** Reads the interaction sections of a pet file; {@code model} is the species' model, the migration default. */
    public static PetInteractions read(ConfigurationSection config, String filename, String model) {
        try {
            return new PetInteractions(campfire(config, filename), rainBloom(config), glow(config),
                    sting(config, filename), dayPerch(config), drizzle(config), migration(config, model));
        } catch (RuntimeException failure) {
            Logger.warn("Could not read the world interactions in " + filename + ": " + failure.getMessage());
            return NONE;
        }
    }

    /** The section under the key when it is present and not switched off, otherwise null. */
    private static ConfigurationSection enabled(ConfigurationSection config, String key) {
        ConfigurationSection section = config.getConfigurationSection(key);
        if (section == null || !section.getBoolean("enabled", true)) return null;
        return section;
    }

    private static Campfire campfire(ConfigurationSection config, String filename) {
        ConfigurationSection section = enabled(config, "campfire");
        if (section == null) return null;
        Set<Material> blocks = EnumSet.noneOf(Material.class);
        for (String name : section.getStringList("blocks")) {
            Material material = Material.matchMaterial(name);
            if (material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE) blocks.add(material);
            else Logger.warn("campfire.blocks in " + filename + " lists " + name + "; only CAMPFIRE and SOUL_CAMPFIRE work");
        }
        if (blocks.isEmpty()) {
            blocks.add(Material.CAMPFIRE);
            blocks.add(Material.SOUL_CAMPFIRE);
        }
        return new Campfire(Math.max(1D, section.getDouble("radius", 12D)), section.getDouble("height", 2.5D),
                Math.max(0D, section.getDouble("ring", 0.8D)), section.getBoolean("nightOnly", true),
                section.getBoolean("wild", true), section.getBoolean("pets", true), Set.copyOf(blocks),
                Set.copyOf(section.getStringList("soulVariants")),
                (int) Math.max(20D, section.getDouble("searchSeconds", 5D) * 20D));
    }

    private static RainBloom rainBloom(ConfigurationSection config) {
        ConfigurationSection section = enabled(config, "rainBloom");
        if (section == null) return null;
        return new RainBloom(Math.max(0.1D, section.getDouble("speedMultiplier", 1.4D)),
                Math.max(0.1D, section.getDouble("scaleMultiplier", 1.25D)),
                Math.max(0.1D, section.getDouble("driftFrequency", 2.5D)),
                (int) Math.max(2D, section.getDouble("scaleSeconds", 3D) * 20D),
                section.getDouble("rise", 1D));
    }

    /** {@code glow: true} takes every default; a section tunes them. */
    private static Glow glow(ConfigurationSection config) {
        ConfigurationSection section = config.getConfigurationSection("glow");
        if (section == null) {
            if (!config.getBoolean("glow", false)) return null;
            section = new YamlConfiguration();
        } else if (!section.getBoolean("enabled", true)) return null;
        int light = Math.max(0, Math.min(15, section.getInt("light", 15)));
        return new Glow(section.getBoolean("nightOnly", true), light, section.getString("lanternBone", "lantern"),
                section.getString("animation", "glow"));
    }

    private static Sting sting(ConfigurationSection config, String filename) {
        ConfigurationSection section = enabled(config, "sting");
        if (section == null) return null;
        String effectName = section.getString("effect", "SLOWNESS");
        PotionEffectType effect = effect(effectName);
        if (effect == null && effectName != null && !effectName.isBlank() && !effectName.equalsIgnoreCase("NONE"))
            Logger.warn("Unknown potion effect " + effectName + " in sting.effect of " + filename);
        return new Sting(section.getBoolean("wild", true), section.getBoolean("pets", true), effect,
                Math.max(1, section.getInt("durationTicks", 60)), Math.max(0, section.getInt("amplifier", 0)),
                Math.max(0D, section.getDouble("damage", 0D)),
                (int) Math.max(1D, section.getDouble("cooldownSeconds", 3D) * 20D),
                Math.max(0D, section.getDouble("reach", 0.5D)));
    }

    @SuppressWarnings("deprecation")
    private static PotionEffectType effect(String name) {
        if (name == null || name.isBlank()) return null;
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        PotionEffectType effect = key == null ? null : Registry.EFFECT.get(key);
        // Older names such as SLOW still resolve through the legacy lookup.
        return effect != null ? effect : PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
    }

    private static DayPerch dayPerch(ConfigurationSection config) {
        ConfigurationSection section = enabled(config, "dayPerch");
        if (section == null) return null;
        return new DayPerch(Math.max(0D, section.getDouble("height", 0.3D)),
                Math.max(0, Math.min(4, section.getInt("searchRadius", 2))),
                section.getBoolean("wild", true), section.getBoolean("pets", true));
    }

    private static Drizzle drizzle(ConfigurationSection config) {
        ConfigurationSection section = enabled(config, "drizzle");
        if (section == null) return null;
        return new Drizzle(Math.max(0D, Math.min(1D, section.getDouble("chance", 0.1D))),
                (int) Math.max(20D, section.getDouble("intervalSeconds", 5D) * 20D),
                Math.max(1, Math.min(8, section.getInt("depth", 4))));
    }

    private static Migration migration(ConfigurationSection config, String model) {
        ConfigurationSection section = enabled(config, "migration");
        if (section == null) return null;
        String bone = section.getString("bone", "");
        String animation = section.getString("animation", "");
        return new Migration(section.getString("model", model), bone == null || bone.isBlank() ? null : bone,
                Math.max(0.05D, section.getDouble("scale", 1D)),
                animation == null || animation.isBlank() ? null : animation,
                Math.max(0D, section.getDouble("weight", 1D)));
    }

    public Campfire campfire() {
        return campfire;
    }

    public RainBloom rainBloom() {
        return rainBloom;
    }

    public Glow glow() {
        return glow;
    }

    public Sting sting() {
        return sting;
    }

    public DayPerch dayPerch() {
        return dayPerch;
    }

    public Drizzle drizzle() {
        return drizzle;
    }

    public Migration migration() {
        return migration;
    }
}
