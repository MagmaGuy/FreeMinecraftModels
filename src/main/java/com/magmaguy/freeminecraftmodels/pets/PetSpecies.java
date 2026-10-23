package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * One pet species, read from a {@code <species>.pet.yml} file that sits beside its model in the models folder, so a
 * model pack ships, enables and disables its pets together with its models. It names the model and the body the
 * model rides, and says how the species is tamed, bred and raised, how it floats and follows, and where it lives
 * wild.
 */
public final class PetSpecies {
    public static final String FILE_SUFFIX = ".pet.yml";

    public record Variant(String model, double weight) {
    }

    public enum TimeOfDay {ANY, DAY, NIGHT}

    /** Where and how often the species appears wild in CREATURES mode. Empty lists allow everything. */
    public record WildSpawn(boolean enabled, double chance, int cap, List<String> worlds,
                            List<World.Environment> environments, List<Biome> biomes, TimeOfDay time,
                            int minY, int maxY) {
        public boolean allows(Location location) {
            World world = location.getWorld();
            if (world == null) return false;
            if (!worlds.isEmpty() && !worlds.contains(world.getName())) return false;
            if (!environments.isEmpty() && !environments.contains(world.getEnvironment())) return false;
            if (location.getBlockY() < minY || location.getBlockY() > maxY) return false;
            if (!biomes.isEmpty() && !biomes.contains(world.getBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ())))
                return false;
            long dayTime = world.getTime();
            boolean day = dayTime < 12300 || dayTime > 23850;
            return time == TimeOfDay.ANY || (time == TimeOfDay.DAY) == day;
        }
    }

    private final String id;
    private final File file;
    private final String model;
    private final String name;
    private final EntityType body;
    private final double scale;
    private final double health;
    private final Material icon;
    private final NamespacedKey iconItemModel;
    private final List<Material> tamingFood;
    private final List<Material> breedingFood;
    private final double tameChance;
    private final String babyModel;
    private final double babyScale;
    private final long babyGrowthMillis;
    private final long breedingCooldownMillis;
    private final int loveTicks;
    private final List<Variant> variants;
    private final double followDistance;
    private final double leashDistance;
    private final double teleportDistance;
    private final double roamRadius;
    private final double speed;
    private final boolean canStay;
    private final double hoverHeight;
    private final double hoverVariance;
    private final WildSpawn wildSpawn;
    private final MindBodyLocomotion locomotion;

    private PetSpecies(File file, YamlConfiguration config) {
        this.file = file;
        String filename = file.getName();
        String defaultId = filename.substring(0, filename.length() - FILE_SUFFIX.length());
        this.id = config.getString("species", defaultId).toLowerCase(Locale.ROOT);
        this.model = config.getString("model", defaultId);
        this.name = config.getString("name", defaultId);
        EntityType bodyType = EntityType.ALLAY;
        String bodyName = config.getString("body", "ALLAY");
        try {
            bodyType = EntityType.valueOf(bodyName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            Logger.warn("Unknown body " + bodyName + " in " + filename + "; using ALLAY");
        }
        this.body = bodyType;
        this.scale = Math.max(0.0625D, config.getDouble("scale", 1D));
        this.health = Math.max(1D, config.getDouble("health", 20D));
        Material iconMaterial = Material.matchMaterial(config.getString("icon", "GLOW_BERRIES"));
        this.icon = iconMaterial == null || !iconMaterial.isItem() ? Material.GLOW_BERRIES : iconMaterial;
        String iconModel = config.getString("iconItemModel", "");
        this.iconItemModel = iconModel == null || iconModel.isBlank() ? null : NamespacedKey.fromString(iconModel.toLowerCase(Locale.ROOT));
        this.tamingFood = materials(config.getStringList("tamingFood"), filename, "tamingFood");
        this.breedingFood = materials(config.getStringList("breedingFood"), filename, "breedingFood");
        // Wolves and cats tame on a third of feeds; the same default keeps taming a small hunt for food.
        this.tameChance = config.getDouble("tameChance", 1D / 3D);
        String baby = config.getString("babyModel", "");
        this.babyModel = baby == null || baby.isBlank() ? null : baby;
        this.babyScale = config.getDouble("babyScale", 0.5D);
        // Minecraft babies grow up in 20 minutes and parents breed again after 5.
        this.babyGrowthMillis = (long) (config.getDouble("babyGrowthMinutes", 20D) * 60_000D);
        this.breedingCooldownMillis = (long) (config.getDouble("breedingCooldownMinutes", 5D) * 60_000D);
        this.loveTicks = (int) (config.getDouble("loveSeconds", 30D) * 20D);
        this.variants = variants(config.getStringList("variants"), filename);
        ConfigurationSection follow = section(config, "follow");
        this.followDistance = follow.getDouble("distance", 5D);
        this.leashDistance = follow.getDouble("leashDistance", 12D);
        this.teleportDistance = follow.getDouble("teleportDistance", 24D);
        this.roamRadius = follow.getDouble("roamRadius", 5D);
        this.speed = Math.max(0.05D, follow.getDouble("speed", 1D));
        this.canStay = follow.getBoolean("stay", true);
        ConfigurationSection floating = section(config, "float");
        this.hoverHeight = floating.getDouble("hoverHeight", 1.5D);
        this.hoverVariance = floating.getDouble("hoverVariance", 0.75D);
        ConfigurationSection wild = section(config, "wildSpawn");
        TimeOfDay time = TimeOfDay.ANY;
        try {
            time = TimeOfDay.valueOf(wild.getString("time", "ANY").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            Logger.warn("Unknown wildSpawn.time in " + filename + "; using ANY");
        }
        this.wildSpawn = new WildSpawn(wild.getBoolean("enabled", true), wild.getDouble("chance", 1D), wild.getInt("cap", 4),
                List.copyOf(wild.getStringList("worlds")), environments(wild.getStringList("environments"), filename),
                biomes(wild.getStringList("biomes"), filename), time, wild.getInt("minY", -64), wild.getInt("maxY", 320));
        MindBodyLocomotion movement = MindBodyLocomotion.FLYING;
        String locomotionName = config.getString("locomotion", "FLYING");
        try {
            movement = MindBodyLocomotion.valueOf(locomotionName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            Logger.warn("Unknown locomotion " + locomotionName + " in " + filename + "; using FLYING");
        }
        this.locomotion = movement;
    }

    /** The species a pet file defines, or null when the file is disabled or unreadable. */
    public static PetSpecies load(File file) {
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            if (!config.getBoolean("isEnabled", true)) return null;
            return new PetSpecies(file, config);
        } catch (RuntimeException failure) {
            Logger.warn("Could not read pet file " + file.getName() + ": " + failure.getMessage());
            return null;
        }
    }

    private static ConfigurationSection section(ConfigurationSection parent, String key) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        return section != null ? section : parent.createSection(key);
    }

    private static List<Material> materials(List<String> names, String filename, String key) {
        List<Material> materials = new ArrayList<>();
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material == null) Logger.warn("Unknown item " + name + " in " + key + " of " + filename);
            else materials.add(material);
        }
        return List.copyOf(materials);
    }

    private static List<World.Environment> environments(List<String> names, String filename) {
        List<World.Environment> environments = new ArrayList<>();
        for (String name : names) {
            try {
                environments.add(World.Environment.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                Logger.warn("Unknown environment " + name + " in wildSpawn.environments of " + filename);
            }
        }
        return List.copyOf(environments);
    }

    private static List<Biome> biomes(List<String> names, String filename) {
        List<Biome> biomes = new ArrayList<>();
        for (String name : names) {
            NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
            Biome biome = key == null ? null : Registry.BIOME.get(key);
            if (biome == null) Logger.warn("Unknown biome " + name + " in wildSpawn.biomes of " + filename);
            else biomes.add(biome);
        }
        return List.copyOf(biomes);
    }

    /** {@code model} or {@code model:weight}; the species' own model is the only variant when none are listed. */
    private static List<Variant> variants(List<String> entries, String filename) {
        List<Variant> variants = new ArrayList<>();
        for (String entry : entries) {
            int split = entry.lastIndexOf(':');
            String model = split < 0 ? entry.trim() : entry.substring(0, split).trim();
            double weight = 1D;
            if (split >= 0) {
                try {
                    weight = Double.parseDouble(entry.substring(split + 1).trim());
                } catch (NumberFormatException ignored) {
                    Logger.warn("Bad variant weight in " + entry + " of " + filename + "; using 1");
                }
            }
            variants.add(new Variant(model, Math.max(0D, weight)));
        }
        return List.copyOf(variants);
    }

    public String id() {
        return id;
    }

    public File file() {
        return file;
    }

    public String model() {
        return model;
    }

    public String name() {
        return name;
    }

    public EntityType body() {
        return body;
    }

    public double scale() {
        return scale;
    }

    public double health() {
        return health;
    }

    public Material icon() {
        return icon;
    }

    /** An item model for the pets menu icon, or null to show the icon material as it is. */
    public NamespacedKey iconItemModel() {
        return iconItemModel;
    }

    public List<Material> tamingFood() {
        return tamingFood;
    }

    public List<Material> breedingFood() {
        return breedingFood;
    }

    public boolean isTamingFood(ItemStack item) {
        return item != null && tamingFood.contains(item.getType());
    }

    public boolean isBreedingFood(ItemStack item) {
        return item != null && breedingFood.contains(item.getType());
    }

    public double tameChance() {
        return tameChance;
    }

    public String babyModel() {
        return babyModel;
    }

    public double babyScale() {
        return babyScale;
    }

    public long babyGrowthMillis() {
        return babyGrowthMillis;
    }

    public long breedingCooldownMillis() {
        return breedingCooldownMillis;
    }

    public int loveTicks() {
        return loveTicks;
    }

    public List<Variant> variants() {
        return variants;
    }

    /** A variant model by weight, or the species' model when it lists none. */
    public String pickVariant(Random random) {
        double total = 0D;
        for (Variant variant : variants) total += variant.weight();
        if (total <= 0D) return model;
        double roll = random.nextDouble() * total;
        for (Variant variant : variants) {
            roll -= variant.weight();
            if (roll < 0D) return variant.model();
        }
        return variants.get(variants.size() - 1).model();
    }

    public double followDistance() {
        return followDistance;
    }

    public double leashDistance() {
        return leashDistance;
    }

    public double teleportDistance() {
        return teleportDistance;
    }

    public double roamRadius() {
        return roamRadius;
    }

    public double speed() {
        return speed;
    }

    public boolean canStay() {
        return canStay;
    }

    public double hoverHeight() {
        return hoverHeight;
    }

    public double hoverVariance() {
        return hoverVariance;
    }

    public WildSpawn wildSpawn() {
        return wildSpawn;
    }

    public MindBodyLocomotion locomotion() {
        return locomotion;
    }
}
