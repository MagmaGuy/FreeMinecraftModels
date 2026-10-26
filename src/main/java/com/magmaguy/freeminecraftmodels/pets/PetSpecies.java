package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.customentity.core.TravelOrientation;
import com.magmaguy.freeminecraftmodels.pets.behavior.PetBehaviorEntry;
import com.magmaguy.freeminecraftmodels.pets.behavior.PetBehaviorYaml;
import com.magmaguy.freeminecraftmodels.pets.behavior.PetPrograms;
import com.magmaguy.freeminecraftmodels.pets.interactions.PetInteractions;
import com.magmaguy.freeminecraftmodels.utils.StringToResourcePackFilename;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One pet species, read from a {@code <species>.pet.yml} file that sits beside its model in the models folder, so a
 * model pack ships, enables and disables its pets together with its models. It names the model and the body the
 * model rides, and says how the species is tamed, bred and raised, how it floats and follows, and where it lives
 * wild.
 */
public final class PetSpecies {
    public static final String FILE_SUFFIX = ".pet.yml";
    /** The keys a variant written as a map may hold. */
    private static final List<String> VARIANT_KEYS = List.of("model", "weight", "behaviors");
    /** The world interaction sections pet files had before behavior modules, each with the module that replaces it. */
    private static final Map<String, String> LEGACY_SECTIONS = new LinkedHashMap<>();

    static {
        LEGACY_SECTIONS.put("campfire", "campfire");
        LEGACY_SECTIONS.put("rainBloom", "rain_bloom");
        LEGACY_SECTIONS.put("glow", "night_glow");
        LEGACY_SECTIONS.put("sting", "sting");
        LEGACY_SECTIONS.put("dayPerch", "day_perch");
        LEGACY_SECTIONS.put("drizzle", "crop_drizzle");
    }

    /**
     * A model the species' pets may wear, its share of the picks, and the behavior modules it adds to the species'.
     * The behaviors are copied; null means none.
     */
    public record Variant(String model, double weight, List<PetBehaviorEntry> behaviors) {
        public Variant {
            behaviors = behaviors == null ? List.of() : List.copyOf(behaviors);
        }
    }

    public enum TimeOfDay {ANY, DAY, NIGHT}

    /**
     * Where a wild group may stand when it spawns, like a vanilla mob's spawn placement: on the ground, in water, in
     * the air just over a water surface, or in the open air over solid ground.
     */
    public enum Placement {GROUND, WATER, WATER_SURFACE, AIR}

    /**
     * How the species appears wild in CREATURES mode, shaped like a vanilla mob's entry in a biome's spawn list: a
     * weight against the other species that may spawn at the same spot, a group size, where the group may stand, and
     * the light, world, environment, biome and height it needs. Empty lists allow everything. {@code time} and
     * {@code cap} only hold back the top-up spawns around players: {@code cap} is how many of this species may be
     * wild around one player, and new chunks are stocked whatever the time.
     */
    public record WildSpawn(boolean enabled, int weight, int minGroup, int maxGroup, Placement placement, int minLight,
                            int maxLight, int cap, List<String> worlds, List<World.Environment> environments,
                            List<Biome> biomes, TimeOfDay time, int minY, int maxY) {
        /** Whether this world could ever hold the species, before looking at any spot in it. */
        public boolean allowsWorld(World world) {
            if (world == null) return false;
            if (!worlds.isEmpty() && !worlds.contains(world.getName())) return false;
            return environments.isEmpty() || environments.contains(world.getEnvironment());
        }

        /** Whether it is the time of day the species tops up at in this world. */
        public boolean allowsTime(World world) {
            long dayTime = world.getTime();
            boolean day = dayTime < 12300 || dayTime > 23850;
            return time == TimeOfDay.ANY || (time == TimeOfDay.DAY) == day;
        }

        /** Whether a member of the species may spawn at this spot: world, height, biome and light level. */
        public boolean allows(Location location) {
            World world = location.getWorld();
            if (!allowsWorld(world)) return false;
            if (location.getBlockY() < minY || location.getBlockY() > maxY) return false;
            if (!biomes.isEmpty() && !biomes.contains(world.getBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ())))
                return false;
            int light = location.getBlock().getLightLevel();
            return light >= minLight && light <= maxLight;
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
    private final List<PetBehaviorEntry> behaviors; // null: the locomotion's defaults
    private final TravelOrientation orientation;
    private final boolean centerOnBody;
    private final PetInteractions interactions;

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
        this.variants = variants(config.get("variants"), filename, Logger::warn);
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
        MindBodyLocomotion movement = MindBodyLocomotion.FLYING;
        String locomotionName = config.getString("locomotion", "FLYING");
        try {
            movement = MindBodyLocomotion.valueOf(locomotionName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            Logger.warn("Unknown locomotion " + locomotionName + " in " + filename + "; using FLYING");
        }
        this.locomotion = movement;
        this.behaviors = listedBehaviors(config, filename, Logger::warn);
        warnLegacySections(config, filename, Logger::warn);
        this.wildSpawn = readWildSpawn(section(config, "wildSpawn"), movement, filename);
        this.orientation = orientation(section(config, "orientation"), filename);
        this.centerOnBody = centerOnBody(config, movement);
        this.interactions = PetInteractions.read(config, filename, model);
    }

    /** How the model leans with its travel; upright unless the file asks for a top-first or front-first lean. */
    static TravelOrientation orientation(ConfigurationSection section, String filename) {
        TravelOrientation.Style style = TravelOrientation.Style.UPRIGHT;
        String styleName = section.getString("style", "UPRIGHT");
        try {
            style = TravelOrientation.Style.valueOf(styleName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            Logger.warn("Unknown orientation.style " + styleName + " in " + filename + "; using UPRIGHT");
        }
        TravelOrientation defaults = TravelOrientation.UPRIGHT;
        return new TravelOrientation(style, section.getDouble("startSpeed", defaults.startSpeed()),
                section.getDouble("fullSpeed", defaults.fullSpeed()), section.getDouble("maxTilt", defaults.maxTilt()),
                section.getDouble("turnSpeed", defaults.turnSpeed()));
    }

    /**
     * Whether the model is drawn centred on its hidden body. Flying and swimming bodies are much smaller than the
     * creatures they carry, so those species centre by default; walkers stand on their body's feet.
     */
    static boolean centerOnBody(ConfigurationSection config, MindBodyLocomotion locomotion) {
        return config.getBoolean("centerOnBody",
                locomotion == MindBodyLocomotion.FLYING || locomotion == MindBodyLocomotion.AQUATIC);
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

    /**
     * Reads the wildSpawn section. Placement defaults from how the species moves: flyers spawn in the air, swimmers
     * in water, the rest on the ground. A file from before weights keeps its old {@code chance} (a share of spawn
     * attempts, 0 to 1) as a weight out of 10.
     */
    static WildSpawn readWildSpawn(ConfigurationSection wild, MindBodyLocomotion locomotion, String filename) {
        TimeOfDay time = TimeOfDay.ANY;
        try {
            time = TimeOfDay.valueOf(wild.getString("time", "ANY").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            Logger.warn("Unknown wildSpawn.time in " + filename + "; using ANY");
        }
        Placement fallback = switch (locomotion) {
            case FLYING -> Placement.AIR;
            case AQUATIC -> Placement.WATER;
            default -> Placement.GROUND;
        };
        Placement placement = fallback;
        String placementName = wild.getString("placement", fallback.name());
        try {
            placement = Placement.valueOf(placementName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            Logger.warn("Unknown wildSpawn.placement " + placementName + " in " + filename + "; using " + fallback);
        }
        int weight = wild.contains("weight") ? wild.getInt("weight")
                : (int) Math.round(Math.max(0D, Math.min(1D, wild.getDouble("chance", 1D))) * 10D);
        int minGroup = Math.max(1, wild.getInt("minGroup", 1));
        int maxGroup = Math.max(minGroup, wild.getInt("maxGroup", minGroup));
        int minLight = Math.max(0, Math.min(15, wild.getInt("minLight", 0)));
        int maxLight = Math.max(minLight, Math.min(15, wild.getInt("maxLight", 15)));
        return new WildSpawn(wild.getBoolean("enabled", true), Math.max(0, weight), minGroup, maxGroup, placement,
                minLight, maxLight, Math.max(0, wild.getInt("cap", 4)), List.copyOf(wild.getStringList("worlds")),
                environments(wild.getStringList("environments"), filename), biomes(wild.getStringList("biomes"), filename),
                time, wild.getInt("minY", -64), wild.getInt("maxY", 320));
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

    /**
     * The file's behaviors list, or null when it has none or has something that is not a list (after a warning), so
     * the species runs its locomotion's defaults.
     */
    static List<PetBehaviorEntry> listedBehaviors(ConfigurationSection config, String filename, Consumer<String> warn) {
        return config.contains("behaviors") ? PetBehaviorEntry.read(config.get("behaviors"), filename, warn) : null;
    }

    /**
     * Warns, once per load of the file, about each section it still has from before world interactions became
     * behavior modules, naming the module that does the same now; the section itself does nothing.
     */
    static void warnLegacySections(ConfigurationSection config, String filename, Consumer<String> warn) {
        LEGACY_SECTIONS.forEach((key, module) -> {
            if (!config.contains(key)) return;
            // glow could also be just "glow: true".
            String kind = config.isConfigurationSection(key) ? "section" : "setting";
            warn.accept(filename + ": the " + key + " " + kind + " no longer does anything; world interactions are"
                    + " behavior modules now. List the " + module + " module under behaviors: instead (see"
                    + " plugins/FreeMinecraftModels/pet_behaviors/" + module + ".lua for its settings); this " + kind
                    + " is ignored.");
        });
    }

    /**
     * Variants as "model", "model:weight", or a map with a model, a weight and its own behaviors list; the species'
     * own model is the only variant when none are listed. A malformed entry is reported through {@code warn}, naming
     * the file, and skipped. A weight that is not a number counts as 1, and one below 0 as 0. A model listed more than
     * once is reported and kept, as picking has always taken it.
     */
    static List<Variant> variants(Object raw, String filename, Consumer<String> warn) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> entries)) {
            warn.accept(filename + ": variants must be a list, one model per line starting with \"- \"; the species wears "
                    + "its own model.");
            return List.of();
        }
        List<Variant> variants = new ArrayList<>();
        for (Object entry : entries) {
            Variant variant = entry instanceof Map<?, ?> map && !map.isEmpty() ? mapVariant(map, filename, warn)
                    : textVariant(entry, filename, warn);
            if (variant != null) variants.add(variant);
        }
        warnDuplicates(variants, filename, warn);
        return List.copyOf(variants);
    }

    /**
     * Picking takes each entry by its own weight, so entries that share a name add up, and {@link #behaviors(String)}
     * takes the first. Names that differ yet name the same model (in case, or in signs model names turn into '_') are
     * a mistake: a pet wears a variant's model only when the variant is written as the model's exact name.
     */
    private static void warnDuplicates(List<Variant> variants, String filename, Consumer<String> warn) {
        Map<String, String> firstById = new HashMap<>();
        Set<String> said = new HashSet<>();
        for (Variant variant : variants) {
            String first = firstById.putIfAbsent(StringToResourcePackFilename.convert(variant.model()), variant.model());
            if (first == null) continue;
            if (first.equals(variant.model())) {
                if (said.add(first))
                    warn.accept(filename + ": variants lists " + first + " more than once; its weights add up and the "
                            + "first entry's behaviors apply.");
            } else if (said.add(first + "\n" + variant.model()))
                warn.accept(filename + ": variants lists " + first + " and " + variant.model() + ", which name the same "
                        + "model; pets wear it only from the entry written as the model's name, in lower case with _ for "
                        + "spaces, so keep that one.");
        }
    }

    /** A variant written as text, "model" or "model:weight", or null after a warning. */
    private static Variant textVariant(Object entry, String filename, Consumer<String> warn) {
        // YAML reads some names as numbers; the model is then the number as Java writes it, which is the name only for
        // plain digits: YAML reads 012 as 10 and 1:30 as 90. Such names need quotes.
        String text = entry instanceof String || entry instanceof Number ? String.valueOf(entry).trim() : null;
        if (text == null || text.isEmpty()) {
            if (PetBehaviorYaml.isEmpty(entry)) warn.accept(filename + ": variants has an empty entry; skipped.");
            else if (entry instanceof Boolean)
                skip(entry, "YAML reads true, false, yes, no, on and off as true or false, so put a model with such a "
                        + "name in quotes", filename, warn);
            else skip(entry, "each entry is a model name, \"model:weight\", or a map with model, weight and behaviors",
                    filename, warn);
            return null;
        }
        int split = text.lastIndexOf(':');
        String model = split < 0 ? text : text.substring(0, split).trim();
        if (model.isEmpty()) {
            skip(entry, "it names no model", filename, warn);
            return null;
        }
        double weight = 1D;
        if (split >= 0) {
            String given = text.substring(split + 1).trim();
            weight = weight(given.isEmpty() ? null : given, filename + ", variant " + model, warn);
        }
        return new Variant(model, weight, List.of());
    }

    /** A variant written as a map with a model, a weight and its own behaviors, or null after a warning. */
    private static Variant mapVariant(Map<?, ?> map, String filename, Consumer<String> warn) {
        Object named = map.get("model");
        String model = named instanceof String || named instanceof Number ? String.valueOf(named).trim() : "";
        if (model.isEmpty()) {
            if (map.containsKey("model")) skip(map, "its model must be a model's name", filename, warn);
            else if (map.size() == 1 && map.keySet().iterator().next() instanceof String name && !VARIANT_KEYS.contains(name))
                // Usually "- model: weight" with a space after the colon, or a model name with its settings below it.
                skip(map, "write \"- " + name + ":<weight>\" with no space after the colon, or \"- model: " + name
                        + "\" with weight and behaviors lined up below it", filename, warn);
            else skip(map, "a variant written as a map needs \"model: <model name>\"", filename, warn);
            return null;
        }
        String where = filename + ", variant " + model;
        // YAML reads "~:" and "null:" as a key that is no text at all.
        for (Object key : map.keySet())
            if (!(key instanceof String name) || !VARIANT_KEYS.contains(name))
                warn.accept(where + ": a variant has no setting " + key + " (its settings are model, weight and behaviors); ignored.");
        double weight = map.containsKey("weight") ? weight(map.get("weight"), where, warn) : 1D;
        // A blank "behaviors:" counts as absent, as it does for the species; read returns null after a warning for a
        // value that is not a list, and the variant then adds nothing either.
        List<PetBehaviorEntry> behaviors = map.get("behaviors") != null
                ? PetBehaviorEntry.read(map.get("behaviors"), where, warn) : null;
        return new Variant(model, weight, behaviors);
    }

    /** A variant's weight: a number, or text that reads as one ("0.5"); otherwise 1, and below 0 it is 0. */
    private static double weight(Object value, String where, Consumer<String> warn) {
        if (value == null) {
            warn.accept(where + ": weight has no value; using 1.");
            return 1D;
        }
        double weight;
        try {
            weight = value instanceof Number number ? number.doubleValue()
                    : value instanceof String text ? new BigDecimal(text.strip()).doubleValue() : Double.NaN;
        } catch (NumberFormatException notANumber) {
            weight = Double.NaN;
        }
        if (!Double.isFinite(weight)) {
            warn.accept(where + ": weight must be a number, not " + PetBehaviorYaml.typed(value) + "; using 1.");
            return 1D;
        }
        if (weight < 0D) {
            warn.accept(where + ": weight must be 0 or more, not " + PetBehaviorYaml.plain(value) + "; using 0.");
            return 0D;
        }
        return weight;
    }

    private static void skip(Object entry, String reason, String filename, Consumer<String> warn) {
        warn.accept(filename + ": skipping " + PetBehaviorYaml.describe(entry) + " in variants; " + reason + ".");
    }

    /**
     * The modules a pet wearing this model runs: the file's list, or the locomotion's defaults when it has none, then
     * the modules of the first variant named exactly as the model, else of the first whose name matches as
     * FreeMinecraftModels names models (lower case, other characters as '_'). A pet wears the model its record names
     * exactly, so a variant whose name the file later wrote in another case still gives that pet its modules.
     */
    static List<PetBehaviorEntry> behaviors(List<PetBehaviorEntry> listed, MindBodyLocomotion locomotion,
                                            List<Variant> variants, String model) {
        List<PetBehaviorEntry> base = listed != null ? listed : PetPrograms.defaults(locomotion);
        if (model == null) return base;
        for (Variant variant : variants)
            if (variant.model().equals(model)) return PetBehaviorEntry.overlay(base, variant.behaviors());
        String id = StringToResourcePackFilename.convert(model);
        for (Variant variant : variants)
            if (StringToResourcePackFilename.convert(variant.model()).equals(id))
                return PetBehaviorEntry.overlay(base, variant.behaviors());
        return base;
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

    /** The species' look in night migrations; its migration() is null when off. */
    public PetInteractions interactions() {
        return interactions;
    }

    public MindBodyLocomotion locomotion() {
        return locomotion;
    }

    /**
     * Whether the pet file lists its own behaviors; false when it has no list or one that is not a list, and the
     * species runs its locomotion's defaults.
     */
    public boolean listsBehaviors() {
        return behaviors != null;
    }

    /** The modules a pet wearing this model runs: the species' list or its locomotion's defaults, then the variant's. */
    public List<PetBehaviorEntry> behaviors(String model) {
        return behaviors(behaviors, locomotion, variants, model);
    }

    /** How the model leans with its travel. */
    public TravelOrientation orientation() {
        return orientation;
    }

    /** Whether the model is drawn centred on its hidden body rather than standing on its feet. */
    public boolean centerOnBody() {
        return centerOnBody;
    }
}
